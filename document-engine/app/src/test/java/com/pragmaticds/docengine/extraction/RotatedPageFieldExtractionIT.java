package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A {@code /Rotate 180} page must produce the same ten paystub fields, with the same real values,
 * as the upright page it is a turn of. Nothing about being rotated may reach the field engine:
 * spans arrive canonical rotation-0 by contract, so an upside-down scan is an ordinary document.
 *
 * <p>WHY THIS EXISTS: the live stack answered {@code UNKNOWN / confidence 0.0 / 0 fields} for a
 * {@code /Rotate 180} paystub, because the worker's text stage handed back every word's characters
 * REVERSED — {@code '$3,565.87'} as {@code '78.565,3$'} — with perfect boxes and no error. Total,
 * silent, data-dependent loss; real loan packages carry upside-down scans routinely.
 *
 * <p>WHAT THIS CAN AND CANNOT SEE: the Java half consumes PERSISTED spans and never calls the
 * worker, so it cannot observe that reversal — {@code TestRotatedPageWordText} in
 * {@code worker/tests/text/test_text_endpoint.py} is the guard that fails when the characters flip,
 * and it is the mutation-checked one. Its blind spot was WORD ORDER: this test replays pages from
 * {@code fixtures/truth/}, whose words are already in canonical reading order, so for a while the
 * worker returned those same words REVERSED (it ordered them in display space) and nothing on
 * either side compared the two. {@code worker/tests/text/test_span_order.py} closed that by pinning
 * span i to truth word i, which is the sequence this test assumes it is being handed. What this
 * pins is the other half of the same claim, which
 * had no test at all: given a page STORED as rotated, extraction still yields real values rather
 * than the empty result the defect produced downstream. Deleting the fixture's rotation, or
 * special-casing rotated pages out of the extraction path, turns this red.
 */
class RotatedPageFieldExtractionIT extends AbstractExtractionIT {

    private static final String FIXTURE = "paystub_complete_rot180";

    @Test
    void a_rotate_180_paystub_extracts_the_same_ten_fields_with_the_same_values() {
        UUID packageId = insertPackage("extract-rot180-it");
        insertFixturePages(packageId, FIXTURE);
        insertFixtureLayout(packageId, FIXTURE);

        // The page really is stored rotated — otherwise this test is the upright one twice.
        assertThat(
                        jdbc.queryForList(
                                "SELECT rotation FROM page WHERE package_id = ?",
                                Integer.class,
                                packageId))
                .containsExactly(180);

        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT document_type_code FROM logical_document WHERE id = ?",
                                String.class,
                                documentId))
                .as("a rotated paystub is still a PAYSTUB, not the UNKNOWN the defect produced")
                .isEqualTo("PAYSTUB");

        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        // paystub@1.5.0's eighteen declared fields (ten read by rules, eight AI-only MISSING),
        // not the zero the defect produced.
        assertThat(fields).as("eighteen fields, not the zero the defect produced").hasSize(18);

        for (JsonNode expected : truth(FIXTURE).get("expectedFields")) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();

            // Real values, spelled out by truth — "$3,565.87", not "78.565,3$" and not null.
            assertThat(row.get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(expected.get("method").asText());
            assertThat((BigDecimal) row.get("confidence"))
                    .as("field %s confidence", name)
                    .isGreaterThan(BigDecimal.ZERO);

            // Evidence still points at the truth box on the right page: the canonical frame
            // is rotation-0 no matter which way the page is stored.
            List<Map<String, Object>> valueEvidence =
                    evidenceOf((UUID) row.get("id"), "VALUE");
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
        }
    }
}
