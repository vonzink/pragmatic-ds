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
 * SIGNATURE_PRESENCE end-to-end through the EXTRACTING stage, covering the three states D5
 * separates: SIGNED (element-backed VALUE evidence), UNSIGNED (a FOUND row with LABEL evidence
 * and ZERO VALUE rows — the one legal empty-VALUE persisted find), and missing (no signature
 * block located → NONE / MANUAL_REVIEW_REQUIRED). Requires V11 §1 (T1): the method CHECK must
 * admit SIGNATURE_PRESENCE.
 */
class SignaturePresenceExtractionIT extends AbstractExtractionIT {

    /** One 612×792 page: "Buyer's Signature" at y=400 — label union box [72,170]×[400,410]. */
    private static final String PAGES_JSON =
            """
            [{"pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "words": [
                {"text": "Buyer's",   "x": 72.0,  "y": 400.0, "width": 44.0, "height": 10.0},
                {"text": "Signature", "x": 120.0, "y": 400.0, "width": 50.0, "height": 10.0}
            ]}]
            """;

    /** The same page with NO signature block anywhere — the D5 "missing" case. */
    private static final String PAGES_WITHOUT_BLOCK_JSON =
            """
            [{"pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0, "words": [
                {"text": "Seller",   "x": 72.0,  "y": 400.0, "width": 32.0, "height": 10.0},
                {"text": "Initials", "x": 110.0, "y": 400.0, "width": 40.0, "height": 10.0}
            ]}]
            """;

    /** The CONTRACTS shape verbatim: region label + windowPt, no value block. */
    private static final String SCHEMA_JSON =
            """
            {"fields": [{"name": "buyerSigned", "dataType": "ENUM", "required": true,
              "normalizer": null, "sensitive": false,
              "extractors": [{"method": "SIGNATURE_PRESENCE", "strength": 0.9,
                "region": {"label": {"kind": "literal", "pattern": "Buyer's Signature"},
                           "windowPt": {"left": 0.0, "right": 240.0,
                                        "above": 40.0, "below": 8.0}}}]}]}
            """;

    @Test
    void a_signature_in_the_window_persists_SIGNED_with_element_backed_evidence()
            throws Exception {
        UUID packageId = insertPackage("signature-signed-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, JSON.readTree(PAGES_JSON));
        UUID inkId = insertSignature(pageIds.get(0), 0, "200.0", "375.0", "120.0", "28.0", "0.84");
        UUID documentId = insertContractDocument(packageId, pageIds.get(0));
        insertOrgSchema();
        try {
            assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

            Map<String, Object> field = currentFieldsByName(documentId).get("buyerSigned");
            assertThat(field).as("buyerSigned row").isNotNull();
            assertThat(field.get("extraction_method")).isEqualTo("SIGNATURE_PRESENCE");
            assertThat(field.get("data_type")).isEqualTo("ENUM");
            assertThat(field.get("displayed_text")).isEqualTo("SIGNED");
            assertThat(field.get("raw_value")).isEqualTo("SIGNED");
            assertThat(field.get("normalized_text")).isEqualTo("SIGNED");
            assertThat(field.get("validation_status")).isEqualTo("NOT_VALIDATED");
            // 0.84 (detection) × 0.9 (strength) × 1.0, scale 4 HALF_UP.
            assertThat((BigDecimal) field.get("confidence")).isEqualByComparingTo("0.7560");

            JsonNode components =
                    JSON.readTree(String.valueOf(field.get("confidence_components")));
            assertThat(components.get("spanConfidence").decimalValue())
                    .isEqualByComparingTo("0.84");
            assertThat(components.get("anchorStrength").decimalValue())
                    .isEqualByComparingTo("0.9");
            assertThat(components.get("normalizerCertainty").decimalValue())
                    .isEqualByComparingTo("1");
            assertThat(components.size()).isEqualTo(3);

            UUID fieldId = (UUID) field.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).hasSize(1);
            Map<String, Object> value = valueEvidence.get(0);
            assertThat(value.get("layout_element_id")).isEqualTo(inkId);
            assertThat(value.get("text_span_id")).isNull();
            assertThat(scaled(value.get("x"))).isEqualByComparingTo("200.00");
            assertThat(scaled(value.get("y"))).isEqualByComparingTo("375.00");
            assertThat(scaled(value.get("width"))).isEqualByComparingTo("120.00");
            assertThat(scaled(value.get("height"))).isEqualByComparingTo("28.00");

            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).as("Buyer's + Signature").hasSize(2);
            for (Map<String, Object> label : labelEvidence) {
                assertThat(label.get("text_span_id")).isNotNull();
                assertThat(label.get("layout_element_id")).isNull();
            }
        } finally {
            cleanupOrgSchema();
        }
    }

    @Test
    void no_signature_detection_persists_UNSIGNED_with_no_value_evidence() throws Exception {
        UUID packageId = insertPackage("signature-unsigned-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, JSON.readTree(PAGES_JSON));
        // No SIGNATURE layout element at all: the detector looked and found none.
        UUID documentId = insertContractDocument(packageId, pageIds.get(0));
        insertOrgSchema();
        try {
            assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

            // UNSIGNED is a FOUND answer (D5) — NOT the missing-field contract.
            Map<String, Object> field = currentFieldsByName(documentId).get("buyerSigned");
            assertThat(field).isNotNull();
            assertThat(field.get("extraction_method")).isEqualTo("SIGNATURE_PRESENCE");
            assertThat(field.get("displayed_text")).isEqualTo("UNSIGNED");
            assertThat(field.get("raw_value")).isEqualTo("UNSIGNED");
            assertThat(field.get("normalized_text")).isEqualTo("UNSIGNED");
            assertThat(field.get("validation_status")).isEqualTo("NOT_VALIDATED");
            // min(label span confidences)=1.0 (native spans) × 0.9 × 1.0.
            assertThat((BigDecimal) field.get("confidence")).isEqualByComparingTo("0.9000");

            JsonNode components =
                    JSON.readTree(String.valueOf(field.get("confidence_components")));
            assertThat(components.get("spanConfidence").decimalValue()).isEqualByComparingTo("1");
            assertThat(components.get("anchorStrength").decimalValue())
                    .isEqualByComparingTo("0.9");
            assertThat(components.get("normalizerCertainty").decimalValue())
                    .isEqualByComparingTo("1");

            // THE contract point: a found row with ZERO VALUE evidence rows — permitted for
            // SIGNATURE_PRESENCE only — while the LABEL chain still localizes the claim.
            UUID fieldId = (UUID) field.get("id");
            assertThat(evidenceOf(fieldId, "VALUE")).isEmpty();
            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).hasSize(2);
            for (Map<String, Object> label : labelEvidence) {
                assertThat(label.get("text_span_id")).isNotNull();
            }
        } finally {
            cleanupOrgSchema();
        }
    }

    @Test
    void a_missing_signature_block_is_the_missing_field_not_UNSIGNED() throws Exception {
        UUID packageId = insertPackage("signature-missing-it");
        List<UUID> pageIds =
                insertFixturePages(packageId, ORG_DEV, JSON.readTree(PAGES_WITHOUT_BLOCK_JSON));
        // Ink exists — but no "Buyer's Signature" label to bind it to (D5's other arm).
        insertSignature(pageIds.get(0), 0, "200.0", "375.0", "120.0", "28.0", "0.84");
        UUID documentId = insertContractDocument(packageId, pageIds.get(0));
        insertOrgSchema();
        try {
            assertThat(runStage(packageId, ProcessingStatus.EXTRACTING).success()).isTrue();

            Map<String, Object> field = currentFieldsByName(documentId).get("buyerSigned");
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

    /** A SIGNATURE layout element exactly as the worker persists one. */
    private UUID insertSignature(
            UUID pageId, int ordinal, String x, String y, String width, String height,
            String confidence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version,
                    attributes)
                VALUES (?, ?, ?, NULL, 'SIGNATURE', ?, ?::numeric, ?::numeric, ?::numeric,
                        ?::numeric, ?::numeric, 'signature-cv', '0.2.0',
                        '{"inkFraction": 0.21}'::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                ordinal,
                x,
                y,
                width,
                height,
                confidence);
        return id;
    }

    /**
     * A one-page PURCHASE_CONTRACT logical document, seeded directly: this IT drives ONLY the
     * EXTRACTING stage — the PURCHASE_CONTRACT rule pack does not exist until T9.
     */
    private UUID insertContractDocument(UUID packageId, UUID pageId) {
        UUID documentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO logical_document (id, org_id, package_id, ordinal,"
                        + " document_type_code, classification_confidence)"
                        + " VALUES (?, ?, ?, 0, 'PURCHASE_CONTRACT', 0.9900)",
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
                        + " VALUES (?, 'PURCHASE_CONTRACT', '0.1.0', ?::jsonb)",
                ORG_DEV,
                SCHEMA_JSON);
        schemaLoader.invalidateAll();
    }

    /** Children before parents (FK); a leaked org schema would shadow the global
     * purchase_contract schema for every later test in the shared container. */
    private void cleanupOrgSchema() {
        jdbc.update(
                """
                DELETE FROM field_evidence WHERE extracted_field_id IN
                    (SELECT id FROM extracted_field WHERE schema_id IN
                        (SELECT id FROM extraction_schema
                         WHERE org_id = ? AND document_type_code = 'PURCHASE_CONTRACT'
                           AND version = '0.1.0'))
                """,
                ORG_DEV);
        jdbc.update(
                """
                DELETE FROM extracted_field WHERE schema_id IN
                    (SELECT id FROM extraction_schema
                     WHERE org_id = ? AND document_type_code = 'PURCHASE_CONTRACT'
                       AND version = '0.1.0')
                """,
                ORG_DEV);
        jdbc.update(
                "DELETE FROM extraction_schema WHERE org_id = ? AND document_type_code ="
                        + " 'PURCHASE_CONTRACT' AND version = '0.1.0'",
                ORG_DEV);
        schemaLoader.invalidateAll();
    }
}
