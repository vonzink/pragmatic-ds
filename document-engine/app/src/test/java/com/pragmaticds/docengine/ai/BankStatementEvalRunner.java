package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Check;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.DateCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.MoneyCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.TextCell;
import com.pragmaticds.docengine.platform.ai.BankStatementExtraction.Txn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/** Scores golden provider responses through the production parser, anchor, and reconciliation gate. */
final class BankStatementEvalRunner {

    private static final Predicate<FieldFact> SUMMARY_FIELD = fact -> fact.path().startsWith("summary.");
    private static final Predicate<FieldFact> MONEY_OR_DATE =
            fact -> fact.kind() == Kind.MONEY || fact.kind() == Kind.DATE;
    private final AiEvidenceAnchor anchor = new AiEvidenceAnchor();
    private final BankStatementReconciler reconciler = new BankStatementReconciler();

    BankStatementEvalReport evaluate(BankStatementEvalCorpus corpus) {
        return evaluate(corpus, BankStatementEvalCorpus.EvalCase::golden);
    }

    BankStatementEvalReport evaluate(
            BankStatementEvalCorpus corpus,
            Function<BankStatementEvalCorpus.EvalCase, BankStatementExtraction> provider) {
        Counts field = new Counts();
        Map<String, Counts> fieldsByName = new TreeMap<>();
        Count ledger = new Count();
        Count direction = new Count();
        Count anchors = new Count();
        Count reconciliation = new Count();
        Count reconciled = new Count();
        Count unsafe = new Count();
        Count autoAccepted = new Count();
        Count expectedOutcomes = new Count();
        int manualReviewCases = 0;
        int straightThroughCases = 0;
        List<BankStatementEvalReport.CaseResult> caseResults = new ArrayList<>();

        for (BankStatementEvalCorpus.EvalCase testCase : corpus.cases()) {
            BankStatementExtraction actualExtraction =
                    Objects.requireNonNull(
                            provider.apply(testCase),
                            "provider returned no extraction for " + testCase.id());
            Map<String, FieldFact> expectedFacts = facts(testCase.expected());
            Map<String, FieldFact> actualFacts = facts(actualExtraction);
            scoreFields(expectedFacts, actualFacts, field, fieldsByName);
            scoreLedger(testCase.expected().transactions(), actualExtraction.transactions(), ledger);
            scoreDirections(testCase.expected().transactions(), actualExtraction.transactions(), direction);

            Map<String, AiEvidenceAnchor.Status> actualAnchors =
                    actualAnchors(testCase, actualFacts);
            boolean anchorOutcomesMatched = true;
            List<String> outcomeMismatches = new ArrayList<>();
            for (Map.Entry<String, AiEvidenceAnchor.Status> actual : actualAnchors.entrySet()) {
                AiEvidenceAnchor.Status expectedAnchor = testCase.expectedAnchor(actual.getKey());
                boolean matched = actual.getValue() == expectedAnchor;
                anchors.add(matched);
                expectedOutcomes.add(matched);
                anchorOutcomesMatched &= matched;
                if (!matched) {
                    outcomeMismatches.add(
                            actual.getKey() + " expected=" + expectedAnchor + " actual=" + actual.getValue());
                }
            }

            BankStatementReconciler.Status status =
                    reconciler.reconcile(actualExtraction).status();
            boolean reconciliationMatched = status == testCase.expectedReconciliation();
            if (!reconciliationMatched) {
                outcomeMismatches.add(
                        "reconciliation expected="
                                + testCase.expectedReconciliation()
                                + " actual="
                                + status);
            }
            reconciliation.add(reconciliationMatched);
            expectedOutcomes.add(reconciliationMatched);
            if (testCase.expectedReconciliation() == BankStatementReconciler.Status.RECONCILED) {
                reconciled.add(status == BankStatementReconciler.Status.RECONCILED);
            } else {
                unsafe.add(status != BankStatementReconciler.Status.RECONCILED);
            }

            List<FieldFact> acceptedFacts =
                    actualFacts.values().stream()
                            .filter(MONEY_OR_DATE)
                            .filter(this::eligibleForAutoAcceptance)
                            .filter(fact -> actualAnchors.get(fact.path()) == AiEvidenceAnchor.Status.MATCHED)
                            .filter(fact -> status == BankStatementReconciler.Status.RECONCILED)
                            .toList();
            for (FieldFact accepted : acceptedFacts) {
                autoAccepted.add(accepted.sameValue(expectedFacts.get(accepted.path())));
            }
            boolean forbiddenAccepted =
                    acceptedFacts.stream()
                            .map(FieldFact::value)
                            .anyMatch(testCase.outcomes().forbiddenAutoAcceptedValues()::contains);

            boolean straightThrough =
                    status == BankStatementReconciler.Status.RECONCILED
                            && actualAnchors.values().stream()
                                    .allMatch(value -> value == AiEvidenceAnchor.Status.MATCHED);
            if (straightThrough) {
                straightThroughCases++;
            } else {
                manualReviewCases++;
            }
            boolean straightThroughMatched = straightThrough == testCase.outcomes().straightThrough();
            if (!straightThroughMatched) {
                outcomeMismatches.add(
                        "straightThrough expected="
                                + testCase.outcomes().straightThrough()
                                + " actual="
                                + straightThrough);
            }
            if (forbiddenAccepted) {
                outcomeMismatches.add("forbidden value was auto-accepted");
            }
            expectedOutcomes.add(straightThroughMatched);
            expectedOutcomes.add(!forbiddenAccepted);
            caseResults.add(
                    new BankStatementEvalReport.CaseResult(
                            testCase.id(),
                            status,
                            straightThrough,
                            anchorOutcomesMatched
                                    && reconciliationMatched
                                    && straightThroughMatched
                                    && !forbiddenAccepted,
                            forbiddenAccepted,
                            outcomeMismatches));
        }

        return new BankStatementEvalReport(
                ratio(field.truePositive, field.truePositive + field.falsePositive),
                ratio(field.truePositive, field.truePositive + field.falseNegative),
                fieldMetrics(fieldsByName),
                ledger.ratio(),
                direction.ratio(),
                anchors.ratio(),
                reconciliation.ratio(),
                reconciled.ratio(),
                unsafe.ratio(),
                ratio(manualReviewCases, corpus.cases().size()),
                autoAccepted.ratio(),
                ratio(straightThroughCases, corpus.cases().size()),
                expectedOutcomes.ratio(),
                caseResults);
    }

    private static void scoreFields(
            Map<String, FieldFact> expected,
            Map<String, FieldFact> actual,
            Counts counts,
            Map<String, Counts> fieldsByName) {
        Map<String, FieldFact> expectedSummary = filter(expected, SUMMARY_FIELD);
        Map<String, FieldFact> actualSummary = filter(actual, SUMMARY_FIELD);
        Set<String> paths = new TreeSet<>(expectedSummary.keySet());
        paths.addAll(actualSummary.keySet());
        for (String path : paths) {
            FieldFact expectedFact = expectedSummary.get(path);
            FieldFact actualFact = actualSummary.get(path);
            Counts fieldCounts = fieldsByName.computeIfAbsent(path, ignored -> new Counts());
            if (actualFact != null && actualFact.sameValue(expectedFact)) {
                counts.truePositive++;
                fieldCounts.truePositive++;
            } else {
                if (actualFact != null) {
                    counts.falsePositive++;
                    fieldCounts.falsePositive++;
                }
                if (expectedFact != null) {
                    counts.falseNegative++;
                    fieldCounts.falseNegative++;
                }
            }
        }
    }

    private static Map<String, BankStatementEvalReport.FieldMetric> fieldMetrics(
            Map<String, Counts> fieldsByName) {
        Map<String, BankStatementEvalReport.FieldMetric> metrics = new LinkedHashMap<>();
        fieldsByName.forEach(
                (path, counts) ->
                        metrics.put(
                                path,
                                new BankStatementEvalReport.FieldMetric(
                                        ratio(
                                                counts.truePositive,
                                                counts.truePositive + counts.falsePositive),
                                        ratio(
                                                counts.truePositive,
                                                counts.truePositive + counts.falseNegative))));
        return metrics;
    }

    private static Map<String, FieldFact> filter(
            Map<String, FieldFact> source, Predicate<FieldFact> predicate) {
        Map<String, FieldFact> selected = new LinkedHashMap<>();
        source.forEach(
                (path, fact) -> {
                    if (predicate.test(fact)) {
                        selected.put(path, fact);
                    }
                });
        return selected;
    }

    private static void scoreLedger(List<Txn> expected, List<Txn> actual, Count count) {
        Map<LedgerRow, Integer> available = new HashMap<>();
        expected.forEach(row -> available.merge(LedgerRow.of(row), 1, Integer::sum));
        int correct = 0;
        for (Txn row : actual) {
            LedgerRow key = LedgerRow.of(row);
            int remaining = available.getOrDefault(key, 0);
            if (remaining > 0) {
                correct++;
                available.put(key, remaining - 1);
            }
        }
        count.correct += correct;
        count.total += Math.max(expected.size(), actual.size());
    }

    private static void scoreDirections(List<Txn> expected, List<Txn> actual, Count count) {
        int total = Math.max(expected.size(), actual.size());
        int correct = 0;
        for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) {
            if (expected.get(index).direction() == actual.get(index).direction()) {
                correct++;
            }
        }
        count.correct += correct;
        count.total += total;
    }

    private Map<String, AiEvidenceAnchor.Status> actualAnchors(
            BankStatementEvalCorpus.EvalCase testCase, Map<String, FieldFact> facts) {
        Map<String, AiEvidenceAnchor.Status> result = new LinkedHashMap<>();
        Map<Integer, List<TextSpan>> spansByPage = testCase.spansByPage();
        facts.values().stream()
                .filter(fact -> fact.kind() != Kind.DIRECTION)
                .forEach(
                        fact ->
                                result.put(
                                        fact.path(),
                                        anchor.match(
                                                        fact.printedText(),
                                                        spansByPage.getOrDefault(
                                                                fact.page(), List.of()))
                                                .status()));
        facts.values().stream()
                .filter(fact -> fact.kind() == Kind.DIRECTION)
                .forEach(
                        fact -> {
                            String amountPath = fact.path().replace(".direction", ".amount");
                            result.put(
                                    fact.path(),
                                    result.getOrDefault(
                                            amountPath, AiEvidenceAnchor.Status.UNANCHORED));
                        });
        return result;
    }

    private boolean eligibleForAutoAcceptance(FieldFact fact) {
        if (fact.path().startsWith("transactions[")) {
            return true;
        }
        return fact.path().equals("summary.beginningBalance")
                || fact.path().equals("summary.endingBalance")
                || fact.path().equals("summary.totalDeposits")
                || fact.path().equals("summary.totalWithdrawals");
    }

    private static Map<String, FieldFact> facts(BankStatementExtraction extraction) {
        Map<String, FieldFact> facts = new LinkedHashMap<>();
        BankStatementExtraction.Summary summary = extraction.summary();
        add(facts, "summary.bankName", summary.bankName());
        add(facts, "summary.accountHolderName", summary.accountHolderName());
        add(facts, "summary.accountHolderAddress", summary.accountHolderAddress());
        add(facts, "summary.accountNumber", summary.accountNumber());
        add(facts, "summary.statementPeriodStart", summary.statementPeriodStart());
        add(facts, "summary.statementPeriodEnd", summary.statementPeriodEnd());
        add(facts, "summary.beginningBalance", summary.beginningBalance());
        add(facts, "summary.endingBalance", summary.endingBalance());
        add(facts, "summary.totalDeposits", summary.totalDeposits());
        add(facts, "summary.totalWithdrawals", summary.totalWithdrawals());
        for (int index = 0; index < extraction.transactions().size(); index++) {
            Txn transaction = extraction.transactions().get(index);
            String prefix = "transactions[" + index + "].";
            add(facts, prefix + "date", transaction.date());
            add(facts, prefix + "description", transaction.description());
            add(facts, prefix + "amount", transaction.amount());
            add(facts, prefix + "balance", transaction.balance());
            if (transaction.direction() != null) {
                facts.put(
                        prefix + "direction",
                        new FieldFact(
                                prefix + "direction",
                                Kind.DIRECTION,
                                transaction.direction().name(),
                                null,
                                transaction.page()));
            }
        }
        for (int index = 0; index < extraction.checks().size(); index++) {
            Check check = extraction.checks().get(index);
            String prefix = "checks[" + index + "].";
            add(facts, prefix + "checkNumber", check.checkNumber());
            add(facts, prefix + "datePaid", check.datePaid());
            add(facts, prefix + "amount", check.amount());
        }
        return Map.copyOf(facts);
    }

    private static void add(Map<String, FieldFact> facts, String path, TextCell cell) {
        if (cell != null && cell.value() != null) {
            facts.put(path, new FieldFact(path, Kind.TEXT, cell.value(), cell.text(), cell.page()));
        }
    }

    private static void add(Map<String, FieldFact> facts, String path, DateCell cell) {
        if (cell != null && cell.value() != null) {
            facts.put(
                    path,
                    new FieldFact(path, Kind.DATE, cell.value().toString(), cell.text(), cell.page()));
        }
    }

    private static void add(Map<String, FieldFact> facts, String path, MoneyCell cell) {
        if (cell != null && cell.value() != null) {
            facts.put(
                    path,
                    new FieldFact(
                            path,
                            Kind.MONEY,
                            cell.value().stripTrailingZeros().toPlainString(),
                            cell.text(),
                            cell.page()));
        }
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ONE.setScale(4);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private enum Kind {
        TEXT,
        DATE,
        MONEY,
        DIRECTION
    }

    private record FieldFact(
            String path, Kind kind, String value, String printedText, Integer page) {
        boolean sameValue(FieldFact other) {
            return other != null && kind == other.kind && Objects.equals(value, other.value);
        }
    }

    private record LedgerRow(
            LocalDate date,
            String amount,
            BankStatementExtraction.Direction direction,
            String normalizedDescription) {
        static LedgerRow of(Txn transaction) {
            return new LedgerRow(
                    transaction.date() == null ? null : transaction.date().value(),
                    transaction.amount() == null || transaction.amount().value() == null
                            ? null
                            : transaction.amount().value().stripTrailingZeros().toPlainString(),
                    transaction.direction(),
                    normalize(
                            transaction.description() == null
                                    ? null
                                    : transaction.description().value()));
        }

        private static String normalize(String value) {
            return value == null
                    ? null
                    : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    private static final class Counts {
        private long truePositive;
        private long falsePositive;
        private long falseNegative;
    }

    private static final class Count {
        private long correct;
        private long total;

        void add(boolean value) {
            total++;
            if (value) {
                correct++;
            }
        }

        BigDecimal ratio() {
            return BankStatementEvalRunner.ratio(correct, total);
        }
    }
}
