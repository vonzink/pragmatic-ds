package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TEXT PROVENANCE end to end: {@code /fields}, {@code /export} and {@code fields.md} agree about
 * where a value's characters came from, on the same fixture, in the same words.
 *
 * <p><b>What this class actually risks.</b> The fold itself is unit-tested
 * ({@code FieldTextProvenanceTest}); what cannot be unit-tested is whether the spans reaching that
 * fold are the ones the value was captured from. The engine could cite no span at all, or cite only
 * LABEL spans, and a NATIVE-everywhere fixture would look perfectly correct while reporting a fact
 * it never checked. So every assertion here is written against a span this test MUTATED: if the read
 * path is not really following {@code field_evidence.text_span_id} for VALUE evidence, flipping that
 * span changes nothing and the test fails.
 *
 * <p>The committed fixtures insert every span as {@code NATIVE} ({@code insertFixturePages}), which
 * is what makes them usable as a control: the OCR and MIXED cases below are produced by editing
 * exactly one span each, so the difference in the response is attributable to that edit and to
 * nothing else.
 */
class TextProvenanceApiIT extends AbstractExtractionIT {

    /** A found paystub field with VALUE evidence — the subject of the mutations below. */
    private static final String SUBJECT = "netPay";

    // ── the control: a wholly native fixture ────────────────────────────────

    @Test
    void a_native_fixture_reports_NATIVE_on_every_found_field_and_UNKNOWN_on_every_missing_one()
            throws Exception {
        UUID documentId = paystub("provenance-native-it");

        for (JsonNode field : fieldsOf(documentId)) {
            String name = field.path("fieldName").asText();
            JsonNode provenance = field.path("textProvenance");
            assertThat(provenance.isMissingNode())
                    .as("textProvenance is present on %s, never omitted", name)
                    .isFalse();
            assertThat(provenance.path("ocrEngine").isNull())
                    .as("no engine on a native fixture (%s)", name)
                    .isTrue();
            assertThat(provenance.path("source").asText())
                    .as("source on %s", name)
                    .isEqualTo("NONE".equals(field.path("extractionMethod").asText())
                            ? "UNKNOWN"
                            : "NATIVE");
        }
    }

    /**
     * The paystub fixture finds all ten of its fields, so it cannot exercise the missing arm.
     * Schedule E can: 23 of its 67 occurrences are explicitly missing, and a missing occurrence
     * cites no span — so it must read UNKNOWN. Reporting NATIVE there would be the engine claiming
     * it read a text layer it never touched.
     */
    @Test
    void a_missing_occurrence_reads_UNKNOWN_because_it_cites_no_span_at_all() throws Exception {
        UUID packageId = insertPackage("provenance-missing-it");
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);

        int missing = 0;
        int found = 0;
        for (JsonNode field : fieldsOf(onlyDocumentOf(packageId))) {
            String source = field.path("textProvenance").path("source").asText();
            if ("NONE".equals(field.path("extractionMethod").asText())) {
                missing++;
                assertThat(source)
                        .as("missing occurrence %s", field.path("fieldName").asText())
                        .isEqualTo("UNKNOWN");
            } else {
                found++;
                assertThat(source)
                        .as("found occurrence %s", field.path("fieldName").asText())
                        .isEqualTo("NATIVE");
            }
        }
        assertThat(missing).as("Schedule E's explicitly-missing occurrences").isEqualTo(23);
        assertThat(found).as("...and the ones it read").isEqualTo(44);
    }

    // ── OCR ─────────────────────────────────────────────────────────────────

    /**
     * Flip every span behind one value to OCR and the value — and only that value — starts saying
     * so, with the engine named. The untouched sibling field is the control that proves this is
     * per-occurrence and not a page-wide or document-wide verdict.
     */
    @Test
    void a_value_read_by_ocr_reports_OCR_and_names_the_engine() throws Exception {
        UUID documentId = paystub("provenance-ocr-it");
        List<Long> spans = valueSpansOf(documentId, SUBJECT);
        assertThat(spans)
                .as("the read path can only report provenance if VALUE evidence cites spans at all")
                .isNotEmpty();
        recogniseSpans(spans, "RAPIDOCR");

        JsonNode subject = fieldNamed(documentId, SUBJECT);
        assertThat(subject.path("textProvenance").path("source").asText()).isEqualTo("OCR");
        assertThat(subject.path("textProvenance").path("ocrEngine").asText()).isEqualTo("RAPIDOCR");

        assertThat(otherFoundFields(documentId, SUBJECT))
                .as("every other occurrence is untouched — provenance is per value, not per page")
                .isNotEmpty()
                .allSatisfy(field ->
                        assertThat(field.path("textProvenance").path("source").asText())
                                .isEqualTo("NATIVE"));
    }

    /** The export carries the same fact, because a consumer books from it without a human. */
    @Test
    void the_export_reports_the_same_provenance_as_the_fields_endpoint() throws Exception {
        UUID packageId = insertPackage("provenance-export-it");
        UUID documentId = paystubIn(packageId);
        recogniseSpans(valueSpansOf(documentId, SUBJECT), "TESSERACT");

        JsonNode exported =
                JSON.readTree(
                                mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("documents")
                        .get(0)
                        .get("fields");

        JsonNode subject = null;
        for (JsonNode field : exported) {
            assertThat(field.path("textProvenance").isMissingNode())
                    .as("textProvenance on every export field, never omitted")
                    .isFalse();
            if (SUBJECT.equals(field.path("fieldName").asText())) {
                subject = field;
            }
        }
        assertThat(subject).as("the export carries %s", SUBJECT).isNotNull();
        assertThat(subject.path("textProvenance").path("source").asText()).isEqualTo("OCR");
        assertThat(subject.path("textProvenance").path("ocrEngine").asText())
                .isEqualTo("TESSERACT");
    }

    /** The Markdown surface spells the SAME string, so the two surfaces tell one story. */
    @Test
    void the_markdown_projection_prints_the_same_label() throws Exception {
        UUID documentId = paystub("provenance-md-it");
        recogniseSpans(valueSpansOf(documentId, SUBJECT), "RAPIDOCR");

        String rendered =
                mockMvc.perform(get("/v1/documents/{id}/fields.md", documentId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(rendered)
                .as("the appendix row for the OCR'd occurrence names the engine")
                .contains("| " + SUBJECT + " | ∅ | NONE | FOUND ")
                .contains("| OCR RAPIDOCR |")
                .as("the untouched siblings still read NATIVE, in the same column")
                .contains("| NATIVE |");
    }

    // ── MIXED: the case a page-level verdict cannot express ─────────────────

    /**
     * ONE value, characters from BOTH sources — a native-text form whose figure was overwritten by
     * hand and recognised from the scan. The answer must be MIXED with the engine still named: a
     * majority rule would report NATIVE and quietly retire the reviewer's reason to look.
     */
    @Test
    void a_value_whose_spans_straddle_both_sources_reports_MIXED_and_still_names_the_engine()
            throws Exception {
        UUID documentId = paystub("provenance-mixed-it");
        UUID fieldId = fieldIdOf(documentId, SUBJECT);
        assertThat(valueSpansOf(documentId, SUBJECT)).isNotEmpty();

        // A second VALUE box for the same occurrence, backed by an OCR'd span — exactly the shape
        // a MIXED page yields when part of one value sits in the text layer and part in the scan.
        addRecognisedValueSpan(fieldId, "RAPIDOCR");

        JsonNode provenance = fieldNamed(documentId, SUBJECT).path("textProvenance");
        assertThat(provenance.path("source").asText())
                .as("neither arm is discarded — the honest answer is MIXED")
                .isEqualTo("MIXED");
        assertThat(provenance.path("ocrEngine").asText())
                .as("...and the recognised half still names the engine that produced it")
                .isEqualTo("RAPIDOCR");
    }

    /**
     * Provenance travels BESIDE confidence, never inside it. The three components are a published
     * formula whose product is the score; a fourth factor would silently change every number a
     * consumer already stored, and a reader could no longer multiply the components back.
     */
    @Test
    void marking_a_value_as_ocr_changes_no_confidence_number_and_adds_no_component()
            throws Exception {
        UUID documentId = paystub("provenance-confidence-it");
        JsonNode before = fieldNamed(documentId, SUBJECT);
        String confidenceBefore = before.path("confidence").asText();
        JsonNode componentsBefore = before.path("confidenceComponents");

        recogniseSpans(valueSpansOf(documentId, SUBJECT), "RAPIDOCR");

        JsonNode after = fieldNamed(documentId, SUBJECT);
        assertThat(after.path("confidence").asText())
                .as("the score is the same number it was")
                .isEqualTo(confidenceBefore);
        assertThat(after.path("confidenceComponents"))
                .as("and its inputs are the same three")
                .isEqualTo(componentsBefore);
        assertThat(after.path("confidenceComponents").properties())
                .as("exactly three components — provenance is not a fourth")
                .hasSize(3);
        assertThat(after.path("confidenceComponents").has("textProvenance")).isFalse();
    }

    // ── fixture plumbing ────────────────────────────────────────────────────

    private UUID paystub(String packageName) {
        return paystubIn(insertPackage(packageName));
    }

    private UUID paystubIn(UUID packageId) {
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    private JsonNode fieldsOf(UUID documentId) throws Exception {
        return JSON.readTree(
                        mockMvc.perform(get("/v1/documents/{id}/fields", documentId))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("fields");
    }

    private JsonNode fieldNamed(UUID documentId, String fieldName) throws Exception {
        for (JsonNode field : fieldsOf(documentId)) {
            if (fieldName.equals(field.path("fieldName").asText())) {
                return field;
            }
        }
        throw new AssertionError("no field " + fieldName + " on document " + documentId);
    }

    private List<JsonNode> otherFoundFields(UUID documentId, String excluded) throws Exception {
        List<JsonNode> others = new java.util.ArrayList<>();
        for (JsonNode field : fieldsOf(documentId)) {
            if (!excluded.equals(field.path("fieldName").asText())
                    && !"NONE".equals(field.path("extractionMethod").asText())) {
                others.add(field);
            }
        }
        return others;
    }

    private UUID fieldIdOf(UUID documentId, String fieldName) {
        return jdbc.queryForObject(
                "SELECT id FROM extracted_field WHERE logical_document_id = ? AND is_current"
                        + " AND field_name = ?",
                UUID.class,
                documentId,
                fieldName);
    }

    /** The spans the field's VALUE evidence cites — the only ones provenance may consider. */
    private List<Long> valueSpansOf(UUID documentId, String fieldName) {
        return jdbc.queryForList(
                "SELECT e.text_span_id FROM field_evidence e"
                        + " JOIN extracted_field f ON f.id = e.extracted_field_id"
                        + " WHERE f.logical_document_id = ? AND f.is_current AND f.field_name = ?"
                        + "   AND e.role = 'VALUE' AND e.text_span_id IS NOT NULL",
                Long.class,
                documentId,
                fieldName);
    }

    /** Rewrites existing spans as if the OCR ladder, not the text layer, had produced them. */
    private void recogniseSpans(List<Long> spanIds, String engine) {
        for (Long spanId : spanIds) {
            jdbc.update(
                    "UPDATE text_span SET source = 'OCR', ocr_engine = ?, confidence = 0.9200"
                            + " WHERE id = ?",
                    engine,
                    spanId);
        }
    }

    /** Adds one recognised span and a VALUE evidence row citing it, on the field's own page. */
    private void addRecognisedValueSpan(UUID fieldId, String engine) {
        Map<String, Object> anchor =
                jdbc.queryForMap(
                        "SELECT page_id, x, y, width, height, ordinal FROM field_evidence"
                                + " WHERE extracted_field_id = ? AND role = 'VALUE'"
                                + " ORDER BY ordinal DESC LIMIT 1",
                        fieldId);
        UUID pageId = (UUID) anchor.get("page_id");
        Long spanId =
                jdbc.queryForObject(
                        "INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width,"
                                + " height, source, ocr_engine, confidence)"
                                + " VALUES (?, ?, 9999, 'handwritten', ?, ?, ?, ?, 'OCR', ?, 0.8800)"
                                + " RETURNING id",
                        Long.class,
                        ORG_DEV,
                        pageId,
                        anchor.get("x"),
                        anchor.get("y"),
                        anchor.get("width"),
                        anchor.get("height"),
                        engine);
        jdbc.update(
                "INSERT INTO field_evidence (id, org_id, extracted_field_id, page_id,"
                        + " text_span_id, x, y, width, height, role, ordinal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VALUE', ?)",
                UUID.randomUUID(),
                ORG_DEV,
                fieldId,
                pageId,
                spanId,
                anchor.get("x"),
                anchor.get("y"),
                anchor.get("width"),
                anchor.get("height"),
                ((Number) anchor.get("ordinal")).intValue() + 1);
    }
}
