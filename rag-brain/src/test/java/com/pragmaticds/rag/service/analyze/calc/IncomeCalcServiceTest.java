package com.pragmaticds.rag.service.analyze.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncomeCalcServiceTest {

    private final IncomeCalcService svc = new IncomeCalcService();
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode in(String json) {
        try { return om.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // --- income.monthly_from_annual.v1 ---

    @Test
    void monthlyFromAnnual_exact() {
        CalcResult r = svc.compute("income.monthly_from_annual.v1", in("{\"annual\": 90000}"));
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("7500.00"), r.value());
    }

    @Test
    void monthlyFromAnnual_roundsHalfUp() {
        CalcResult r = svc.compute("income.monthly_from_annual.v1", in("{\"annual\": 100}"));
        assertEquals(new BigDecimal("8.33"), r.value());
    }

    @Test
    void monthlyFromAnnual_missingInputFails() {
        CalcResult r = svc.compute("income.monthly_from_annual.v1", in("{}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
        assertTrue(r.error().contains("annual"));
    }

    @Test
    void negativeAnnualFails() {
        CalcResult r = svc.compute("income.monthly_from_annual.v1", in("{\"annual\": -1000}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
    }

    // --- income.monthly_from_rate.v1 ---

    @Test
    void hourly() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 25.50, \"frequency\": \"HOURLY\", \"hoursPerWeek\": 40}"));
        assertEquals(new BigDecimal("4420.00"), r.value());          // 25.50*40*52/12
        assertEquals(new BigDecimal("53040.00"), r.intermediates().get("annualized"));
    }

    @Test
    void weekly() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 1000, \"frequency\": \"WEEKLY\"}"));
        assertEquals(new BigDecimal("4333.33"), r.value());          // 52000/12
    }

    @Test
    void biweekly() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 2000, \"frequency\": \"BIWEEKLY\"}"));
        assertEquals(new BigDecimal("4333.33"), r.value());          // 52000/12
    }

    @Test
    void semimonthly() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 2500, \"frequency\": \"SEMIMONTHLY\"}"));
        assertEquals(new BigDecimal("5000.00"), r.value());
    }

    @Test
    void monthly() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 6500, \"frequency\": \"MONTHLY\"}"));
        assertEquals(new BigDecimal("6500.00"), r.value());
    }

    @Test
    void annualFrequency() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 90000, \"frequency\": \"ANNUAL\"}"));
        assertEquals(new BigDecimal("7500.00"), r.value());          // 90000/12, no multiply branch
    }

    @Test
    void hourlyWithoutHoursFails() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 25.50, \"frequency\": \"HOURLY\"}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("hoursPerWeek"));
    }

    @Test
    void zeroHoursFails() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 25.50, \"frequency\": \"HOURLY\", \"hoursPerWeek\": 0}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("hoursPerWeek"));
    }

    @Test
    void excessiveHoursFails() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 25.50, \"frequency\": \"HOURLY\", \"hoursPerWeek\": 169}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("hoursPerWeek"));
    }

    @Test
    void unknownFrequencyFails() {
        CalcResult r = svc.compute("income.monthly_from_rate.v1",
                in("{\"rate\": 10, \"frequency\": \"DAILY\"}"));
        assertEquals("ERROR", r.status());
    }

    // --- income.ytd_monthly_average.v1 ---

    @Test
    void ytdAverage_exact() {
        // Calendar-month convention: Jan 1 through Jun 30 2026 is exactly 6
        // whole months (start + 6 months lands on the day after periodEnd),
        // so monthsElapsed = 6.0000 regardless of days-in-month/leap years.
        // 12526.50 / 6 = 2087.75
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 12526.50, \"periodStart\": \"2026-01-01\", \"periodEnd\": \"2026-06-30\"}"));
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("2087.75"), r.value());
        assertEquals(181L, r.intermediates().get("daysElapsed"));
        assertEquals(new BigDecimal("6.0000"), r.intermediates().get("monthsElapsed"));
    }

    @Test
    void ytdAverage_endBeforeStartFails() {
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 100, \"periodStart\": \"2026-06-30\", \"periodEnd\": \"2026-01-01\"}"));
        assertEquals("ERROR", r.status());
    }

    @Test
    void ytdAverage_tooShortToAnnualizeFails() {
        // 0 whole months + 10/31 (January has 31 days) = 0.3226 months < 0.5 —
        // refuse rather than fabricate an annualized figure.
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 1000, \"periodStart\": \"2026-01-01\", \"periodEnd\": \"2026-01-10\"}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("0.5"));
    }

    @Test
    void ytdAverage_partialMonth() {
        // 6 whole months (Jan 1 -> Jul 1) + tail 15/31 (July has 31 days) =
        // 6 + 0.4839 = 6.4839 months. ytdAmount was chosen as
        // 1000 * 6.4839 = 6483.90 so the computed average is exactly 1000.00.
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 6483.90, \"periodStart\": \"2026-01-01\", \"periodEnd\": \"2026-07-15\"}"));
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("6.4839"), r.intermediates().get("monthsElapsed"));
        assertEquals(196L, r.intermediates().get("daysElapsed"));
        assertEquals(new BigDecimal("1000.00"), r.value());
    }

    @Test
    void ytdAverage_isLeapYearInvariant() {
        // The whole point of the calendar convention: a full year is 12 months whether
        // or not it contains a leap day, so the same salary yields the same monthly.
        CalcResult common = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 90000, \"periodStart\": \"2026-01-01\", \"periodEnd\": \"2026-12-31\"}"));
        CalcResult leap = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 90000, \"periodStart\": \"2028-01-01\", \"periodEnd\": \"2028-12-31\"}"));
        assertEquals(new BigDecimal("12.0000"), common.intermediates().get("monthsElapsed"));
        assertEquals(new BigDecimal("12.0000"), leap.intermediates().get("monthsElapsed"));
        assertEquals(new BigDecimal("7500.00"), common.value());
        assertEquals(new BigDecimal("7500.00"), leap.value());
    }

    @Test
    void ytdAverage_maxYearEndDateFailsClosed() {
        // end.plusDays(1) overflows the year range inside monthsElapsed; it must come
        // back as a structured ERROR, not a DateTimeException escaping compute().
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 1000, \"periodStart\": \"2026-01-01\", \"periodEnd\": \"+999999999-12-31\"}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
    }

    @Test
    void ytdAverage_unparseableDateFails() {
        CalcResult r = svc.compute("income.ytd_monthly_average.v1",
                in("{\"ytdAmount\": 100, \"periodStart\": \"Jan 1\", \"periodEnd\": \"2026-06-30\"}"));
        assertEquals("ERROR", r.status());
    }

    // --- income.total_monthly.v1 ---

    @Test
    void total() {
        CalcResult r = svc.compute("income.total_monthly.v1",
                in("{\"amounts\": [4420.00, 2106.50, 500]}"));
        assertEquals(new BigDecimal("7026.50"), r.value());
    }

    @Test
    void total_emptyFails() {
        CalcResult r = svc.compute("income.total_monthly.v1", in("{\"amounts\": []}"));
        assertEquals("ERROR", r.status());
    }

    @Test
    void negativeAmountFails() {
        CalcResult r = svc.compute("income.total_monthly.v1", in("{\"amounts\": [100, -50]}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
    }

    // --- income.variance.v1 ---

    @Test
    void variance_notMaterial() {
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 7026.50, \"stated\": 6500}"));
        assertEquals(new BigDecimal("526.50"), r.value());
        assertEquals(new BigDecimal("8.10"), r.intermediates().get("variancePct"));
        assertEquals(false, r.intermediates().get("material"));
    }

    @Test
    void variance_material() {
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 6000, \"stated\": 7500}"));
        assertEquals(new BigDecimal("-1500.00"), r.value());
        assertEquals(new BigDecimal("-20.00"), r.intermediates().get("variancePct"));
        assertEquals(true, r.intermediates().get("material"));
    }

    @Test
    void variance_statedZeroFails() {
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 6000, \"stated\": 0}"));
        assertEquals("ERROR", r.status());
    }

    @Test
    void negativeStatedFails() {
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 6000, \"stated\": -7500}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
    }

    @Test
    void variance_materialityBoundary_exactlyTenPercentNotMaterial() {
        // variance 1000 / stated 10000 = exactly 10.00% -> "more than 10%" is false.
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 11000, \"stated\": 10000}"));
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("10.00"), r.intermediates().get("variancePct"));
        assertEquals(false, r.intermediates().get("material"));
    }

    @Test
    void variance_intermediatesKeyOrderIsDeterministicAcrossRuns() {
        // CalcResult.computed must preserve insertion order (LinkedHashMap), not
        // Map.copyOf's per-JVM-salted ImmutableCollections.MapN order — otherwise
        // identical inputs would serialize the persisted findings jsonb differently
        // across restarts, defeating run reproducibility.
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 7026.50, \"stated\": 6500}"));
        assertEquals(List.of("variancePct", "material", "thresholdPct"),
                new ArrayList<>(r.intermediates().keySet()));
    }

    @Test
    void variance_materialityBoundary_justOverTenPercentMaterial() {
        // variance 1001 / stated 10000 = 10.01% -> just over the threshold.
        CalcResult r = svc.compute("income.variance.v1",
                in("{\"computed\": 11001, \"stated\": 10000}"));
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("10.01"), r.intermediates().get("variancePct"));
        assertEquals(true, r.intermediates().get("material"));
    }

    // --- income.monthly_from_period_gross.v1 ---

    private static final String PERIOD = "income.monthly_from_period_gross.v1";

    private CalcResult period(String gross, String start, String end) {
        return svc.compute(PERIOD, in("{\"gross\": " + gross + ", \"periodStart\": \"" + start
                + "\", \"periodEnd\": \"" + end + "\"}"));
    }

    /** The inferred frequency, or "ERROR" — for the band-boundary table below. */
    private String inferred(String start, String end) {
        CalcResult r = period("1000", start, end);
        return r.isComputed() ? (String) r.intermediates().get("inferredFrequency") : "ERROR";
    }

    @Test
    void periodGross_isSupported() {
        assertTrue(IncomeCalcService.SUPPORTED_METHODS.contains(PERIOD));
    }

    @Test
    void periodGross_biweeklyFromAFourteenDayPeriod() {
        // The loan-1000000253 figure: $4,030.77 × 26 = $104,800.02, ÷ 12 = 8733.335 → 8733.34.
        CalcResult r = period("4030.77", "2026-02-02", "2026-02-15");
        assertEquals("COMPUTED", r.status());
        assertEquals(new BigDecimal("8733.34"), r.value());
        assertEquals(14L, r.intermediates().get("periodDays"));
        assertEquals("BIWEEKLY", r.intermediates().get("inferredFrequency"));
        assertEquals(new BigDecimal("104800.02"), r.intermediates().get("annualized"));
        assertEquals(List.of("periodDays", "inferredFrequency", "annualized"),
                new ArrayList<>(r.intermediates().keySet()));
    }

    @Test
    void periodGross_semimonthlyWhenTheShortPeriodStartsOnTheFirstOrSixteenth() {
        CalcResult first = period("5000", "2026-03-01", "2026-03-15");
        assertEquals("SEMIMONTHLY", first.intermediates().get("inferredFrequency"));
        assertEquals(new BigDecimal("120000.00"), first.intermediates().get("annualized"));
        assertEquals(new BigDecimal("10000.00"), first.value());
        assertEquals("SEMIMONTHLY", inferred("2026-03-16", "2026-03-31"));   // 16 days
        // A first-half semimonthly period always ends on the 15th; 01/01–01/14 is a biweekly period.
        assertEquals("BIWEEKLY", inferred("2026-03-01", "2026-03-14"));      // 14 days, not ending on the 15th
        assertEquals("BIWEEKLY", inferred("2026-01-01", "2026-01-14"));
        assertEquals("SEMIMONTHLY", inferred("2026-01-01", "2026-01-15"));
        // A February second half is 13 days (14 in a leap year) and still semimonthly.
        assertEquals("SEMIMONTHLY", inferred("2026-02-16", "2026-02-28"));   // 13, non-leap
        assertEquals("SEMIMONTHLY", inferred("2028-02-16", "2028-02-29"));   // 14, leap
        assertEquals("BIWEEKLY", inferred("2026-02-14", "2026-02-26"));      // 13, not a semimonthly half
    }

    @Test
    void ytdDelta_adjacentPriorPeriodIsAccepted() {
        CalcResult r = svc.compute("income.period_gross_from_ytd_delta.v1", in("""
                {"ytdPrior": 104050.46, "ytdCurrent": 109767.76, "periodStart": "2026-08-16",
                 "periodEnd": "2026-08-29", "priorPeriodEnd": "2026-08-15"}"""));
        assertEquals("COMPUTED", r.status(), r.error());
        assertEquals(new BigDecimal("12387.48"), r.value());
    }

    @Test
    void ytdDelta_aSkippedStubFails() {
        // 08/15 and 09/12 YTDs: a stub between them was skipped, so the delta is two periods' pay.
        CalcResult r = svc.compute("income.period_gross_from_ytd_delta.v1", in("""
                {"ytdPrior": 104050.46, "ytdCurrent": 115485.06, "periodStart": "2026-08-30",
                 "periodEnd": "2026-09-12", "priorPeriodEnd": "2026-08-15"}"""));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("not consecutive"), r.error());
        assertEquals("ERROR", svc.compute("income.period_gross_from_ytd_delta.v1", in("""
                {"ytdPrior": 1, "ytdCurrent": 2, "periodStart": "2026-08-16",
                 "periodEnd": "2026-08-29", "priorPeriodEnd": "15 Aug"}""")).status());
    }

    @Test
    void periodGross_weeklyAndMonthly() {
        CalcResult weekly = period("1000", "2026-03-02", "2026-03-08");
        assertEquals("WEEKLY", weekly.intermediates().get("inferredFrequency"));
        assertEquals(new BigDecimal("4333.33"), weekly.value());             // 52000 / 12
        CalcResult monthly = period("9000", "2026-02-01", "2026-02-28");
        assertEquals("MONTHLY", monthly.intermediates().get("inferredFrequency"));
        assertEquals(new BigDecimal("9000.00"), monthly.value());
    }

    @Test
    void periodGross_bandBoundaries() {
        assertEquals("ERROR", inferred("2026-03-02", "2026-03-05"));        // 4
        assertEquals("WEEKLY", inferred("2026-03-02", "2026-03-06"));       // 5
        assertEquals("WEEKLY", inferred("2026-03-02", "2026-03-10"));       // 9
        assertEquals("ERROR", inferred("2026-03-02", "2026-03-11"));        // 10
        assertEquals("ERROR", inferred("2026-03-02", "2026-03-12"));        // 11
        assertEquals("BIWEEKLY", inferred("2026-03-02", "2026-03-13"));     // 12
        assertEquals("BIWEEKLY", inferred("2026-03-03", "2026-03-18"));     // 16, starts on the 3rd
        assertEquals("BIWEEKLY", inferred("2026-08-16", "2026-08-29"));     // 14, starts on the 16th, not month end
        assertEquals("SEMIMONTHLY", inferred("2026-09-16", "2026-09-30"));  // 15, 16th to month end
        assertEquals("ERROR", inferred("2026-03-02", "2026-03-18"));        // 17
        assertEquals("ERROR", inferred("2026-03-02", "2026-03-27"));        // 26
        assertEquals("MONTHLY", inferred("2026-02-01", "2026-02-27"));      // 27
        assertEquals("MONTHLY", inferred("2026-03-01", "2026-04-01"));      // 32
        assertEquals("ERROR", inferred("2026-03-01", "2026-04-02"));        // 33
    }

    @Test
    void periodGross_unsupportedLengthNamesTheReason() {
        CalcResult r = period("1000", "2026-03-02", "2026-03-11");
        assertEquals("ERROR", r.status());
        assertNull(r.value());
        assertTrue(r.error().startsWith("period length does not match a supported pay frequency"),
                r.error());
    }

    @Test
    void periodGross_rejectsBadInputs() {
        assertEquals("ERROR", period("-1", "2026-03-01", "2026-03-15").status());
        assertTrue(period("1000", "2026-03-15", "2026-03-01").error().contains("periodEnd is before periodStart"));
        assertTrue(svc.compute(PERIOD, in("{\"periodStart\":\"2026-03-01\",\"periodEnd\":\"2026-03-15\"}"))
                .error().contains("gross"));
        assertTrue(svc.compute(PERIOD, in("{\"gross\":1000,\"periodStart\":\"03/01/2026\",\"periodEnd\":\"2026-03-15\"}"))
                .error().contains("periodStart"));
    }

    // --- dispatch ---

    @Test
    void unsupportedMethodFails() {
        CalcResult r = svc.compute("assets.reserves.v1", in("{}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("unsupported method"));
    }

    @Test
    void supportedMethodsConstantAgreesWithDispatch() {
        // Every id SUPPORTED_METHODS claims dispatchable must actually reach the switch —
        // an empty-object input errors on a MISSING input (proving the id was recognized),
        // never on "unsupported method" (which would prove the constant and the switch
        // have drifted apart).
        for (String method : IncomeCalcService.SUPPORTED_METHODS) {
            CalcResult r = svc.compute(method, in("{}"));
            assertEquals("ERROR", r.status(), method + " should fail on missing inputs, not succeed");
            assertFalse(r.error().contains("unsupported method"),
                    method + " is listed in SUPPORTED_METHODS but the dispatch switch doesn't recognize it: "
                            + r.error());
        }
    }

    // --- income.period_gross_from_ytd_delta.v1 ---

    private static final String DELTA = "income.period_gross_from_ytd_delta.v1";

    private CalcResult delta(String prior, String current, String start, String end, String frequency) {
        return svc.compute(DELTA, in("{\"ytdPrior\": " + prior + ", \"ytdCurrent\": " + current
                + ", \"periodStart\": \"" + start + "\", \"periodEnd\": \"" + end + "\""
                + (frequency == null ? "" : ", \"frequency\": \"" + frequency + "\"") + "}"));
    }

    @Test
    void ytdDelta_isSupported() {
        assertTrue(IncomeCalcService.SUPPORTED_METHODS.contains(DELTA));
    }

    @Test
    void ytdDelta_goughConsecutiveStubs() {
        // Loan Gough, 2026-09: stubs 08/02–08/15 (YTD $104,050.46) and 08/16–08/29 (YTD $109,767.76).
        CalcResult r = delta("104050.46", "109767.76", "2026-08-16", "2026-08-29", null);
        assertEquals("COMPUTED", r.status(), r.error());
        assertEquals(new BigDecimal("12387.48"), r.value());
        assertEquals(new BigDecimal("5717.30"), r.intermediates().get("periodGross"));
        assertEquals("BIWEEKLY", r.intermediates().get("frequency"));
        assertEquals(true, r.intermediates().get("frequencyInferred"));
        assertEquals(new BigDecimal("148649.80"), r.intermediates().get("annualized"));
        assertEquals(List.of("periodGross", "frequency", "frequencyInferred", "annualized", "periodDays"),
                new ArrayList<>(r.intermediates().keySet()));
        assertEquals(14L, r.intermediates().get("periodDays"));
    }

    @Test
    void ytdDelta_givenFrequencyIsNotInferred() {
        CalcResult r = delta("104050.46", "109767.76", "2026-08-16", "2026-08-29", "BIWEEKLY");
        assertEquals(new BigDecimal("12387.48"), r.value());
        assertEquals(false, r.intermediates().get("frequencyInferred"));
    }

    @Test
    void ytdDelta_eachInferredBand() {
        assertEquals("WEEKLY", delta("0", "1000", "2026-08-03", "2026-08-09", null).intermediates().get("frequency"));
        assertEquals(new BigDecimal("4333.33"), delta("0", "1000", "2026-08-03", "2026-08-09", null).value());
        assertEquals("BIWEEKLY", delta("0", "1000", "2026-08-03", "2026-08-16", null).intermediates().get("frequency"));
        assertEquals("SEMIMONTHLY", delta("0", "1000", "2026-08-16", "2026-08-31", null).intermediates().get("frequency"));
        assertEquals(new BigDecimal("2000.00"), delta("0", "1000", "2026-08-16", "2026-08-31", null).value());
        assertEquals("MONTHLY", delta("0", "1000", "2026-08-01", "2026-08-31", null).intermediates().get("frequency"));
        assertEquals(new BigDecimal("1000.00"), delta("0", "1000", "2026-08-01", "2026-08-31", null).value());
    }

    @Test
    void ytdDelta_givenFrequencyMustMatchThePeriodLength() {
        CalcResult r = delta("0", "1000", "2026-08-01", "2026-08-31", "WEEKLY");
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("period length"), r.error());
        assertEquals("ERROR", delta("0", "1000", "2026-08-03", "2026-08-16", "FORTNIGHTLY").status());
    }

    @Test
    void ytdDelta_priorAboveCurrentFails() {
        CalcResult r = delta("109767.76", "104050.46", "2026-08-16", "2026-08-29", null);
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("ytdPrior"), r.error());
    }

    @Test
    void ytdDelta_unmatchedLengthFails() {
        CalcResult r = delta("0", "1000", "2026-08-01", "2026-08-21", null);
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("period length"), r.error());
    }

    @Test
    void ytdDelta_missingOrNegativeInputFails() {
        assertEquals("ERROR", svc.compute(DELTA, in(
                "{\"ytdCurrent\": 1000, \"periodStart\": \"2026-08-03\", \"periodEnd\": \"2026-08-16\"}")).status());
        assertEquals("ERROR", svc.compute(DELTA, in(
                "{\"ytdPrior\": 0, \"ytdCurrent\": 1000, \"periodStart\": \"2026-08-03\"}")).status());
        assertEquals("ERROR", delta("-5", "1000", "2026-08-03", "2026-08-16", null).status());
        assertEquals("ERROR", delta("0", "1000", "2026-08-16", "2026-08-03", null).status());
    }

    @Test
    void nullInputsFails() {
        CalcResult r = svc.compute("income.total_monthly.v1", null);
        assertEquals("ERROR", r.status());
    }

    @Test
    void nullMethodFails() {
        CalcResult r = svc.compute(null, in("{}"));
        assertEquals("ERROR", r.status());
        assertTrue(r.error().contains("method"));
    }

    @Test
    void nonFiniteInputFails() {
        // 1e400 overflows double to Infinity; must fail closed rather than
        // let decimalValue() throw an uncaught NumberFormatException.
        CalcResult r = svc.compute("income.monthly_from_annual.v1", in("{\"annual\": 1e400}"));
        assertEquals("ERROR", r.status());
        assertNull(r.value());
    }
}
