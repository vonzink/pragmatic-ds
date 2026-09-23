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
 * {@code schedule_f@1.0.0} end to end: CLASSIFYING → SPLITTING → EXTRACTING over the Schedule F
 * fixture, every field checked against the words generate.py actually drew.
 *
 * <p>Schedule F is the widest schema the engine seeds — a farm's whole income and expense picture
 * is 47 fields — and the risk it carries is not recall but CROSSTALK. Part II is two columns on a
 * shared baseline, so "17 Fertilizers and lime 18,200.00 29 Taxes 4,300.00" is ONE line carrying
 * two captions and two amounts. Every expense rung is {@code ANCHOR_LABEL}/{@code LINE_RIGHT} off
 * its own caption for that reason, and {@link #the_two_column_expense_grid_never_reads_its_neighbour}
 * pins both halves of the row that would break first if a caption were shortened into ambiguity.
 */
class ScheduleFExtractionIT extends AbstractExtractionIT {

    /** The one Schedule F field the seed marks sensitive — sensitivity comes from DATA. */
    private static final Set<String> SENSITIVE = Set.of("proprietorSsn");

    private UUID extractScheduleF() {
        UUID packageId = insertPackage("schedule-f-extract-it");
        insertFixturePages(packageId, "schedule_f");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    @Test
    void every_field_persists_with_the_value_and_the_evidence_the_fixture_drew() throws Exception {
        UUID documentId = extractScheduleF();
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT DISTINCT s.version FROM extracted_field f"
                                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                                        + " WHERE f.logical_document_id = ? AND f.is_current",
                                String.class,
                                documentId))
                .as("the schema version that produced these rows")
                .isEqualTo("1.0.0");

        for (JsonNode expected : truth("schedule_f").get("expectedFields")) {
            String name = expected.get("field").asText();
            String key = name + "#";
            Map<String, Object> row = occurrences.get(key);
            assertThat(row).as("current row for %s", name).isNotNull();

            assertThat(row.get("extraction_method"))
                    .as("%s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat(row.get("displayed_text"))
                    .as("%s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertThat((BigDecimal) row.get("confidence"))
                    .as("%s confidence", name)
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(row.get("is_sensitive"))
                    .as("%s sensitivity comes from the SEED", name)
                    .isEqualTo(SENSITIVE.contains(name));

            List<Map<String, Object>> valueEvidence = evidenceOf((UUID) row.get("id"), "VALUE");
            JsonNode expectedWords = expected.get("valueWords");
            assertThat(valueEvidence).as("%s VALUE box count", name).hasSize(expectedWords.size());
            for (int i = 0; i < expectedWords.size(); i++) {
                assertThat(boxMatches(valueEvidence.get(i), expectedWords.get(i)))
                        .as("%s VALUE box %d equals the drawn word", name, i)
                        .isTrue();
            }
            assertThat(evidenceOf((UUID) row.get("id"), "LABEL"))
                    .as("%s LABEL evidence — the reason this value was read as this field", name)
                    .isNotEmpty();
        }
    }

    @Test
    void the_two_column_expense_grid_never_reads_its_neighbour() {
        // One printed row, two captions, two amounts. Named by value because a rung that
        // walked past its own column into the next one would still return a plausible
        // farm expense — just the wrong one, at full confidence.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleF());

        assertThat(occurrences.get("fertilizersAndLime#").get("displayed_text")).isEqualTo("18,200.00");
        assertThat(occurrences.get("taxes#").get("displayed_text")).isEqualTo("4,300.00");
        assertThat(occurrences.get("feed#").get("displayed_text")).isEqualTo("22,600.00");
        assertThat(occurrences.get("supplies#").get("displayed_text")).isEqualTo("3,750.00");
        assertThat(occurrences.get("mortgageInterest#").get("displayed_text")).isEqualTo("14,700.00");
        assertThat(occurrences.get("otherInterest#").get("displayed_text")).isEqualTo("1,450.00");
    }

    @Test
    void the_repeated_line_captions_resolve_to_their_own_amounts() {
        // 3a/3b, 4a/4b and 6a/6b each print the same noun phrase twice — gross and
        // taxable. The draft generator parked all six with "value_text_appears_more_
        // than_once_on_page"; V44 names each by the caption the value is actually under,
        // and this is the assertion that says the naming worked.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleF());

        assertThat(occurrences.get("cooperativeDistributions#").get("displayed_text"))
                .isEqualTo("6,200.00");
        assertThat(occurrences.get("cooperativeDistributionsTaxable#").get("displayed_text"))
                .isEqualTo("5,900.00");
        assertThat(occurrences.get("agriculturalProgramPayments#").get("displayed_text"))
                .isEqualTo("8,300.00");
        assertThat(occurrences.get("agriculturalProgramPaymentsTaxable#").get("displayed_text"))
                .isEqualTo("7,950.00");
        assertThat(occurrences.get("cropInsuranceProceeds#").get("displayed_text"))
                .isEqualTo("4,100.00");
        assertThat(occurrences.get("cropInsuranceProceedsTaxable#").get("displayed_text"))
                .isEqualTo("3,850.00");
    }

    @Test
    void the_custom_hire_line_appears_as_income_AND_as_an_expense() {
        // Line 7 and line 13 print the same five words, once on each side of the ledger.
        // The income rung anchors on the trailing "income"; the expense rung anchors on
        // the printed line number, because on the expense side there is nothing else.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleF());

        assertThat(occurrences.get("customHireIncome#").get("displayed_text")).isEqualTo("9,600.00");
        assertThat(occurrences.get("customHireExpense#").get("displayed_text")).isEqualTo("4,750.00");
    }

    @Test
    void the_bottom_line_is_the_one_a_farm_income_calculation_needs() {
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleF());

        assertThat(occurrences.get("grossIncome#").get("displayed_text")).isEqualTo("219,300.00");
        assertThat(occurrences.get("totalExpenses#").get("displayed_text")).isEqualTo("194,300.00");
        assertThat(occurrences.get("netFarmProfitOrLoss#").get("displayed_text")).isEqualTo("25,000.00");
    }
}
