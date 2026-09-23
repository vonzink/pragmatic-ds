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
 * drivers_license@1.0.0 end to end against the drivers_license card fixture: CLASSIFYING →
 * SPLITTING → EXTRACTING on truth spans, then every expected field asserted on method, value,
 * normalization, seed-declared sensitivity, and evidence boxes — the same truth-by-construction
 * loop as W2ExtractionIT (T10). The three REGEX fields (fullName, address, issuingState) have no
 * LABEL evidence by design; the address value regex spans TWO visual lines (street + city),
 * proving a PAGE-scoped value may cross lines while its evidence still cites every drawn word.
 */
class DriversLicenseExtractionIT extends AbstractExtractionIT {

    /** The two DL fields the V11 seed marks sensitive — sensitivity comes from DATA, not a flip. */
    private static final Set<String> SENSITIVE = Set.of("licenseNumber", "dateOfBirth");

    @Test
    void all_eight_drivers_license_fields_extract_with_full_evidence() {
        UUID packageId = insertPackage("dl-extract-it");
        insertFixturePages(packageId, "drivers_license");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).hasSize(8);

        for (JsonNode expected : truth("drivers_license").get("expectedFields")) {
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
