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
 * {@code schedule_c@1.1.0} (V48) end to end over the Schedule C fixtures, every field checked
 * against the words generate.py actually drew.
 *
 * <p>Two real filled returns measured on 2026-09-14 extracted 2 of 10 each: businessName and
 * taxYear were MISSING on both. The filled official form types the business name under the box
 * LETTER, 26 pt left of the caption text and in Title Case, and sets the masthead year as two
 * abutting runs under the OMB number rather than on the title row. {@code schedule_c_filled} is
 * drawn to that geometry; {@code schedule_c_blank_business} is the same page with the box left
 * empty, which pins the guard that matters most: an empty box must stay MISSING and never read
 * the next caption row's street address, 17 pt below, as a business name.
 */
class ScheduleCExtractionIT extends AbstractExtractionIT {

    /** The one Schedule C field the seed marks sensitive — sensitivity comes from DATA. */
    private static final Set<String> SENSITIVE = Set.of("proprietorSsn");

    private UUID extract(String fixture) {
        UUID packageId = insertPackage("schedule-c-extract-it-" + fixture);
        insertFixturePages(packageId, fixture);
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
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
                .isEqualTo("1.1.0");
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
            assertThat(row.get("is_sensitive"))
                    .as("%s sensitivity comes from the SEED", name)
                    .isEqualTo(SENSITIVE.contains(name));

            if (expected.get("method").asText().equals("NONE")) {
                // A missing field is a result, not an absence: a row at confidence 0 with no
                // evidence at all — never a value borrowed from a neighbouring cell.
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
            assertThat((BigDecimal) row.get("confidence"))
                    .as("%s confidence", name)
                    .isGreaterThan(BigDecimal.ZERO);

            List<Map<String, Object>> valueEvidence = evidenceOf((UUID) row.get("id"), "VALUE");
            JsonNode expectedWords = expected.get("valueWords");
            assertThat(valueEvidence).as("%s VALUE box count", name).hasSize(expectedWords.size());
            for (int i = 0; i < expectedWords.size(); i++) {
                assertThat(boxMatches(valueEvidence.get(i), expectedWords.get(i)))
                        .as("%s VALUE box %d equals the drawn word", name, i)
                        .isTrue();
            }
            List<Map<String, Object>> labelEvidence = evidenceOf((UUID) row.get("id"), "LABEL");
            assertThat(labelEvidence)
                    .as("%s LABEL evidence — the reason this value was read as this field", name)
                    .isNotEmpty();
            // The established contract (AbstractExtractionEvalIT): some LABEL evidence box equals
            // a truth labelWords box. The truth lists the whole printed caption; a literal that
            // anchors a prefix of it (`Employer ID number` under `Employer ID number (EIN)`)
            // cites only the spans it matched.
            assertThat(citesAny(labelEvidence, expected.get("labelWords")))
                    .as("%s LABEL evidence cites a drawn caption word", name)
                    .isTrue();
        }
    }

    private static boolean citesAny(List<Map<String, Object>> evidence, JsonNode words) {
        for (JsonNode word : words) {
            if (cites(evidence, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cites(List<Map<String, Object>> evidence, JsonNode word) {
        for (Map<String, Object> box : evidence) {
            if (boxMatches(box, word)) {
                return true;
            }
        }
        return false;
    }

    /** The truth's label word with this text for a field — the drawn caption, by construction. */
    private static JsonNode labelWord(String fixture, String field, String text) {
        for (JsonNode expected : truth(fixture).get("expectedFields")) {
            if (expected.get("field").asText().equals(field)) {
                for (JsonNode word : expected.get("labelWords")) {
                    if (word.get("text").asText().equals(text)) {
                        return word;
                    }
                }
            }
        }
        throw new IllegalStateException(fixture + "/" + field + " has no label word " + text);
    }

    @Test
    void the_filled_form_reads_the_business_name_under_its_box_letter_and_the_masthead_year()
            throws Exception {
        UUID documentId = extract("schedule_c_filled");
        assertEveryExpectedField(documentId, "schedule_c_filled");
        assertSchemaVersion(documentId);

        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);
        // Named by value: Title Case, whole, starting 26 pt left of the caption text.
        assertThat(occurrences.get("businessName#").get("displayed_text"))
                .isEqualTo("Sample Widget Works");
        assertThat(occurrences.get("businessName#").get("extraction_method"))
                .isEqualTo("LABEL_BELOW");
        // The rung that read it is the letter-anchored one: the box letter "C" is cited as
        // LABEL evidence, which is what puts the cell's left edge under the typed value.
        assertThat(
                        cites(
                                evidenceOf((UUID) occurrences.get("businessName#").get("id"), "LABEL"),
                                labelWord("schedule_c_filled", "businessName", "C")))
                .as("the box letter C is part of the label evidence")
                .isTrue();
        // "20" + "25" abut under the OMB caption; SpanJoin reads them as one token. The title
        // row carries no year on this form, so the first rung fails and the cell rung answers.
        assertThat(occurrences.get("taxYear#").get("displayed_text")).isEqualTo("2025");
        assertThat(occurrences.get("taxYear#").get("extraction_method")).isEqualTo("LABEL_BELOW");
    }

    @Test
    void a_blank_business_name_box_stays_missing_and_never_reads_the_next_captions_address()
            throws Exception {
        UUID documentId = extract("schedule_c_blank_business");
        assertEveryExpectedField(documentId, "schedule_c_blank_business");
        assertSchemaVersion(documentId);

        // The E row — "Business address ... 5678 Sample St" — sits 17 pt below the C caption's
        // bottom edge, inside a 24 pt drop and shaped like a name. The 12 pt drop refuses it.
        Map<String, Object> businessName = currentOccurrences(documentId).get("businessName#");
        assertThat(businessName.get("extraction_method")).isEqualTo("NONE");
        assertThat(businessName.get("displayed_text")).isNull();
        // Everything else on the page reads exactly as on the filled fixture.
        assertThat(currentOccurrences(documentId).get("taxYear#").get("displayed_text"))
                .isEqualTo("2025");
    }

    @Test
    void the_blank_form_fixture_still_reads_every_field_under_the_superseding_schema()
            throws Exception {
        // schedule_c draws the caption and the value at nearly the same x, so it read under
        // 1.0.0 by a narrow margin; it must keep reading — now through the letter-anchored
        // rung whose label evidence includes the box letter.
        UUID documentId = extract("schedule_c");
        assertEveryExpectedField(documentId, "schedule_c");
        assertSchemaVersion(documentId);
        assertThat(currentOccurrences(documentId).get("businessName#").get("displayed_text"))
                .isEqualTo("ACME WIDGET CONSULTING LLC");
    }
}
