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
 * bank_statement@1.0.0 end to end against the three-page bank_statement fixture. The closing
 * figures (endingBalance, totalDeposits, totalWithdrawals) are drawn ONLY on the last page, so
 * their evidence page indexes prove the engine attributes fields to the page they came from.
 */
class BankStatementExtractionIT extends AbstractExtractionIT {

    /** The one BANK_STATEMENT field the V11 seed marks sensitive. */
    private static final Set<String> SENSITIVE = Set.of("accountNumber");

    @Test
    void all_eleven_bank_statement_fields_persist_and_the_nine_drawn_carry_full_evidence() {
        UUID packageId = insertPackage("bank-extract-it");
        insertFixturePages(packageId, "bank_statement");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        // Eleven since V41: the online print-out's two month-to-date fields belong to the
        // schema, and on a mailed statement — which prints no such tiles — each lands as a
        // MISSING row (method NONE). A missing field is a result, not an absence.
        assertThat(fields).hasSize(11);

        for (JsonNode expected : truth("bank_statement").get("expectedFields")) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();

            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            if (expected.get("method").asText().equals("NONE")) {
                // Truth pins the field MISSING: no value, no evidence — and never a
                // plausible number reached sideways from the summary block.
                assertThat(row.get("displayed_text")).as("field %s stays empty", name).isNull();
                assertThat(evidenceOf((UUID) row.get("id"), "VALUE"))
                        .as("field %s has no VALUE evidence", name)
                        .isEmpty();
                continue;
            }
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

    @Test
    void a_derived_field_persists_without_evidence() {
        UUID packageId = insertPackage("bank-derived-it");
        insertFixturePages(packageId, "bank_statement_usbank");
        runPipelineToExtraction(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        Map<String, Object> withdrawals = fields.get("totalWithdrawals");
        assertThat(withdrawals.get("extraction_method")).isEqualTo("DERIVED");
        assertThat(withdrawals.get("displayed_text")).isEqualTo("27,906.32");
        assertThat(withdrawals.get("raw_value").toString()).startsWith("beginningBalance=");
        assertThat(evidenceOf((UUID) withdrawals.get("id"), "VALUE")).isEmpty();
        assertThat(evidenceOf((UUID) withdrawals.get("id"), "LABEL")).isEmpty();
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
