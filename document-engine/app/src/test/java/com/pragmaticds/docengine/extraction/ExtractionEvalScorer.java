package com.pragmaticds.docengine.extraction;

import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.EvalCase;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.ExpectedDocument;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.ExpectedField;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Scores observed extraction against the corpus. Deliberately DB-free and Spring-free: it consumes
 * {@link ActualDocument} values the IT assembles from {@code extracted_field} rows, so every
 * scoring rule is unit-testable in milliseconds without Testcontainers — a scoring bug cannot hide
 * behind a Docker failure, and the rules can be reviewed without reading SQL.
 *
 * <p>The scorer knows no document type. A type is whatever string the corpus put on a document; it
 * is tallied under that string and nothing else. That is the property issue #59 asks for — adding a
 * type means adding labelled cases, never touching this class — and {@code
 * ExtractionEvalScorerTest} proves it with a type that exists nowhere but in the test.
 *
 * <p>Two headline numbers ride on top of the finer metrics, defined exactly as the issue defines
 * them so the build's number and the number someone would compute with {@code psql} agree:
 *
 * <ul>
 *   <li><b>completeness</b> = filled ÷ expected-present — of the fields the document actually
 *       prints, how many the engine captured anything for. Fields the fixture deliberately omits
 *       are NOT in the denominator; they are scored separately as phantoms if captured.
 *   <li><b>accuracy</b> = correct ÷ filled — of the fields the engine filled, how many hold the
 *       right value. An engine that captures nothing has an accuracy of 1.0 and a completeness of
 *       0.0; the two are published side by side so neither can flatter the other.
 * </ul>
 *
 * <p><b>What a mismatch line may carry.</b> The report is a file on disk, and the corpus run reads
 * {@code extracted_field} unmasked. So a value appears in a mismatch line only when the case is
 * synthetic AND the field is not sensitive; a sensitive field's values are {@code «redacted»}, and a
 * real document's lines carry the field's name and the kind of miss (missing / wrong / phantom) and
 * nothing else. The counts and field lists are the finding; the value was never needed to act on it.
 */
final class ExtractionEvalScorer {

    private static final String REDACTED = "«redacted»";

    /**
     * What the pipeline actually produced for one logical document.
     *
     * @param type the classified {@code document_type_code}
     * @param fields observed rows keyed by {@code fieldName#groupKey}
     */
    record ActualDocument(String type, Map<String, ActualField> fields) {
        ActualDocument {
            fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
        }
    }

    /**
     * One {@code extracted_field} row flattened to the columns the harness scores.
     *
     * <p>A null {@code displayedText} is the engine's own "captured nothing" signal: a missing field
     * still persists a row (confidence 0, method NONE), so counting ROWS proves nothing. That is
     * exactly the trap {@code W2ExtractionIT} documents, where the pre-Spec-4 W-2 persisted ten rows
     * and captured two values.
     *
     * @param sensitive the schema's {@code is_sensitive} flag — a sensitive value never reaches a
     *     report line, synthetic or not
     */
    record ActualField(
            String displayedText,
            String normalizedText,
            BigDecimal normalizedNumber,
            String normalizedDate,
            String method,
            boolean sensitive,
            int evidenceCount) {

        boolean captured() {
            return displayedText != null && !displayedText.isBlank();
        }
    }

    ExtractionEvalReport score(
            ExtractionEvalCorpus corpus, Map<String, List<ActualDocument>> actualByCaseId) {
        Tally overall = new Tally();
        Map<String, Tally> byType = new TreeMap<>();
        List<ExtractionEvalReport.CaseResult> caseResults = new ArrayList<>();

        for (EvalCase testCase : corpus.cases()) {
            List<ActualDocument> actualDocuments =
                    Objects.requireNonNull(
                            actualByCaseId.get(testCase.id()),
                            "no observed documents for eval case " + testCase.id());
            List<String> mismatches = new ArrayList<>();

            if (actualDocuments.size() != testCase.documents().size()) {
                mismatches.add(
                        "document count expected="
                                + testCase.documents().size()
                                + " actual="
                                + actualDocuments.size());
            }
            int paired = Math.min(actualDocuments.size(), testCase.documents().size());
            for (int index = 0; index < paired; index++) {
                ExpectedDocument expected = testCase.documents().get(index);
                Tally typeTally = byType.computeIfAbsent(expected.type(), ignored -> new Tally());
                overall.cases.add(testCase.id());
                typeTally.cases.add(testCase.id());
                scoreDocument(
                        testCase.id(),
                        testCase.synthetic(),
                        index,
                        expected,
                        actualDocuments.get(index),
                        overall,
                        typeTally,
                        mismatches);
            }
            // A document the corpus expected but the splitter never produced is every one of its
            // fields going missing at once; charge them, or a splitter regression that drops a
            // whole document would read as 100% complete on the documents that survived.
            for (int index = paired; index < testCase.documents().size(); index++) {
                ExpectedDocument expected = testCase.documents().get(index);
                Tally typeTally = byType.computeIfAbsent(expected.type(), ignored -> new Tally());
                overall.cases.add(testCase.id());
                typeTally.cases.add(testCase.id());
                overall.documents++;
                typeTally.documents++;
                for (ExpectedField field : expected.fields().values()) {
                    if (field.mustCapture()) {
                        String where = testCase.id() + "/document[" + index + "]." + field.key();
                        overall.expectMissing(where);
                        typeTally.expectMissing(where);
                    }
                }
            }
            // The mirror image: a document the splitter produced that the case never declared — a
            // duplicated W-2, a statement cut in two. Nothing in the corpus pins its fields, so it
            // cannot be scored for value; but every value it captured is a value the engine
            // invented a document for, which is what a phantom is. Charged under the type the
            // engine gave it, and it drops that type's gated capture rate, so an over-splitter
            // cannot read 100% / 100% on the documents that were expected.
            for (int index = paired; index < actualDocuments.size(); index++) {
                ActualDocument extra = actualDocuments.get(index);
                Tally typeTally = byType.computeIfAbsent(extra.type(), ignored -> new Tally());
                overall.cases.add(testCase.id());
                typeTally.cases.add(testCase.id());
                overall.documents++;
                typeTally.documents++;
                for (Map.Entry<String, ActualField> field : extra.fields().entrySet()) {
                    if (!field.getValue().captured()) {
                        continue;
                    }
                    String where = testCase.id() + "/document[" + index + "]." + field.getKey();
                    overall.phantom(where);
                    typeTally.phantom(where);
                    mismatches.add(where + " phantom value on a document the case does not expect");
                }
            }

            caseResults.add(
                    new ExtractionEvalReport.CaseResult(
                            testCase.id(),
                            testCase.documents().stream().map(ExpectedDocument::type).toList(),
                            actualDocuments.stream().map(ActualDocument::type).toList(),
                            mismatches.isEmpty(),
                            mismatches));
        }

        Map<String, ExtractionEvalReport.TypeMetric> typeMetrics = new LinkedHashMap<>();
        byType.forEach((type, tally) -> typeMetrics.put(type, tally.toTypeMetric()));
        return overall.toReport(typeMetrics, caseResults);
    }

    private void scoreDocument(
            String caseId,
            boolean synthetic,
            int ordinal,
            ExpectedDocument expected,
            ActualDocument actual,
            Tally overall,
            Tally typeTally,
            List<String> mismatches) {
        overall.documents++;
        typeTally.documents++;
        boolean typeMatched = expected.type().equals(actual.type());
        overall.classification.add(typeMatched);
        typeTally.classification.add(typeMatched);
        if (!typeMatched) {
            mismatches.add(
                    "document[%d] type expected=%s actual=%s"
                            .formatted(ordinal, expected.type(), actual.type()));
            // A misclassified document ran the WRONG schema, so its rows describe a different
            // document type entirely. Scoring them field by field would charge every field with a
            // failure that has exactly one cause, already recorded on the line above. For the
            // headline numbers, though, every printed field IS missing — the borrower's W-2 came
            // back with nothing — so completeness takes the hit while the finer metrics stay quiet.
            for (ExpectedField field : expected.fields().values()) {
                if (field.mustCapture()) {
                    String where = caseId + "/document[" + ordinal + "]." + field.key();
                    overall.expectMissing(where);
                    typeTally.expectMissing(where);
                }
            }
            return;
        }

        Set<String> pinned = new LinkedHashSet<>();
        for (ExpectedField field : expected.fields().values()) {
            pinned.add(field.key());
            scoreField(
                    caseId,
                    synthetic,
                    ordinal,
                    field,
                    actual.fields().get(field.key()),
                    overall,
                    typeTally,
                    mismatches);
        }

        // How much of what the engine produced this corpus actually checks. Published rather than
        // gated: a schema field the fixture does not draw is a fixture gap to close deliberately,
        // not a regression, and calling it one would train people to ignore the gate.
        for (String observed : actual.fields().keySet()) {
            boolean measured = pinned.contains(observed);
            overall.schemaCoverage.add(measured);
            typeTally.schemaCoverage.add(measured);
        }
    }

    private void scoreField(
            String caseId,
            boolean synthetic,
            int ordinal,
            ExpectedField expected,
            ActualField actual,
            Tally overall,
            Tally typeTally,
            List<String> mismatches) {
        String where = caseId + "/document[" + ordinal + "]." + expected.key();

        if (actual == null) {
            // No row at all: the schema does not declare a field the fixture draws. That is a
            // corpus/schema disagreement, not an extraction miss, and it must be loud.
            mismatches.add(where + " has no extracted_field row — the schema does not declare it");
            overall.capture.add(false);
            typeTally.capture.add(false);
            if (expected.mustCapture()) {
                overall.expectMissing(where);
                typeTally.expectMissing(where);
            } else {
                overall.absentExpected++;
                typeTally.absentExpected++;
            }
            return;
        }

        // Values reach a report line only for a synthetic case's non-sensitive field. A real
        // document's line names the field and the kind of miss; a sensitive field is redacted even
        // on a fixture, so the rule never depends on remembering which corpus is running.
        Wording wording =
                !synthetic ? Wording.NAMES_ONLY : actual.sensitive() ? Wording.REDACTED : Wording.VALUES;

        boolean captured = actual.captured();
        if (!expected.mustCapture()) {
            // The fixture deliberately omits this field. Capturing anything here means the rung
            // reached outside its anchor and invented a value — a false positive, which is worse
            // than the miss it looks like on a capture-rate chart.
            overall.capture.add(!captured);
            typeTally.capture.add(!captured);
            overall.absentExpected++;
            typeTally.absentExpected++;
            if (captured) {
                overall.phantoms.add(where);
                typeTally.phantoms.add(where);
                mismatches.add(
                        wording == Wording.NAMES_ONLY
                                ? where + " phantom value"
                                : "%s captured %s but the fixture draws no such field"
                                        .formatted(where, wording.quote(actual.displayedText())));
            }
            return;
        }
        overall.capture.add(captured);
        typeTally.capture.add(captured);
        if (!captured) {
            mismatches.add(where + (wording == Wording.NAMES_ONLY ? " missing" : " captured nothing"));
        }

        boolean correct = false;
        if (expected.assertsValue()) {
            correct = expected.displayedText().equals(actual.displayedText());
            overall.value.add(captured, correct);
            typeTally.value.add(captured, correct);
            if (!correct && captured) {
                mismatches.add(
                        wording == Wording.NAMES_ONLY
                                ? where + " wrong value"
                                : "%s value expected=%s actual=%s"
                                        .formatted(
                                                where,
                                                wording.quote(expected.displayedText()),
                                                wording.quote(actual.displayedText())));
            }
        }
        overall.expectPresent(where, captured, correct);
        typeTally.expectPresent(where, captured, correct);

        scoreNormalization(where, wording, expected, actual, overall, typeTally, mismatches);

        if (expected.method() != null) {
            boolean methodCorrect = expected.method().equals(actual.method());
            overall.method.add(methodCorrect);
            typeTally.method.add(methodCorrect);
            if (!methodCorrect) {
                // A rung name is not a value; it is the same wording on every corpus.
                mismatches.add(
                        "%s method wanted %s, got %s".formatted(where, expected.method(), actual.method()));
            }
        }
        if (captured) {
            // The engine's one guiding principle: every value traces to a page and a box — with
            // ONE deliberate exception. A DERIVED field (spec 2026-09-23 §4) is arithmetic over
            // other fields' already-anchored values, not a read off the page, and it persists
            // with NO field_evidence rows by design; charging that as a broken anchor would
            // regress the evidence-coverage floor on every derivation the schema declares.
            boolean anchored = actual.evidenceCount() > 0 || "DERIVED".equals(actual.method());
            overall.evidence.add(anchored);
            typeTally.evidence.add(anchored);
            if (!anchored) {
                mismatches.add(where + " captured a value with NO evidence rows");
            }
        }
    }

    private void scoreNormalization(
            String where,
            Wording wording,
            ExpectedField expected,
            ActualField actual,
            Tally overall,
            Tally typeTally,
            List<String> mismatches) {
        if (expected.normalizedNumber() != null) {
            boolean correct =
                    actual.normalizedNumber() != null
                            && new BigDecimal(expected.normalizedNumber())
                                            .compareTo(actual.normalizedNumber())
                                    == 0;
            record(overall, typeTally, correct, mismatches, correct ? null
                    : normalizationLine(where, wording, "normalizedNumber",
                            expected.normalizedNumber(), String.valueOf(actual.normalizedNumber())));
        }
        if (expected.normalizedDate() != null) {
            boolean correct = expected.normalizedDate().equals(actual.normalizedDate());
            record(overall, typeTally, correct, mismatches, correct ? null
                    : normalizationLine(where, wording, "normalizedDate",
                            expected.normalizedDate(), actual.normalizedDate()));
        }
        if (expected.normalizedText() != null) {
            boolean correct = expected.normalizedText().equals(actual.normalizedText());
            record(overall, typeTally, correct, mismatches, correct ? null
                    : normalizationLine(where, wording, "normalizedText",
                            expected.normalizedText(), actual.normalizedText()));
        }
    }

    private static String normalizationLine(
            String where, Wording wording, String what, String expected, String actual) {
        if (wording == Wording.NAMES_ONLY) {
            return where + " " + what + " wrong";
        }
        return "%s %s expected=%s actual=%s"
                .formatted(where, what, wording.quote(expected), wording.quote(actual));
    }

    private static void record(
            Tally overall, Tally typeTally, boolean correct, List<String> mismatches, String failure) {
        overall.normalization.add(correct);
        typeTally.normalization.add(correct);
        if (failure != null) {
            mismatches.add(failure);
        }
    }

    /** How much of a value a mismatch line may show. */
    private enum Wording {
        /** Synthetic case, ordinary field: the value, quoted. */
        VALUES,
        /** Sensitive field: the line keeps its shape, the value does not appear. */
        REDACTED,
        /** Real document: field name and kind of miss only. */
        NAMES_ONLY;

        String quote(String value) {
            return this == VALUES ? "\"" + value + "\"" : ExtractionEvalScorer.REDACTED;
        }
    }

    private static final class Tally {
        private final Set<String> cases = new LinkedHashSet<>();
        private long documents;
        private final Count classification = new Count();
        private final Count capture = new Count();
        private final PrecisionRecall value = new PrecisionRecall();
        private final Count normalization = new Count();
        private final Count method = new Count();
        private final Count evidence = new Count();
        private final Count schemaCoverage = new Count();

        // The headline pair, kept as raw counts so the report can print "7 of 10", not only 70%.
        private long fieldsExpected;
        private long filled;
        private long correct;
        private long absentExpected;
        private final List<String> missing = new ArrayList<>();
        private final List<String> wrong = new ArrayList<>();
        private final List<String> phantoms = new ArrayList<>();

        void expectMissing(String where) {
            fieldsExpected++;
            missing.add(where);
        }

        void expectPresent(String where, boolean captured, boolean valueCorrect) {
            fieldsExpected++;
            if (!captured) {
                missing.add(where);
                return;
            }
            filled++;
            if (valueCorrect) {
                correct++;
            } else {
                wrong.add(where);
            }
        }

        /** A captured value nothing in the corpus asked for: named, and a failed capture. */
        void phantom(String where) {
            phantoms.add(where);
            capture.add(false);
        }

        ExtractionEvalReport.TypeMetric toTypeMetric() {
            return new ExtractionEvalReport.TypeMetric(
                    ratio(filled, fieldsExpected),
                    ratio(correct, filled),
                    classification.ratio(),
                    capture.ratio(),
                    value.precision(),
                    value.recall(),
                    normalization.ratio(),
                    method.ratio(),
                    evidence.ratio(),
                    schemaCoverage.ratio(),
                    capture.total,
                    value.expected,
                    new ExtractionEvalReport.Counts(
                            cases.size(),
                            documents,
                            fieldsExpected,
                            filled,
                            correct,
                            absentExpected,
                            missing,
                            wrong,
                            phantoms));
        }

        ExtractionEvalReport toReport(
                Map<String, ExtractionEvalReport.TypeMetric> byType,
                List<ExtractionEvalReport.CaseResult> cases) {
            return new ExtractionEvalReport(
                    ratio(filled, fieldsExpected),
                    ratio(correct, filled),
                    classification.ratio(),
                    capture.ratio(),
                    value.precision(),
                    value.recall(),
                    normalization.ratio(),
                    method.ratio(),
                    evidence.ratio(),
                    schemaCoverage.ratio(),
                    capture.total,
                    value.expected,
                    new ExtractionEvalReport.Counts(
                            this.cases.size(),
                            documents,
                            fieldsExpected,
                            filled,
                            correct,
                            absentExpected,
                            missing,
                            wrong,
                            phantoms),
                    byType,
                    cases);
        }
    }

    /**
     * Value precision and recall over value-asserted fields. Precision is over the fields that
     * produced a value, recall over the fields the fixture says should have one — the two diverge
     * exactly when the engine goes QUIET rather than wrong, which is the failure mode a single
     * "accuracy" number hides and the one a rule-pack change causes most often.
     */
    private static final class PrecisionRecall {
        private long expected;
        private long produced;
        private long correct;

        void add(boolean captured, boolean matched) {
            expected++;
            if (captured) {
                produced++;
            }
            if (matched) {
                correct++;
            }
        }

        BigDecimal precision() {
            return ratio(correct, produced);
        }

        BigDecimal recall() {
            return ratio(correct, expected);
        }
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
            return ExtractionEvalScorer.ratio(correct, total);
        }
    }

    /** An empty denominator scores 1.0 — nothing was asserted, so nothing failed. */
    static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ONE.setScale(4);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
