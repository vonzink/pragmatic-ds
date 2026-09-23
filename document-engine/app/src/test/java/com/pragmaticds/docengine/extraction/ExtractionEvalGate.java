package com.pragmaticds.docengine.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Baseline;
import com.pragmaticds.docengine.extraction.ExtractionEvalReport.Counts;
import com.pragmaticds.docengine.extraction.ExtractionEvalReport.TypeMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regression gate: a report against its committed floors, answered as a list of violations
 * rather than as the first assertion to trip. Pure — no Spring, no database — so the exact text a
 * red build prints is unit-tested ({@code ExtractionEvalGateTest}) and cannot drift into "assertion
 * failed" with the type and the field buried in a stack trace.
 *
 * <p>Two kinds of violation, both build-failing:
 *
 * <ul>
 *   <li>{@link Kind#REGRESSION} — a metric fell below its floor. The message names the scope (a
 *       document type, or {@code overall}), the metric, the two numbers, and every field that went
 *       missing or came back wrong for that type, because "W2 completeness 60%" is a fact and "W2
 *       lost employerEin and wagesTipsOtherComp" is a lead.
 *   <li>{@link Kind#STALE} — a metric beat its floor by more than {@link #STALE_BASELINE_MARGIN}.
 *       A floor that far below the measurement would let a later regression hide inside the gap,
 *       so the ratchet demands to be tightened.
 * </ul>
 */
final class ExtractionEvalGate {

    /**
     * How far a metric may exceed its floor before the baseline counts as stale. A run that beats
     * its floor by more than this has moved enough that leaving the floor down would let a later
     * regression hide inside the gap.
     */
    static final BigDecimal STALE_BASELINE_MARGIN = new BigDecimal("0.05");

    /** Floors are recorded slightly under the measured value so ordinary jitter is not a failure. */
    static final BigDecimal CALIBRATION_SLACK = new BigDecimal("0.01");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private ExtractionEvalGate() {}

    enum Kind {
        REGRESSION,
        STALE,
        /** The baseline names a type the corpus no longer covers — a silently unmeasured type. */
        UNCOVERED_TYPE,
        /** The baseline names a metric the report does not publish — a typo nobody would notice. */
        UNKNOWN_METRIC
    }

    record Violation(Kind kind, String scope, String metric, BigDecimal measured, BigDecimal floor) {
        String describe() {
            return switch (kind) {
                case REGRESSION ->
                        "%s.%s regressed below its committed floor: measured %s, floor %s"
                                .formatted(scope, metric, measured, floor);
                case STALE ->
                        ("%s.%s now scores %s against a floor of %s — re-run with"
                                        + " -Ddocengine.eval.calibrate=true and commit the raised"
                                        + " floors so the ratchet keeps holding")
                                .formatted(scope, metric, measured, floor);
                case UNCOVERED_TYPE ->
                        "baseline names type %s but the corpus covers none".formatted(scope);
                case UNKNOWN_METRIC ->
                        "baseline names unknown metric %s.%s".formatted(scope, metric);
            };
        }
    }

    /** One scope's verdict: the floors it was held to and every violation against them. */
    record Verdict(String scope, Map<String, Double> floors, List<Violation> violations) {
        Verdict {
            floors = new LinkedHashMap<>(floors);
            violations = List.copyOf(violations);
        }

        boolean gated() {
            return !floors.isEmpty();
        }

        boolean passed() {
            return violations.isEmpty();
        }

        String label() {
            return !gated() ? "not gated" : passed() ? "pass" : "FAIL";
        }
    }

    record Result(
            ExtractionEvalReport report,
            Verdict overall,
            Map<String, Verdict> byType,
            List<Violation> violations) {
        Result {
            byType = new LinkedHashMap<>(byType);
            violations = List.copyOf(violations);
        }

        boolean passed() {
            return violations.isEmpty();
        }

        /** True when at least one floor exists — a floor-less baseline gates nothing. */
        boolean gated() {
            return overall.gated() || byType.values().stream().anyMatch(Verdict::gated);
        }

        /**
         * The text a red build prints: every violation, then the fields behind each regressed type,
         * then the whole table — so the tail of a CI log is self-describing.
         */
        String describe() {
            StringBuilder text = new StringBuilder();
            if (passed()) {
                text.append("Extraction evaluation gate: PASS\n");
            } else {
                text.append("Extraction evaluation gate: FAIL (")
                        .append(violations.size())
                        .append(" violation")
                        .append(violations.size() == 1 ? "" : "s")
                        .append(")\n");
            }
            for (Violation violation : violations) {
                text.append("  - ").append(violation.describe()).append('\n');
            }
            byType.forEach(
                    (type, verdict) -> {
                        if (verdict.violations().stream().noneMatch(v -> v.kind() == Kind.REGRESSION)) {
                            return;
                        }
                        TypeMetric metric = report.byType().get(type);
                        if (metric != null) {
                            appendFields(text, type, metric.counts());
                        }
                    });
            if (overall.violations().stream().anyMatch(v -> v.kind() == Kind.REGRESSION)) {
                appendFields(text, "overall", report.counts());
            }
            text.append('\n').append(report.toSummaryTable());
            return text.toString();
        }

        private static void appendFields(StringBuilder text, String scope, Counts counts) {
            text.append("  ").append(scope).append(":\n");
            counts.missing().forEach(field -> text.append("    missing  ").append(field).append('\n'));
            counts.wrong().forEach(field -> text.append("    wrong    ").append(field).append('\n'));
            counts.phantoms().forEach(field -> text.append("    phantom  ").append(field).append('\n'));
        }

        /** The build artifact a reader opens: one row per document type, floors and verdict beside. */
        String toMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# Extraction completeness by document type\n\n");
            md.append(
                    "Deterministic path (rule packs + extraction schemas), no AI, no network. ")
                    .append(report.counts().cases())
                    .append(" cases, ")
                    .append(report.counts().documents())
                    .append(" documents, ")
                    .append(report.counts().fieldsExpected())
                    .append(" expected fields.\n\n");
            md.append("**Gate: ")
                    .append(
                            !gated()
                                    ? "not gated — no floors committed"
                                    : passed()
                                            ? "PASS"
                                            : "FAIL (" + violations.size() + " violation"
                                                    + (violations.size() == 1 ? "" : "s") + ")")
                    .append("**\n\n");
            md.append(
                    "| type | cases | fields | filled | correct | completeness | accuracy |"
                            + " floor (completeness) | floor (accuracy) | verdict |\n");
            md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            report.byType()
                    .forEach(
                            (type, metric) ->
                                    md.append(
                                            row(
                                                    type,
                                                    metric.counts(),
                                                    metric.completeness(),
                                                    metric.accuracy(),
                                                    byType.getOrDefault(
                                                            type,
                                                            new Verdict(type, Map.of(), List.of())))));
            md.append(
                    row(
                            "**overall**",
                            report.counts(),
                            report.completeness(),
                            report.accuracy(),
                            overall));
            boolean anyFields =
                    report.byType().values().stream()
                            .anyMatch(
                                    metric ->
                                            !metric.counts().missing().isEmpty()
                                                    || !metric.counts().wrong().isEmpty()
                                                    || !metric.counts().phantoms().isEmpty());
            if (anyFields) {
                md.append("\n## Missing, wrong and phantom fields\n\n");
                report.byType()
                        .forEach(
                                (type, metric) -> {
                                    Counts counts = metric.counts();
                                    if (counts.missing().isEmpty()
                                            && counts.wrong().isEmpty()
                                            && counts.phantoms().isEmpty()) {
                                        return;
                                    }
                                    md.append("- **").append(type).append("**\n");
                                    counts.missing()
                                            .forEach(f -> md.append("  - missing `").append(f).append("`\n"));
                                    counts.wrong()
                                            .forEach(f -> md.append("  - wrong `").append(f).append("`\n"));
                                    counts.phantoms()
                                            .forEach(f -> md.append("  - phantom `").append(f).append("`\n"));
                                });
            }
            if (!violations.isEmpty()) {
                md.append("\n## Violations\n\n");
                violations.forEach(v -> md.append("- ").append(v.describe()).append('\n'));
            }
            return md.toString();
        }

        private static String row(
                String label,
                Counts counts,
                BigDecimal completeness,
                BigDecimal accuracy,
                Verdict verdict) {
            return "| %s | %d | %d | %d | %d | %s | %s | %s | %s | %s |\n"
                    .formatted(
                            label,
                            counts.cases(),
                            counts.fieldsExpected(),
                            counts.filled(),
                            counts.correct(),
                            ExtractionEvalReport.percent(completeness),
                            ExtractionEvalReport.percent(accuracy),
                            floor(verdict, "completeness"),
                            floor(verdict, "accuracy"),
                            verdict.label());
        }

        private static String floor(Verdict verdict, String metric) {
            Double floor = verdict.floors().get(metric);
            return floor == null ? "—" : ExtractionEvalReport.percent(BigDecimal.valueOf(floor));
        }
    }

    /** Every floor in the baseline against the report; a floor-less baseline yields a passed, ungated result. */
    static Result check(ExtractionEvalReport report, Baseline baseline) {
        List<Violation> all = new ArrayList<>();
        Verdict overall = verdict("overall", report.gatedMetrics(), baseline.overall());
        all.addAll(overall.violations());

        Map<String, Verdict> byType = new LinkedHashMap<>();
        // Report order first, so the table reads the same way whether or not a type is gated…
        report.byType()
                .forEach(
                        (type, metric) -> {
                            Verdict verdict =
                                    verdict(
                                            type,
                                            metric.gatedMetrics(),
                                            baseline.byType().getOrDefault(type, Map.of()));
                            byType.put(type, verdict);
                            all.addAll(verdict.violations());
                        });
        // …then anything the baseline gates that the corpus no longer measures.
        baseline.byType()
                .forEach(
                        (type, floors) -> {
                            if (!report.byType().containsKey(type)) {
                                Violation uncovered =
                                        new Violation(Kind.UNCOVERED_TYPE, type, null, null, null);
                                byType.put(type, new Verdict(type, floors, List.of(uncovered)));
                                all.add(uncovered);
                            }
                        });
        return new Result(report, overall, byType, all);
    }

    private static Verdict verdict(
            String scope, Map<String, BigDecimal> measured, Map<String, Double> floors) {
        List<Violation> violations = new ArrayList<>();
        floors.forEach(
                (metric, floor) -> {
                    BigDecimal value = measured.get(metric);
                    BigDecimal required = BigDecimal.valueOf(floor);
                    if (value == null) {
                        violations.add(new Violation(Kind.UNKNOWN_METRIC, scope, metric, null, required));
                    } else if (value.compareTo(required) < 0) {
                        violations.add(new Violation(Kind.REGRESSION, scope, metric, value, required));
                    } else if (value.compareTo(required.add(STALE_BASELINE_MARGIN)) > 0) {
                        violations.add(new Violation(Kind.STALE, scope, metric, value, required));
                    }
                });
        return new Verdict(scope, floors, violations);
    }

    /** Measured floors, one notch below the observed values, as a ready-to-commit baseline file. */
    static String calibrate(ExtractionEvalReport report) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("overall", floors(report.gatedMetrics()));
        Map<String, Object> byType = new LinkedHashMap<>();
        report.byType().forEach((type, metric) -> byType.put(type, floors(metric.gatedMetrics())));
        baseline.put("byType", byType);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(baseline);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to serialize calibrated baseline", failure);
        }
    }

    private static Map<String, BigDecimal> floors(Map<String, BigDecimal> measured) {
        Map<String, BigDecimal> floors = new LinkedHashMap<>();
        measured.forEach(
                (metric, value) ->
                        floors.put(
                                metric,
                                value.subtract(CALIBRATION_SLACK)
                                        .max(BigDecimal.ZERO)
                                        .setScale(2, RoundingMode.DOWN)));
        return floors;
    }
}
