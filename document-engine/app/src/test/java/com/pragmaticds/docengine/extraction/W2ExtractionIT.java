package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * w2@1.1.0 end to end against the CORRECTED box-grid w2_form fixture: CLASSIFYING → SPLITTING →
 * EXTRACTING on truth spans, then every expected field asserted on method, value, normalization,
 * seed-declared sensitivity, and evidence boxes — the same truth-by-construction loop
 * FieldExtractionStageIT runs for the paystub.
 *
 * <p><b>Spec 4.</b> Nine of the ten fields are read by {@code LABEL_BELOW}: on a real W-2 the
 * caption captions a CELL and the value sits on the next line inside it (measured on the corpus
 * form: +12.5pt below, x-overlapping). Against the pre-Spec-4 flat fixture and w2@1.0.0 every one
 * of those nine searched LINE_RIGHT and found nothing — the real form extracted 2 of 10 at a
 * classification score of 1.00. The captured-value assertion below is that headline pinned as a
 * NAMED SET, and the plan's mandated mutation check reads it: revert the rungs to LINE_RIGHT and
 * it collapses far below 10, the survivors being a genuinely flat field plus unanchored page-wide
 * guesses. Named rather than counted so the failure prints WHICH fields survived — that, not the
 * digit, is what the mutation check is for.
 */
class W2ExtractionIT extends AbstractExtractionIT {

    /** The one W2 field the seed marks sensitive — sensitivity comes from DATA, not a flip. */
    private static final Set<String> SENSITIVE = Set.of("employeeSsn");

    /** Methods that read a label anchor, and therefore MUST write LABEL evidence. */
    private static final Set<String> LABEL_BEARING = Set.of("ANCHOR_LABEL", "LABEL_BELOW");

    // `throws Exception` because ObjectMapper.readTree(String) is checked — the same signature
    // TaxReturnExtractionIT's components-asserting test carries.
    @Test
    void all_ten_w2_fields_extract_with_full_evidence() throws Exception {
        UUID packageId = insertPackage("w2-extract-it");
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).hasSize(10);

        // THE Spec 4 headline. A missing field still persists a row (confidence 0,
        // MANUAL_REVIEW_REQUIRED, method NONE), so counting ROWS proves nothing — counting rows
        // that actually captured text is what separates 10 of 10 from the 2 of 10 the real form
        // produced before this spec.
        //
        // Asserted as the NAMED set rather than as a bare count, because the mandated mutation
        // check (plan root; this task's Step 6) deliberately makes this assertion fail: AssertJ
        // then prints the surviving field NAMES alongside the number, and "which fields survived
        // and on what evidence" is the finding. A bare count reports a digit and loses the reason.
        List<String> captured =
                fields.entrySet().stream()
                        .filter(entry -> entry.getValue().get("displayed_text") != null)
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
        assertThat(captured)
                .as("W2 fields that captured a value (2 of 10 on a real W-2 before Spec 4)")
                .hasSize(10);

        // The supersession actually took effect: these rows were produced by 1.1.0, not by the
        // retired 1.0.0 that reads LINE_RIGHT.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT DISTINCT s.version FROM extracted_field f"
                                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                                        + " WHERE f.logical_document_id = ? AND f.is_current",
                                String.class,
                                documentId))
                .as("the schema version that produced these rows")
                .isEqualTo("1.3.0");

        for (JsonNode expected : truth("w2_form").get("expectedFields")) {
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

            // Design §7 asks for CORRECT confidence components, not merely a positive number,
            // and the V7 contract is exactly three keys — never a fourth. anchorStrength is the
            // WINNING rung's strength: every rung w2@1.2.0 can win with on the IRS drawing is
            // 0.9 — V43's ADP-caption alternates (0.85) and taxYear's page-wide year (0.7)
            // sit BELOW the IRS literal in every ladder they join, and the only other rungs
            // are the two unanchored REGEX fallbacks (employeeSsn 0.5, employerName 0.6) — so
            // pinning 0.9 here also proves no field fell through to a page-wide guess with no
            // anchor —
            // the Phase 5 trap class, and the reason employeeSsn gained a LABEL_BELOW rung.
            JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
            assertThat(components.size())
                    .as("field %s confidence components: exactly three, never a fourth", name)
                    .isEqualTo(3);
            assertThat(components.get("anchorStrength").decimalValue())
                    .as("field %s anchorStrength is the winning rung's strength", name)
                    .isEqualByComparingTo("0.9");
            assertThat(components.hasNonNull("spanConfidence"))
                    .as("field %s spanConfidence present", name)
                    .isTrue();
            assertThat(components.hasNonNull("normalizerCertainty"))
                    .as("field %s normalizerCertainty present", name)
                    .isTrue();

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

            // Spec 4: LABEL_BELOW writes the label's spans as LABEL evidence exactly as
            // ANCHOR_LABEL does — the reviewer must still see WHY the value was read as this
            // field, and click-to-highlight must work with no UI change.
            if (LABEL_BEARING.contains(expected.get("method").asText())) {
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

    /**
     * THE DECOY TEST (plan root, design D5). On a W-2, box 3 "Social security wages" sits directly
     * beneath box 1 "Wages, tips, other compensation" in the same column, and both routinely carry
     * similar — often identical — amounts. A rung that over-reaches by one line produces a
     * CONFIDENT WRONG value with a perfectly plausible evidence box, which is strictly worse than
     * the missing field we have today and is the same failure class as the unanchored-regex trap
     * from Phase 5. Box identity, not string equality, is what discriminates here: the two boxes
     * may draw the same number.
     */
    @Test
    void box_one_takes_its_own_cell_and_not_the_box_three_cell_below_it() {
        UUID packageId = insertPackage("w2-decoy-it");
        insertFixturePages(packageId, "w2_form");
        runPipelineToExtraction(packageId);

        Map<String, JsonNode> expectedByName = expectedFieldsByName();
        JsonNode ownValueWord = expectedByName.get("wagesTipsOtherComp").get("valueWords").get(0);
        JsonNode decoyValueWord = expectedByName.get("socialSecurityWages").get("valueWords").get(0);

        // Guard against a vacuous decoy: if the corrected fixture ever draws box 3 ABOVE box 1,
        // or in a different column, this test would silently stop testing anything.
        assertThat(decoyValueWord.get("y").decimalValue())
                .as("the decoy (box 3's value) must sit BELOW box 1's value")
                .isGreaterThan(ownValueWord.get("y").decimalValue());
        assertThat(decoyValueWord.get("x").decimalValue())
                .as("the decoy must sit in box 1's column")
                .isEqualByComparingTo(ownValueWord.get("x").decimalValue());

        Map<String, Object> wages =
                currentFieldsByName(onlyDocumentOf(packageId)).get("wagesTipsOtherComp");
        assertThat(wages).as("current row for wagesTipsOtherComp").isNotNull();
        assertThat(wages.get("extraction_method")).isEqualTo("LABEL_BELOW");

        List<Map<String, Object>> valueEvidence = evidenceOf((UUID) wages.get("id"), "VALUE");
        assertThat(valueEvidence).as("wagesTipsOtherComp VALUE evidence").isNotEmpty();
        for (Map<String, Object> evidence : valueEvidence) {
            assertThat(boxMatches(evidence, ownValueWord))
                    .as("every VALUE evidence box is box 1's OWN value word")
                    .isTrue();
            assertThat(boxMatches(evidence, decoyValueWord))
                    .as("no VALUE evidence box is box 3's value — the cell one line further down")
                    .isFalse();
        }

        // The direction itself, asserted end to end: the value lies BELOW its label.
        List<Map<String, Object>> labelEvidence = evidenceOf((UUID) wages.get("id"), "LABEL");
        assertThat(labelEvidence).as("wagesTipsOtherComp LABEL evidence").isNotEmpty();
        BigDecimal labelBottom = BigDecimal.ZERO;
        for (Map<String, Object> evidence : labelEvidence) {
            labelBottom =
                    labelBottom.max(scaled(evidence.get("y")).add(scaled(evidence.get("height"))));
        }
        assertThat(scaled(valueEvidence.get(0).get("y")))
                .as("the value's top edge is below the label's bottom edge — LABEL_BELOW, not LINE_RIGHT")
                .isGreaterThanOrEqualTo(labelBottom.subtract(new BigDecimal("0.50")));
    }

    /** The fixture's per-field expectations, keyed by field name. */
    private static Map<String, JsonNode> expectedFieldsByName() {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode expected : truth("w2_form").get("expectedFields")) {
            byName.put(expected.get("field").asText(), expected);
        }
        return byName;
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
