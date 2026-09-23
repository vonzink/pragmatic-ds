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
 * {@code schedule_d@1.0.0} end to end: CLASSIFYING → SPLITTING → EXTRACTING over the Schedule D
 * fixture, every field checked against the words generate.py actually drew.
 *
 * <p>The three-column assertion is the one worth reading. Schedule D's 8949 totals rows print ONE
 * caption and THREE amounts on a single baseline, so proceeds, cost basis and gain are separated
 * only by {@code LINE_RIGHT} occurrence — 0, 1, 2 off the same label. That is the shape the draft
 * generator was reaching for when it minted {@code totalsForAllTransactionsReportedOn5}, and it is
 * also the shape that fails silently: an off-by-one in the occurrence gives a confident, correctly
 * boxed, WRONG number, which on a capital-gains schedule is a cost basis reported as a gain.
 * {@link #the_8949_totals_row_reads_three_columns_off_one_caption} pins each column by value.
 */
class ScheduleDExtractionIT extends AbstractExtractionIT {

    /** The one Schedule D field the seed marks sensitive — sensitivity comes from DATA. */
    private static final Set<String> SENSITIVE = Set.of("taxpayerSsn");

    private UUID extractScheduleD() {
        UUID packageId = insertPackage("schedule-d-extract-it");
        insertFixturePages(packageId, "schedule_d");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    @Test
    void every_field_persists_with_the_value_and_the_evidence_the_fixture_drew() throws Exception {
        UUID documentId = extractScheduleD();
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

        for (JsonNode expected : truth("schedule_d").get("expectedFields")) {
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

            // D4: the evidence must point at the cell the value was actually read from,
            // which is the only thing separating a right answer from a lucky one.
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
    void all_six_8949_totals_rows_read_three_columns_off_their_own_caption() {
        // Named by value, not by count. An occurrence off by one still produces
        // eighteen rows, eighteen confident scores and eighteen correct-looking boxes;
        // only the numbers say a cost basis landed in the gain. The six rows differ
        // ONLY by the box letter inside their caption, which is why every one of them
        // is asserted rather than a representative pair: a rung that matched the wrong
        // "Box _ checked" line would still return money from a real 8949 totals row.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleD());

        assertThat(columns(occurrences, "shortTermBoxA"))
                .containsExactly("18,400.00", "15,250.00", "3,150.00");
        assertThat(columns(occurrences, "shortTermBoxB"))
                .containsExactly("9,800.00", "8,600.00", "1,200.00");
        assertThat(columns(occurrences, "shortTermBoxC"))
                .containsExactly("4,500.00", "4,750.00", "325.00");
        assertThat(columns(occurrences, "longTermBoxD"))
                .containsExactly("96,500.00", "71,300.00", "25,200.00");
        assertThat(columns(occurrences, "longTermBoxE"))
                .containsExactly("32,750.00", "28,900.00", "3,850.00");
        assertThat(columns(occurrences, "longTermBoxF"))
                .containsExactly("12,600.00", "11,100.00", "1,500.00");
    }

    /** One 8949 totals row's three columns, in printed order. */
    private List<String> columns(Map<String, Map<String, Object>> occurrences, String prefix) {
        return List.of("Proceeds", "CostBasis", "GainOrLoss").stream()
                .map(column -> occurrences.get(prefix + column + "#"))
                .map(row -> row == null ? "<absent>" : String.valueOf(row.get("displayed_text")))
                .toList();
    }

    @Test
    void the_totals_rows_add_up_to_the_net_gain_lines_the_form_prints() {
        // The fixture's arithmetic is consistent, so this is a real cross-check rather
        // than a restatement: 3,150 + 1,200 + 325 + 1,725 - 4,000 = 2,400 and
        // 25,200 + 3,850 + 1,500 + 3,400 + 1,980 - 2,500 = 33,430. If a gain column
        // were read off the wrong row the sum stops matching the printed total, which
        // is the check an underwriter would do by hand.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleD());

        assertThat(sum(occurrences, "shortTermBoxAGainOrLoss", "shortTermBoxBGainOrLoss",
                        "shortTermBoxCGainOrLoss", "netShortTermGainFromK1")
                        .subtract(money(occurrences, "shortTermCapitalLossCarryover")))
                .isEqualByComparingTo(money(occurrences, "netShortTermCapitalGain"));

        assertThat(sum(occurrences, "longTermBoxDGainOrLoss", "longTermBoxEGainOrLoss",
                        "longTermBoxFGainOrLoss", "netLongTermGainFromK1",
                        "capitalGainDistributions")
                        .subtract(money(occurrences, "longTermCapitalLossCarryover")))
                .isEqualByComparingTo(money(occurrences, "netLongTermCapitalGain"));
    }

    private BigDecimal sum(Map<String, Map<String, Object>> occurrences, String... fields) {
        BigDecimal total = BigDecimal.ZERO;
        for (String field : fields) {
            total = total.add(money(occurrences, field));
        }
        return total;
    }

    /** The NORMALIZED value, which is the one a calculation may use. */
    private BigDecimal money(Map<String, Map<String, Object>> occurrences, String field) {
        Map<String, Object> row = occurrences.get(field + "#");
        assertThat(row).as("row for %s", field).isNotNull();
        return (BigDecimal) row.get("normalized_number");
    }

    @Test
    void the_two_net_gain_lines_are_the_numbers_an_underwriter_reads() {
        // Lines 7 and 15 are what Part III's summary combines, and the reason V44 does
        // not model Part III at all: these two ARE the summary's inputs, extracted from
        // the page that prints them rather than re-derived from a page that does not.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleD());

        assertThat(occurrences.get("netShortTermCapitalGain#").get("displayed_text"))
                .isEqualTo("2,400.00");
        assertThat(occurrences.get("netLongTermCapitalGain#").get("displayed_text"))
                .isEqualTo("33,430.00");
    }
}
