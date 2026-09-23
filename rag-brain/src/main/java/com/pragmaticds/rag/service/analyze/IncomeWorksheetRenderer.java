package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the income "Calculation worksheet" appended to an income-domain-v2 report (spec
 * 2026-09-14 A6). Pure and layout-only, like {@link AssetsReportRenderer}: every figure is read
 * from a calculation's inputs, result value, or intermediates — never recomputed — so a layout
 * change can never alter a number. The only literals printed are the fixed periods-per-year
 * multipliers and the divisor 12 that the calculator itself used.
 *
 * <p>A calculation that did not compute is printed as {@code not computed: <error>} on its own
 * line, and a source without a calculator figure says so, so a failure is never hidden by layout.
 */
@Service
public class IncomeWorksheetRenderer {

    private static final int LABEL_WIDTH = 15;
    private static final String INDENT = "  ";

    private record Line(int rank, String label, String content) {}

    public String render(JsonNode envelope) {
        if (!IncomeDomainEnricher.appliesTo(envelope)) {
            return "";
        }
        JsonNode domain = envelope.path("domain");
        Map<String, JsonNode> calcById = new LinkedHashMap<>();
        for (JsonNode calc : envelope.path("calculations")) {
            calcById.putIfAbsent(calc.path("id").asText(), calc);
        }
        JsonNode borrowers = domain.path("borrowers");
        boolean nameBorrowers = borrowers.size() > 1;

        StringBuilder sb = new StringBuilder("## Calculation worksheet\n\n```text\n");
        boolean anySource = false;
        for (int b = 0; b < borrowers.size(); b++) {
            for (JsonNode source : borrowers.get(b).path("sources")) {
                if (anySource) {
                    sb.append('\n');
                }
                anySource = true;
                appendSource(sb, b + 1, source, borrowers.get(b).path("sources"), calcById, nameBorrowers);
            }
        }
        if (anySource) {
            sb.append('\n');
        }
        String requestedTotal = totalLine(domain.get("totalCalcId"), calcById);
        sb.append("Total qualifying monthly income: ")
          .append(domain.path("totalAdjustedForDuplicates").asBoolean() && domain.path("computedTotalMonthly").isNumber()
                  ? usd(domain.get("computedTotalMonthly")) + " (duplicate employment counted once; the requested"
                          + " total was " + requestedTotal + ")"
                  : requestedTotal)
          .append('\n');
        String reconciliationId = textOrNull(domain.get("reconciliationCalcId"));
        if (reconciliationId != null) {
            JsonNode adjusted = domain.get("adjustedReconciliation");
            if (domain.path("totalAdjustedForDuplicates").asBoolean() && adjusted != null && adjusted.isObject()) {
                // The model's reconciliation compared the pre-adjustment total; print the engine's re-run.
                sb.append("Application total: ").append(varianceLine(adjusted, reconciliationId))
                  .append(" (against the adjusted total)\n");
            } else {
                sb.append("Application total: ")
                  .append(varianceLine(calcById.get(reconciliationId), reconciliationId)).append('\n');
            }
        }
        return sb.append("```").toString();
    }

    private static void appendSource(StringBuilder sb, int borrowerIndex, JsonNode source, JsonNode siblings,
                                     Map<String, JsonNode> calcById, boolean nameBorrowers) {
        if (nameBorrowers) {
            sb.append("Borrower ").append(borrowerIndex).append(" · ");
        }
        sb.append(source.path("employer").isTextual() ? source.path("employer").asText() : "Unnamed source")
          .append(" — ").append(source.path("type").asText("income")).append('\n');

        List<String> ids = new ArrayList<>();
        String qualifyingId = textOrNull(source.get("qualifyingCalcId"));
        if (qualifyingId != null) {
            ids.add(qualifyingId);
        }
        for (JsonNode idNode : source.path("calcIds")) {
            String id = textOrNull(idNode);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (String id : ids) {
            JsonNode calc = calcById.get(id);
            if (calc == null) {
                lines.add(new Line(3, "Calculation:", notRequested(id)));
                continue;
            }
            String method = calc.path("method").asText();
            int rank = rankFor(method);
            if (rank < 0) {
                // Totals and variances are normally rendered on their own lines; but one that
                // did not compute would otherwise vanish with no trace, so surface it here.
                if (!"COMPUTED".equals(calc.path("result").path("status").asText())) {
                    lines.add(new Line(3, "Calculation:", calculationLine(calc)));
                }
                continue;
            }
            lines.add(new Line(rank, labelFor(rank), calculationLine(calc)));
        }
        lines.sort(Comparator.comparingInt(Line::rank));   // stable: request order within a rank
        String previousLabel = null;
        for (Line line : lines) {
            line(sb, line.label().equals(previousLabel) ? "" : line.label(), line.content());
            previousLabel = line.label();
        }

        line(sb, "Qualifying:", qualifyingLine(source.path("computed")));
        JsonNode duplicateOf = source.path("computed").get("duplicateOf");
        if (duplicateOf != null && duplicateOf.canConvertToInt()) {
            JsonNode kept = siblings.path(duplicateOf.asInt());
            line(sb, "Counted:", "no — duplicate of "
                    + (kept.path("employer").isTextual() ? kept.path("employer").asText() : "source " + duplicateOf.asInt()));
        }
        String varianceId = textOrNull(source.get("varianceCalcId"));
        if (varianceId != null) {
            line(sb, "Application:", varianceLine(calcById.get(varianceId), varianceId));
        } else if (source.path("urlaStatedMonthly").isNumber()) {
            line(sb, "Application:", "stated " + usd(source.get("urlaStatedMonthly"))
                    + " (no variance calculation requested)");
        }
    }

    private static int rankFor(String method) {
        return switch (method) {
            case "income.monthly_from_rate.v1", "income.monthly_from_period_gross.v1",
                 "income.period_gross_from_ytd_delta.v1" -> 0;
            case "income.ytd_monthly_average.v1" -> 1;
            case "income.monthly_from_annual.v1" -> 2;
            default -> -1;
        };
    }

    private static String labelFor(int rank) {
        return switch (rank) {
            case 0 -> "Current pay:";
            case 1 -> "YTD check:";
            case 2 -> "Prior year:";
            default -> "Calculation:";
        };
    }

    private static String calculationLine(JsonNode calc) {
        JsonNode result = calc.path("result");
        if (!"COMPUTED".equals(result.path("status").asText())) {
            return "not computed: " + result.path("error").asText("calculation failed");
        }
        JsonNode in = calc.path("inputs");
        JsonNode mid = result.path("intermediates");
        String monthly = usd(result.get("value"));
        return switch (calc.path("method").asText()) {
            case "income.monthly_from_rate.v1" -> rateLine(in, mid, monthly);
            case "income.monthly_from_period_gross.v1" -> {
                String frequency = mid.path("inferredFrequency").asText();
                yield usd(in.get("gross")) + " × " + periodsPerYear(frequency) + " (" + frequency
                        + ", inferred from " + mid.path("periodDays").asText() + "-day period) = "
                        + usd(mid.get("annualized")) + " ÷ 12 = " + monthly;
            }
            case "income.period_gross_from_ytd_delta.v1" -> {
                String frequency = mid.path("frequency").asText();
                String label = mid.path("frequencyInferred").asBoolean()
                        ? frequency + ", inferred from " + mid.path("periodDays").asText() + "-day period"
                        : frequency;
                yield usd(in.get("ytdCurrent")) + " − " + usd(in.get("ytdPrior")) + " = "
                        + usd(mid.get("periodGross")) + " × " + periodsPerYear(frequency) + " (" + label
                        + ") = " + usd(mid.get("annualized")) + " ÷ 12 = " + monthly;
            }
            case "income.ytd_monthly_average.v1" -> usd(in.get("ytdAmount")) + " ÷ "
                    + mid.path("monthsElapsed").asText() + " months ("
                    + dateRange(in.path("periodStart").asText(), in.path("periodEnd").asText()) + ") = "
                    + monthly;
            case "income.monthly_from_annual.v1" -> calc.path("name").asText() + " "
                    + usd(in.get("annual")) + " ÷ 12 = " + monthly;
            default -> monthly;
        };
    }

    private static String rateLine(JsonNode in, JsonNode mid, String monthly) {
        String frequency = mid.path("frequency").asText();
        return switch (frequency) {
            case "HOURLY" -> usd(in.get("rate")) + "/hr × " + plain(in.get("hoursPerWeek"))
                    + " hrs/wk × 52 (HOURLY) = " + usd(mid.get("annualized")) + " ÷ 12 = " + monthly;
            case "ANNUAL" -> usd(in.get("rate")) + " (ANNUAL) ÷ 12 = " + monthly;
            default -> usd(in.get("rate")) + " × " + periodsPerYear(frequency) + " (" + frequency
                    + ") = " + usd(mid.get("annualized")) + " ÷ 12 = " + monthly;
        };
    }

    private static String periodsPerYear(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> "52";
            case "BIWEEKLY" -> "26";
            case "SEMIMONTHLY" -> "24";
            case "MONTHLY" -> "12";
            default -> "?";
        };
    }

    private static String qualifyingLine(JsonNode computed) {
        return switch (computed.path("basis").asText("NONE")) {
            case "CALCULATOR" -> usd(computed.get("monthly")) + " (" + qualifyingWords(computed.path("method").asText()) + ")";
            case "MODEL_STATED" -> usd(computed.get("monthly")) + " (model-stated, not verified by a calculation)";
            default -> "not computed — no calculation or stated figure";
        };
    }

    private static String qualifyingWords(String method) {
        return switch (method) {
            case "income.monthly_from_rate.v1", "income.monthly_from_period_gross.v1",
                 "income.period_gross_from_ytd_delta.v1" -> "current pay";
            case "income.ytd_monthly_average.v1" -> "YTD average";
            case "income.monthly_from_annual.v1" -> "annual income";
            default -> method;
        };
    }

    private static String varianceLine(JsonNode calc, String id) {
        if (calc == null) {
            return notRequested(id);
        }
        JsonNode result = calc.path("result");
        String stated = "stated " + usd(calc.path("inputs").get("stated"));
        if (!"COMPUTED".equals(result.path("status").asText())) {
            return stated + " → not computed: " + result.path("error").asText("calculation failed");
        }
        JsonNode mid = result.path("intermediates");
        return stated + " → variance " + signed(result.get("value"), "$", "") + " ("
                + signed(mid.get("variancePct"), "", "%") + "), "
                + (mid.path("material").asBoolean() ? "MATERIAL" : "not material");
    }

    private static String totalLine(JsonNode idNode, Map<String, JsonNode> calcById) {
        String id = textOrNull(idNode);
        if (id == null) {
            return "not computed (no total calculation requested)";
        }
        JsonNode calc = calcById.get(id);
        if (calc == null) {
            return notRequested(id);
        }
        JsonNode result = calc.path("result");
        return "COMPUTED".equals(result.path("status").asText())
                ? usd(result.get("value"))
                : "not computed: " + result.path("error").asText("calculation failed");
    }

    private static String notRequested(String id) {
        return "not computed: no calculation with id \"" + id + "\" was requested";
    }

    private static void line(StringBuilder sb, String label, String content) {
        sb.append(INDENT).append(label).append(" ".repeat(LABEL_WIDTH - label.length()))
          .append(content).append('\n');
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        try {
            return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** A new DecimalFormat per call: DecimalFormat is not thread-safe and this bean is shared. */
    private static String money(BigDecimal magnitude) {
        DecimalFormat format = new DecimalFormat("#,##0.00");
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(magnitude);
    }

    private static String usd(JsonNode n) {
        BigDecimal v = decimal(n);
        return v == null ? "—" : "$" + money(v.abs());
    }

    /** "+$1,119.83" / "−$12.00" (true minus, U+2212) / "$0.00"; percent when prefix is empty. */
    private static String signed(JsonNode n, String prefix, String suffix) {
        BigDecimal v = decimal(n);
        if (v == null) {
            return "—";
        }
        String sign = v.signum() > 0 ? "+" : v.signum() < 0 ? "−" : "";
        String body = prefix.isEmpty() ? v.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() : prefix + money(v.abs());
        return sign + body + suffix;
    }

    private static String plain(JsonNode n) {
        BigDecimal v = decimal(n);
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String dateRange(String startIso, String endIso) {
        LocalDate start = LocalDate.parse(startIso);
        LocalDate end = LocalDate.parse(endIso);
        String endText = String.format(Locale.US, "%02d/%02d/%d", end.getMonthValue(), end.getDayOfMonth(), end.getYear());
        String startText = start.getYear() == end.getYear()
                ? String.format(Locale.US, "%02d/%02d", start.getMonthValue(), start.getDayOfMonth())
                : String.format(Locale.US, "%02d/%02d/%d", start.getMonthValue(), start.getDayOfMonth(), start.getYear());
        return startText + "–" + endText;
    }
}
