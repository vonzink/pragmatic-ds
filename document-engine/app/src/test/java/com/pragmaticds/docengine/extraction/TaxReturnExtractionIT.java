package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * tax_return@1.0.0 end to end — the CHECKBOX_STATE production proof. The worker is not in the
 * loop (the classification fixture bridge inserts spans from truth), so the CHECKBOX detections
 * the 1040 page would produce are inserted directly as layout_element rows in the exact shape the
 * T2/T3 worker path persists ({@code detector='checkbox-cv'}, {@code {"checked", "fillRatio"}}
 * attributes, a per-detection confidence). Five boxes, one per filing status, ONLY the
 * "Married filing jointly" row checked — mirroring the drawn fixture.
 *
 * <p>Spec §7's four status-variant fixtures get a parameterized pass: every filing-status option
 * must map to its enum code, not just the canonical MFJ row.
 *
 * <p>The degraded twin proves D6's extraction-side consequence: a degraded scan's checkbox
 * candidates fall below the worker's 0.5 confidence floor and are OMITTED, so extraction sees
 * label anchors but ZERO detections — filingStatus persists as the missing-field contract, never
 * a guessed status.
 */
class TaxReturnExtractionIT extends AbstractExtractionIT {

    @Test
    void filing_status_extracts_MARRIED_FILING_JOINTLY_with_element_backed_value_evidence()
            throws Exception {
        UUID packageId = insertPackage("tax-extract-it");
        List<UUID> pageIds = insertFixturePages(packageId, "tax_return");
        UUID pageId = pageIds.get(0);
        // The five detections the worker would emit for the drawn boxes. Each box's left edge
        // sits 18pt left of its label's box edge (drawn: size 10 + gap 8), so the box CENTRE is
        // 13pt from the label edge — inside the schema's 18pt proximityPt, while the
        // neighbouring row's centre (18pt row spacing) is 18.4pt away and falls outside it.
        // "Married" occurrence 0 is the jointly row, occurrence 1 the separately row.
        insertCheckbox(pageId, 0, spanBox(pageId, "Single", 0), false, "0.88");
        BigDecimal[] mfjLabel = spanBox(pageId, "Married", 0);
        UUID checkedBox = insertCheckbox(pageId, 1, mfjLabel, true, "0.91");
        insertCheckbox(pageId, 2, spanBox(pageId, "Married", 1), false, "0.87");
        insertCheckbox(pageId, 3, spanBox(pageId, "Head", 0), false, "0.89");
        insertCheckbox(pageId, 4, spanBox(pageId, "Qualifying", 0), false, "0.90");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("tax_return current field rows").hasSize(10);

        // The supersession took effect — these rows were produced by the NEWEST ACTIVE seed, not
        // by a version a later migration retired. Asserted against the database rather than a
        // literal so the check keeps its meaning the day tax_return is corrected again: a test
        // pinned to "1.1.0" went on passing while guarding a row nothing loads any more.
        assertThat(producingSchemaVersion(documentId))
                .as("the schema version that produced these rows")
                .isEqualTo(newestActiveTaxReturnSchemaVersion());
        assertThat(retiredTaxReturnSchemaVersions())
                .as("every superseded tax_return seed is retired, never deleted")
                .contains("1.1.0")
                .doesNotContain(newestActiveTaxReturnSchemaVersion());

        // The flat fixture is ALSO the fallback proof: primarySsn's LABEL_BELOW rung finds no SSN
        // in the cell below its caption here (this synthetic 1040 prints the caption and the value
        // on one line), so the ladder falls through to the ANCHOR_LABEL rung the truth declares,
        // and every field below still extracts exactly as it did under 1.0.0.

        // ── filingStatus: the CHECKBOX_STATE field ──────────────────────────
        Map<String, Object> filingStatus = fields.get("filingStatus");
        assertThat(filingStatus).as("current row for filingStatus").isNotNull();
        assertThat(filingStatus.get("extraction_method")).isEqualTo("CHECKBOX_STATE");
        assertThat(filingStatus.get("data_type")).isEqualTo("ENUM");
        assertThat(filingStatus.get("displayed_text")).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(filingStatus.get("raw_value")).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(filingStatus.get("normalized_text")).isEqualTo("MARRIED_FILING_JOINTLY");
        assertThat(filingStatus.get("validation_status")).isEqualTo("NOT_VALIDATED");
        // 0.91 (detection) × 0.9 (strength) × 1.0 (normalizer), scale 4 HALF_UP.
        assertThat((BigDecimal) filingStatus.get("confidence")).isEqualByComparingTo("0.8190");

        // The V7 components contract: exactly these three keys, the DETECTION's confidence in
        // the spanConfidence slot — never a fourth key.
        JsonNode components =
                JSON.readTree(String.valueOf(filingStatus.get("confidence_components")));
        assertThat(components.get("spanConfidence").decimalValue()).isEqualByComparingTo("0.91");
        assertThat(components.get("anchorStrength").decimalValue()).isEqualByComparingTo("0.9");
        assertThat(components.get("normalizerCertainty").decimalValue()).isEqualByComparingTo("1");
        assertThat(components.size()).isEqualTo(3);

        UUID filingStatusId = (UUID) filingStatus.get("id");
        // THE evidence shape this task exists to prove: element-backed VALUE evidence —
        // layout_element_id set, text_span_id NULL, box = the checkbox element's box.
        List<Map<String, Object>> valueEvidence = evidenceOf(filingStatusId, "VALUE");
        assertThat(valueEvidence).as("filingStatus VALUE evidence — the checked box").hasSize(1);
        Map<String, Object> value = valueEvidence.get(0);
        assertThat(value.get("layout_element_id")).isEqualTo(checkedBox);
        assertThat(value.get("text_span_id"))
                .as("VALUE evidence has no span — a checkbox is pixels, not text")
                .isNull();
        assertThat(scaled(value.get("x")))
                .isEqualByComparingTo(scaled(mfjLabel[0].subtract(new BigDecimal("18"))));
        assertThat(scaled(value.get("y"))).isEqualByComparingTo(scaled(mfjLabel[1]));
        assertThat(scaled(value.get("width"))).isEqualByComparingTo("10.00");
        assertThat(scaled(value.get("height"))).isEqualByComparingTo("10.00");
        assertThat(packagePageIndexOf((UUID) value.get("page_id"))).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT element_type FROM layout_element WHERE id = ?",
                                String.class,
                                checkedBox))
                .isEqualTo("CHECKBOX");

        List<Map<String, Object>> labelEvidence = evidenceOf(filingStatusId, "LABEL");
        assertThat(labelEvidence).as("Married + filing + jointly").hasSize(3);
        boolean labelBoxMatched = false;
        for (Map<String, Object> label : labelEvidence) {
            assertThat(label.get("text_span_id"))
                    .as("filingStatus LABEL evidence is span-backed")
                    .isNotNull();
            assertThat(label.get("layout_element_id")).isNull();
            labelBoxMatched |= boxMatches(label, truthWord("tax_return", 0, "jointly", 0));
        }
        assertThat(labelBoxMatched)
                .as("some LABEL evidence box equals the 'jointly' truth box")
                .isTrue();

        // ── the nine anchor fields, against truth-by-construction ──────────
        // Guards the loop against a vacuous pass: filingStatus is deliberately NOT in the
        // truth (its VALUE evidence is an element, not a drawn word).
        JsonNode expectedFields = truth("tax_return").get("expectedFields");
        assertThat(expectedFields).hasSize(9);
        for (JsonNode expected : expectedFields) {
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

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> fieldValueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(fieldValueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            boolean valueBoxMatched = false;
            for (Map<String, Object> evidence : fieldValueEvidence) {
                // totalTax and refundAmount live on package page 1 — the page attribution
                // proves the engine reads the page the value came from, not just page 0.
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

            List<Map<String, Object>> fieldLabelEvidence = evidenceOf(fieldId, "LABEL");
            assertThat(fieldLabelEvidence).as("field %s LABEL evidence", name).isNotEmpty();
            boolean anchorBoxMatched = false;
            for (Map<String, Object> evidence : fieldLabelEvidence) {
                for (JsonNode word : expected.get("labelWords")) {
                    anchorBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(anchorBoxMatched)
                    .as("field %s: some LABEL evidence box equals a truth labelWords box", name)
                    .isTrue();
        }

        // The seed, not a test flip, marks primarySsn sensitive — this schema's one production
        // PII field. Everything else stays unmasked data.
        assertThat((Boolean) fields.get("primarySsn").get("is_sensitive"))
                .as("primarySsn is sensitive from the seed")
                .isTrue();
        assertThat((Boolean) fields.get("primaryTaxpayerName").get("is_sensitive"))
                .as("primaryTaxpayerName is not sensitive")
                .isFalse();
    }

    @Test
    void the_degraded_scan_persists_filingStatus_as_missing_because_below_floor_detections_are_omitted() {
        UUID packageId = insertPackage("tax-degraded-it");
        insertFixturePages(packageId, "degraded_tax_return");
        // Deliberately NO checkbox elements: on the degraded scan every checkbox candidate fell
        // below the worker's 0.5 floor and was DROPPED (D6) — an omitted detection must surface
        // as a review case, never a guessed filing status. The spans still arrive from truth
        // (same words, worse pixels), so every label anchor still matches.
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        // All ten schema rows persist. This one-page scan is 1040 page 1 only, so the two
        // page-2 fields (totalTax, refundAmount) are legitimately missing here too — the
        // assertions below therefore pin filingStatus's contract and keep a page-1
        // text-anchored control field, rather than counting missing rows.
        assertThat(fields).as("degraded tax_return current field rows").hasSize(10);

        Map<String, Object> filingStatus = fields.get("filingStatus");
        assertThat(filingStatus).as("current row for filingStatus").isNotNull();
        // The standard missing-field contract — NOT an error, NOT a guess.
        assertThat(filingStatus.get("extraction_method")).as("filingStatus method").isEqualTo("NONE");
        assertThat((BigDecimal) filingStatus.get("confidence"))
                .as("filingStatus confidence")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(filingStatus.get("validation_status"))
                .as("filingStatus validation status")
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(filingStatus.get("displayed_text")).isNull();
        assertThat(filingStatus.get("raw_value")).isNull();
        assertThat(filingStatus.get("normalized_text")).isNull();
        assertThat(filingStatus.get("confidence_components")).isNull();
        UUID fieldId = (UUID) filingStatus.get("id");
        assertThat(evidenceOf(fieldId, "VALUE")).isEmpty();
        assertThat(evidenceOf(fieldId, "LABEL")).isEmpty();

        // Control: the miss is checkbox-specific — a text-anchored field on the SAME page
        // extracts from the same spans on the same run.
        Map<String, Object> totalIncome = fields.get("totalIncome");
        assertThat(totalIncome.get("extraction_method")).isEqualTo("ANCHOR_LABEL");
        assertThat(totalIncome.get("displayed_text")).isEqualTo("104,982.00");
        assertThat((BigDecimal) totalIncome.get("confidence")).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * Spec §7: the generator produces EACH filing status, and the schema's option table must map
     * every one — a wrong enum code or a mis-anchored option would be invisible to the MFJ-only
     * test above. The canonical fixture keeps the deep assertions; the four single-page variants
     * assert filingStatus plus one same-page anchor field as the extraction control, nothing
     * deeper (the fixture side — each variant's drawn ink checking exactly its own box — is
     * pinned by T9's parametrized generator test).
     */
    @ParameterizedTest
    @CsvSource({
        "tax_return, MARRIED_FILING_JOINTLY",
        "tax_return_single, SINGLE",
        "tax_return_mfs, MARRIED_FILING_SEPARATELY",
        "tax_return_hoh, HEAD_OF_HOUSEHOLD",
        "tax_return_qss, QUALIFYING_SURVIVING_SPOUSE"
    })
    void filing_status_maps_each_checked_box_to_its_enum_code(String fixture, String expected) {
        UUID packageId = insertPackage(fixture + "-status-it");
        List<UUID> pageIds = insertFixturePages(packageId, fixture);
        insertAllStatusCheckboxes(pageIds.get(0), expected);
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));

        Map<String, Object> filingStatus = fields.get("filingStatus");
        assertThat(filingStatus).as("current row for filingStatus (%s)", fixture).isNotNull();
        assertThat(filingStatus.get("extraction_method"))
                .as("%s filingStatus method", fixture)
                .isEqualTo("CHECKBOX_STATE");
        assertThat(filingStatus.get("raw_value"))
                .as("%s filing status", fixture)
                .isEqualTo(expected);
        assertThat(filingStatus.get("normalized_text")).isEqualTo(expected);

        // One anchor field as the same-page extraction control (1040 page 1 in every fixture).
        assertThat(fields.get("totalIncome").get("displayed_text"))
                .as("%s totalIncome control", fixture)
                .isEqualTo("104,982.00");
    }

    /**
     * The 1040's identity block is a BOX GRID: {@code Your social security number} captions a
     * cell and the value sits on the next line inside it (measured on a real filled 1040: the
     * value row is ~12.5pt below the caption row and x-overlaps it). Under tax_return@1.0.0
     * every rung searched LINE_RIGHT, so on that layout primarySsn could only be found by an
     * unanchored page-wide regex or not at all. This is the tax-return half of the Spec 4 fix.
     *
     * <p><b>Decoy (plan root, design D5).</b> A real 1040 prints the Dependents table further
     * down the SAME column, so the line below the taxpayer's SSN row is another SSN-shaped
     * number. A rung that over-reaches by one line would attribute a dependent's SSN to the
     * taxpayer — a confident wrong value with a plausible evidence box, strictly worse than a
     * missing field. The decoy sits INSIDE the {@code maxDropPt} window on purpose: what
     * excludes it is reading order (first match wins), which is the semantic under test.
     */
    // `throws Exception` because ObjectMapper.readTree(String) is checked — the same signature
    // filing_status_extracts_MARRIED_FILING_JOINTLY_... already carries in this class.
    @Test
    void primary_ssn_reads_the_cell_below_its_caption_and_not_the_row_beneath_that()
            throws Exception {
        UUID packageId = insertPackage("tax-box-grid-it");
        insertFixturePages(packageId, ORG_DEV, boxGridIdentityPage());
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        assertThat(fields).as("tax_return current field rows").hasSize(10);

        Map<String, Object> ssn = fields.get("primarySsn");
        assertThat(ssn).as("current row for primarySsn").isNotNull();
        assertThat(ssn.get("extraction_method")).isEqualTo("LABEL_BELOW");
        assertThat(ssn.get("displayed_text")).isEqualTo("987-65-4321");
        assertThat(ssn.get("raw_value")).isEqualTo("987-65-4321");
        assertThat((Boolean) ssn.get("is_sensitive"))
                .as("primarySsn is sensitive from the SEED, not from a test flip")
                .isTrue();
        // 1.0 (span) × 0.9 (strength) × 1.0 (normalizer null), scale 4 HALF_UP.
        assertThat((BigDecimal) ssn.get("confidence")).isEqualByComparingTo("0.9000");

        // The V7 components contract: exactly these three keys, never a fourth.
        JsonNode components = JSON.readTree(String.valueOf(ssn.get("confidence_components")));
        assertThat(components.get("spanConfidence").decimalValue()).isEqualByComparingTo("1");
        assertThat(components.get("anchorStrength").decimalValue()).isEqualByComparingTo("0.9");
        assertThat(components.get("normalizerCertainty").decimalValue()).isEqualByComparingTo("1");
        assertThat(components.size()).isEqualTo(3);

        UUID ssnId = (UUID) ssn.get("id");
        List<Map<String, Object>> valueEvidence = evidenceOf(ssnId, "VALUE");
        assertThat(valueEvidence).as("primarySsn VALUE evidence").hasSize(1);
        Map<String, Object> value = valueEvidence.get(0);
        assertThat(value.get("text_span_id"))
                .as("LABEL_BELOW value evidence is span-backed, exactly like ANCHOR_LABEL")
                .isNotNull();
        assertThat(scaled(value.get("x"))).isEqualByComparingTo("430.00");
        assertThat(scaled(value.get("y")))
                .as("the taxpayer's own row (112.50) — NOT the dependent row at 125.00")
                .isEqualByComparingTo("112.50");

        // LABEL evidence: the caption spans, so click-to-highlight shows WHY this number was
        // read as the taxpayer's SSN. No UI change is needed for the new method.
        List<Map<String, Object>> labelEvidence = evidenceOf(ssnId, "LABEL");
        assertThat(labelEvidence).as("Your + social + security + number").hasSize(4);
        for (Map<String, Object> label : labelEvidence) {
            assertThat(label.get("text_span_id")).isNotNull();
            assertThat(scaled(label.get("y")))
                    .as("every LABEL box is on the caption row, above the value")
                    .isEqualByComparingTo("100.00");
        }
    }

    /**
     * The deliberate NON-change, pinned so it cannot be undone silently. A real 1040 splits the
     * taxpayer's name across TWO cells ("Your first name and middle initial" | "Last name") and
     * LABEL_BELOW reads ONE cell, so a rung anchored on the first caption would return a
     * first-name-only value at full confidence with a plausible evidence box — a confident
     * partial, strictly worse than the missing-field contract a reviewer can act on. Composing a
     * value across sibling cells is a different mechanism and Spec 4 does not invent it. If this
     * test ever fails because someone added that rung, the burden is to show the composed value
     * is complete, not to delete this test.
     */
    /**
     * V47: the name a real Form 1040 prints ACROSS two cells — "Jordan Q." under "Your first
     * name and middle initial", "Fixture" under "Last name" — is read as ONE value. Until V47
     * this page pinned the opposite (the name stayed MISSING) because 1.2.0's rung read one
     * cell and the two-word pattern refused "Jordan Q." alone; the refusal was right, and the
     * surname was on the page one caption to the right. Both cells' words are the VALUE
     * evidence and both captions the LABEL evidence.
     */
    @Test
    void the_taxpayer_name_is_read_across_the_first_name_and_last_name_cells() {
        UUID packageId = insertPackage("tax-box-grid-name-it");
        insertFixturePages(packageId, ORG_DEV, boxGridIdentityPage(true));
        runPipelineToExtraction(packageId);

        Map<String, Map<String, Object>> fields = currentFieldsByName(onlyDocumentOf(packageId));
        Map<String, Object> name = fields.get("primaryTaxpayerName");
        assertThat(name).as("current row for primaryTaxpayerName").isNotNull();
        assertThat(name.get("extraction_method")).as("method").isEqualTo("LABEL_BELOW");
        assertThat(name.get("displayed_text")).isEqualTo("Jordan Q. Fixture");
        assertThat(name.get("normalized_text")).isEqualTo("Jordan Q. Fixture");
        assertThat((BigDecimal) name.get("confidence")).isEqualByComparingTo("0.9000");

        List<Map<String, Object>> valueEvidence = evidenceOf((UUID) name.get("id"), "VALUE");
        assertThat(valueEvidence).as("Jordan + Q. + Fixture").hasSize(3);
        assertThat(valueEvidence.stream().map(evidence -> scaled(evidence.get("x"))).toList())
                .as("the first-name cell's words AND the surname under 'Last name' at x 300")
                .containsExactlyInAnyOrder(
                        new BigDecimal("72.00"), new BigDecimal("114.00"), new BigDecimal("300.00"));
        for (Map<String, Object> evidence : valueEvidence) {
            assertThat(scaled(evidence.get("y")))
                    .as("every value word is on the taxpayer's own row — never the row beneath")
                    .isEqualByComparingTo("112.50");
        }
        List<Map<String, Object>> labelEvidence = evidenceOf((UUID) name.get("id"), "LABEL");
        assertThat(labelEvidence.stream().map(evidence -> scaled(evidence.get("x"))).toList())
                .as("the anchoring caption's words and the joined 'Last name' caption's")
                .contains(new BigDecimal("72.00"), new BigDecimal("300.00"), new BigDecimal("328.00"));

        // No spouse caption row on this page: the spouse rung anchors on nothing and the row
        // beneath the taxpayer's (a dependent, at 125.0) is never read as the spouse.
        Map<String, Object> spouse = fields.get("spouseName");
        assertThat(spouse.get("extraction_method")).isEqualTo("NONE");
        assertThat(spouse.get("displayed_text")).isNull();
    }

    /**
     * The other half of V47's contract, on the same page with the surname cell EMPTY: the
     * composed text is "Jordan Q." — one name word — and the pattern refuses it. A first-name-
     * only capture must never be persisted (design D5): missing over a confident partial.
     */
    @Test
    void a_first_name_cell_whose_last_name_cell_is_empty_stays_missing_rather_than_a_confident_partial() {
        UUID packageId = insertPackage("tax-box-grid-partial-it");
        insertFixturePages(packageId, ORG_DEV, boxGridIdentityPage(false));
        runPipelineToExtraction(packageId);

        Map<String, Object> name =
                currentFieldsByName(onlyDocumentOf(packageId)).get("primaryTaxpayerName");
        assertThat(name).as("current row for primaryTaxpayerName").isNotNull();
        assertThat(name.get("extraction_method")).as("method").isEqualTo("NONE");
        assertThat((BigDecimal) name.get("confidence")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(name.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(name.get("displayed_text")).isNull();
        assertThat(evidenceOf((UUID) name.get("id"), "VALUE")).isEmpty();
    }

    /**
     * The generated box-grid fixture (tax_return_boxgrid, V47): page 1 drawn to the real
     * form's identity geometry — both names split across their cells, the SSN in its own
     * cell — and the filing status marked the way preparer software marks it: every box
     * UNCHECKED as ink, a printed "X" glyph inside the Head-of-household box. The detections
     * seeded here are what the worker emitted on the real page — all five unchecked — and
     * the field is read from the glyph. Every expected text field is asserted on method,
     * value, normalization and evidence boxes, exactly as the flat fixture's are above.
     */
    @Test
    void the_generated_box_grid_return_reads_split_names_and_a_glyph_marked_filing_status()
            throws Exception {
        UUID packageId = insertPackage("tax-boxgrid-fixture-it");
        List<UUID> pageIds = insertFixturePages(packageId, "tax_return_boxgrid");
        insertAllStatusCheckboxes(pageIds.get(0), "NONE_OF_THEM_AS_INK");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("tax_return current field rows").hasSize(10);
        assertThat(producingSchemaVersion(documentId))
                .isEqualTo(newestActiveTaxReturnSchemaVersion());

        Map<String, Object> filingStatus = fields.get("filingStatus");
        assertThat(filingStatus.get("extraction_method")).isEqualTo("CHECKBOX_STATE");
        assertThat(filingStatus.get("displayed_text")).isEqualTo("HEAD_OF_HOUSEHOLD");
        assertThat(filingStatus.get("normalized_text")).isEqualTo("HEAD_OF_HOUSEHOLD");
        List<Map<String, Object>> markEvidence =
                evidenceOf((UUID) filingStatus.get("id"), "VALUE");
        assertThat(markEvidence).as("the marked box element, glyph and all").hasSize(1);
        assertThat(markEvidence.get(0).get("layout_element_id")).isNotNull();
        assertThat(markEvidence.get(0).get("text_span_id")).isNull();
        assertThat(scaled(markEvidence.get(0).get("x")))
                .as("the Head-of-household box, 18 pt left of its label")
                .isEqualByComparingTo(scaled(spanBox(pageIds.get(0), "Head", 0)[0].subtract(new BigDecimal("18"))));
        boolean householdCited = false;
        for (Map<String, Object> label : evidenceOf((UUID) filingStatus.get("id"), "LABEL")) {
            householdCited |= boxMatches(label, truthWord("tax_return_boxgrid", 0, "household", 0));
        }
        assertThat(householdCited).as("the option's own caption is the LABEL evidence").isTrue();

        JsonNode expectedFields = truth("tax_return_boxgrid").get("expectedFields");
        assertThat(expectedFields).hasSize(7);
        for (JsonNode expected : expectedFields) {
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

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> fieldValueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(fieldValueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            for (JsonNode word : expected.get("valueWords")) {
                boolean matched = false;
                for (Map<String, Object> evidence : fieldValueEvidence) {
                    matched |= boxMatches(evidence, word);
                }
                assertThat(matched)
                        .as("field %s: EVERY truth value word is cited — '%s'", name, word.get("text").asText())
                        .isTrue();
            }
            List<Map<String, Object>> fieldLabelEvidence = evidenceOf(fieldId, "LABEL");
            boolean anchorBoxMatched = false;
            for (Map<String, Object> evidence : fieldLabelEvidence) {
                for (JsonNode word : expected.get("labelWords")) {
                    anchorBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(anchorBoxMatched)
                    .as("field %s: some LABEL evidence box equals a truth labelWords box", name)
                    .isTrue();
        }
    }

    /**
     * The generated preparer-print fixture (tax_return_preparer, V49): the money column as
     * preparer software prints it, measured on three real filings — the caption sentence, dot
     * leaders as separate spans, the line number repeated beside the amount column, and the
     * amount a BARE comma-grouped whole-dollar token (`128,540`: no cents, no trailing period)
     * in a larger face raised off the caption baseline. Before tax_return@1.4.0 every money rung
     * found its full-sentence caption on these pages and matched nothing at all.
     *
     * <p>Two negatives ride along: the refund column is EMPTY (an owed return), so refundAmount
     * must stay MISSING rather than reading the `35a` beside it; and the bare four-digit form
     * references on the tax line (`8814`, `4972`) and the refund line (`8888`) are never amounts.
     */
    @Test
    void the_preparer_printed_return_reads_its_bare_whole_dollar_money_column() throws Exception {
        UUID packageId = insertPackage("tax-preparer-fixture-it");
        List<UUID> pageIds = insertFixturePages(packageId, "tax_return_preparer");
        insertAllStatusCheckboxes(pageIds.get(0), "MARRIED_FILING_JOINTLY");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("tax_return current field rows").hasSize(10);
        assertThat(producingSchemaVersion(documentId))
                .isEqualTo(newestActiveTaxReturnSchemaVersion());
        assertThat(fields.get("filingStatus").get("displayed_text"))
                .isEqualTo("MARRIED_FILING_JOINTLY");

        // 0.8550 = 1.0 span x 0.95 strength (the full printed sentence) x 0.9 normalizer (a
        // lenient money parse — no cents were printed, and saying so is honest).
        assertField(fields, "totalIncome", "ANCHOR_LABEL", "128,540", "0.8550");
        assertMoney(fields, "totalIncome", "128540");
        assertField(fields, "adjustedGrossIncome", "ANCHOR_LABEL", "124,310", "0.8550");
        assertMoney(fields, "adjustedGrossIncome", "124310");
        assertField(fields, "taxableIncome", "ANCHOR_LABEL", "95,110", "0.8550");
        assertMoney(fields, "taxableIncome", "95110");
        assertField(fields, "totalTax", "ANCHOR_LABEL", "12,780", "0.8550");
        assertMoney(fields, "totalTax", "12780");
        assertThat(fields.get("refundAmount").get("displayed_text"))
                .as("an empty refund column stays missing — `35a` is not an amount")
                .isNull();

        assertEveryExpectedTruthField("tax_return_preparer", pageIds, fields);
    }

    /**
     * The generated scan fixture (tax_return_scan, V49): the same lines as a scanner's own text
     * layer hands them over — tilde-run leaders, the line number its own span, and the amount as
     * digits with NO thousands separator and the cents period kept (`124310.`). The period is
     * the cents separator and stays out of the capture, exactly as on the V45 page; line 9's
     * column is empty, as the measured scan's was, so totalIncome stays MISSING.
     */
    @Test
    void the_scanned_return_reads_its_separator_less_amounts_and_drops_the_cents_period()
            throws Exception {
        UUID packageId = insertPackage("tax-scan-fixture-it");
        List<UUID> pageIds = insertFixturePages(packageId, "tax_return_scan");
        insertAllStatusCheckboxes(pageIds.get(0), "MARRIED_FILING_JOINTLY");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("tax_return current field rows").hasSize(10);
        assertThat(producingSchemaVersion(documentId))
                .isEqualTo(newestActiveTaxReturnSchemaVersion());

        assertField(fields, "adjustedGrossIncome", "ANCHOR_LABEL", "124310", "0.8550");
        assertMoney(fields, "adjustedGrossIncome", "124310");
        assertField(fields, "taxableIncome", "ANCHOR_LABEL", "95110", "0.8550");
        assertMoney(fields, "taxableIncome", "95110");
        assertField(fields, "totalTax", "ANCHOR_LABEL", "12780", "0.8550");
        assertMoney(fields, "totalTax", "12780");
        assertField(fields, "refundAmount", "ANCHOR_LABEL", "1950", "0.8550");
        assertMoney(fields, "refundAmount", "1950");
        assertThat(fields.get("totalIncome").get("displayed_text"))
                .as("an empty line-9 column stays missing — the repeated `9` is not an amount")
                .isNull();

        assertEveryExpectedTruthField("tax_return_scan", pageIds, fields);
    }

    /**
     * Every {@code expectedFields} entry of a generated fixture's truth, asserted the way the
     * box-grid test above asserts its own: method, displayed text, normalization, and the
     * evidence boxes — every truth value word cited as VALUE evidence, some truth label word
     * cited as LABEL evidence. A {@code NONE} entry pins a field the fixture deliberately leaves
     * unreadable: its current row must carry no value at all.
     */
    private void assertEveryExpectedTruthField(
            String fixture, List<UUID> pageIds, Map<String, Map<String, Object>> fields) {
        JsonNode expectedFields = truth(fixture).get("expectedFields");
        assertThat(expectedFields).as("%s truth declares expected fields", fixture).isNotEmpty();
        for (JsonNode expected : expectedFields) {
            String name = expected.get("field").asText();
            Map<String, Object> row = fields.get(name);
            assertThat(row).as("current row for %s", name).isNotNull();
            String method = expected.get("method").asText();
            if ("NONE".equals(method)) {
                assertThat(row.get("extraction_method")).as("%s stays missing", name).isEqualTo("NONE");
                assertThat(row.get("displayed_text")).as("%s has no value", name).isNull();
                continue;
            }
            assertThat(row.get("extraction_method"))
                    .as("field %s extraction method", name)
                    .isEqualTo(method);
            assertThat(row.get("displayed_text"))
                    .as("field %s displayed text", name)
                    .isEqualTo(expected.get("displayedText").asText());
            assertNormalized(name, row, expected.get("normalized"));

            UUID fieldId = (UUID) row.get("id");
            List<Map<String, Object>> valueEvidence = evidenceOf(fieldId, "VALUE");
            assertThat(valueEvidence).as("field %s VALUE evidence", name).isNotEmpty();
            for (JsonNode word : expected.get("valueWords")) {
                boolean matched = false;
                for (Map<String, Object> evidence : valueEvidence) {
                    matched |= boxMatches(evidence, word);
                }
                assertThat(matched)
                        .as("field %s: EVERY truth value word is cited — '%s'", name, word.get("text").asText())
                        .isTrue();
            }
            boolean anchorBoxMatched = false;
            for (Map<String, Object> evidence : evidenceOf(fieldId, "LABEL")) {
                for (JsonNode word : expected.get("labelWords")) {
                    anchorBoxMatched |= boxMatches(evidence, word);
                }
            }
            assertThat(anchorBoxMatched)
                    .as("field %s: some LABEL evidence box equals a truth labelWords box", name)
                    .isTrue();
        }
    }

    @Test
    void every_field_extracts_from_a_page_drawn_to_the_real_1040_geometry() throws Exception {
        UUID packageId = insertPackage("tax-real-geometry-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, realGeometry1040Page());
        insertAllStatusCheckboxes(pageIds.get(0), "MARRIED_FILING_JOINTLY");
        runPipelineToExtraction(packageId);

        UUID documentId = onlyDocumentOf(packageId);
        Map<String, Map<String, Object>> fields = currentFieldsByName(documentId);
        assertThat(fields).as("tax_return current field rows").hasSize(10);
        assertThat(producingSchemaVersion(documentId))
                .isEqualTo(newestActiveTaxReturnSchemaVersion());

        // Not one of the ten may be missing: this page is the whole point of the version.
        for (Map.Entry<String, Map<String, Object>> entry : fields.entrySet()) {
            assertThat(entry.getValue().get("extraction_method"))
                    .as("%s must not fall through to the missing-field contract", entry.getKey())
                    .isNotEqualTo("NONE");
        }

        // ── the identity BOX GRID: LABEL_BELOW, the whole name, all caps ────
        assertField(fields, "primaryTaxpayerName", "LABEL_BELOW", "MORGAN T FIXTURE", "0.9000");
        assertThat(fields.get("primaryTaxpayerName").get("normalized_text"))
                .as("the WHOLE name, not the first-name cell")
                .isEqualTo("MORGAN T FIXTURE");
        assertField(fields, "spouseName", "LABEL_BELOW", "CASEY R FIXTURE", "0.9000");
        assertField(fields, "primarySsn", "LABEL_BELOW", "400-55-6789", "0.9000");

        // ── the three that already worked, pinned against regression ───────
        assertField(fields, "taxYear", "ANCHOR_LABEL", "2025", "0.9000");
        assertField(
                fields, "filingStatus", "CHECKBOX_STATE", "MARRIED_FILING_JOINTLY", "0.8100");

        // ── the money column: whole dollars, empty cents box ───────────────
        // 0.8550 = 1.0 span x 0.95 strength (the FULL printed sentence, the strongest rung)
        // x 0.9 normalizer (a lenient money parse — the form printed no cents, and saying so
        // is honest rather than a defect).
        assertField(fields, "totalIncome", "ANCHOR_LABEL", "104,982", "0.8550");
        assertMoney(fields, "totalIncome", "104982");
        assertField(fields, "adjustedGrossIncome", "ANCHOR_LABEL", "96,410", "0.8550");
        assertMoney(fields, "adjustedGrossIncome", "96410");
        // The GLUED sentence rung: this line's caption arrives as one span with no space
        // glyphs, so no spaced literal can match it and only the glued spelling answers.
        assertField(fields, "totalTax", "ANCHOR_LABEL", "21,455", "0.8550");
        assertMoney(fields, "totalTax", "21455");
        // The SHORT caption, the weakest rung (0.9 x 0.9 = 0.8100) — what a transcript or a
        // differently worded tax year prints.
        assertField(fields, "taxableIncome", "ANCHOR_LABEL", "72,430", "0.8100");
        assertMoney(fields, "taxableIncome", "72430");

        // ── a cents-printing filer must not regress ────────────────────────
        // 0.9500 = 1.0 x 0.95 x 1.0: the STRICT money parse, full normalizer certainty. The
        // difference from 0.8550 above is the whole contract — the same pattern reads both
        // shapes, and the confidence says which one the form actually printed.
        assertField(fields, "refundAmount", "ANCHOR_LABEL", "1,809.44", "0.9500");
        assertMoney(fields, "refundAmount", "1809.44");
        JsonNode components =
                JSON.readTree(String.valueOf(fields.get("refundAmount").get("confidence_components")));
        assertThat(components.get("normalizerCertainty").decimalValue())
                .as("printed cents parse strictly")
                .isEqualByComparingTo("1");
        assertThat(
                        JSON.readTree(String.valueOf(fields.get("totalIncome").get("confidence_components")))
                                .get("normalizerCertainty")
                                .decimalValue())
                .as("an empty cents box parses leniently — 0.9 says the form printed no cents")
                .isEqualByComparingTo("0.9");
    }

    /**
     * THE refusal the money pattern exists for. The 1040 repeats its LINE NUMBER immediately left
     * of the amount column, so the value scope for {@code This is your total income} reads
     * {@code "9 104,982."} — a rung that admitted a bare integer would persist {@code 9} as the
     * borrower's total income, at full confidence, with a real evidence box a reviewer would have
     * no reason to doubt. A confident wrong value is strictly worse than the missing field it
     * replaces, so the pattern requires either printed cents or the trailing period.
     *
     * <p>Pinned on the EVIDENCE BOX, not just the text: an assertion on the string alone would
     * still pass if the engine cited the wrong span.
     */
    @Test
    void the_line_number_beside_the_amount_column_is_never_read_as_the_amount() {
        UUID packageId = insertPackage("tax-line-number-it");
        insertFixturePages(packageId, ORG_DEV, realGeometry1040Page());
        runPipelineToExtraction(packageId);

        Map<String, Object> totalIncome =
                currentFieldsByName(onlyDocumentOf(packageId)).get("totalIncome");
        assertThat(totalIncome.get("raw_value"))
                .as("the amount, never the line number printed beside it")
                .isEqualTo("104,982");

        List<Map<String, Object>> value = evidenceOf((UUID) totalIncome.get("id"), "VALUE");
        assertThat(value).hasSize(1);
        assertThat(scaled(value.get(0).get("x")))
                .as("the amount column at x 506 — NOT the repeated line number at x 474")
                .isEqualByComparingTo("506.00");
        assertThat(scaled(value.get(0).get("y"))).isEqualByComparingTo("300.00");
    }

    /**
     * The other shape that could have slipped past a cents-or-period test: the form's own CROSS
     * REFERENCE. {@code Subtract line 14 from line 13.} ends a sentence with digits and a period,
     * and this line's only available anchor is the SHORT caption {@code Taxable income}, which
     * sits mid-sentence and can therefore see that prose to its right. Without the {@code
     * (?<![Ll]ine )} guard the field would read 13.
     */
    @Test
    void a_cross_reference_to_another_line_is_not_mistaken_for_the_value() {
        UUID packageId = insertPackage("tax-cross-reference-it");
        insertFixturePages(packageId, ORG_DEV, realGeometry1040Page());
        runPipelineToExtraction(packageId);

        Map<String, Object> taxableIncome =
                currentFieldsByName(onlyDocumentOf(packageId)).get("taxableIncome");
        assertThat(taxableIncome.get("raw_value"))
                .as("the amount, not the line the caption refers to")
                .isEqualTo("72,430");

        List<Map<String, Object>> value = evidenceOf((UUID) taxableIncome.get("id"), "VALUE");
        assertThat(value).hasSize(1);
        assertThat(scaled(value.get(0).get("x")))
                .as("the amount at x 550 — not 'line 13.' at x 350 nor the '15' at x 530")
                .isEqualByComparingTo("550.00");
    }

    /**
     * One page of a Form 1040 in the geometry the real form prints, as a truth-pages node the
     * fixture bridge inserts verbatim — the same mechanism {@link #boxGridIdentityPage()} uses,
     * for the same reason: this shape is a schema fact, not a rendering fact, and drawing a PDF
     * for it would put a laptop render of every rasterised fixture at risk on regeneration.
     *
     * <p>Every word carries the box it would be drawn with (height 11, gaps of at least 2 pt so
     * {@code SpanJoin} prints a space at every seam except the deliberately glued caption on the
     * total-tax line). Row spacing is 18 pt, and 12.5 pt inside the identity grid so each value
     * row sits 1.5 pt below its caption's bottom edge — measured on a real return, and the reason
     * the name rungs cap their reach at {@code maxDropPt} 12.0 rather than the 24.0 default: the
     * caption row 14 pt below would otherwise be admitted into the cell as well.
     *
     * <p>Classification: {@code U.S. Individual Income Tax Return} (weight 5) + {@code Form 1040}
     * (2) + {@code Filing Status} (1) = 8 of targetScore 10 = 0.80, over the pack's 0.60 floor.
     *
     * <p>Every taxpayer, amount and identifier below is INVENTED, in the same family as the rest
     * of the fixtures. Nothing read off a borrower's return may ever be written down here.
     */
    private static JsonNode realGeometry1040Page() {
        ArrayNode pages = JSON.createArrayNode();
        ObjectNode page = pages.addObject();
        page.put("pageIndex", 0);
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        page.put("contentRotation", 0);
        ArrayNode w = page.putArray("words");

        // ── masthead ────────────────────────────────────────────────────────
        row(w, 60.0, "Form", 72.0, 28.0, "1040", 104.0, 26.0, "U.S.", 140.0, 22.0,
                "Individual", 166.0, 48.0, "Income", 218.0, 36.0, "Tax", 258.0, 20.0,
                "Return", 282.0, 34.0, "2025", 322.0, 26.0);

        // ── identity box grid: captions, then the value row 1.5pt below them ─
        row(w, 100.0, "Your", 72.0, 26.0, "first", 102.0, 22.0, "name", 128.0, 30.0,
                "and", 162.0, 22.0, "middle", 188.0, 36.0, "initial", 228.0, 26.0,
                "Last", 300.0, 24.0, "name", 328.0, 30.0,
                "Your", 430.0, 26.0, "social", 460.0, 30.0, "security", 494.0, 40.0,
                "number", 538.0, 38.0);
        // The whole name is left-packed in the FIRST cell — the surname does not sit under the
        // "Last name" caption. That is what makes a LABEL_BELOW rung admissible here at all.
        row(w, 112.5, "MORGAN", 72.0, 44.0, "T", 122.0, 8.0, "FIXTURE", 136.0, 50.0,
                "400-55-6789", 430.0, 62.0);
        // 14pt below the row above: inside the 24.0 default reach, outside the 12.0 the name
        // rungs declare. If maxDropPt ever drifts back to the default, this caption row joins
        // the taxpayer's cell.
        row(w, 125.0, "If", 72.0, 12.0, "joint", 88.0, 22.0, "return,", 114.0, 32.0,
                "spouse's", 150.0, 40.0, "first", 194.0, 22.0, "name", 220.0, 30.0,
                "and", 254.0, 22.0, "middle", 280.0, 36.0, "initial", 320.0, 26.0,
                "Last", 360.0, 24.0, "name", 388.0, 30.0);
        row(w, 137.5, "CASEY", 150.0, 38.0, "R", 194.0, 8.0, "FIXTURE", 208.0, 50.0);

        // ── filing status: one row per option, 18pt apart, boxes 18pt left ──
        row(w, 160.0, "Filing", 72.0, 32.0, "Status", 108.0, 36.0);
        row(w, 178.0, "Single", 160.0, 34.0);
        row(w, 196.0, "Married", 160.0, 46.0, "filing", 210.0, 26.0, "jointly", 240.0, 34.0);
        row(w, 214.0, "Married", 160.0, 46.0, "filing", 210.0, 26.0, "separately", 240.0, 52.0);
        row(w, 232.0, "Head", 160.0, 28.0, "of", 192.0, 12.0, "household", 208.0, 56.0);
        row(w, 250.0, "Qualifying", 160.0, 56.0, "surviving", 220.0, 48.0, "spouse", 272.0, 36.0);

        // ── the money column ────────────────────────────────────────────────
        // Line 9: the FULL printed sentence ends the caption, so LINE_RIGHT after it can only
        // see the repeated line number and the amount. Whole dollars, empty cents box.
        row(w, 300.0, "9", 72.0, 8.0, "Add", 86.0, 22.0, "lines", 112.0, 26.0,
                "1z,", 142.0, 18.0, "2b,", 164.0, 18.0, "3b,", 186.0, 18.0,
                "4b,", 208.0, 18.0, "5b,", 230.0, 18.0, "6b,", 252.0, 18.0,
                "7,", 274.0, 12.0, "and", 290.0, 22.0, "8.", 316.0, 12.0,
                "This", 332.0, 24.0, "is", 360.0, 10.0, "your", 374.0, 26.0,
                "total", 404.0, 26.0, "income", 434.0, 38.0,
                "9", 474.0, 8.0, "104,982.", 506.0, 68.0);
        // Line 11a: the line NUMBER is renumbered between tax years (11a here, 11 on a 2023
        // return), which is why no rung anchors on it.
        row(w, 318.0, "11a", 72.0, 18.0, "Subtract", 96.0, 44.0, "line", 146.0, 22.0,
                "10", 172.0, 14.0, "from", 192.0, 26.0, "line", 224.0, 22.0, "9.", 252.0, 12.0,
                "This", 270.0, 24.0, "is", 298.0, 10.0, "your", 312.0, 26.0,
                "adjusted", 344.0, 44.0, "gross", 394.0, 30.0, "income", 430.0, 38.0,
                "11a", 474.0, 18.0, "96,410.", 506.0, 60.0);
        // Line 15: only the SHORT caption is printed, and it anchors MID-SENTENCE — the cross
        // reference "from line 13." lies to its RIGHT, inside the value scope.
        row(w, 336.0, "15", 72.0, 14.0, "Taxable", 92.0, 44.0, "income.", 142.0, 44.0,
                "Subtract", 192.0, 44.0, "line", 242.0, 22.0, "14", 270.0, 14.0,
                "from", 290.0, 26.0, "line", 322.0, 22.0, "13.", 350.0, 18.0,
                "If", 374.0, 10.0, "zero", 390.0, 26.0, "or", 422.0, 12.0, "less,", 440.0, 26.0,
                "enter", 472.0, 28.0, "-0-", 506.0, 18.0,
                "15", 530.0, 14.0, "72,430.", 550.0, 60.0);
        // Line 24: the caption arrives as ONE span with no space glyphs — the shape a measured
        // preparer PDF emits. SpanJoin only inserts a separator where the page printed a gap,
        // so a spaced literal can never match it and only the glued rung answers.
        row(w, 354.0, "24", 72.0, 14.0, "Addlines22and23.Thisisyourtotaltax", 92.0, 200.0,
                "24", 474.0, 14.0, "21,455.", 506.0, 60.0);
        // Line 35a: a cents-printing amount, on the same page as the whole-dollar ones.
        row(w, 372.0, "35a", 72.0, 18.0, "Amount", 96.0, 44.0, "of", 146.0, 12.0,
                "line", 164.0, 22.0, "34", 192.0, 14.0, "you", 212.0, 22.0, "want", 240.0, 32.0,
                "refunded", 278.0, 52.0, "to", 336.0, 12.0, "you.", 354.0, 26.0,
                "35a", 474.0, 18.0, "1,809.44", 506.0, 68.0);
        return pages;
    }

    /** One printed row: {@code y}, then (text, x, width) triples, all at height 11. */
    private static void row(ArrayNode words, double y, Object... textXWidth) {
        for (int i = 0; i < textXWidth.length; i += 3) {
            word(
                    words,
                    (String) textXWidth[i],
                    (Double) textXWidth[i + 1],
                    y,
                    (Double) textXWidth[i + 2],
                    11.0);
        }
    }

    private void assertField(
            Map<String, Map<String, Object>> fields,
            String name,
            String method,
            String displayed,
            String confidence) {
        Map<String, Object> row = fields.get(name);
        assertThat(row).as("current row for %s", name).isNotNull();
        assertThat(row.get("extraction_method")).as("%s method", name).isEqualTo(method);
        assertThat(row.get("displayed_text")).as("%s displayed text", name).isEqualTo(displayed);
        assertThat(row.get("raw_value")).as("%s raw value", name).isEqualTo(displayed);
        assertThat((BigDecimal) row.get("confidence"))
                .as("%s confidence", name)
                .isEqualByComparingTo(confidence);
        assertThat(evidenceOf((UUID) row.get("id"), "VALUE")).as("%s VALUE evidence", name).isNotEmpty();
        assertThat(evidenceOf((UUID) row.get("id"), "LABEL")).as("%s LABEL evidence", name).isNotEmpty();
    }

    private void assertMoney(
            Map<String, Map<String, Object>> fields, String name, String expectedNumber) {
        assertThat((BigDecimal) fields.get(name).get("normalized_number"))
                .as("%s normalized number", name)
                .isEqualByComparingTo(expectedNumber);
    }

    /** The single schema version every current row of this document was produced by. */
    private String producingSchemaVersion(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT DISTINCT s.version FROM extracted_field f"
                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                        + " WHERE f.logical_document_id = ? AND f.is_current",
                String.class,
                documentId);
    }

    /** What {@code ExtractionSchemaLoader} resolves for a global TAX_RETURN document. */
    private String newestActiveTaxReturnSchemaVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM extraction_schema WHERE org_id IS NULL"
                        + " AND document_type_code = 'TAX_RETURN' AND is_active"
                        + " ORDER BY string_to_array(version, '.')::int[] DESC LIMIT 1",
                String.class);
    }

    private List<String> retiredTaxReturnSchemaVersions() {
        return jdbc.queryForList(
                "SELECT version FROM extraction_schema WHERE org_id IS NULL"
                        + " AND document_type_code = 'TAX_RETURN' AND NOT is_active",
                String.class);
    }

    /**
     * A one-page 1040 identity block in REAL box-grid geometry, as a truth-pages node the
     * fixture bridge inserts verbatim. Rows:
     *
     * <pre>
     *   y=60     Form 1040   U.S. Individual Income Tax Return     &lt;- pack anchors: 2 + 5 = 7/10
     *   y=100    Your first name and middle initial | Last name | Your social security number
     *   y=112.5  Jordan Q.                          | Fixture   | 987-65-4321
     *   y=125    Emily R.                           | Fixture   | 111-22-3333   &lt;- DECOY row
     * </pre>
     *
     * The decoy row is the Dependents table a real 1040 prints further down the same columns.
     * Every value is synthetic and matches the existing fixture family — nothing from the
     * gitignored corpus/ may ever be copied into a committed file.
     */
    private static JsonNode boxGridIdentityPage() {
        return boxGridIdentityPage(true);
    }

    /**
     * The identity block as a BOX GRID — captions on one row, values on the next, the
     * surname under "Last name" at x 300. {@code surnameFilled} false leaves the taxpayer's
     * "Last name" cell EMPTY (the partial-name guard's page); the dependent row beneath keeps
     * its surname either way, so an empty cell can never be answered from the row below.
     */
    private static JsonNode boxGridIdentityPage(boolean surnameFilled) {
        ArrayNode pages = JSON.createArrayNode();
        ObjectNode page = pages.addObject();
        page.put("pageIndex", 0);
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        page.put("contentRotation", 0);
        ArrayNode words = page.putArray("words");
        // Row A — enough of the TAX_RETURN pack for the page to classify: "Form 1040" (2) plus
        // "U.S. Individual Income Tax Return" (5) = 7 of targetScore 10 = 0.70 >= 0.60.
        word(words, "Form", 72.0, 60.0, 28.0, 11.0);
        word(words, "1040", 104.0, 60.0, 26.0, 11.0);
        word(words, "U.S.", 140.0, 60.0, 22.0, 11.0);
        word(words, "Individual", 166.0, 60.0, 48.0, 11.0);
        word(words, "Income", 218.0, 60.0, 36.0, 11.0);
        word(words, "Tax", 258.0, 60.0, 20.0, 11.0);
        word(words, "Return", 282.0, 60.0, 34.0, 11.0);
        // Row B — the three captions, in reading order.
        word(words, "Your", 72.0, 100.0, 26.0, 11.0);
        word(words, "first", 102.0, 100.0, 22.0, 11.0);
        word(words, "name", 128.0, 100.0, 30.0, 11.0);
        word(words, "and", 162.0, 100.0, 22.0, 11.0);
        word(words, "middle", 188.0, 100.0, 36.0, 11.0);
        word(words, "initial", 228.0, 100.0, 26.0, 11.0);
        word(words, "Last", 300.0, 100.0, 24.0, 11.0);
        word(words, "name", 328.0, 100.0, 30.0, 11.0);
        word(words, "Your", 430.0, 100.0, 26.0, 11.0);
        word(words, "social", 460.0, 100.0, 30.0, 11.0);
        word(words, "security", 494.0, 100.0, 40.0, 11.0);
        word(words, "number", 538.0, 100.0, 38.0, 11.0);
        // Row C — the values, +12.5pt below their captions and x-overlapping them.
        word(words, "Jordan", 72.0, 112.5, 38.0, 11.0);
        word(words, "Q.", 114.0, 112.5, 14.0, 11.0);
        if (surnameFilled) {
            word(words, "Fixture", 300.0, 112.5, 36.0, 11.0);
        }
        word(words, "987-65-4321", 430.0, 112.5, 62.0, 11.0);
        // Row D — the DECOY: a dependent's row, one line further down the same columns.
        word(words, "Emily", 72.0, 125.0, 30.0, 11.0);
        word(words, "R.", 106.0, 125.0, 12.0, 11.0);
        word(words, "Fixture", 300.0, 125.0, 36.0, 11.0);
        word(words, "111-22-3333", 430.0, 125.0, 62.0, 11.0);
        return pages;
    }

    private static void word(
            ArrayNode words, String text, double x, double y, double width, double height) {
        ObjectNode word = words.addObject();
        word.put("text", text);
        word.put("x", x);
        word.put("y", y);
        word.put("width", width);
        word.put("height", height);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * A CHECKBOX layout element exactly as the worker persists one: a 10×10pt box whose left
     * edge sits 18pt left of the given label box (the drawn geometry: size 10 + gap 8).
     */
    private UUID insertCheckbox(
            UUID pageId, int ordinal, BigDecimal[] labelBox, boolean checked, String confidence) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version,
                    attributes)
                VALUES (?, ?, ?, NULL, 'CHECKBOX', ?, ?, ?, 10.0, 10.0, ?::numeric,
                        'checkbox-cv', 'it', ?::jsonb)
                """,
                id,
                ORG_DEV,
                pageId,
                ordinal,
                labelBox[0].subtract(new BigDecimal("18")),
                labelBox[1],
                confidence,
                "{\"checked\": " + checked + ", \"fillRatio\": " + (checked ? "0.42" : "0.02")
                        + "}");
        return id;
    }

    /**
     * The five detections the worker would emit for page 1's drawn boxes, with the row named by
     * {@code checkedCode} the only checked one — mirroring each variant's drawn ink. "Married"
     * occurrence 0 is the jointly row, occurrence 1 the separately row (drawing order),
     * identical across the canonical fixture and every variant.
     */
    private void insertAllStatusCheckboxes(UUID pageId, String checkedCode) {
        insertCheckbox(pageId, 0, spanBox(pageId, "Single", 0),
                "SINGLE".equals(checkedCode), "0.90");
        insertCheckbox(pageId, 1, spanBox(pageId, "Married", 0),
                "MARRIED_FILING_JOINTLY".equals(checkedCode), "0.90");
        insertCheckbox(pageId, 2, spanBox(pageId, "Married", 1),
                "MARRIED_FILING_SEPARATELY".equals(checkedCode), "0.90");
        insertCheckbox(pageId, 3, spanBox(pageId, "Head", 0),
                "HEAD_OF_HOUSEHOLD".equals(checkedCode), "0.90");
        insertCheckbox(pageId, 4, spanBox(pageId, "Qualifying", 0),
                "QUALIFYING_SURVIVING_SPOUSE".equals(checkedCode), "0.90");
    }

    /** [x, y, width, height] of the nth span (reading order) with this exact text. */
    private BigDecimal[] spanBox(UUID pageId, String text, int occurrence) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT x, y, width, height FROM text_span WHERE page_id = ? AND text = ?"
                                + " ORDER BY ordinal",
                        pageId,
                        text);
        Map<String, Object> row = rows.get(occurrence);
        return new BigDecimal[] {
            (BigDecimal) row.get("x"), (BigDecimal) row.get("y"),
            (BigDecimal) row.get("width"), (BigDecimal) row.get("height")
        };
    }

    private static JsonNode truthWord(String fixture, int pageIndex, String text, int occurrence) {
        int seen = 0;
        for (JsonNode word : truth(fixture).get("pages").get(pageIndex).get("words")) {
            if (word.get("text").asText().equals(text)) {
                if (seen == occurrence) {
                    return word;
                }
                seen++;
            }
        }
        throw new IllegalStateException("truth word not found: " + text);
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
