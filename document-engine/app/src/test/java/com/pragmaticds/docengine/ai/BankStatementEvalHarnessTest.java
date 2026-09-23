package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BankStatementEvalHarnessTest {

    private static final Set<String> REQUIRED_COVERAGE =
            Set.of(
                    "clean-reconciling",
                    "unknown-continuation",
                    "debit-credit-columns",
                    "signed-amounts",
                    "parenthesized-negative",
                    "duplicate-amounts",
                    "duplicate-descriptions",
                    "repeated-check-numbers",
                    "missing-totals",
                    "partial-running-balances",
                    "page-boundary-ledger",
                    "one-cent-mismatch",
                    "prompt-injection");

    @Test
    void corpus_is_synthetic_and_covers_every_required_failure_mode() {
        BankStatementEvalCorpus corpus = BankStatementEvalCorpus.load();

        assertThat(corpus.cases()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(
                        corpus.cases().stream()
                                .flatMap(testCase -> testCase.coverage().stream())
                                .collect(Collectors.toSet()))
                .containsAll(REQUIRED_COVERAGE);
        assertThat(corpus.cases()).allMatch(BankStatementEvalCorpus.EvalCase::synthetic);
        assertThat(corpus.caseById("multi-page-unknown").assembledDocumentText())
                .contains("PAGE 2", "PAGE 3", "UNKNOWN CONTINUATION");
        assertThat(corpus.caseById("prompt-injection").assembledDocumentText())
                .containsIgnoringCase("ignore previous instructions");
    }

    @Test
    void deterministic_golden_run_reports_and_gates_release_metrics_separately() throws Exception {
        BankStatementEvalReport report =
                new BankStatementEvalRunner().evaluate(BankStatementEvalCorpus.load());

        assertThat(report.autoAcceptedMoneyDatePrecision())
                .isGreaterThanOrEqualTo(new BigDecimal("0.995"));
        assertThat(report.straightThroughCoverage()).isLessThan(BigDecimal.ONE);
        assertThat(report.summaryFieldMetrics())
                .containsKeys(
                        "summary.bankName",
                        "summary.statementPeriodStart",
                        "summary.beginningBalance",
                        "summary.endingBalance")
                .hasSize(10);
        assertThat(report.expectedOutcomeAccuracy())
                .as(report.toJson())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.toJson())
                .contains(
                        "autoAcceptedMoneyDatePrecision",
                        "straightThroughCoverage",
                        "fieldPrecision",
                        "fieldRecall",
                        "ledgerRowAccuracy",
                        "amountDirectionAccuracy",
                        "anchorAccuracy",
                        "reconciliationAccuracy",
                        "manualReviewRate");
        Path reportFile = Path.of("build/reports/ai-eval/summary.json");
        report.writeJson(reportFile);
        assertThat(Files.readString(reportFile))
                .contains("autoAcceptedMoneyDatePrecision", "straightThroughCoverage");
    }

    @Test
    void arithmetic_and_injection_traps_are_never_straight_through() {
        BankStatementEvalReport report =
                new BankStatementEvalRunner().evaluate(BankStatementEvalCorpus.load());

        assertThat(report.caseResult("one-cent-mismatch").reconciliationStatus())
                .isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(report.caseResult("one-cent-mismatch").straightThrough()).isFalse();
        assertThat(report.caseResult("prompt-injection").reconciliationStatus())
                .isEqualTo(BankStatementReconciler.Status.NON_RECONCILING);
        assertThat(report.caseResult("prompt-injection").straightThrough()).isFalse();
        assertThat(report.caseResult("prompt-injection").forbiddenValueAutoAccepted()).isFalse();
    }

    @Test
    void runner_accepts_provider_results_without_changing_the_corpus_or_metric_definitions() {
        BankStatementEvalCorpus corpus = BankStatementEvalCorpus.load();

        BankStatementEvalReport report =
                new BankStatementEvalRunner().evaluate(corpus, BankStatementEvalCorpus.EvalCase::expected);

        assertThat(report.fieldPrecision()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.fieldRecall()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.ledgerRowAccuracy()).isEqualByComparingTo(BigDecimal.ONE);
    }
}
