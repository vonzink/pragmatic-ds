package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code schedule_e@1.0.1} end to end: CLASSIFYING → SPLITTING → EXTRACTING over the two-page
 * Schedule E fixture, with every occurrence asserted on its own value, its own group key and its
 * own evidence box.
 *
 * <p>This is the test the whole spec exists for. On the pre-Spec-5a model {@code rentsReceived}
 * could hold exactly one value per document, so a three-property Schedule E would have captured
 * column A at full confidence with a correct-looking evidence box and silently discarded B and C.
 * The mandated mutation check deliberately makes {@link
 * #part_one_extracts_one_occurrence_per_property_column} fail by removing the group block from the
 * seed, and the assertion is written to print WHICH occurrences survived — that, not a count, is
 * the finding.
 */
class ScheduleEExtractionIT extends AbstractExtractionIT {

    /** The one Schedule E field the seed marks sensitive — sensitivity comes from DATA. */
    private static final Set<String> SENSITIVE = Set.of("taxpayerSsn");

    /** Part I's five COLUMN-grouped money lines, in schema order. */
    private static final List<String> PART_ONE_COLUMNS =
            List.of("rentsReceived", "mortgageInterest", "depreciationExpense", "totalExpenses",
                    "incomeOrLoss");

    private UUID extractScheduleE() {
        UUID packageId = insertPackage("schedule-e-extract-it");
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);
        return onlyDocumentOf(packageId);
    }

    @Test
    void every_occurrence_of_every_field_persists_with_its_own_evidence() throws Exception {
        UUID documentId = extractScheduleE();
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);

        // 8 ungrouped + 5 Part I fields x 3 property columns + 1 address field x 3 rows
        // + 7 Part II fields x 4 PREPRINTED row letters (A-D — C and D are letter-only
        // rows the form preprints and the filer leaves blank, each a MISSING occurrence
        // under its own letter) + 5 Part III fields x 2 letters + Part IV's 3
        // (remicName and remicIncome on the one drawn row, and remicExcessInclusion's
        // single null-keyed MISSING — its column caption is interleaved with the row
        // beneath it and cannot be named)
        // = 8 + 15 + 3 + 28 + 10 + 3 = 67 rows.
        assertThat(occurrences.keySet()).as("current occurrence rows").hasSize(67);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT DISTINCT s.version FROM extracted_field f"
                                        + " JOIN extraction_schema s ON s.id = f.schema_id"
                                        + " WHERE f.logical_document_id = ? AND f.is_current",
                                String.class,
                                documentId))
                .as("the schema version that produced these rows")
                .isEqualTo("1.0.1");

        for (JsonNode expected : truth("schedule_e").get("expectedFields")) {
            String name = expected.get("field").asText();
            String groupKey = expected.path("groupKey").asText("");
            String key = name + "#" + groupKey;
            Map<String, Object> row = occurrences.get(key);
            assertThat(row).as("current row for %s", key).isNotNull();

            assertThat(row.get("group_key"))
                    .as("%s carries the occurrence key the form or the ordinal gives it", key)
                    .isEqualTo(groupKey.isEmpty() ? null : groupKey);
            assertThat(row.get("extraction_method"))
                    .as("%s extraction method", key)
                    .isEqualTo(expected.get("method").asText());
            assertThat(row.get("displayed_text"))
                    .as("%s displayed text", key)
                    .isEqualTo(expected.get("displayedText").asText());
            assertThat((BigDecimal) row.get("confidence")).as("%s confidence", key)
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(row.get("is_sensitive"))
                    .as("%s sensitivity comes from the SEED", key)
                    .isEqualTo(SENSITIVE.contains(name));

            // The V7 contract: exactly three components, never a fourth. A group key is a
            // COLUMN on the row, not a smuggled confidence input.
            JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
            assertThat(components.size()).as("%s components: exactly three", key).isEqualTo(3);

            // D4: every occurrence carries its OWN evidence, pointing at its own cell.
            List<Map<String, Object>> valueEvidence = evidenceOf((UUID) row.get("id"), "VALUE");
            assertThat(valueEvidence).as("%s VALUE evidence", key).isNotEmpty();
            JsonNode expectedWords = expected.get("valueWords");
            assertThat(valueEvidence).as("%s VALUE box count", key).hasSize(expectedWords.size());
            for (int i = 0; i < expectedWords.size(); i++) {
                assertThat(boxMatches(valueEvidence.get(i), expectedWords.get(i)))
                        .as("%s VALUE box %d equals the drawn word", key, i)
                        .isTrue();
            }
            assertThat(evidenceOf((UUID) row.get("id"), "LABEL"))
                    .as("%s LABEL evidence — the reason this value was read as this field", key)
                    .isNotEmpty();
        }
    }

    @Test
    void part_one_extracts_one_occurrence_per_property_column() {
        UUID documentId = extractScheduleE();
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);

        // Named, not counted. The mutation check makes this assertion fail on purpose, and
        // AssertJ then prints which occurrences survived — "column A only" is the finding,
        // and a bare count would lose it.
        List<String> partOne =
                occurrences.keySet().stream()
                        .filter(key -> key.startsWith("rentsReceived#"))
                        .sorted()
                        .toList();
        assertThat(partOne)
                .as("rentsReceived occurrences (ONE on the pre-Spec-5a model)")
                .containsExactly("rentsReceived#A", "rentsReceived#B", "rentsReceived#C");
    }

    @Test
    void the_empty_third_column_persists_MISSING_per_occurrence_not_absent_and_not_zero() {
        // Design D5, and the reason it is not negotiable: a defaulted 0.00 in a rental
        // expense silently changes a qualifying-income calculation, and an ABSENT row makes
        // "column C is empty" indistinguishable from "we failed to read column C".
        UUID documentId = extractScheduleE();
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);

        for (String name : PART_ONE_COLUMNS) {
            Map<String, Object> row = occurrences.get(name + "#C");
            assertThat(row).as("column C row for %s exists at all", name).isNotNull();
            assertThat(row.get("extraction_method")).as("%s C method", name).isEqualTo("NONE");
            assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
            assertThat(row.get("displayed_text")).as("%s C displayed text", name).isNull();
            assertThat(row.get("normalized_number")).as("%s C is NOT zero", name).isNull();
            assertThat(evidenceOf((UUID) row.get("id"), "VALUE")).isEmpty();
        }
    }

    @Test
    void a_blank_cell_inside_a_populated_row_is_MISSING_for_that_row_only() {
        // The row-wise counterpart of the empty column, and the sharper case: the row is
        // there, four of its five cells are filled, and the fifth must not borrow a
        // neighbour's number or default to zero. The third property's ADDRESS row is the
        // same shape on page 1 — a row letter printed with nothing beside it.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        for (String key :
                List.of("partnershipNonpassiveLossAllowed#A", "partnershipPassiveIncome#B",
                        "propertyAddress#03")) {
            Map<String, Object> row = occurrences.get(key);
            assertThat(row).as("row for %s exists at all", key).isNotNull();
            assertThat(row.get("extraction_method")).as("%s method", key).isEqualTo("NONE");
            assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
            assertThat(row.get("displayed_text")).as("%s displayed text", key).isNull();
            assertThat(row.get("normalized_number")).as("%s is NOT zero", key).isNull();
        }
        // and the row's OTHER cells survived — a blank cell is not a lost row.
        assertThat(occurrences.get("partnershipName#A").get("displayed_text"))
                .isEqualTo("SUMMIT RIDGE PARTNERS LP");
        assertThat(occurrences.get("partnershipNonpassiveIncome#A").get("displayed_text"))
                .isEqualTo("42,150");
        assertThat(occurrences.get("partnershipEin#B").get("displayed_text"))
                .isEqualTo("84-7654321");
    }

    @Test
    void a_column_B_value_is_never_attributed_to_column_A() {
        // THE DECOY. This spec's analogue of Spec 4's wrong-cell test, and the same failure
        // class: a wrong-column value is confident, evidence-backed and wrong, which is worse
        // than missing because nothing looks broken. Every A/B pair in the fixture differs,
        // so a column swap cannot pass by coincidence.
        UUID documentId = extractScheduleE();
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentId);

        for (String name : PART_ONE_COLUMNS) {
            String a = (String) occurrences.get(name + "#A").get("displayed_text");
            String b = (String) occurrences.get(name + "#B").get("displayed_text");
            assertThat(a).as("%s A", name).isNotNull();
            assertThat(b).as("%s B", name).isNotNull();
            assertThat(a).as("%s A and B are different numbers on this fixture", name)
                    .isNotEqualTo(b);
        }

        // And the boxes prove it geometrically: A's evidence sits LEFT of B's, every time.
        for (String name : PART_ONE_COLUMNS) {
            BigDecimal aX =
                    (BigDecimal)
                            evidenceOf((UUID) occurrences.get(name + "#A").get("id"), "VALUE")
                                    .get(0)
                                    .get("x");
            BigDecimal bX =
                    (BigDecimal)
                            evidenceOf((UUID) occurrences.get(name + "#B").get("id"), "VALUE")
                                    .get(0)
                                    .get("x");
            assertThat(aX).as("%s: column A's box is left of column B's", name).isLessThan(bX);
        }
    }

    @Test
    void the_loss_column_keeps_its_sign() {
        // Line 21 column B is a LOSS, printed in accounting parentheses. The displayed text
        // keeps the form's own notation; the normalized number is NEGATIVE. Dropping the sign
        // would book an eighteen-thousand-dollar loss as eighteen thousand of income.
        //
        // The fixture prints the parenthesis pair as its own glyph runs — "(", the amount,
        // ")" — because that is what a preprinted paren pair around a typed amount produces,
        // so the captured value is the SPACED "( 18,470 )". Until this defect was fixed the
        // schema's money pattern admitted only the tightly-typeset "(18,470)": every other
        // spelling of a negative fell through to the unsigned alternative, which matched the
        // digits alone, dropped the sign and drew the evidence box around the digits with the
        // sign glyphs OUTSIDE it. This test passed on a fixture that printed the one spelling
        // the pattern happened to admit — Spec 4's w2_form.pdf trap, one layer down.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        Map<String, Object> loss = occurrences.get("incomeOrLoss#B");
        assertThat(loss.get("displayed_text")).isEqualTo("( 18,470 )");
        assertThat((BigDecimal) loss.get("normalized_number")).isEqualByComparingTo("-18470");
        // Every sign glyph is INSIDE the captured value, so every one of them is inside an
        // evidence box: a reviewer who opens this field sees the parentheses that make it a
        // loss, not a box drawn around bare digits.
        assertThat(evidenceOf((UUID) loss.get("id"), "VALUE"))
                .as("the parentheses are evidence-backed, not cropped out of the box")
                .hasSize(3);
        // The dollar sign INSIDE the parentheses is the other spelling that lost its sign:
        // "$" was reachable only on the unsigned alternative, so "($6,310)" read as +6,310.
        assertThat(occurrences.get("partnershipNonpassiveLossAllowed#B").get("displayed_text"))
                .isEqualTo("($6,310)");
        assertThat((BigDecimal) occurrences.get("incomeOrLoss#A").get("normalized_number"))
                .as("and the profit column is still positive")
                .isEqualByComparingTo("12860");
        // The same rule one part down: a partnership's nonpassive LOSS, and the document's
        // own bottom line, which the form prints in parentheses when it is negative.
        assertThat(
                        (BigDecimal)
                                occurrences
                                        .get("partnershipNonpassiveLossAllowed#B")
                                        .get("normalized_number"))
                .isEqualByComparingTo("-6310");
        assertThat(
                        (BigDecimal)
                                occurrences
                                        .get("totalRentalRealEstateIncomeOrLoss#")
                                        .get("normalized_number"))
                .isEqualByComparingTo("-5610");
    }

    @Test
    void every_entity_name_is_captured_WHOLE_and_a_partial_capture_costs_confidence() {
        // The Parts II-IV name cells print the five shapes a real filer's entities take: an
        // all-caps baseline, a LEADING DIGIT, an APOSTROPHE, MIXED CASE, and an internal
        // lowercase run inside an all-caps token. An all-caps-only pattern captures a
        // FRAGMENT of four of them at certainty 1.0 — "ST CHOICE PROPERTIES LLC" is a
        // different legal entity from "1ST CHOICE PROPERTIES LLC", not a missing field, and
        // its evidence box is drawn around part of a word.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        Map<String, String> expected =
                Map.of(
                        "partnershipName#A", "SUMMIT RIDGE PARTNERS LP",
                        "partnershipName#B", "1ST CHOICE PROPERTIES LLC",
                        "estateOrTrustName#A", "O'BRIEN FAMILY TRUST",
                        "estateOrTrustName#B", "Meridian Fixture Estate",
                        "remicName#01", "McALLISTER REMIC TRUST");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Map<String, Object> row = occurrences.get(entry.getKey());
            assertThat(row).as("row for %s", entry.getKey()).isNotNull();
            assertThat(row.get("displayed_text"))
                    .as("%s is the WHOLE printed name, never a fragment of it", entry.getKey())
                    .isEqualTo(entry.getValue());
            assertThat(row.get("normalized_text"))
                    .as("%s normalizes to the same whole name", entry.getKey())
                    .isEqualTo(entry.getValue());
        }

        // And the value the reviewer is shown is the value the boxes cover: one drawn word,
        // one box, over the WHOLE name.
        for (String key : expected.keySet()) {
            assertThat(evidenceOf((UUID) occurrences.get(key).get("id"), "VALUE"))
                    .as("%s VALUE evidence covers the whole name", key)
                    .hasSize(1);
        }

        // An entity name is no longer normalizer-less. A null normalizer answers certainty
        // 1.0 to ANY string it is handed, which is what let a fragment persist fully
        // confident; the entityName normalizer scores what it is given, so a name carrying
        // characters outside the entity-name set costs confidence instead of passing
        // silently. These five are clean, so they keep full certainty.
        for (String key : expected.keySet()) {
            assertThat(certaintyOf(occurrences.get(key)))
                    .as("%s: a clean whole name keeps full normalizer certainty", key)
                    .isEqualByComparingTo("1.0");
        }
    }

    /** The normalizer-certainty component of a row's three-component confidence. */
    private static BigDecimal certaintyOf(Map<String, Object> row) {
        try {
            JsonNode components = JSON.readTree(String.valueOf(row.get("confidence_components")));
            return components.get("normalizerCertainty").decimalValue();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable confidence components", e);
        }
    }

    @Test
    void the_entity_tables_yield_one_occurrence_per_PRINTED_key_and_fabricate_no_empties() {
        // Parts II and III key by the form's PREPRINTED row letters (rowLabels): the key
        // space is the four letters A-D / two letters A-B the form prints, never the
        // twenty maxRows admits — a rung that filled the region up to maxRows would invent
        // sixteen partnerships. Part IV is unlettered on the real form and keeps the
        // counted ordinal, capped the same way (one drawn row, one occurrence — not ten).
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(keysFor(occurrences, "partnershipName"))
                .containsExactly(
                        "partnershipName#A", "partnershipName#B",
                        "partnershipName#C", "partnershipName#D");
        assertThat(occurrences.get("partnershipName#A").get("displayed_text"))
                .isEqualTo("SUMMIT RIDGE PARTNERS LP");
        assertThat(occurrences.get("partnershipName#B").get("displayed_text"))
                .isEqualTo("1ST CHOICE PROPERTIES LLC");
        assertThat(occurrences.get("partnershipNonpassiveIncome#B").get("displayed_text"))
                .isEqualTo("18,725");
        assertThat(keysFor(occurrences, "estateOrTrustName"))
                .containsExactly("estateOrTrustName#A", "estateOrTrustName#B");
        assertThat(keysFor(occurrences, "estateOrTrustOtherIncome"))
                .containsExactly("estateOrTrustOtherIncome#A", "estateOrTrustOtherIncome#B");
        assertThat(keysFor(occurrences, "remicIncome"))
                .as("one REMIC row drawn, one occurrence — not ten")
                .containsExactly("remicIncome#01");
        // The lettered key is the letter EXACTLY as printed — single characters sort
        // lexically in printed order, which is what T9's order-by and the export comparator
        // rely on; the unlettered ordinal stays zero-padded for the same reason.
        assertThat(occurrences.get("partnershipName#A").get("group_key")).isEqualTo("A");
        assertThat(occurrences.get("remicName#01").get("group_key")).isEqualTo("01");
    }

    @Test
    void the_two_sub_tables_join_on_the_printed_letter_with_evidence_in_each_band() {
        // THE defect this fix removes, asserted geometrically. The real form prints Part II
        // TWICE — the entity band (name, EIN) and the money band — with the row letters
        // preprinted beside BOTH. One counted origin can only serve one band, and serving the
        // lower one keyed the name fields from the money sub-table: caption continuations and
        // money rows became phantom "name" occurrences on a real document, at 0.63-0.90
        // confidence with real evidence boxes. The letter is the join the form itself
        // provides: entity A's NAME evidence sits on the UPPER band's row A and its MONEY
        // evidence on the LOWER band's row A — different printed lines, one key.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        for (String key : List.of("A", "B")) {
            BigDecimal nameY = valueEvidenceY(occurrences, "partnershipName#" + key);
            BigDecimal einY = valueEvidenceY(occurrences, "partnershipEin#" + key);
            BigDecimal moneyY =
                    valueEvidenceY(occurrences, "partnershipNonpassiveIncome#" + key);
            assertThat(nameY)
                    .as("row %s: name and EIN sit on ONE printed line of the entity band", key)
                    .isEqualByComparingTo(einY);
            assertThat(nameY)
                    .as("row %s: the name band lies ABOVE the money band — two sub-tables", key)
                    .isLessThan(moneyY);
        }
        // And the same claim across Part III's two letters.
        for (String key : List.of("A", "B")) {
            assertThat(valueEvidenceY(occurrences, "estateOrTrustName#" + key))
                    .as("Part III row %s: name band above money band", key)
                    .isLessThan(
                            valueEvidenceY(occurrences, "estateOrTrustOtherIncome#" + key));
        }
    }

    /** The y of an occurrence's first VALUE evidence box. */
    private BigDecimal valueEvidenceY(
            Map<String, Map<String, Object>> occurrences, String key) {
        List<Map<String, Object>> evidence =
                evidenceOf((UUID) occurrences.get(key).get("id"), "VALUE");
        assertThat(evidence).as("%s has VALUE evidence", key).isNotEmpty();
        return (BigDecimal) evidence.get(0).get("y");
    }

    @Test
    void a_preprinted_letter_with_a_blank_row_persists_MISSING_under_its_own_letter() {
        // Rows C and D print only their letter — the form preprints all four and this filer
        // has two partnerships. Design D5, letter-keyed: the occurrence exists under the
        // PRINTED key, is MISSING, and is never a value borrowed from a Totals row or a
        // neighbouring band. "Row C was left blank" stays distinguishable from "we failed to
        // read row C" only because the row's own printed letter names it.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        for (String name :
                List.of("partnershipName", "partnershipEin", "partnershipNonpassiveIncome")) {
            for (String letter : List.of("C", "D")) {
                Map<String, Object> row = occurrences.get(name + "#" + letter);
                assertThat(row).as("row for %s#%s exists at all", name, letter).isNotNull();
                assertThat(row.get("extraction_method")).isEqualTo("NONE");
                assertThat((BigDecimal) row.get("confidence"))
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
                assertThat(row.get("displayed_text")).isNull();
                assertThat(evidenceOf((UUID) row.get("id"), "VALUE")).isEmpty();
            }
        }
    }

    @Test
    void a_totals_row_or_caption_continuation_is_never_an_entity_row() {
        // The two unlettered decoys the real form prints INSIDE the region. The 29a/29b
        // Totals rows carry column sums squarely inside the money columns and the word
        // "Totals" inside the name column; the caption continuation line sits directly under
        // the money captions. Counted ordinals keyed BOTH — that is exactly how the real
        // document manufactured phantom partnershipName occurrences — and neither carries a
        // letter in the gutter, so neither is a row.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        List<String> partnershipTexts =
                occurrences.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith("partnership"))
                        .map(entry -> (String) entry.getValue().get("displayed_text"))
                        .filter(text -> text != null)
                        .toList();
        assertThat(partnershipTexts)
                .as("no Totals-row text is ever an entity occurrence")
                .doesNotContain("Totals", "60,875", "6,200");
        // 6,310 appears twice on the form: the ROW's own "($6,310)" — which IS captured,
        // sign intact — and 29b's unsigned column sum "6,310", which must never be.
        assertThat(partnershipTexts).contains("($6,310)").doesNotContain("6,310");
    }

    @Test
    void section_179_expense_is_never_reported_as_nonpassive_income() {
        // THE COLUMN-LETTER TRAP, and the reason no anchor in this schema is a bare letter.
        //
        // The schema shipped declaring "(g) Passive income", "(h) Nonpassive loss" and
        // "(j) Nonpassive income". The IRS prints (g) Passive loss allowed, (h) Passive
        // income, (i) Nonpassive loss allowed, (j) SECTION 179 EXPENSE and (k) Nonpassive
        // income — so all three were mislettered and all three went missing. The obvious
        // "fix" is to match on the letter, or to loosen "(j) Nonpassive income" to "(j)".
        // That converts a missing field into a WRONG one: column (j) is a deduction, and
        // reporting a deduction as nonpassive income inflates qualifying income at full
        // confidence with a correct-looking evidence box.
        //
        // Every one of the five amounts below is different, so no swap or slip between
        // adjacent columns can pass by coincidence.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(occurrences.get("partnershipPassiveLossAllowed#A").get("displayed_text"))
                .as("(g) is the passive loss ALLOWED, money the schema used to ignore entirely")
                .isEqualTo("2,400");
        assertThat(occurrences.get("partnershipPassiveIncome#A").get("displayed_text"))
                .as("(h) is the passive income — which the schema used to letter (g)")
                .isEqualTo("4,200");
        assertThat(occurrences.get("partnershipNonpassiveLossAllowed#B").get("displayed_text"))
                .as("(i) is the nonpassive loss allowed — which the schema used to letter (h)")
                .isEqualTo("($6,310)");
        assertThat(occurrences.get("partnershipSection179Expense#A").get("displayed_text"))
                .as("(j) is the SECTION 179 EXPENSE, never nonpassive income")
                .isEqualTo("1,150");
        assertThat(occurrences.get("partnershipNonpassiveIncome#A").get("displayed_text"))
                .as("(k) is the nonpassive income — which the schema used to letter (j)")
                .isEqualTo("42,150");

        // And the two are not each other, on either row: the decoy stated as an inequality
        // so it fails loudly if the columns are ever crossed.
        for (String row : List.of("#A", "#B")) {
            assertThat(occurrences.get("partnershipSection179Expense" + row).get("displayed_text"))
                    .as("row %s: the section 179 deduction is not the nonpassive income", row)
                    .isNotEqualTo(
                            occurrences.get("partnershipNonpassiveIncome" + row)
                                    .get("displayed_text"));
        }
        // The sub-$1,000 cell that only matches BECAUSE it is printed with cents. The money
        // pattern admits a comma group or exact cents and never a bare integer, so "950"
        // would be MISSING here — the recorded, accepted cost of not letting a line number
        // read as an amount.
        assertThat((BigDecimal) occurrences.get("partnershipSection179Expense#B")
                        .get("normalized_number"))
                .isEqualByComparingTo("950.00");
    }

    @Test
    void the_estate_and_trust_passthrough_band_is_read_WHOLE() {
        // "It can also show passthrough gains and losses. We need the data for all of it."
        // Part III prints four money columns and the schema read two of them: (c) and (f),
        // with the (d) passive income and the (e) deduction or loss between them ignored.
        // A band read at half its width is not a missing field a reviewer can see — the
        // row looks complete.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(occurrences.get("estateOrTrustPassiveDeductionOrLoss#A").get("displayed_text"))
                .isEqualTo("3,150");
        assertThat(occurrences.get("estateOrTrustPassiveIncome#A").get("displayed_text"))
                .isEqualTo("5,400");
        assertThat(occurrences.get("estateOrTrustDeductionOrLoss#A").get("displayed_text"))
                .isEqualTo("2,100");
        assertThat(occurrences.get("estateOrTrustOtherIncome#A").get("displayed_text"))
                .isEqualTo("9,850");
    }

    @Test
    void a_caption_the_form_WRAPS_is_anchored_on_its_first_line_only() {
        // "(d) Employer identification number" is not a line on this form: "(d) Employer"
        // prints on the caption row and "identification number" on the row below it. A
        // literal is matched against ONE assembled line, so the fuller caption matched
        // nothing, the EIN column was never located, and partnershipEin collapsed to a
        // single null-keyed MISSING — which ALSO moved the group's row origin up above the
        // remaining caption rows, so the two printed entities were keyed #03 and #04 behind
        // two phantom rows made of caption text.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(keysFor(occurrences, "partnershipEin"))
                .as("the EIN column is located, so it keys BY PRINTED LETTER and not by a null")
                .containsExactly(
                        "partnershipEin#A", "partnershipEin#B",
                        "partnershipEin#C", "partnershipEin#D");
        assertThat(occurrences.get("partnershipEin#A").get("displayed_text"))
                .isEqualTo("27-1234567");
        assertThat(occurrences.get("partnershipEin#B").get("displayed_text"))
                .isEqualTo("84-7654321");
        // Part IV's (e) wraps the same way — "(e) Income from" then "Schedules Q, line 3b".
        assertThat(occurrences.get("remicIncome#01").get("displayed_text")).isEqualTo("5,600");
    }

    @Test
    void a_column_whose_caption_cannot_be_named_is_MISSING_and_never_a_neighbour_s_number() {
        // Part IV's (c) caption is "(c) Excess inclusion from" printed directly above
        // "Schedules Q, line 2c" in the same x-range. Line assembly merges the two rows and
        // sorting by x threads them together —
        //
        //   38 (a) Name (c) Schedules Excess Q, inclusion line 2c from (e) Income from
        //
        // — so no literal can name that column. The required outcome is the honest one: ONE
        // missing occurrence with a NULL key. What must never happen is the column being
        // resolved to a neighbour's box and 5,600 (the (e) income) or the REMIC name being
        // reported as the excess inclusion.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(keysFor(occurrences, "remicExcessInclusion"))
                .as("an unnameable column yields ONE occurrence, not one per row")
                .containsExactly("remicExcessInclusion#");
        Map<String, Object> row = occurrences.get("remicExcessInclusion#");
        assertThat(row.get("group_key")).as("and its key is null: it read no rows").isNull();
        assertThat(row.get("extraction_method")).isEqualTo("NONE");
        assertThat(row.get("displayed_text")).as("never a neighbouring column's value").isNull();
        assertThat(row.get("normalized_number")).as("and never a defaulted zero").isNull();
        assertThat(row.get("validation_status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(evidenceOf((UUID) row.get("id"), "VALUE")).isEmpty();
    }

    @Test
    void part_one_reads_its_keys_from_the_printed_key_row_not_the_caption_above_it() {
        // The form prints "Properties:" alone on one line and "Income: A B C" on the next.
        // The COLUMN rung takes its header anchor's OWN visual line and needs every declared
        // key on it, so an anchor on the caption above the keys yields no bands at all and
        // every Part I amount goes MISSING — which is exactly what a real Schedule E did
        // while this fixture, which drew both on one baseline, passed.
        //
        // Asserting the VALUES rather than the anchor: the anchor is an implementation
        // detail of the seed, the three distinct amounts per line are the claim.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(occurrences.get("rentsReceived#A").get("displayed_text")).isEqualTo("44,400");
        assertThat(occurrences.get("rentsReceived#B").get("displayed_text")).isEqualTo("29,700");
        assertThat(occurrences.get("mortgageInterest#A").get("displayed_text")).isEqualTo("13,260");
        assertThat(occurrences.get("depreciationExpense#B").get("displayed_text"))
                .isEqualTo("7,150");
        assertThat(occurrences.get("totalExpenses#A").get("displayed_text")).isEqualTo("31,540");
    }

    @Test
    void the_property_addresses_are_ROWS_under_one_caption_not_a_fourth_column() {
        // Line 1a stacks the three properties as rows beneath a single caption — that is how
        // the real form lays it out, and a schema that forced them into the A/B/C column
        // group would be describing a layout the form does not have.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());

        assertThat(keysFor(occurrences, "propertyAddress"))
                .containsExactly("propertyAddress#01", "propertyAddress#02", "propertyAddress#03");
        assertThat(occurrences.get("propertyAddress#01").get("displayed_text"))
                .isEqualTo("1234 SYNTHETIC AVE, DENVER, CO 80202");
        assertThat(occurrences.get("propertyAddress#02").get("displayed_text"))
                .isEqualTo("5678 SAMPLE ST, AURORA, CO 80014");
    }

    @Test
    void the_ungrouped_fields_carry_a_NULL_group_key() {
        // The migration's central claim, observed on a document that has BOTH kinds of field:
        // an ungrouped field is exactly what it was before this spec.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(extractScheduleE());
        for (String name :
                List.of("taxpayerName", "taxpayerSsn", "taxYear",
                        "totalRentalRealEstateIncomeOrLoss", "partnershipAndSCorpTotal",
                        "estateAndTrustTotal", "remicTotal", "totalIncomeOrLoss")) {
            assertThat(occurrences.get(name + "#").get("group_key"))
                    .as("%s is ungrouped", name)
                    .isNull();
        }
    }

    @Test
    void both_pages_of_the_form_are_ONE_document_and_both_are_read() {
        // The startsDocument trap, seen from the extraction side: if the two pages had split
        // into two documents, page 2's fields would live on a DIFFERENT logical document and
        // every Part II assertion above would be reading a second row set. Asserting the page
        // count here is what makes the rest of this class trustworthy.
        UUID packageId = insertPackage("schedule-e-one-document-it");
        insertFixturePages(packageId, "schedule_e");
        runPipelineToExtraction(packageId);

        List<UUID> documentIds =
                jdbc.queryForList(
                        "SELECT id FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                        UUID.class,
                        packageId);
        assertThat(documentIds).as("one two-page form, one logical document").hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM logical_document_page"
                                        + " WHERE logical_document_id = ?",
                                Integer.class,
                                documentIds.get(0)))
                .isEqualTo(2);

        // Fields from BOTH pages landed on that one document.
        Map<String, Map<String, Object>> occurrences = currentOccurrences(documentIds.get(0));
        assertThat(occurrences.get("rentsReceived#A").get("displayed_text")).isEqualTo("44,400");
        assertThat(occurrences.get("partnershipName#A").get("displayed_text"))
                .isEqualTo("SUMMIT RIDGE PARTNERS LP");
        assertThat(
                        packagePageIndexOf(
                                (UUID)
                                        evidenceOf(
                                                        (UUID)
                                                                occurrences
                                                                        .get("partnershipName#A")
                                                                        .get("id"),
                                                        "VALUE")
                                                .get(0)
                                                .get("page_id")))
                .as("Part II's evidence points at the SECOND page of the same document")
                .isEqualTo(1);
    }

    private static List<String> keysFor(
            Map<String, Map<String, Object>> occurrences, String fieldName) {
        return occurrences.keySet().stream()
                .filter(key -> key.startsWith(fieldName + "#"))
                .sorted()
                .toList();
    }
}
