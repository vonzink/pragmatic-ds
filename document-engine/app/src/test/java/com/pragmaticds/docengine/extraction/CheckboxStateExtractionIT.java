package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CHECKBOX_STATE end-to-end through the EXTRACTING stage: CHECKBOX layout elements seeded the
 * way the worker persists them (attributes {"checked": bool, "fillRatio": n}, detector
 * "checkbox-cv") become an extracted ENUM value whose VALUE evidence row is ELEMENT-backed —
 * layout_element_id set, text_span_id NULL — and whose confidence components carry the
 * detection's confidence in the spanConfidence slot. Requires V11 §1 (T1): the method CHECK
 * must admit CHECKBOX_STATE.
 */
class CheckboxStateExtractionIT extends AbstractExtractionIT {

    /** One 612×792 page: "Filing Status:", "Single" (y=200), "Married filing jointly" (y=220). */
    private static final String PAGES_JSON =
            """
            [{"pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "words": [
                {"text": "Filing",  "x": 72.0,  "y": 180.0, "width": 30.0, "height": 10.0},
                {"text": "Status:", "x": 106.0, "y": 180.0, "width": 36.0, "height": 10.0},
                {"text": "Single",  "x": 140.0, "y": 200.0, "width": 30.0, "height": 10.0},
                {"text": "Married", "x": 140.0, "y": 220.0, "width": 40.0, "height": 10.0},
                {"text": "filing",  "x": 184.0, "y": 220.0, "width": 26.0, "height": 10.0},
                {"text": "jointly", "x": 214.0, "y": 220.0, "width": 34.0, "height": 10.0}
            ]}]
            """;

    /** The CONTRACTS shape verbatim: options + proximityPt, no value block. */
    private static final String SCHEMA_JSON =
            """
            {"fields": [{"name": "filingStatus", "dataType": "ENUM", "required": true,
              "normalizer": null, "sensitive": false,
              "extractors": [{"method": "CHECKBOX_STATE", "strength": 0.9, "proximityPt": 18.0,
                "options": [
                  {"label": {"kind": "literal", "pattern": "Single"}, "value": "SINGLE"},
                  {"label": {"kind": "literal", "pattern": "Married filing jointly"},
                   "value": "MARRIED_FILING_JOINTLY"}
                ]}]}]}
            """;

    @Test
    void exactly_one_checked_box_extracts_the_mapped_enum_with_element_backed_evidence()
            throws Exception {
        UUID packageId = insertPackage("checkbox-state-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, JSON.readTree(PAGES_JSON));
        insertCheckbox(pageIds.get(0), 0, "120.0", "200.0", false, "0.88");
        UUID checkedBox = insertCheckbox(pageIds.get(0), 1, "120.0", "220.0", true, "0.91");
        UUID documentId = insertTaxReturnDocument(packageId, pageIds.get(0));
        insertOrgSchema();
        try {
            assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

            Map<String, Object> field = currentFieldsByName(documentId).get("filingStatus");
            assertThat(field).as("filingStatus row").isNotNull();
            assertThat(field.get("extraction_method")).isEqualTo("CHECKBOX_STATE");
            assertThat(field.get("data_type")).isEqualTo("ENUM");
            assertThat(field.get("displayed_text")).isEqualTo("MARRIED_FILING_JOINTLY");
            assertThat(field.get("raw_value")).isEqualTo("MARRIED_FILING_JOINTLY");
            assertThat(field.get("normalized_text")).isEqualTo("MARRIED_FILING_JOINTLY");
            assertThat(field.get("validation_status")).isEqualTo("NOT_VALIDATED");
            // 0.91 (detection) × 0.9 (strength) × 1.0 (normalizer), scale 4 HALF_UP.
            assertThat((BigDecimal) field.get("confidence")).isEqualByComparingTo("0.8190");

            // The V7 components contract: exactly these three keys, detector confidence in the
            // spanConfidence slot — never a fourth key.
            JsonNode components =
                    JSON.readTree(String.valueOf(field.get("confidence_components")));
            assertThat(components.get("spanConfidence").decimalValue())
                    .isEqualByComparingTo("0.91");
            assertThat(components.get("anchorStrength").decimalValue())
                    .isEqualByComparingTo("0.9");
            assertThat(components.get("normalizerCertainty").decimalValue())
                    .isEqualByComparingTo("1");
            assertThat(components.size()).isEqualTo(3);

            UUID fieldId = (UUID) field.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).hasSize(1);
            Map<String, Object> value = valueEvidence.get(0);
            assertThat(value.get("layout_element_id")).isEqualTo(checkedBox);
            assertThat(value.get("text_span_id")).isNull();
            assertThat(scaled(value.get("x"))).isEqualByComparingTo("120.00");
            assertThat(scaled(value.get("y"))).isEqualByComparingTo("220.00");
            assertThat(scaled(value.get("width"))).isEqualByComparingTo("10.00");
            assertThat(scaled(value.get("height"))).isEqualByComparingTo("10.00");
            assertThat(packagePageIndexOf((UUID) value.get("page_id"))).isZero();

            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).as("Married + filing + jointly").hasSize(3);
            for (Map<String, Object> label : labelEvidence) {
                assertThat(label.get("text_span_id")).isNotNull();
                assertThat(label.get("layout_element_id")).isNull();
            }
        } finally {
            cleanupOrgSchema();
        }
    }

    @Test
    void two_checked_boxes_persist_the_missing_field_contract() throws Exception {
        UUID packageId = insertPackage("checkbox-two-checked-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, JSON.readTree(PAGES_JSON));
        insertCheckbox(pageIds.get(0), 0, "120.0", "200.0", true, "0.88");
        insertCheckbox(pageIds.get(0), 1, "120.0", "220.0", true, "0.91");
        UUID documentId = insertTaxReturnDocument(packageId, pageIds.get(0));
        insertOrgSchema();
        try {
            assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

            // Two checked filing statuses is a review case, not a guess (D6): the field
            // persists as the standard missing-field RESULT.
            Map<String, Object> field = currentFieldsByName(documentId).get("filingStatus");
            assertThat(field).isNotNull();
            assertThat(field.get("extraction_method")).isEqualTo("NONE");
            assertThat((BigDecimal) field.get("confidence"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(field.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
            assertThat(field.get("displayed_text")).isNull();
            assertThat(field.get("raw_value")).isNull();
            assertThat(field.get("normalized_text")).isNull();
            assertThat(field.get("confidence_components")).isNull();
            assertThat(evidenceOf((UUID) field.get("id"), "VALUE")).isEmpty();
            assertThat(evidenceOf((UUID) field.get("id"), "LABEL")).isEmpty();
        } finally {
            cleanupOrgSchema();
        }
    }

    // ── seeding helpers ─────────────────────────────────────────────────────

    /** A CHECKBOX layout element exactly as the worker persists one (10×10pt box). */
    private UUID insertCheckbox(
            UUID pageId, int ordinal, String x, String y, boolean checked, String confidence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version,
                    attributes)
                VALUES (?, ?, ?, NULL, 'CHECKBOX', ?, ?::numeric, ?::numeric, 10.0, 10.0,
                        ?::numeric, 'checkbox-cv', '0.2.0', ?::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                ordinal,
                x,
                y,
                confidence,
                "{\"checked\": " + checked + ", \"fillRatio\": " + (checked ? "0.42" : "0.02")
                        + "}");
        return id;
    }

    /**
     * A one-page TAX_RETURN logical document, seeded directly: this IT drives ONLY the
     * EXTRACTING stage — the TAX_RETURN rule pack does not exist until T9, so CLASSIFYING would
     * label the page UNKNOWN.
     */
    private UUID insertTaxReturnDocument(UUID packageId, UUID pageId) {
        UUID documentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal,"
                        + " document_type_code, classification_confidence)"
                        + " VALUES (?, ?, ?, 0, 'TAX_RETURN', 0.9900)",
                documentId,
                ORG_DEV,
                packageId);
        jdbc.update(
                "INSERT INTO logical_document_page (org_id, logical_document_id, page_id,"
                        + " ordinal) VALUES (?, ?, ?, 0)",
                ORG_DEV,
                documentId,
                pageId);
        return documentId;
    }

    private void insertOrgSchema() {
        jdbc.update(
                "INSERT INTO extraction_schema (org_id, document_type_code, version, definition)"
                        + " VALUES (?, 'TAX_RETURN', '0.1.0', ?::jsonb)",
                ORG_DEV,
                SCHEMA_JSON);
        schemaLoader.invalidateAll();
    }

    /** Children before parents (FK), then the schema row — a leaked org schema would shadow
     * the global tax_return schema for every later test in the shared container. */
    private void cleanupOrgSchema() {
        jdbc.update(
                """
                DELETE FROM field_evidence WHERE extracted_field_id IN
                    (SELECT id FROM extracted_field WHERE schema_id IN
                        (SELECT id FROM extraction_schema
                         WHERE org_id = ? AND document_type_code = 'TAX_RETURN'
                           AND version = '0.1.0'))
                """,
                ORG_DEV);
        jdbc.update(
                """
                DELETE FROM extracted_field WHERE schema_id IN
                    (SELECT id FROM extraction_schema
                     WHERE org_id = ? AND document_type_code = 'TAX_RETURN'
                       AND version = '0.1.0')
                """,
                ORG_DEV);
        jdbc.update(
                "DELETE FROM extraction_schema WHERE org_id = ? AND document_type_code ="
                        + " 'TAX_RETURN' AND version = '0.1.0'",
                ORG_DEV);
        schemaLoader.invalidateAll();
    }
}
