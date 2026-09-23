package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.RulePack;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The W2 pack must not fire on an IRS Form 1040 page.
 *
 * <p>Empirically grounded (a real 23-page scanned 2024 Form 1040 package, 2026-08-08): a 1040
 * legitimately prints "Form(s) W-2" on line 25a and "Federal income tax withheld" as the line 25
 * header. Under the original V6 seed those two anchors alone scored 4 + 2 = 6 against
 * {@code targetScore} 10 — exactly {@code min_confidence} 0.6, which QUALIFIES — so the page would
 * have been labelled W2 at a plausible-looking 0.6000. The real package escaped only because OCR
 * dropped the word "Federal" from the line-25 header; it missed by one token.
 *
 * <p>The defect is that the pack's two heaviest-hitting anchors were not W2-EXCLUSIVE. The fix is
 * data (V10 reweights the pack by exclusivity, not by salience), so the guard here is data too:
 * the anchors a 1040 can legitimately carry must not reach the pack's own threshold on their own.
 */
class W2PackExclusivityIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    /**
     * The seeded-W2 anchor ids that an IRS Form 1040 page legitimately contains — the exact pair
     * observed on the real package. They are FORM REFERENCES and a shared line label, not evidence
     * that the page IS a W-2, so no pack version may let them qualify by themselves.
     */
    private static final Set<String> ANCHORS_A_1040_LEGITIMATELY_CARRIES =
            Set.of("form-w2", "fed-income-tax");

    /** A Form 1040 page, word for word in reading order, no W-2 in sight. */
    private static ArrayNode form1040TruthPages() {
        ArrayNode pages = JSON.createArrayNode();
        ObjectNode page = pages.addObject();
        page.put("pageIndex", 0);
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode words = page.putArray("words");
        List<String> tokens =
                List.of(
                        "Form", "1040", "(2024)", "U.S.", "Individual", "Income", "Tax", "Return",
                        "Department", "of", "the", "Treasury", "Internal", "Revenue", "Service",
                        "25", "Federal", "income", "tax", "withheld", "from:",
                        "a", "Form(s)", "W-2", "25a", "9,730.44",
                        "b", "Form(s)", "1099", "25b", "0.00",
                        "26", "2024", "estimated", "tax", "payments", "26", "0.00");
        double x = 72.0;
        double y = 730.0;
        for (String token : tokens) {
            ObjectNode word = words.addObject();
            word.put("text", token);
            word.put("x", x);
            word.put("y", y);
            word.put("width", token.length() * 6.0);
            word.put("height", 12.0);
            x += token.length() * 6.0 + 4.0;
            if (x > 520.0) {
                x = 72.0;
                y -= 18.0;
            }
        }
        return pages;
    }

    private RulePack seededW2Pack() {
        return rulePackLoader.activePacksForCurrentOrg().stream()
                .filter(pack -> pack.documentTypeCode().equals("W2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active W2 pack for the dev org"));
    }

    @Test
    void a_form_1040_page_does_not_classify_as_W2() throws Exception {
        UUID packageId = insertPackage("form-1040-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, form1040TruthPages());

        ParserPort.StageOutcome outcome =
                parserPort.run(
                        new ParserPort.StageRequest(
                                UUID.randomUUID(),
                                packageId,
                                ProcessingStatus.CLASSIFYING,
                                1,
                                "form-1040-idem"));
        assertThat(outcome.success()).isTrue();

        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT * FROM classification_result WHERE subject_id = ? AND is_current",
                        pageIds.get(0));

        // Spec 3 (V11 §3, T9) gave the 1040 a pack of its own, so this page now lands in its
        // TRUE type instead of UNKNOWN. That is a strictly sharper expectation than the
        // original — the page was always a 1040, and until V11 the engine simply had no type
        // to say so. What the test guards is unchanged and is asserted below: whatever wins,
        // it must not be W2, and the W2 pack's own score must fail its own threshold.
        assertThat(result.get("document_type_code")).isEqualTo("TAX_RETURN");
        assertThat(result.get("document_type_code")).isNotEqualTo("W2");

        // And the W2 pack's own recorded score must sit BELOW its threshold — a 1040 that loses
        // merely because some other pack outscored it would not prove anything.
        JsonNode evidence = JSON.readTree(result.get("evidence").toString());
        JsonNode w2Score =
                java.util.stream.StreamSupport.stream(evidence.get("scores").spliterator(), false)
                        .filter(node -> node.get("packType").asText().equals("W2"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("W2 pack absent from the score list"));
        assertThat(w2Score.get("score").asDouble())
                .isLessThan(w2Score.get("minConfidence").asDouble());
    }

    @Test
    void the_anchors_a_1040_carries_cannot_reach_the_W2_packs_threshold_alone() {
        RulePack w2 = seededW2Pack();

        double shared =
                w2.anchors().stream()
                        .filter(anchor -> ANCHORS_A_1040_LEGITIMATELY_CARRIES.contains(anchor.id()))
                        .mapToDouble(Anchor::weight)
                        .sum();
        // Guards the test itself: a pack that renamed these anchors away would score 0 here and
        // pass vacuously.
        assertThat(
                        w2.anchors().stream()
                                .map(Anchor::id)
                                .filter(ANCHORS_A_1040_LEGITIMATELY_CARRIES::contains)
                                .toList())
                .containsExactlyInAnyOrderElementsOf(ANCHORS_A_1040_LEGITIMATELY_CARRIES);

        assertThat(Math.min(1.0, shared / w2.targetScore())).isLessThan(w2.minConfidence());
    }

    @Test
    void a_genuine_W2_page_still_classifies_W2_above_its_threshold() throws Exception {
        // The other half of the contract: tightening the pack must not cost recall on the real
        // form. The w2_form fixture carries every anchor the pack asks for.
        UUID packageId = insertPackage("w2-recall-it");
        List<UUID> pageIds = insertFixturePages(packageId, "w2_form");

        assertThat(
                        parserPort
                                .run(
                                        new ParserPort.StageRequest(
                                                UUID.randomUUID(),
                                                packageId,
                                                ProcessingStatus.CLASSIFYING,
                                                1,
                                                "w2-recall-idem"))
                                .success())
                .isTrue();

        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT * FROM classification_result WHERE subject_id = ? AND is_current",
                        pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("W2");
        assertThat((BigDecimal) result.get("confidence"))
                .isGreaterThanOrEqualTo(new BigDecimal("0.6"));
    }

    @Test
    void a_W2_page_that_lost_its_title_to_OCR_still_classifies_on_the_remaining_exclusives() {
        // Recall under degradation, the failure mode that makes a naive "just raise targetScore"
        // fix expensive: a scan that drops the "Wage and Tax Statement" title line still carries
        // the box labels, and those are W2-exclusive.
        ArrayNode pages = JSON.createArrayNode();
        ObjectNode page = pages.addObject();
        page.put("pageIndex", 0);
        page.put("widthPt", 612.0);
        page.put("heightPt", 792.0);
        ArrayNode words = page.putArray("words");
        List<String> tokens =
                List.of(
                        "Form", "W-2", "2024",
                        "Employer", "identification", "number", "(EIN)", "98-7654321",
                        "1", "Wages,", "tips,", "other", "compensation", "61,538.72",
                        "2", "Federal", "income", "tax", "withheld", "9,730.44",
                        "3", "Social", "security", "wages", "61,538.72",
                        "Copy", "B", "To", "Be", "Filed", "With", "Employee's", "FEDERAL", "Tax",
                        "Return");
        double x = 72.0;
        double y = 730.0;
        for (String token : tokens) {
            ObjectNode word = words.addObject();
            word.put("text", token);
            word.put("x", x);
            word.put("y", y);
            word.put("width", token.length() * 6.0);
            word.put("height", 12.0);
            x += token.length() * 6.0 + 4.0;
            if (x > 520.0) {
                x = 72.0;
                y -= 18.0;
            }
        }

        UUID packageId = insertPackage("w2-degraded-it");
        List<UUID> pageIds = insertFixturePages(packageId, ORG_DEV, pages);

        assertThat(
                        parserPort
                                .run(
                                        new ParserPort.StageRequest(
                                                UUID.randomUUID(),
                                                packageId,
                                                ProcessingStatus.CLASSIFYING,
                                                1,
                                                "w2-degraded-idem"))
                                .success())
                .isTrue();

        Map<String, Object> result =
                jdbc.queryForMap(
                        "SELECT * FROM classification_result WHERE subject_id = ? AND is_current",
                        pageIds.get(0));
        assertThat(result.get("document_type_code")).isEqualTo("W2");
    }
}
