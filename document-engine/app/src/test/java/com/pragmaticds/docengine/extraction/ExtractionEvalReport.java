package com.pragmaticds.docengine.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable metrics from one deterministic-extraction evaluation run, overall and per document
 * type. Mirrors {@code BankStatementEvalReport}'s shape on purpose: the two harnesses are meant to
 * converge onto one reporting core once the AI path serves more than two dialects.
 *
 * @param completeness filled ÷ expected-present fields — the issue #59 headline
 * @param accuracy correct ÷ filled fields — its companion; see {@link ExtractionEvalScorer}
 * @param fieldsAsserted how many field-level capture assertions the corpus made
 * @param valuesAsserted how many of those pinned an exact value — published so a reader can tell a
 *     thoroughly checked type from a loosely pinned one instead of reading 100% as equal rigour
 * @param counts the raw numbers behind the two headline ratios, with the field names that went
 *     missing or came back wrong, so a regression names the field and not just the percentage
 */
record ExtractionEvalReport(
        BigDecimal completeness,
        BigDecimal accuracy,
        BigDecimal classificationAccuracy,
        BigDecimal fieldCaptureRate,
        BigDecimal valuePrecision,
        BigDecimal valueRecall,
        BigDecimal normalizationAccuracy,
        BigDecimal methodAccuracy,
        BigDecimal evidenceCoverage,
        BigDecimal schemaCoverage,
        long fieldsAsserted,
        long valuesAsserted,
        Counts counts,
        Map<String, TypeMetric> byType,
        List<CaseResult> cases) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    ExtractionEvalReport {
        byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
        cases = List.copyOf(cases);
    }

    CaseResult caseResult(String id) {
        return cases.stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown eval result: " + id));
    }

    /** Every metric that carries a regression floor, by the key the baseline file uses. */
    Map<String, BigDecimal> gatedMetrics() {
        return gatedMetrics(
                completeness,
                accuracy,
                classificationAccuracy,
                fieldCaptureRate,
                valuePrecision,
                valueRecall,
                normalizationAccuracy,
                methodAccuracy,
                evidenceCoverage);
    }

    private static Map<String, BigDecimal> gatedMetrics(
            BigDecimal completeness,
            BigDecimal accuracy,
            BigDecimal classificationAccuracy,
            BigDecimal fieldCaptureRate,
            BigDecimal valuePrecision,
            BigDecimal valueRecall,
            BigDecimal normalizationAccuracy,
            BigDecimal methodAccuracy,
            BigDecimal evidenceCoverage) {
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("completeness", completeness);
        metrics.put("accuracy", accuracy);
        metrics.put("classificationAccuracy", classificationAccuracy);
        metrics.put("fieldCaptureRate", fieldCaptureRate);
        metrics.put("valuePrecision", valuePrecision);
        metrics.put("valueRecall", valueRecall);
        metrics.put("normalizationAccuracy", normalizationAccuracy);
        metrics.put("methodAccuracy", methodAccuracy);
        metrics.put("evidenceCoverage", evidenceCoverage);
        return metrics;
    }

    String toJson() {
        Map<String, Object> output = new LinkedHashMap<>(gatedMetrics());
        output.put("schemaCoverage", schemaCoverage);
        output.put("fieldsAsserted", fieldsAsserted);
        output.put("valuesAsserted", valuesAsserted);
        output.put("counts", counts);
        output.put("byType", byType);
        output.put("cases", cases);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to serialize eval report", failure);
        }
    }

    void writeJson(Path destination) {
        write(destination, toJson());
    }

    static void write(Path destination, String content) {
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.writeString(destination, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to write eval report " + destination, failure);
        }
    }

    String toSummaryTable() {
        StringBuilder table = new StringBuilder();
        table.append("Deterministic extraction evaluation\n");
        table.append(
                """
                metric                        value
                completeness                  %s  (%d of %d expected fields filled)
                accuracy                      %s  (%d of %d filled fields correct)
                classification accuracy       %s
                field capture rate            %s
                value precision               %s
                value recall                  %s
                normalization accuracy        %s
                method accuracy               %s
                evidence coverage             %s
                schema coverage (measured)    %s
                """
                        .formatted(
                                percent(completeness),
                                counts.filled(),
                                counts.fieldsExpected(),
                                percent(accuracy),
                                counts.correct(),
                                counts.filled(),
                                percent(classificationAccuracy),
                                percent(fieldCaptureRate),
                                percent(valuePrecision),
                                percent(valueRecall),
                                percent(normalizationAccuracy),
                                percent(methodAccuracy),
                                percent(evidenceCoverage),
                                percent(schemaCoverage)));
        table.append(
                "\n%-20s %5s %6s %6s %7s %8s %8s %8s %8s\n"
                        .formatted(
                                "type", "cases", "fields", "filled", "correct", "complete",
                                "accurate", "class", "flds"));
        byType.forEach(
                (type, metric) ->
                        table.append(
                                "%-20s %5d %6d %6d %7d %8s %8s %8s %8d\n"
                                        .formatted(
                                                type,
                                                metric.counts().cases(),
                                                metric.counts().fieldsExpected(),
                                                metric.counts().filled(),
                                                metric.counts().correct(),
                                                percent(metric.completeness()),
                                                percent(metric.accuracy()),
                                                percent(metric.classificationAccuracy()),
                                                metric.fieldsAsserted())));
        return table.toString();
    }

    static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    /**
     * The raw numbers behind completeness and accuracy.
     *
     * @param cases distinct corpus cases that contributed a document
     * @param documents logical documents scored (a case can split into several)
     * @param fieldsExpected fields the document prints and the engine must capture
     * @param filled expected fields the engine captured anything for
     * @param correct filled fields whose captured value matched
     * @param absentExpected fields the fixture deliberately omits (not in any denominator)
     * @param missing {@code case/document[i].field#group} for every expected field left empty
     * @param wrong the same coordinate for every filled field holding the wrong value
     * @param phantoms the same coordinate for every deliberately absent field that captured anyway
     */
    record Counts(
            long cases,
            long documents,
            long fieldsExpected,
            long filled,
            long correct,
            long absentExpected,
            List<String> missing,
            List<String> wrong,
            List<String> phantoms) {
        Counts {
            missing = List.copyOf(missing);
            wrong = List.copyOf(wrong);
            phantoms = List.copyOf(phantoms);
        }
    }

    record TypeMetric(
            BigDecimal completeness,
            BigDecimal accuracy,
            BigDecimal classificationAccuracy,
            BigDecimal fieldCaptureRate,
            BigDecimal valuePrecision,
            BigDecimal valueRecall,
            BigDecimal normalizationAccuracy,
            BigDecimal methodAccuracy,
            BigDecimal evidenceCoverage,
            BigDecimal schemaCoverage,
            long fieldsAsserted,
            long valuesAsserted,
            Counts counts) {

        Map<String, BigDecimal> gatedMetrics() {
            return ExtractionEvalReport.gatedMetrics(
                    completeness,
                    accuracy,
                    classificationAccuracy,
                    fieldCaptureRate,
                    valuePrecision,
                    valueRecall,
                    normalizationAccuracy,
                    methodAccuracy,
                    evidenceCoverage);
        }
    }

    record CaseResult(
            String id,
            List<String> expectedTypes,
            List<String> actualTypes,
            boolean matched,
            List<String> mismatches) {
        CaseResult {
            expectedTypes = List.copyOf(expectedTypes);
            actualTypes = List.copyOf(actualTypes);
            mismatches = List.copyOf(mismatches);
        }
    }
}
