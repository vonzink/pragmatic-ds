package com.pragmaticds.docengine.ai;

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

/** Immutable metrics emitted by one deterministic evaluation-corpus run. */
record BankStatementEvalReport(
        BigDecimal fieldPrecision,
        BigDecimal fieldRecall,
        Map<String, FieldMetric> summaryFieldMetrics,
        BigDecimal ledgerRowAccuracy,
        BigDecimal amountDirectionAccuracy,
        BigDecimal anchorAccuracy,
        BigDecimal reconciliationAccuracy,
        BigDecimal reconciledRecall,
        BigDecimal unsafeLedgerDetectionRate,
        BigDecimal manualReviewRate,
        BigDecimal autoAcceptedMoneyDatePrecision,
        BigDecimal straightThroughCoverage,
        BigDecimal expectedOutcomeAccuracy,
        List<CaseResult> cases) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    BankStatementEvalReport {
        summaryFieldMetrics =
                Collections.unmodifiableMap(new LinkedHashMap<>(summaryFieldMetrics));
        cases = List.copyOf(cases);
    }

    CaseResult caseResult(String id) {
        return cases.stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown eval result: " + id));
    }

    String toJson() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("fieldPrecision", fieldPrecision);
        output.put("fieldRecall", fieldRecall);
        output.put("summaryFieldMetrics", summaryFieldMetrics);
        output.put("ledgerRowAccuracy", ledgerRowAccuracy);
        output.put("amountDirectionAccuracy", amountDirectionAccuracy);
        output.put("anchorAccuracy", anchorAccuracy);
        output.put("reconciliationAccuracy", reconciliationAccuracy);
        output.put("reconciledRecall", reconciledRecall);
        output.put("unsafeLedgerDetectionRate", unsafeLedgerDetectionRate);
        output.put("manualReviewRate", manualReviewRate);
        output.put("autoAcceptedMoneyDatePrecision", autoAcceptedMoneyDatePrecision);
        output.put("straightThroughCoverage", straightThroughCoverage);
        output.put("expectedOutcomeAccuracy", expectedOutcomeAccuracy);
        output.put("releaseBar", new BigDecimal("0.9950"));
        output.put(
                "releaseBarPassed",
                autoAcceptedMoneyDatePrecision.compareTo(new BigDecimal("0.995")) >= 0);
        output.put("cases", cases);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to serialize eval report", failure);
        }
    }

    void writeJson(Path destination) {
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.writeString(destination, toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to write eval report", failure);
        }
    }

    String toSummaryTable() {
        return """
                AI extraction synthetic evaluation
                metric                                  value
                auto-accepted money/date precision      %s
                straight-through coverage               %s
                field precision                         %s
                field recall                            %s
                ledger-row accuracy                     %s
                direction accuracy                      %s
                anchor accuracy                         %s
                reconciliation accuracy                 %s
                manual-review rate                      %s
                """
                .formatted(
                        percent(autoAcceptedMoneyDatePrecision),
                        percent(straightThroughCoverage),
                        percent(fieldPrecision),
                        percent(fieldRecall),
                        percent(ledgerRowAccuracy),
                        percent(amountDirectionAccuracy),
                        percent(anchorAccuracy),
                        percent(reconciliationAccuracy),
                        percent(manualReviewRate));
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    record CaseResult(
            String id,
            BankStatementReconciler.Status reconciliationStatus,
            boolean straightThrough,
            boolean expectedOutcomesMatched,
            boolean forbiddenValueAutoAccepted,
            List<String> outcomeMismatches) {
        CaseResult {
            outcomeMismatches = List.copyOf(outcomeMismatches);
        }
    }

    record FieldMetric(BigDecimal precision, BigDecimal recall) {}
}
