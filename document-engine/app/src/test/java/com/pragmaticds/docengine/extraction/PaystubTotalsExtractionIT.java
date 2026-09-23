package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code paystub@1.4.0} (V48): the gross-pay pair read from the two places real stubs print
 * it when there is no {@code Gross} row for TABLE_CLUSTER or the {@code Gross Pay} anchors.
 *
 * <p>Two real stubs measured on 2026-09-14 both came back with currentGrossPay and ytdGrossPay
 * MISSING. A payroll bureau prints its totals as a strip — {@code This Pay Period | Year To Date}
 * over {@code Earnings Deductions Net Pay Earnings Deductions Net Pay} over six amounts — whose
 * lone word {@code Earnings} repeats up the page; the caption ROW read whole does not. A national
 * payroll provider prints {@code TOTAL EARNINGS >>> current ytd} as one line far below its detail
 * grid, and captions federal tax {@code FIT WH}.
 */
class PaystubTotalsExtractionIT extends AbstractExtractionIT {

    private UUID extract(String fixture) {
        UUID packageId = insertPackage("paystub-totals-it-" + fixture);
        insertFixturePages(packageId, fixture);
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    /**
     * V53's supersession, stated from the table: {@code paystub@1.5.0} is the ONLY active global
     * paystub schema, and {@code 1.4.0} is still there, RETIRED — never edited, never deleted,
     * because every row it produced still cites it through {@code schema_id}.
     */
    @Test
    void v53_retires_1_4_0_and_leaves_1_5_0_the_only_active_paystub_schema() {
        java.util.Map<String, Boolean> activeByVersion = new java.util.TreeMap<>();
        for (java.util.Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT version, is_active FROM extraction_schema"
                                + " WHERE org_id IS NULL AND document_type_code = 'PAYSTUB'")) {
            activeByVersion.put((String) row.get("version"), (Boolean) row.get("is_active"));
        }
        assertThat(activeByVersion).containsEntry("1.4.0", false).containsEntry("1.5.0", true);
        assertThat(activeByVersion.values().stream().filter(active -> active).count())
                .as("exactly one active global paystub schema")
                .isEqualTo(1L);
    }

    private void assertSchemaVersion(UUID documentId) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT DISTINCT s.version FROM extracted_field f"
                                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                                        + " WHERE f.logical_document_id = ? AND f.is_current",
                                String.class,
                                documentId))
                .as("the schema version that produced these rows")
                .isEqualTo("1.5.0");
    }

    /** Every expectation the fixture's truth carries, present or deliberately missing. */
    private void assertEveryExpectedField(UUID documentId, String fixture) {
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);
        for (JsonNode expected : truth(fixture).get("expectedFields")) {
            String name = expected.get("field").asText();
            Map<String, Object> row = occurrences.get(name + "#");
            assertThat(row).as("current row for %s", name).isNotNull();
            assertThat(row.get("extraction_method"))
                    .as("%s extraction method", name)
                    .isEqualTo(expected.get("method").asText());

            if (expected.get("method").asText().equals("NONE")) {
                assertThat(row.get("displayed_text")).as("%s displayed text", name).isNull();
                assertThat(scaled(row.get("confidence")))
                        .as("%s confidence", name)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(evidenceOf((UUID) row.get("id"), "VALUE"))
                        .as("%s VALUE evidence", name)
                        .isEmpty();
                continue;
            }

            assertThat(row.get("displayed_text"))
                    .as("%s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            List<Map<String, Object>> valueEvidence = evidenceOf((UUID) row.get("id"), "VALUE");
            JsonNode expectedWords = expected.get("valueWords");
            assertThat(valueEvidence).as("%s VALUE box count", name).hasSize(expectedWords.size());
            for (int i = 0; i < expectedWords.size(); i++) {
                assertThat(boxMatches(valueEvidence.get(i), expectedWords.get(i)))
                        .as("%s VALUE box %d equals the drawn word", name, i)
                        .isTrue();
            }
            // The established contract (AbstractExtractionEvalIT): some LABEL evidence box equals
            // a truth labelWords box.
            List<Map<String, Object>> labelEvidence = evidenceOf((UUID) row.get("id"), "LABEL");
            boolean cited = false;
            for (JsonNode word : expected.get("labelWords")) {
                for (Map<String, Object> evidence : labelEvidence) {
                    cited |= boxMatches(evidence, word);
                }
            }
            assertThat(cited).as("%s LABEL evidence cites a drawn caption word", name).isTrue();
        }
    }

    @Test
    void the_providers_total_earnings_line_yields_the_gross_pair_and_fit_the_federal_tax()
            throws Exception {
        UUID documentId = extract("paystub_adp");
        assertEveryExpectedField(documentId, "paystub_adp");
        assertSchemaVersion(documentId);

        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);
        // Named by value: occurrence 0 and 1 to the right of TOTAL EARNINGS, past the ">>>"
        // glyph, never the deductions total on the same baseline.
        assertThat(occurrences.get("currentGrossPay#").get("displayed_text")).isEqualTo("2,687.52");
        assertThat(occurrences.get("ytdGrossPay#").get("displayed_text")).isEqualTo("45,375.20");
        assertThat(occurrences.get("federalWithholding#").get("displayed_text")).isEqualTo("312.43");
        // The company block has no caption and no corporate suffix: the REGEX rungs stay quiet
        // rather than binding a fragment, which is what the real stub's 0.4 capture was.
        assertThat(occurrences.get("employerName#").get("extraction_method")).isEqualTo("NONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"paystub_bureau_native", "paystub_bureau"})
    void the_bureau_strips_first_block_is_the_current_pair_and_the_block_after_net_pay_the_ytd(
            String fixture) throws Exception {
        // The same strip in both dialects: spaced captions on the native PDF, "NetPay" fused on
        // the scanned copy. V36/V42 left this pair "honestly absent" because the word Earnings
        // repeats; the caption row does not.
        UUID documentId = extract(fixture);
        assertEveryExpectedField(documentId, fixture);
        assertSchemaVersion(documentId);

        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);
        assertThat(occurrences.get("currentGrossPay#").get("displayed_text")).isEqualTo("2,096.77");
        assertThat(occurrences.get("currentGrossPay#").get("extraction_method"))
                .isEqualTo("LABEL_BELOW");
        assertThat(occurrences.get("ytdGrossPay#").get("displayed_text")).isEqualTo("62,082.43");
        assertThat(occurrences.get("ytdGrossPay#").get("extraction_method"))
                .isEqualTo("LABEL_BELOW");
        // The strip's own net pay is neither gross figure, and the netPay field still reads
        // from the deposit line as before.
        assertThat(occurrences.get("netPay#").get("displayed_text")).isEqualTo("1,336.28");
    }
}
