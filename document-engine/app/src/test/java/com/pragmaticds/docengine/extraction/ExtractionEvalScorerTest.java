package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.EvalCase;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.ExpectedDocument;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.ExpectedField;
import com.pragmaticds.docengine.extraction.ExtractionEvalCorpus.Layout;
import com.pragmaticds.docengine.extraction.ExtractionEvalReport.Counts;
import com.pragmaticds.docengine.extraction.ExtractionEvalReport.TypeMetric;
import com.pragmaticds.docengine.extraction.ExtractionEvalScorer.ActualDocument;
import com.pragmaticds.docengine.extraction.ExtractionEvalScorer.ActualField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two headline definitions from issue #59, pinned in milliseconds without a database: how
 * completeness and accuracy count, what they leave out, and that the scorer tallies a type it has
 * never heard of exactly like one it has — the proof that a new document type is labelled cases,
 * not code.
 */
class ExtractionEvalScorerTest {

    /** Exists nowhere but here. If scoring it needs code, the harness is not type-agnostic. */
    static final String UNKNOWN_TYPE = "SYNTHETIC_TYPE_X";

    @Test
    void completeness_is_filled_over_expected_present_and_accuracy_is_correct_over_filled() {
        // Four labelled fields: one right, one wrong, one missing, one the document does not print.
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "right#", captured("10.00"),
                                "wrong#", captured("99.99"),
                                "missing#", empty(),
                                "absent#", empty()));

        ExtractionEvalReport report =
                new ExtractionEvalScorer().score(corpus, Map.of("sample", List.of(observed)));

        TypeMetric metric = report.byType().get(UNKNOWN_TYPE);
        Counts counts = metric.counts();
        assertThat(counts.fieldsExpected()).as("absent field is not expected-present").isEqualTo(3);
        assertThat(counts.filled()).isEqualTo(2);
        assertThat(counts.correct()).isEqualTo(1);
        assertThat(counts.absentExpected()).isEqualTo(1);
        assertThat(metric.completeness()).isEqualByComparingTo("0.6667");
        assertThat(metric.accuracy()).isEqualByComparingTo("0.5000");
        assertThat(counts.missing()).containsExactly("sample/document[0].missing#");
        assertThat(counts.wrong()).containsExactly("sample/document[0].wrong#");
        assertThat(counts.phantoms()).isEmpty();
        assertThat(counts.cases()).isEqualTo(1);
        assertThat(counts.documents()).isEqualTo(1);
        // The overall roll-up is the same arithmetic over every type.
        assertThat(report.completeness()).isEqualByComparingTo("0.6667");
        assertThat(report.accuracy()).isEqualByComparingTo("0.5000");
        assertThat(report.gatedMetrics()).containsKeys("completeness", "accuracy");
    }

    @Test
    void a_value_captured_where_the_document_prints_none_is_a_phantom_not_a_fill() {
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "right#", captured("10.00"),
                                "wrong#", captured("20.00"),
                                "missing#", captured("30.00"),
                                "absent#", captured("invented")));

        Counts counts =
                new ExtractionEvalScorer()
                        .score(corpus, Map.of("sample", List.of(observed)))
                        .byType()
                        .get(UNKNOWN_TYPE)
                        .counts();

        assertThat(counts.filled()).as("the phantom does not count as filled").isEqualTo(3);
        assertThat(counts.phantoms()).containsExactly("sample/document[0].absent#");
    }

    @Test
    void an_engine_that_captures_nothing_scores_zero_completeness_not_zero_accuracy() {
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of("right#", empty(), "wrong#", empty(), "missing#", empty(), "absent#", empty()));

        TypeMetric metric =
                new ExtractionEvalScorer()
                        .score(corpus, Map.of("sample", List.of(observed)))
                        .byType()
                        .get(UNKNOWN_TYPE);

        assertThat(metric.completeness()).isEqualByComparingTo("0");
        // Nothing filled means nothing wrong; completeness is the number that tells the story.
        assertThat(metric.accuracy()).isEqualByComparingTo("1");
        assertThat(metric.counts().missing()).hasSize(3);
    }

    @Test
    void a_misclassified_document_charges_every_printed_field_as_missing() {
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");
        ActualDocument observed = new ActualDocument("SOMETHING_ELSE", Map.of());

        TypeMetric metric =
                new ExtractionEvalScorer()
                        .score(corpus, Map.of("sample", List.of(observed)))
                        .byType()
                        .get(UNKNOWN_TYPE);

        assertThat(metric.classificationAccuracy()).isEqualByComparingTo("0");
        assertThat(metric.completeness()).isEqualByComparingTo("0");
        assertThat(metric.counts().missing()).hasSize(3);
    }

    @Test
    void a_document_the_splitter_never_produced_charges_its_fields_as_missing() {
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");

        TypeMetric metric =
                new ExtractionEvalScorer()
                        .score(corpus, Map.of("sample", List.of()))
                        .byType()
                        .get(UNKNOWN_TYPE);

        assertThat(metric.completeness()).isEqualByComparingTo("0");
        assertThat(metric.counts().documents()).isEqualTo(1);
        assertThat(metric.counts().missing()).hasSize(3);
    }

    @Test
    void types_are_discovered_from_the_cases_and_tallied_separately() {
        ExtractionEvalCorpus corpus =
                ExtractionEvalCorpus.of(
                        List.of(
                                evalCase("one", "TYPE_ALPHA", fields()),
                                evalCase("two", "TYPE_BETA", fields())));
        Map<String, List<ActualDocument>> observed = new LinkedHashMap<>();
        observed.put(
                "one",
                List.of(new ActualDocument("TYPE_ALPHA", Map.of("right#", captured("10.00")))));
        observed.put("two", List.of(new ActualDocument("TYPE_BETA", Map.of())));

        ExtractionEvalReport report = new ExtractionEvalScorer().score(corpus, observed);

        assertThat(report.byType()).containsOnlyKeys("TYPE_ALPHA", "TYPE_BETA");
        assertThat(report.byType().get("TYPE_ALPHA").counts().filled()).isEqualTo(1);
        assertThat(report.byType().get("TYPE_BETA").counts().filled()).isZero();
        assertThat(report.counts().cases()).isEqualTo(2);
    }

    // ── NPI at rest: what a mismatch line may carry ─────────────────────────

    @Test
    void a_sensitive_field_never_puts_its_value_in_a_mismatch_line() {
        Map<String, ExpectedField> fields = new LinkedHashMap<>();
        fields.put("ssn#", ExpectedField.present("ssn", "123-45-6789"));
        fields.put("absentSsn#", ExpectedField.absent("absentSsn"));
        ExtractionEvalCorpus corpus =
                ExtractionEvalCorpus.of(List.of(evalCase("sample", UNKNOWN_TYPE, fields)));
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "ssn#", sensitive("987-65-4321"),
                                "absentSsn#", sensitive("111-22-3333")));

        ExtractionEvalReport report =
                new ExtractionEvalScorer().score(corpus, Map.of("sample", List.of(observed)));

        assertThat(report.toJson())
                .doesNotContain("123-45-6789", "987-65-4321", "111-22-3333")
                .contains("sample/document[0].ssn# value expected=«redacted» actual=«redacted»")
                .contains(
                        "sample/document[0].absentSsn# captured «redacted» but the fixture draws no"
                                + " such field");
        // Redaction hides the value, not the finding.
        assertThat(report.counts().wrong()).containsExactly("sample/document[0].ssn#");
        assertThat(report.counts().phantoms()).containsExactly("sample/document[0].absentSsn#");
    }

    @Test
    void a_real_document_report_carries_field_names_and_kinds_but_no_values() {
        // A corpus/eval case is synthetic=false: every value in it is a borrower's.
        ExtractionEvalCorpus corpus =
                ExtractionEvalCorpus.of(List.of(evalCase("real", UNKNOWN_TYPE, fields(), false)));
        ActualDocument observed =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "right#", captured("10.00"),
                                "wrong#", captured("99.99"),
                                "missing#", empty(),
                                "absent#", captured("invented")));

        ExtractionEvalReport report =
                new ExtractionEvalScorer().score(corpus, Map.of("real", List.of(observed)));

        assertThat(report.toJson())
                .doesNotContain("expected=\"", "actual=\"", "captured \"", "99.99", "invented", "20.00", "30.00")
                .contains("real/document[0].wrong# wrong value")
                .contains("real/document[0].missing# missing")
                .contains("real/document[0].absent# phantom value");
        assertThat(report.counts().wrong()).containsExactly("real/document[0].wrong#");
        assertThat(report.counts().missing()).containsExactly("real/document[0].missing#");
        assertThat(report.counts().phantoms()).containsExactly("real/document[0].absent#");
    }

    @Test
    void an_extra_document_the_splitter_invented_charges_its_captured_fields_as_phantoms() {
        ExtractionEvalCorpus corpus = corpus(UNKNOWN_TYPE, "sample");
        ActualDocument theOneExpected =
                new ActualDocument(
                        UNKNOWN_TYPE,
                        Map.of(
                                "right#", captured("10.00"),
                                "wrong#", captured("20.00"),
                                "missing#", captured("30.00"),
                                "absent#", empty()));
        ActualDocument duplicate =
                new ActualDocument(UNKNOWN_TYPE, Map.of("right#", captured("10.00"), "missing#", empty()));

        ExtractionEvalReport report =
                new ExtractionEvalScorer()
                        .score(corpus, Map.of("sample", List.of(theOneExpected, duplicate)));

        TypeMetric metric = report.byType().get(UNKNOWN_TYPE);
        // The document the case expected is perfect, and the headline pair says so…
        assertThat(metric.completeness()).isEqualByComparingTo("1");
        assertThat(metric.accuracy()).isEqualByComparingTo("1");
        // …but the duplicate's captured field is a named phantom and drops the gated capture rate.
        assertThat(metric.counts().phantoms()).containsExactly("sample/document[1].right#");
        assertThat(metric.fieldCaptureRate()).isLessThan(java.math.BigDecimal.ONE);
        assertThat(metric.counts().documents()).isEqualTo(2);
        assertThat(report.caseResult("sample").mismatches())
                .contains("document count expected=1 actual=2")
                .contains("sample/document[1].right# phantom value on a document the case does not expect");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    static ExtractionEvalCorpus corpus(String type, String caseId) {
        return ExtractionEvalCorpus.of(List.of(evalCase(caseId, type, fields())));
    }

    /** right, wrong, missing (all expected present) and absent (the document prints none). */
    static Map<String, ExpectedField> fields() {
        Map<String, ExpectedField> fields = new LinkedHashMap<>();
        fields.put("right#", ExpectedField.present("right", "10.00"));
        fields.put("wrong#", ExpectedField.present("wrong", "20.00"));
        fields.put("missing#", ExpectedField.present("missing", "30.00"));
        fields.put("absent#", ExpectedField.absent("absent"));
        return fields;
    }

    static EvalCase evalCase(String id, String type, Map<String, ExpectedField> fields) {
        return evalCase(id, type, fields, true);
    }

    static EvalCase evalCase(
            String id, String type, Map<String, ExpectedField> fields, boolean synthetic) {
        return new EvalCase(
                id,
                id,
                Layout.NONE,
                synthetic,
                null,
                List.of(),
                List.of(new ExpectedDocument(type, fields)),
                JsonNodeFactory.instance.objectNode());
    }

    static ActualField captured(String text) {
        return new ActualField(text, null, null, null, "ANCHOR_LABEL", false, 1);
    }

    static ActualField sensitive(String text) {
        return new ActualField(text, null, null, null, "ANCHOR_LABEL", true, 1);
    }

    static ActualField empty() {
        return new ActualField(null, null, null, null, "NONE", false, 0);
    }
}
