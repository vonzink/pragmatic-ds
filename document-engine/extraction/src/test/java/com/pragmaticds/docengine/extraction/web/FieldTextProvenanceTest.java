package com.pragmaticds.docengine.extraction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.web.FieldTextProvenance.Lookup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE FOLD: many spans, one answer. Exercised as a pure function over an already-resolved lookup —
 * no database, no Spring — so a failure names the rule that broke rather than the plumbing.
 *
 * <p>{@code TextProvenanceApiIT} proves the other half: that the spans reaching this fold really are
 * the ones the value was captured from.
 */
class FieldTextProvenanceTest {

    private static final TextProvenanceView NATIVE_SPAN = new TextProvenanceView("NATIVE", null);
    private static final TextProvenanceView RAPID_SPAN = new TextProvenanceView("OCR", "RAPIDOCR");
    private static final TextProvenanceView TESS_SPAN = new TextProvenanceView("OCR", "TESSERACT");

    @Test
    void every_span_from_the_text_layer_reads_NATIVE_and_names_no_engine() {
        Lookup lookup = lookupOf(Map.of(1L, NATIVE_SPAN, 2L, NATIVE_SPAN));

        assertThat(lookup.of(List.of(1L, 2L)))
                .isEqualTo(new TextProvenanceView("NATIVE", null));
    }

    @Test
    void every_span_recognised_reads_OCR_and_names_the_engine() {
        Lookup lookup = lookupOf(Map.of(1L, RAPID_SPAN, 2L, RAPID_SPAN));

        assertThat(lookup.of(List.of(1L, 2L)))
                .isEqualTo(new TextProvenanceView("OCR", "RAPIDOCR"));
    }

    /**
     * The honest answer for a value assembled from both. NOT a majority vote: a single recognised
     * span among nine native ones still means one in ten of these characters was guessed, and a
     * rule that rounded that away would hide exactly the span a reviewer needs to check. The engine
     * is still named, because the guessed part is the part worth naming.
     */
    @Test
    void a_value_spanning_both_sources_reads_MIXED_and_still_names_the_engine() {
        Lookup lookup =
                lookupOf(Map.of(1L, NATIVE_SPAN, 2L, NATIVE_SPAN, 3L, NATIVE_SPAN, 4L, RAPID_SPAN));

        assertThat(lookup.of(List.of(1L, 2L, 3L, 4L)))
                .isEqualTo(new TextProvenanceView("MIXED", "RAPIDOCR"));
    }

    /** Reconciliation may mix engines by region, so both are named — sorted, joined, deduplicated. */
    @Test
    void two_engines_behind_one_value_are_both_named_in_a_stable_order() {
        Lookup lookup = lookupOf(Map.of(1L, TESS_SPAN, 2L, RAPID_SPAN, 3L, RAPID_SPAN));

        assertThat(lookup.of(List.of(1L, 2L, 3L)))
                .as("sorted so the same value never renders two different ways run to run")
                .isEqualTo(new TextProvenanceView("OCR", "RAPIDOCR+TESSERACT"));
    }

    /** A missing occurrence cites no span at all. UNKNOWN, never the comfortable NATIVE default. */
    @Test
    void no_spans_at_all_reads_UNKNOWN_rather_than_defaulting_to_native() {
        assertThat(lookupOf(Map.of(1L, NATIVE_SPAN)).of(List.of()))
                .isEqualTo(new TextProvenanceView("UNKNOWN", null));
    }

    /**
     * A cited span that is no longer visible — purged, or belonging to another tenant — contributes
     * nothing rather than being assumed native. When it is the ONLY citation, the answer is UNKNOWN:
     * the read must not manufacture a provenance for text it cannot see.
     */
    @Test
    void a_span_the_lookup_cannot_see_contributes_nothing() {
        Lookup lookup = lookupOf(Map.of(1L, NATIVE_SPAN));

        assertThat(lookup.of(List.of(999L)))
                .as("invisible span alone → UNKNOWN")
                .isEqualTo(new TextProvenanceView("UNKNOWN", null));
        assertThat(lookup.of(List.of(1L, 999L)))
                .as("...and it neither adds an arm nor removes one beside a visible span")
                .isEqualTo(new TextProvenanceView("NATIVE", null));
    }

    /** An OCR span whose engine was never recorded still reads OCR — the source is the load-bearing half. */
    @Test
    void an_ocr_span_with_no_recorded_engine_still_reads_OCR() {
        Lookup lookup = lookupOf(Map.of(1L, new TextProvenanceView("OCR", null)));

        assertThat(lookup.of(List.of(1L))).isEqualTo(new TextProvenanceView("OCR", null));
    }

    /** The one wording, shared with the review UI's badge and the Markdown projection. */
    @Test
    void the_label_is_the_source_then_the_engine_and_nothing_else() {
        assertThat(new TextProvenanceView("NATIVE", null).label()).isEqualTo("NATIVE");
        assertThat(new TextProvenanceView("OCR", "RAPIDOCR").label()).isEqualTo("OCR RAPIDOCR");
        assertThat(new TextProvenanceView("MIXED", "TESSERACT").label())
                .isEqualTo("MIXED TESSERACT");
        assertThat(new TextProvenanceView("UNKNOWN", null).label()).isEqualTo("UNKNOWN");
    }

    private static Lookup lookupOf(Map<Long, TextProvenanceView> bySpan) {
        return new Lookup(bySpan);
    }
}
