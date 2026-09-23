package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * hoi_declaration@1.0.0 end to end against the synthetic declarations page: classify → split →
 * extract, then every schema field checked against the fixture's truth-by-construction
 * expectations (value, normalization, evidence boxes, page attribution).
 *
 * <p>Two things this page pins beyond the field list: the carrier masthead has no label to anchor
 * on, so insurerName rides a REGEX rung whose evidence is value-only (the T11 precedent — a DL
 * card header and an MS lender name are the same shape); and policyNumber arrives sensitive FROM
 * THE SEED — Spec 3's sensitive fields are production data, not a test-only jdbc flip.
 */
class HoiDeclarationExtractionIT extends AbstractExtractionIT {

    /** The one HOI_DECLARATION field the V11 seed marks sensitive. */
    private static final Set<String> SENSITIVE = Set.of("policyNumber");

    @Test
    void all_eight_hoi_declaration_fields_extract_with_full_evidence() {
        UUID packageId = insertPackage("hoi-extract-it");
        insertFixturePages(packageId, "hoi_declaration");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("hoi_declaration current field rows").hasSize(8);

        for (JsonNode expected : truth("hoi_declaration").get("expectedFields")) {
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
            assertThat(row.get("is_sensitive"))
                    .as("field %s sensitivity comes from the SEED", name)
                    .isEqualTo(SENSITIVE.contains(name));

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            int truthPage = expected.get("pageIndex").asInt();
            boolean valueBoxMatched = false;
            for (Map<String, Object> evidence : valueEvidence) {
                assertThat(packagePageIndexOf((UUID) evidence.get("page_id")))
                        .as("field %s VALUE evidence page", name)
                        .isEqualTo(truthPage);
                for (JsonNode word : expected.get("valueWords")) {
                    valueBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(valueBoxMatched)
                    .as("field %s: some VALUE evidence box equals a truth valueWords box", name)
                    .isTrue();

            if (expected.get("method").asText().equals("ANCHOR_LABEL")) {
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
            } else {
                // The REGEX rung has no label: nothing to cite but the value itself.
                assertThat(evidenceOf(fieldId, "LABEL"))
                        .as("field %s is REGEX — no LABEL evidence exists to cite", name)
                        .isEmpty();
            }
        }
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
