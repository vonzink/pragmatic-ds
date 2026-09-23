package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * w2@1.1.0 end to end against {@code w2_form_typographic} — the SAME W-2 drawing as
 * {@code w2_form}, printed the way the IRS actually sets it: the possessive captions carry U+2019
 * RIGHT SINGLE QUOTATION MARK rather than the ASCII U+0027 every schema label and pack anchor in
 * this repo is authored with.
 *
 * <p><b>Why this IT exists.</b> {@code w2_form.pdf} was drawn with U+0027 because the schema was,
 * so {@link W2ExtractionIT} reported 10 of 10 while a real filled W-2 returned 8 — the three
 * apostrophe-captioned fields (employeeName, employeeSsn, employerName) matched nothing at a
 * classification score of 1.00. That is the box-grid defect's exact shape a second time: the
 * fixture carried the schema's own wrong assumption, and every test passed while both were wrong.
 * Both fixtures now run, both must reach 10 of 10, and neither may be relaxed to satisfy the
 * other — the punctuation fold WIDENS what matches and must never move it.
 *
 * <p>Classification is untouched by the change: the W2 pack's six anchors are all
 * apostrophe-free, so this page scored 1.00 before the fix as well. The failure was extraction's
 * alone, which is precisely what made it survive.
 */
class W2TypographicCaptionsExtractionIT extends AbstractExtractionIT {

    /** The three fields whose SHIPPED label carries an apostrophe — the ones that returned null. */
    private static final Set<String> APOSTROPHE_CAPTIONED =
            Set.of("employeeName", "employeeSsn", "employerName");

    private static final Set<String> SENSITIVE = Set.of("employeeSsn");

    @Test
    void all_ten_w2_fields_extract_from_a_form_whose_captions_print_U2019() throws Exception {
        UUID packageId = insertPackage("w2-typographic-it");
        insertFixturePages(packageId, "w2_form_typographic");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).hasSize(10);

        // The headline, named rather than counted so a regression prints WHICH fields survived.
        // Before the punctuation fold this list was the seven apostrophe-free fields.
        List<String> captured =
                fields.entrySet().stream()
                        .filter(entry -> entry.getValue().get("displayed_text") != null)
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
        assertThat(captured)
                .as("W2 fields that captured a value from a U+2019 form")
                .hasSize(10)
                .containsAll(APOSTROPHE_CAPTIONED);

        for (JsonNode expected : truth("w2_form_typographic").get("expectedFields")) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();

            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat(row.get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertThat(row.get("is_sensitive"))
                    .as("field %s sensitivity comes from the SEED", name)
                    .isEqualTo(SENSITIVE.contains(name));

            // The V7 contract: exactly three confidence components, never a fourth — and the
            // winning rung is the ANCHORED one (0.9), never a page-wide unanchored guess. Both
            // apostrophe-captioned string fields declare a REGEX fallback at 0.5/0.6, so this
            // also proves the fold made the LABEL match rather than the ladder falling through.
            JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
            assertThat(components.size())
                    .as("field %s confidence components: exactly three, never a fourth", name)
                    .isEqualTo(3);
            assertThat(components.get("anchorStrength").decimalValue())
                    .as("field %s anchorStrength is the winning rung's strength", name)
                    .isEqualByComparingTo("0.9");
        }
    }

    @Test
    void the_apostrophe_captioned_fields_cite_the_typographic_caption_as_label_evidence() {
        // Not merely "a value appeared": the value must be anchored on the caption the page
        // actually prints. A field that fell through to its unanchored REGEX rung would produce
        // the same text with NO label evidence — the Phase 5 trap class — and pass a value-only
        // assertion. Evidence boxes are compared against truth-by-construction word boxes.
        UUID packageId = insertPackage("w2-typographic-evidence-it");
        insertFixturePages(packageId, "w2_form_typographic");
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));

        for (JsonNode expected : truth("w2_form_typographic").get("expectedFields")) {
            String name = expected.get("field").asText();
            if (!APOSTROPHE_CAPTIONED.contains(name)) {
                continue;
            }
            assertThat(expected.get("method").asText())
                    .as("%s is read by the box-grid rung on this form", name)
                    .isEqualTo("LABEL_BELOW");

            UUID fieldId = (UUID) fields.get(name).get("id");
            List<Map<String, Object>> labelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(labelEvidence).as("field %s LABEL evidence", name).isNotEmpty();

            boolean labelBoxMatched = false;
            for (Map<String, Object> evidence : labelEvidence) {
                for (JsonNode word : expected.get("labelWords")) {
                    labelBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(labelBoxMatched)
                    .as("field %s: some LABEL evidence box is a drawn caption word's box", name)
                    .isTrue();

            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            boolean valueBoxMatched = false;
            for (Map<String, Object> evidence : valueEvidence) {
                for (JsonNode word : expected.get("valueWords")) {
                    valueBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(valueBoxMatched)
                    .as("field %s: some VALUE evidence box is a drawn value word's box", name)
                    .isTrue();
        }
    }

    @Test
    void the_typographic_fixture_really_does_print_U2019() {
        // A guard against a vacuous regression: if the generator ever drew this variant with the
        // ASCII apostrophe, the two ITs above would pass while testing nothing — exactly how the
        // box-grid defect survived. The fixture's own truth is the witness.
        long curly = 0;
        for (JsonNode word : truth("w2_form_typographic").get("pages").get(0).get("words")) {
            if (word.get("text").asText().indexOf('’') >= 0) {
                curly++;
            }
        }
        assertThat(curly)
                .as("caption words printed with U+2019 RIGHT SINGLE QUOTATION MARK")
                .isEqualTo(6);

        for (JsonNode word : truth("w2_form").get("pages").get(0).get("words")) {
            assertThat(word.get("text").asText().indexOf('’'))
                    .as("w2_form stays the ASCII-apostrophe fixture, unchanged beside it")
                    .isEqualTo(-1);
        }
    }
}
