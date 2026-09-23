package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic income math for envelope-v2 calculation requests: the model
 * EXTRACTS inputs and REQUESTS a method by id; every number the suite sees is
 * computed here, never by the LLM (spec 2026-08-01-income-deterministic-slice).
 *
 * Contract: BigDecimal, HALF_UP throughout. Money-valued intermediates (e.g.
 * {@code annualized} in {@link #monthlyFromRate}, {@code varianceUsd} in
 * {@link #variance}) are rounded to scale 2 as soon as they are produced, and
 * downstream steps consume that already-rounded value rather than the raw
 * unrounded one — this two-step rounding is intentional (it keeps every
 * intermediate an auditable dollar figure) and is accepted as-is; a
 * single-step high-precision divide was considered and rejected. Percentage
 * comparisons (materiality) are the exception: they compare the unrounded
 * ratio and round only the reported value, so the rounding never shifts a
 * threshold decision. The inbound {@link com.fasterxml.jackson.databind.ObjectMapper}
 * does not enable {@code USE_BIG_DECIMAL_FOR_FLOATS}, so numeric JSON literals
 * arrive as {@code DoubleNode}/{@code IntNode}/etc. rather than exact
 * {@code BigDecimal} from the parser; {@link #num} converts via
 * {@link JsonNode#decimalValue()} and rejects non-finite doubles before that
 * conversion can throw.
 *
 * Missing, invalid, negative, or out-of-range inputs FAIL the request with a
 * structured {@link CalcResult#error(String)} — never a defaulted or
 * sign-inverted zero.
 */
@Service
public class IncomeCalcService {

    /** The complete v2 income calculation vocabulary — the prompt and the validator both read this. */
    public static final java.util.Set<String> SUPPORTED_METHODS = java.util.Set.of(
            "income.monthly_from_annual.v1",
            "income.monthly_from_rate.v1",
            "income.monthly_from_period_gross.v1",
            "income.period_gross_from_ytd_delta.v1",
            "income.ytd_monthly_average.v1",
            "income.total_monthly.v1",
            "income.variance.v1");

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MATERIAL_THRESHOLD_PCT = new BigDecimal("10");
    private static final BigDecimal MIN_MONTHS = new BigDecimal("0.5");
    private static final BigDecimal MAX_HOURS_PER_WEEK = new BigDecimal("168");

    /**
     * Dispatches one calculation request to its deterministic implementation.
     * Supported method ids:
     * <ul>
     *   <li>{@code income.monthly_from_annual.v1} — inputs {@code {annual}};
     *       intermediates {@code {annual}}</li>
     *   <li>{@code income.monthly_from_rate.v1} — inputs {@code {rate, frequency,
     *       hoursPerWeek?}} (hoursPerWeek required only for {@code HOURLY});
     *       intermediates {@code {annualized, frequency}}</li>
     *   <li>{@code income.monthly_from_period_gross.v1} — inputs {@code {gross, periodStart,
     *       periodEnd}} (ISO dates); the pay frequency is inferred from the inclusive period
     *       length; intermediates {@code {periodDays, inferredFrequency, annualized}}</li>
     *   <li>{@code income.period_gross_from_ytd_delta.v1} — inputs {@code {ytdPrior, ytdCurrent,
     *       periodStart, periodEnd, frequency?, priorPeriodEnd?}} (dates of the CURRENT stub; when
     *       priorPeriodEnd is given, periodStart must be the day after it); the period gross is
     *       the YTD difference between two consecutive stubs; intermediates {@code {periodGross,
     *       frequency, frequencyInferred, annualized, periodDays}}</li>
     *   <li>{@code income.ytd_monthly_average.v1} — inputs {@code {ytdAmount,
     *       periodStart, periodEnd}} (ISO dates); intermediates
     *       {@code {daysElapsed, monthsElapsed}}</li>
     *   <li>{@code income.total_monthly.v1} — inputs {@code {amounts: [...]}};
     *       intermediates {@code {count}}</li>
     *   <li>{@code income.variance.v1} — inputs {@code {computed, stated}};
     *       intermediates {@code {variancePct, material, thresholdPct}}</li>
     * </ul>
     * Any other method id, a null method, or a non-object/null {@code inputs}
     * returns a structured {@link CalcResult#error(String)} rather than
     * throwing; so do the {@link CalcInputException}s raised by the
     * implementations above and any {@link ArithmeticException} or
     * {@link NumberFormatException} surfaced while converting inputs.
     */
    public CalcResult compute(String method, JsonNode inputs) {
        if (method == null) {
            return CalcResult.error("method is required");
        }
        if (!SUPPORTED_METHODS.contains(method)) {
            return CalcResult.error("unsupported method: " + method);
        }
        if (inputs == null || !inputs.isObject()) {
            return CalcResult.error("inputs must be a JSON object");
        }
        try {
            return switch (method) {
                case "income.monthly_from_annual.v1" -> monthlyFromAnnual(inputs);
                case "income.monthly_from_rate.v1" -> monthlyFromRate(inputs);
                case "income.monthly_from_period_gross.v1" -> monthlyFromPeriodGross(inputs);
                case "income.period_gross_from_ytd_delta.v1" -> periodGrossFromYtdDelta(inputs);
                case "income.ytd_monthly_average.v1" -> ytdMonthlyAverage(inputs);
                case "income.total_monthly.v1" -> totalMonthly(inputs);
                case "income.variance.v1" -> variance(inputs);
                default -> CalcResult.error("unsupported method: " + method);
            };
        } catch (CalcInputException | ArithmeticException | NumberFormatException
                | DateTimeException e) {
            // DateTimeException covers date arithmetic outside date(): a periodEnd at the
            // max year makes end.plusDays(1) overflow inside monthsElapsed.
            return CalcResult.error(e.getMessage());
        }
    }

    private CalcResult monthlyFromAnnual(JsonNode in) {
        BigDecimal annual = num(in, "annual");
        requireNonNegative(annual, "annual");
        BigDecimal monthly = annual.divide(TWELVE, MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("annual", annual);
        return CalcResult.computed(monthly, mid);
    }

    private CalcResult monthlyFromRate(JsonNode in) {
        BigDecimal rate = num(in, "rate");
        requireNonNegative(rate, "rate");
        String frequency = text(in, "frequency");
        BigDecimal annualized = switch (frequency) {
            case "HOURLY" -> {
                BigDecimal hours = num(in, "hoursPerWeek");
                if (hours.compareTo(BigDecimal.ZERO) <= 0 || hours.compareTo(MAX_HOURS_PER_WEEK) > 0) {
                    throw new CalcInputException("hoursPerWeek must be in (0, 168]");
                }
                yield rate.multiply(hours, MC).multiply(new BigDecimal("52"), MC);
            }
            case "WEEKLY" -> rate.multiply(new BigDecimal("52"), MC);
            case "BIWEEKLY" -> rate.multiply(new BigDecimal("26"), MC);
            case "SEMIMONTHLY" -> rate.multiply(new BigDecimal("24"), MC);
            case "MONTHLY" -> rate.multiply(TWELVE, MC);
            case "ANNUAL" -> rate;
            default -> throw new CalcInputException("unknown frequency: " + frequency);
        };
        annualized = annualized.setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthly = annualized.divide(TWELVE, MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("annualized", annualized);
        mid.put("frequency", frequency);
        return CalcResult.computed(monthly, mid);
    }

    /**
     * Current-pay monthly income when the paystub does not print its pay frequency: the
     * frequency is inferred from the inclusive pay-period length (spec 2026-09-14 A3).
     * Bands: 14–16 days starting on the 1st, or on the 16th and ending on the month's last day →
     * SEMIMONTHLY (checked first, because it
     * overlaps BIWEEKLY); 5–9 → WEEKLY; 12–16 → BIWEEKLY; 27–32 → MONTHLY; anything else fails.
     *
     * <p>Known artifact: a semimonthly second half in February (Feb 16–28, 13 days) falls in the
     * BIWEEKLY band. The worksheet labels every inferred frequency as inferred so a reviewer sees it.
     */
    private CalcResult monthlyFromPeriodGross(JsonNode in) {
        BigDecimal gross = num(in, "gross");
        requireNonNegative(gross, "gross");
        LocalDate start = date(in, "periodStart");
        LocalDate end = date(in, "periodEnd");
        if (end.isBefore(start)) {
            throw new CalcInputException("periodEnd is before periodStart");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;   // inclusive span
        String frequency = inferFrequency(days, start, end);
        BigDecimal annualized = gross.multiply(periodsPerYear(frequency), MC).setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthly = annualized.divide(TWELVE, MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("periodDays", days);
        mid.put("inferredFrequency", frequency);
        mid.put("annualized", annualized);
        return CalcResult.computed(monthly, mid);
    }

    /**
     * Current pay from two consecutive paystubs of the same employer: the current period's gross
     * is {@code ytdCurrent − ytdPrior}, which holds even when the current stub's own gross or
     * frequency is missing or misread. {@code periodStart}/{@code periodEnd} are the CURRENT
     * stub's. An absent {@code frequency} is inferred with the same bands as
     * {@link #monthlyFromPeriodGross}; a given one must be consistent with the period length.
     */
    private CalcResult periodGrossFromYtdDelta(JsonNode in) {
        BigDecimal prior = num(in, "ytdPrior");
        requireNonNegative(prior, "ytdPrior");
        BigDecimal current = num(in, "ytdCurrent");
        requireNonNegative(current, "ytdCurrent");
        if (prior.compareTo(current) > 0) {
            throw new CalcInputException("ytdPrior is greater than ytdCurrent; the stubs are not"
                    + " consecutive in that order or a YTD figure is misread");
        }
        LocalDate start = date(in, "periodStart");
        LocalDate end = date(in, "periodEnd");
        if (end.isBefore(start)) {
            throw new CalcInputException("periodEnd is before periodStart");
        }
        JsonNode priorEndNode = in.get("priorPeriodEnd");
        if (priorEndNode != null && !priorEndNode.isNull()) {
            LocalDate priorEnd = date(in, "priorPeriodEnd");
            if (!start.equals(priorEnd.plusDays(1))) {
                throw new CalcInputException("stubs are not consecutive: periodStart " + start
                        + " is not the day after priorPeriodEnd " + priorEnd
                        + "; a skipped stub would count more than one period's pay");
            }
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;   // inclusive span
        JsonNode given = in.get("frequency");
        boolean inferred = given == null || given.isNull();
        String frequency;
        if (inferred) {
            frequency = inferFrequency(days, start, end);
        } else {
            frequency = text(in, "frequency");
            if (!inBand(frequency, days)) {
                throw new CalcInputException("period length does not match the given frequency "
                        + frequency + " (" + days + " days)");
            }
        }
        BigDecimal periodGross = current.subtract(prior).setScale(2, RoundingMode.HALF_UP);
        BigDecimal annualized = periodGross.multiply(periodsPerYear(frequency), MC).setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthly = annualized.divide(TWELVE, MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("periodGross", periodGross);
        mid.put("frequency", frequency);
        mid.put("frequencyInferred", inferred);
        mid.put("annualized", annualized);
        mid.put("periodDays", days);
        return CalcResult.computed(monthly, mid);
    }

    private static BigDecimal periodsPerYear(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> new BigDecimal("52");
            case "BIWEEKLY" -> new BigDecimal("26");
            case "SEMIMONTHLY" -> new BigDecimal("24");
            case "MONTHLY" -> TWELVE;
            default -> throw new CalcInputException("unknown frequency: " + frequency);
        };
    }

    /**
     * Inclusive period-length band per pay frequency. SEMIMONTHLY is 13–16 so a given
     * semimonthly February second half (13 days) is accepted; inference additionally requires a
     * 14–16 day period starting on the 1st or 16th (see {@link #inferFrequency}).
     */
    private static boolean inBand(String frequency, long days) {
        return switch (frequency) {
            case "WEEKLY" -> days >= 5 && days <= 9;
            case "BIWEEKLY" -> days >= 12 && days <= 16;
            case "SEMIMONTHLY" -> days >= 13 && days <= 16;
            case "MONTHLY" -> days >= 27 && days <= 32;
            default -> throw new CalcInputException("unknown frequency: " + frequency);
        };
    }

    /**
     * A semimonthly period runs 1st–15th or 16th–last day of the month, so a 14–16 day period that
     * starts on the 1st or 16th but ends elsewhere is BIWEEKLY (loan Gough, 2026-09: 08/16–08/29
     * was misread as SEMIMONTHLY; so was 01/01–01/14, which annualized ~7.7% low). A 13-day
     * non-leap February 16–28 is a second half, so SEMIMONTHLY, not BIWEEKLY.
     */
    private static String inferFrequency(long days, LocalDate start, LocalDate end) {
        int startDay = start.getDayOfMonth();
        boolean firstHalf = startDay == 1 && end.getDayOfMonth() == 15 && days >= 14 && days <= 16;
        boolean secondHalf = startDay == 16 && end.getDayOfMonth() == end.lengthOfMonth()
                && days >= 13 && days <= 16;
        if (firstHalf || secondHalf) {
            return "SEMIMONTHLY";
        }
        for (String frequency : List.of("WEEKLY", "BIWEEKLY", "MONTHLY")) {
            if (inBand(frequency, days)) {
                return frequency;
            }
        }
        throw new CalcInputException(
                "period length does not match a supported pay frequency (" + days + " days)");
    }

    private CalcResult ytdMonthlyAverage(JsonNode in) {
        BigDecimal ytd = num(in, "ytdAmount");
        requireNonNegative(ytd, "ytdAmount");
        LocalDate start = date(in, "periodStart");
        LocalDate end = date(in, "periodEnd");
        if (end.isBefore(start)) {
            throw new CalcInputException("periodEnd is before periodStart");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;   // inclusive span, audit value only
        BigDecimal months = monthsElapsed(start, end);
        if (months.compareTo(MIN_MONTHS) < 0) {
            throw new CalcInputException(
                    "period covers " + months + " months (< 0.5); refusing to average");
        }
        BigDecimal monthly = ytd.divide(months, MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("daysElapsed", days);
        mid.put("monthsElapsed", months);
        return CalcResult.computed(monthly, mid);
    }

    /**
     * Calendar-month convention: whole months between {@code start} and the day
     * after {@code end} (the period is end-inclusive), plus a fractional tail
     * measured as tailDays / length-of-the-tail-month. A paystub running Jan 1
     * through Jun 30 is exactly 6.0000 months, matching how underwriters read a
     * YTD period — not a fixed 30.4375-day average that runs high for exact
     * calendar spans and drifts with leap years.
     *
     * <p>Known artifact: a {@code start} on the 29th–31st inherits plusMonths
     * clamping, so the divisor jumps at the month boundary (Jan 31–Feb 27 is
     * 0.9032 months, Jan 31–Feb 28 is 1.0357). Real YTD periods start Jan 1, so
     * this is accepted rather than special-cased.
     */
    private static BigDecimal monthsElapsed(LocalDate start, LocalDate end) {
        LocalDate endExclusive = end.plusDays(1);               // the period INCLUDES end
        long wholeMonths = ChronoUnit.MONTHS.between(start, endExclusive);
        LocalDate afterWhole = start.plusMonths(wholeMonths);
        long tailDays = ChronoUnit.DAYS.between(afterWhole, endExclusive);
        BigDecimal fraction = tailDays == 0
                ? BigDecimal.ZERO
                : new BigDecimal(tailDays).divide(
                        new BigDecimal(afterWhole.lengthOfMonth()), 4, RoundingMode.HALF_UP);
        return new BigDecimal(wholeMonths).add(fraction).setScale(4, RoundingMode.HALF_UP);
    }

    private CalcResult totalMonthly(JsonNode in) {
        JsonNode amounts = in.get("amounts");
        if (amounts == null || !amounts.isArray() || amounts.isEmpty()) {
            throw new CalcInputException("amounts must be a non-empty array of numbers");
        }
        BigDecimal sum = BigDecimal.ZERO;
        int i = 0;
        for (JsonNode a : amounts) {
            String field = "amounts[" + i + "]";
            if (!a.isNumber()) {
                throw new CalcInputException("amounts must contain only numbers");
            }
            BigDecimal value = decimalValueOf(a, field);
            requireNonNegative(value, field);
            sum = sum.add(value, MC);
            i++;
        }
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("count", amounts.size());
        return CalcResult.computed(sum.setScale(2, RoundingMode.HALF_UP), mid);
    }

    /**
     * variance = computed − stated, rounded to scale 2. variancePct compares
     * the UNROUNDED ratio against {@link #MATERIAL_THRESHOLD_PCT}: material
     * means strictly more than 10% ("more than 10%" per the v1 prompt), so a
     * variance of exactly 10.00% is not material. Rounding variancePct to 2dp
     * before that comparison would silently widen the threshold to
     * effectively >10.005%, so the comparison is done on the raw ratio and the
     * rounded value is kept only for reporting.
     */
    private CalcResult variance(JsonNode in) {
        BigDecimal computed = num(in, "computed");
        BigDecimal stated = num(in, "stated");
        if (stated.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CalcInputException("stated must be positive; variance percent is undefined for zero or negative stated income");
        }
        BigDecimal varianceUsd = computed.subtract(stated).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pctRaw = varianceUsd.divide(stated, MC).multiply(HUNDRED, MC);
        BigDecimal pct = pctRaw.setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("variancePct", pct);
        mid.put("material", pctRaw.abs().compareTo(MATERIAL_THRESHOLD_PCT) > 0);
        mid.put("thresholdPct", MATERIAL_THRESHOLD_PCT);
        return CalcResult.computed(varianceUsd, mid);
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new CalcInputException(field + " must not be negative");
        }
    }

    private static BigDecimal num(JsonNode in, String field) {
        JsonNode n = in.get(field);
        if (n == null || !n.isNumber()) {
            throw new CalcInputException("missing or non-numeric input: " + field);
        }
        return decimalValueOf(n, field);
    }

    /** Converts a numeric JsonNode to BigDecimal, rejecting NaN/Infinity before decimalValue() can throw. */
    private static BigDecimal decimalValueOf(JsonNode n, String field) {
        if (n.isDouble() || n.isFloat()) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new CalcInputException("non-finite numeric input: " + field);
            }
        }
        return n.decimalValue();
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new CalcInputException("missing or non-textual input: " + field);
        }
        return n.asText();
    }

    private static LocalDate date(JsonNode in, String field) {
        try {
            return LocalDate.parse(text(in, field));
        } catch (DateTimeParseException e) {
            throw new CalcInputException("unparseable date (want YYYY-MM-DD): " + field);
        }
    }
}
