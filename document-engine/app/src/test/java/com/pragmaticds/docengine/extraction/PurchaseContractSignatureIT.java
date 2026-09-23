package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SIGNATURE_PRESENCE end to end on both purchase-contract fixture variants — word-identical
 * documents that differ by six bezier curves of ink on page 2. The worker is not in the loop (the
 * classification fixture bridge inserts spans from truth), so the SIGNATURE detections the signed
 * page would produce are inserted directly as layout_element rows in the exact shape the T2–T4
 * worker path persists ({@code detector='signature-cv'}, {@code inkFraction} attributes, a
 * per-detection confidence).
 *
 * <p>The load-bearing distinction (design D5): on the unsigned fixture the region anchors ARE
 * found and no ink exists, so buyerSigned/sellerSigned are the REAL value UNSIGNED — LABEL
 * evidence only, ZERO VALUE rows, confidence from the label spans, NOT_VALIDATED. "This contract
 * is unsigned" must never be able to hide inside "I could not find the signature block".
 */
class PurchaseContractSignatureIT extends AbstractExtractionIT {

    @Test
    void both_signature_fields_are_SIGNED_with_element_backed_value_evidence_on_the_signed_fixture()
            throws Exception {
        UUID packageId = insertPackage("pc-signed-it");
        List<UUID> pageIds = insertFixturePages(packageId, "purchase_contract_signed");
        UUID signaturePageId = pageIds.get(1);
        // Ink above each signature rule, inside the schema window (left 0 / right 240 /
        // above 40 / below 8 around the label box). The blocks are ~100pt apart vertically,
        // so neither element can fall into the other party's window.
        UUID buyerInk =
                insertSignatureElement(signaturePageId, 0, spanBox(signaturePageId, "Buyer's"), "0.88");
        UUID sellerInk =
                insertSignatureElement(signaturePageId, 1, spanBox(signaturePageId, "Seller's"), "0.91");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("purchase_contract current field rows").hasSize(9);

        assertSigned(fields.get("buyerSigned"), "buyerSigned", buyerInk, "0.88", "0.7920",
                "purchase_contract_signed", "Buyer's");
        assertSigned(fields.get("sellerSigned"), "sellerSigned", sellerInk, "0.91", "0.8190",
                "purchase_contract_signed", "Seller's");

        // The seven anchor fields extract exactly as on the unsigned twin — the squiggles are
        // ink, not text, and must not perturb text extraction.
        assertAnchorFields(fields, "purchase_contract_signed");
    }

    @Test
    void both_signature_fields_are_UNSIGNED_real_values_with_label_only_evidence_on_the_unsigned_fixture()
            throws Exception {
        UUID packageId = insertPackage("pc-unsigned-it");
        insertFixturePages(packageId, "purchase_contract_unsigned");
        // Deliberately NO signature elements: the page carries no ink, so the detector that
        // DID look found nothing.
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        assertThat(fields).as("purchase_contract current field rows").hasSize(9);

        for (String name : List.of("buyerSigned", "sellerSigned")) {
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();
            // A REAL value, not the missing contract.
            assertThat(row.get("extraction_method"))
                    .as("%s method", name)
                    .isEqualTo("SIGNATURE_PRESENCE");
            assertThat(row.get("raw_value")).as("%s raw value", name).isEqualTo("UNSIGNED");
            assertThat(row.get("displayed_text")).as("%s displayed text", name).isEqualTo("UNSIGNED");
            assertThat(row.get("normalized_text"))
                    .as("%s normalized text", name)
                    .isEqualTo("UNSIGNED");
            assertThat(row.get("data_type")).as("%s data type", name).isEqualTo("ENUM");
            assertThat(row.get("validation_status"))
                    .as("%s validation status — found, not review-required", name)
                    .isEqualTo("NOT_VALIDATED");
            // spanConfidence = min(label span confidences) = 1.0 (native spans), × 0.9 strength.
            assertThat((BigDecimal) row.get("confidence"))
                    .as("%s confidence", name)
                    .isEqualByComparingTo("0.9000");
            JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
            assertThat(components.get("spanConfidence").decimalValue())
                    .as("%s spanConfidence — the label localization", name)
                    .isEqualByComparingTo("1");
            assertThat(components.get("anchorStrength").decimalValue())
                    .isEqualByComparingTo("0.9");
            assertThat(components.get("normalizerCertainty").decimalValue())
                    .isEqualByComparingTo("1");
            assertThat(components.size()).as("%s components keys", name).isEqualTo(3);

            UUID fieldId = (UUID) row.get("id");
            // THE evidence shape of the absence claim: no VALUE rows, label rows only.
            assertThat(evidenceOf(fieldId, "VALUE"))
                    .as("%s VALUE evidence — UNSIGNED has none by contract", name)
                    .isEmpty();
            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).as("%s LABEL evidence — the region label spans", name)
                    .hasSize(2);
            String firstWord = name.equals("buyerSigned") ? "Buyer's" : "Seller's";
            boolean labelBoxMatched = false;
            for (Map<String, Object> evidence : labelEvidence) {
                assertThat(evidence.get("text_span_id"))
                        .as("%s LABEL evidence is span-backed", name)
                        .isNotNull();
                assertThat(evidence.get("layout_element_id"))
                        .as("%s LABEL evidence is text, not an element", name)
                        .isNull();
                assertThat(packagePageIndexOf((UUID) evidence.get("page_id")))
                        .as("%s LABEL evidence page", name)
                        .isEqualTo(1);
                labelBoxMatched |=
                        boxMatches(
                                evidence,
                                truthWord("purchase_contract_unsigned", 1, firstWord, 0));
            }
            assertThat(labelBoxMatched)
                    .as("%s: some LABEL evidence box equals the region label's truth box", name)
                    .isTrue();
        }

        assertAnchorFields(fields, "purchase_contract_unsigned");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The seven text-anchored fields, against the fixture's truth-by-construction. */
    private void assertAnchorFields(Map<String, Map<String, Object>> fields, String fixture) {
        JsonNode expectedFields = truth(fixture).get("expectedFields");
        // Guards the loop against a vacuous pass: the two signature fields are deliberately
        // NOT in the truth (their VALUE evidence is an element, not a drawn word).
        assertThat(expectedFields).as("%s expectedFields", fixture).hasSize(7);
        for (JsonNode expected : expectedFields) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();
            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat(row.get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertNormalized(name, row, expected.get("normalized"));
            assertThat((BigDecimal) row.get("confidence"))
                    .as("field %s confidence", name)
                    .isGreaterThan(BigDecimal.ZERO);

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            boolean valueBoxMatched = false;
            for (Map<String, Object> evidence : valueEvidence) {
                assertThat(packagePageIndexOf((UUID) evidence.get("page_id")))
                        .as("field %s VALUE evidence page", name)
                        .isEqualTo(expected.get("pageIndex").asInt());
                for (JsonNode word : expected.get("valueWords")) {
                    valueBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(valueBoxMatched)
                    .as("field %s: some VALUE evidence box equals a truth valueWords box", name)
                    .isTrue();

            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).as("field %s LABEL evidence", name).isNotEmpty();
            boolean labelBoxMatched = false;
            for (Map<String, Object> evidence : labelEvidence) {
                for (JsonNode word : expected.get("labelWords")) {
                    labelBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(labelBoxMatched)
                    .as("field %s: some LABEL evidence box equals a truth labelWords box", name)
                    .isTrue();
        }
    }

    private void assertSigned(
            Map<String, Object> row,
            String name,
            UUID inkElementId,
            String detectionConfidence,
            String overallConfidence,
            String fixture,
            String firstWord)
            throws Exception {
        assertThat(row).as("current row for %s", name).isNotNull();
        assertThat(row.get("extraction_method"))
                .as("%s method", name)
                .isEqualTo("SIGNATURE_PRESENCE");
        assertThat(row.get("raw_value")).as("%s raw value", name).isEqualTo("SIGNED");
        assertThat(row.get("displayed_text")).as("%s displayed text", name).isEqualTo("SIGNED");
        assertThat(row.get("normalized_text")).as("%s normalized text", name).isEqualTo("SIGNED");
        assertThat(row.get("data_type")).as("%s data type", name).isEqualTo("ENUM");
        assertThat(row.get("validation_status"))
                .as("%s validation status", name)
                .isEqualTo("NOT_VALIDATED");
        assertThat((BigDecimal) row.get("confidence"))
                .as("%s confidence = detection × strength", name)
                .isEqualByComparingTo(overallConfidence);
        JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
        assertThat(components.get("spanConfidence").decimalValue())
                .as("%s spanConfidence — the detection's confidence", name)
                .isEqualByComparingTo(detectionConfidence);
        assertThat(components.get("anchorStrength").decimalValue()).isEqualByComparingTo("0.9");
        assertThat(components.get("normalizerCertainty").decimalValue()).isEqualByComparingTo("1");
        assertThat(components.size()).as("%s components keys", name).isEqualTo(3);

        UUID fieldId = (UUID) row.get("id");
        List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
        assertThat(valueEvidence).as("%s VALUE evidence — exactly the ink element", name).hasSize(1);
        assertThat(valueEvidence.get(0).get("layout_element_id"))
                .as("%s VALUE evidence element id", name)
                .isEqualTo(inkElementId);
        assertThat(valueEvidence.get(0).get("text_span_id"))
                .as("%s VALUE evidence has no span — ink is not text", name)
                .isNull();
        assertThat(packagePageIndexOf((UUID) valueEvidence.get(0).get("page_id")))
                .as("%s VALUE evidence page", name)
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT element_type FROM layout_element WHERE id = ?",
                                String.class,
                                inkElementId))
                .isEqualTo("SIGNATURE");

        List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
        assertThat(labelEvidence).as("%s LABEL evidence", name).hasSize(2);
        boolean labelBoxMatched = false;
        for (Map<String, Object> evidence : labelEvidence) {
            labelBoxMatched |= boxMatches(evidence, truthWord(fixture, 1, firstWord, 0));
        }
        assertThat(labelBoxMatched)
                .as("%s: some LABEL evidence box equals the region label's truth box", name)
                .isTrue();
    }

    /**
     * A SIGNATURE layout element placed inside the window above the given label box: 10pt right
     * of the label's left edge and 34pt above its top, 150×26 — the footprint a real squiggle
     * leaves above the signature rule.
     */
    private UUID insertSignatureElement(
            UUID pageId, int ordinal, BigDecimal[] labelBox, String confidence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version,
                    attributes)
                VALUES (?, ?, ?, NULL, 'SIGNATURE', ?, ?, ?, 150.0, 26.0, ?::numeric,
                        'signature-cv', 'it', ?::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                ordinal,
                labelBox[0].add(BigDecimal.TEN),
                labelBox[1].subtract(new BigDecimal("34")),
                confidence,
                "{\"inkFraction\": 0.21}");
        return id;
    }

    /** [x, y, width, height] of the unique span with this exact text on the page. */
    private BigDecimal[] spanBox(UUID pageId, String text) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT x, y, width, height FROM text_span WHERE page_id = ? AND text = ?",
                        pageId,
                        text);
        return new BigDecimal[] {
            (BigDecimal) row.get("x"), (BigDecimal) row.get("y"),
            (BigDecimal) row.get("width"), (BigDecimal) row.get("height")
        };
    }

    private static JsonNode truthWord(String fixture, int pageIndex, String text, int occurrence) {
        int seen = 0;
        for (JsonNode word : truth(fixture).get("pages").get(pageIndex).get("words")) {
            if (word.get("text").asText().equals(text)) {
                if (seen == occurrence) {
                    return word;
                }
                seen++;
            }
        }
        throw new IllegalStateException("truth word not found: " + text);
    }

    private static void assertNormalized(String name, Map<String, Object> row, JsonNode normalized) {
        if (normalized == null || normalized.isNull()) {
            return;
        }
        if (normalized.has("text")) {
            assertThat(row.get("normalized_text"))
                    .as("field %s normalized text", name)
                    .isEqualTo(normalized.get("text").asText());
        }
        if (normalized.has("number")) {
            assertThat((BigDecimal) row.get("normalized_number"))
                    .as("field %s normalized number", name)
                    .isEqualByComparingTo(normalized.get("number").asText());
        }
        if (normalized.has("date")) {
            assertThat(String.valueOf(row.get("normalized_date")))
                    .as("field %s normalized date", name)
                    .isEqualTo(normalized.get("date").asText());
        }
    }
}
