package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.CheckboxOption;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CHECKBOX_STATE: each option's label anchor binds to the nearest CHECKBOX detection whose
 * center sits within proximityPt of the label box's edge; exactly one checked binding wins the
 * field, anything else fails the rung (refuse to guess, D6). The detection's confidence rides
 * the spanConfidence slot; the VALUE evidence is the checkbox ELEMENT — box + element id, no
 * span.
 */
class CheckboxStateExtractionTest {

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    /** A 10×10pt checkbox detection at (x, y). */
    private static DetectionRef checkbox(
            UUID elementId, String x, String y, boolean checked, String confidence) {
        return new DetectionRef(
                elementId,
                LayoutElementType.CHECKBOX,
                new Box(
                        new BigDecimal(x),
                        new BigDecimal(y),
                        new BigDecimal("10.0"),
                        new BigDecimal("10.0")),
                checked,
                new BigDecimal(confidence));
    }

    private static PageContent page(List<SpanRef> spans, List<DetectionRef> detections) {
        return new PageContent(UUID.randomUUID(), 0, spans, List.of(), detections);
    }

    private static CheckboxOption option(String label, String value) {
        return new CheckboxOption(new LabelSpec(AnchorKind.LITERAL, label), value);
    }

    private static ExtractorSpec checkboxRung(
            double strength, double proximityPt, CheckboxOption... options) {
        return new ExtractorSpec(
                ExtractionMethod.CHECKBOX_STATE,
                strength,
                null,
                null,
                null,
                List.of(options),
                proximityPt,
                null);
    }

    private static FieldSpec filingStatus(ExtractorSpec... rungs) {
        return new FieldSpec("filingStatus", DataType.ENUM, true, null, false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec field) {
        return new SchemaDefinition("TAX_RETURN", "1.0.0", List.of(field));
    }

    /** "Filing Status:" header, "Single" at y=200, "Married filing jointly" at y=220. */
    private static List<SpanRef> filingStatusSpans() {
        return List.of(
                span(1, "Filing", "72.0", "180.0", "30.0", "10.0"),
                span(2, "Status:", "106.0", "180.0", "36.0", "10.0"),
                span(3, "Single", "140.0", "200.0", "30.0", "10.0"),
                span(4, "Married", "140.0", "220.0", "40.0", "10.0"),
                span(5, "filing", "184.0", "220.0", "26.0", "10.0"),
                span(6, "jointly", "214.0", "220.0", "34.0", "10.0"));
    }

    private static ExtractorSpec filingStatusRung() {
        return checkboxRung(
                0.9,
                18.0,
                option("Single", "SINGLE"),
                option("Married filing jointly", "MARRIED_FILING_JOINTLY"));
    }

    @Test
    void exactly_one_checked_box_yields_the_mapped_enum_with_element_backed_evidence() {
        UUID uncheckedId = UUID.randomUUID();
        UUID checkedId = UUID.randomUUID();
        // Checkbox centers: (125, 205) — 15pt left of the "Single" box edge (x=140);
        // (125, 225) — 15pt left of the "Married..." box edge. Both within the 18pt cap.
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(
                                checkbox(uncheckedId, "120.0", "200.0", false, "0.88"),
                                checkbox(checkedId, "120.0", "220.0", true, "0.91")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.CHECKBOX_STATE);
        assertThat(outcome.anchorStrength()).isEqualTo(0.9);
        assertThat(outcome.pageId()).isEqualTo(content.pageId());
        assertThat(outcome.displayedText()).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(outcome.rawValue()).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(outcome.normalized().text()).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(outcome.normalized().certainty()).isEqualByComparingTo("1");
        // VALUE evidence: the checked box's ELEMENT — box + element id, NO span.
        assertThat(outcome.valueEvidence()).hasSize(1);
        EvidenceRef value = outcome.valueEvidence().get(0);
        assertThat(value.spanId()).isNull();
        assertThat(value.layoutElementId()).isEqualTo(checkedId);
        assertThat(value.box().x()).isEqualByComparingTo("120.0");
        assertThat(value.box().y()).isEqualByComparingTo("220.0");
        // LABEL evidence: the WINNING option's label spans, in scope order.
        assertThat(outcome.labelEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(4L, 5L, 6L);
        assertThat(outcome.labelEvidence())
                .allSatisfy(label -> assertThat(label.layoutElementId()).isNull());
        // Detector confidence rides the spanConfidence slot; the normalizer slot is pinned to 1.
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.91");
        assertThat(outcome.confidence().anchorStrength()).isEqualByComparingTo("0.9");
        assertThat(outcome.confidence().normalizerCertainty()).isEqualByComparingTo("1");
        assertThat(outcome.confidence().overall()).isEqualTo(new BigDecimal("0.8190"));
    }

    @Test
    void zero_checked_boxes_fail_the_rung() {
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", false, "0.91")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void two_checked_boxes_fail_the_rung_rather_than_guessing() {
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", true, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", true, "0.91")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
        assertThat(outcome.valueEvidence()).isEmpty();
    }

    @Test
    void a_checkbox_beyond_the_proximity_cap_never_binds() {
        // The only (checked) checkbox center is (325, 225): 77pt from the "Married filing
        // jointly" box's right edge (248) — far outside the 18pt cap. No option maps → missing.
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(checkbox(UUID.randomUUID(), "320.0", "220.0", true, "0.95")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void the_nearest_in_reach_checkbox_wins_the_label() {
        UUID nearId = UUID.randomUUID();
        List<SpanRef> spans = List.of(span(1, "Single", "140.0", "200.0", "30.0", "10.0"));
        // Two candidates: 15pt left of the label (unchecked) and 7pt right of it (checked).
        PageContent content =
                page(
                        spans,
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(nearId, "172.0", "200.0", true, "0.91")));
        FieldSpec field = filingStatus(checkboxRung(0.9, 18.0, option("Single", "SINGLE")));

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("SINGLE");
        assertThat(outcome.valueEvidence().get(0).layoutElementId()).isEqualTo(nearId);
    }

    @Test
    void one_checked_box_claimed_by_two_labels_is_ambiguous_and_fails_the_rung() {
        // One checked box between two stacked labels, within the cap of BOTH: two checked
        // bindings — the ink cannot be attributed to one option, so the rung refuses to guess.
        List<SpanRef> spans =
                List.of(
                        span(1, "Yes", "140.0", "200.0", "20.0", "10.0"),
                        span(2, "No", "140.0", "214.0", "16.0", "10.0"));
        PageContent content =
                page(spans, List.of(checkbox(UUID.randomUUID(), "120.0", "207.0", true, "0.9")));
        FieldSpec field =
                filingStatus(checkboxRung(0.9, 18.0, option("Yes", "YES"), option("No", "NO")));

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void an_option_whose_label_is_not_on_the_page_is_simply_skipped() {
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", true, "0.91")));
        FieldSpec field =
                filingStatus(
                        checkboxRung(
                                0.9,
                                18.0,
                                option("Single", "SINGLE"),
                                option("Married filing jointly", "MARRIED_FILING_JOINTLY"),
                                option("Head of household", "HEAD_OF_HOUSEHOLD")));

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.displayedText()).isEqualTo("MARRIED_FILING_JOINTLY");
    }

    @Test
    void a_page_without_checkbox_detections_fails_the_rung() {
        // 4-arg PageContent constructor: detections default to empty (T5).
        PageContent content = new PageContent(UUID.randomUUID(), 0, filingStatusSpans(), List.of());
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void a_failed_checkbox_rung_lets_the_ladder_fall_through() {
        // Zero checked boxes: the CHECKBOX_STATE rung fails, the weaker REGEX rung still wins.
        PageContent content =
                page(
                        filingStatusSpans(),
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", false, "0.91")));
        ExtractorSpec fallback =
                new ExtractorSpec(
                        ExtractionMethod.REGEX,
                        0.5,
                        null,
                        null,
                        new ValueSpec("Single", 0, ValueScope.PAGE));
        FieldSpec field = filingStatus(filingStatusRung(), fallback);

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.REGEX);
        assertThat(outcome.anchorStrength()).isEqualTo(0.5);
        assertThat(outcome.displayedText()).isEqualTo("Single");
    }

    @Test
    void a_printed_mark_glyph_inside_a_box_the_detector_read_as_empty_counts_as_checked() {
        // Measured on a real 2023 Form 1040 page 1 (2026-09-14): the preparer software marks
        // the filing status by PRINTING an "X" glyph inside the box — a text span in the page's
        // own text layer, 6 pt wide inside a 13 pt box — and the pixel detector's inner-60% fill
        // measured 0.14 against its 0.15 threshold, so the worker reported the box UNCHECKED.
        // The mark is on the page as text; the rung reads it as the mark it is.
        UUID singleId = UUID.randomUUID();
        UUID mfjId = UUID.randomUUID();
        List<SpanRef> spans = new java.util.ArrayList<>(filingStatusSpans());
        spans.add(span(7, "X", "122.0", "221.5", "6.0", "7.0"));
        PageContent content =
                page(
                        spans,
                        List.of(
                                checkbox(singleId, "120.0", "200.0", false, "0.88"),
                                checkbox(mfjId, "120.0", "220.0", false, "0.75")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.CHECKBOX_STATE);
        assertThat(outcome.displayedText()).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(outcome.valueEvidence()).hasSize(1);
        assertThat(outcome.valueEvidence().get(0).layoutElementId())
                .as("the evidence is still the box element the glyph sits in")
                .isEqualTo(mfjId);
        assertThat(outcome.confidence().spanConfidence()).isEqualByComparingTo("0.75");
    }

    @Test
    void a_mark_glyph_outside_every_box_marks_nothing() {
        // The same glyph printed in the prose beside the boxes — a stray "x" — is not a mark:
        // its center lies in no box, both boxes stay unchecked, the rung fails as before.
        List<SpanRef> spans = new java.util.ArrayList<>(filingStatusSpans());
        spans.add(span(7, "X", "260.0", "221.5", "6.0", "7.0"));
        PageContent content =
                page(
                        spans,
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", false, "0.75")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
    }

    @Test
    void a_mark_glyph_in_each_of_two_boxes_is_ambiguous_and_fails_the_rung() {
        // The glyph rule feeds the same exactly-one rule as the pixel detector: a page that
        // prints an "X" inside BOTH boxes has marked two options, and a filer whose marks
        // cannot be attributed to one option is a review case, not a guess (D6) — exactly as
        // two detector-checked boxes fail the rung.
        List<SpanRef> spans = new java.util.ArrayList<>(filingStatusSpans());
        spans.add(span(7, "X", "122.0", "201.5", "6.0", "7.0"));
        spans.add(span(8, "X", "122.0", "221.5", "6.0", "7.0"));
        PageContent content =
                page(
                        spans,
                        List.of(
                                checkbox(UUID.randomUUID(), "120.0", "200.0", false, "0.88"),
                                checkbox(UUID.randomUUID(), "120.0", "220.0", false, "0.75")));
        FieldSpec field = filingStatus(filingStatusRung());

        FieldOutcome outcome = engine.extract(schema(field), List.of(content)).get(0);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.method()).isEqualTo(ExtractionMethod.NONE);
        assertThat(outcome.valueEvidence()).isEmpty();
    }
}
