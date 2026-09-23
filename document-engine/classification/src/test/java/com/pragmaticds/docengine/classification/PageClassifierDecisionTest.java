package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.classification.rules.RulePack;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scoring and decision core, plan acceptance criterion 3 above all: ties or nothing above
 * threshold land UNKNOWN — the classifier never guesses to avoid an unknown — and an UNKNOWN
 * result still records the best loser's confidence and EVERY weak match across packs, so a
 * reviewer sees why the page was ambiguous.
 */
class PageClassifierDecisionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnchorSpan span(long id, String text) {
        return new AnchorSpan(
                id,
                text,
                new BigDecimal("72.0"),
                new BigDecimal("700.0"),
                new BigDecimal("40.0"),
                new BigDecimal("12.0"));
    }

    private static AnchorMatcher matcherFor(String... words) {
        List<AnchorSpan> spans = new java.util.ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            spans.add(span(i + 1, words[i]));
        }
        return AnchorMatcher.forSpans(spans);
    }

    private static Anchor literal(String id, String pattern, double weight) {
        return new Anchor(id, AnchorKind.LITERAL, pattern, weight, false);
    }

    private static RulePack pack(String type, double minConfidence, double target, Anchor... anchors) {
        return new RulePack(type, "1.0.0", minConfidence, target, List.of(anchors));
    }

    @Test
    void score_is_matched_weight_over_target_capped_at_one() {
        RulePack paystub =
                pack(
                        "PAYSTUB",
                        0.6,
                        10,
                        literal("pay-period", "Pay Period", 3),
                        literal("gross-pay", "Gross Pay", 3),
                        literal("net-pay", "Net Pay", 2),
                        literal("absent", "Not On This Page", 5));
        AnchorMatcher matcher = matcherFor("Pay", "Period", "Gross", "Pay", "Net", "Pay");

        PageClassifier.PackEvaluation evaluation = PageClassifier.evaluate(paystub, matcher);

        assertThat(evaluation.score()).isEqualTo(0.8);
        assertThat(evaluation.matches()).extracting(m -> m.anchorId())
                .containsExactly("pay-period", "gross-pay", "net-pay");

        // Cap: weights 3+3+2 against target 5 would be 1.6 — confidence is a probability-shaped
        // number and never exceeds 1.
        RulePack lowTarget =
                pack(
                        "PAYSTUB",
                        0.6,
                        5,
                        literal("pay-period", "Pay Period", 3),
                        literal("gross-pay", "Gross Pay", 3),
                        literal("net-pay", "Net Pay", 2));
        assertThat(PageClassifier.evaluate(lowTarget, matcher).score()).isEqualTo(1.0);
    }

    @Test
    void the_highest_score_at_or_above_its_packs_threshold_wins() {
        AnchorMatcher matcher = matcherFor("Pay", "Period", "Statement", "Period");
        RulePack paystub = pack("PAYSTUB", 0.6, 4, literal("pay-period", "Pay Period", 3));
        RulePack bank = pack("BANK_STATEMENT", 0.6, 4, literal("stmt-period", "Statement Period", 4));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcher),
                                PageClassifier.evaluate(bank, matcher)));

        assertThat(decision.documentTypeCode()).isEqualTo("BANK_STATEMENT");
        assertThat(decision.confidence()).isEqualTo(1.0);
        assertThat(decision.winner()).isNotNull();
        assertThat(decision.winner().pack().version()).isEqualTo("1.0.0");
    }

    @Test
    void nothing_above_threshold_is_UNKNOWN_with_the_best_losers_confidence() {
        // The ambiguous fixture's shape: one weak anchor from each of two packs.
        AnchorMatcher matcher = matcherFor("Net", "Pay", "and", "Ending", "Balance");
        RulePack paystub = pack("PAYSTUB", 0.6, 10, literal("net-pay", "Net Pay", 2));
        RulePack bank = pack("BANK_STATEMENT", 0.6, 10, literal("end-balance", "Ending Balance", 2));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcher),
                                PageClassifier.evaluate(bank, matcher)));

        assertThat(decision.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(decision.confidence()).isEqualTo(0.2);
        assertThat(decision.winner()).isNull();
    }

    @Test
    void an_exact_tie_above_threshold_is_UNKNOWN_never_a_guess() {
        AnchorMatcher matcher = matcherFor("Pay", "Period", "Statement", "Period");
        RulePack paystub = pack("PAYSTUB", 0.5, 4, literal("pay-period", "Pay Period", 2));
        RulePack bank = pack("BANK_STATEMENT", 0.5, 4, literal("stmt-period", "Statement Period", 2));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcher),
                                PageClassifier.evaluate(bank, matcher)));

        assertThat(decision.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(decision.confidence()).isEqualTo(0.5);
    }

    @Test
    void a_page_matching_nothing_is_UNKNOWN_with_zero_confidence() {
        AnchorMatcher matcher = matcherFor("Dear", "Homeowner,");
        RulePack paystub = pack("PAYSTUB", 0.6, 10, literal("net-pay", "Net Pay", 2));

        PageClassifier.Decision decision =
                PageClassifier.decide(List.of(PageClassifier.evaluate(paystub, matcher)));

        assertThat(decision.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(decision.confidence()).isEqualTo(0.0);
    }

    @Test
    void winner_evidence_carries_the_winning_packs_anchors_and_every_packs_score() throws Exception {
        AnchorMatcher matcher = matcherFor("Pay", "Period", "Statement", "Period");
        RulePack paystub = pack("PAYSTUB", 0.6, 4, literal("pay-period", "Pay Period", 3));
        RulePack bank = pack("BANK_STATEMENT", 0.6, 4, literal("stmt-period", "Statement Period", 4));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcher),
                                PageClassifier.evaluate(bank, matcher)));
        JsonNode evidence = JSON.readTree(PageClassifier.evidenceJson(decision));

        JsonNode anchors = evidence.get("anchors");
        assertThat(anchors).hasSize(1);
        JsonNode anchor = anchors.get(0);
        assertThat(anchor.get("packType").asText()).isEqualTo("BANK_STATEMENT");
        assertThat(anchor.get("anchorId").asText()).isEqualTo("stmt-period");
        assertThat(anchor.get("weight").asDouble()).isEqualTo(4.0);
        assertThat(anchor.get("spanIds")).hasSize(2);
        assertThat(anchor.get("boxes")).hasSize(2);
        assertThat(anchor.get("boxes").get(0).has("x")).isTrue();
        assertThat(anchor.get("range").get("start").isInt()).isTrue();
        // Offsets and ids only — the matched text itself must never ride into evidence.
        assertThat(anchor.has("text")).isFalse();
        assertThat(anchor.has("matchedText")).isFalse();

        assertThat(evidence.get("scores")).hasSize(2);
        assertThat(evidence.get("scores").get(0).has("score")).isTrue();
        assertThat(evidence.get("scores").get(0).has("minConfidence")).isTrue();
    }

    @Test
    void unknown_evidence_carries_the_weak_matches_of_every_pack() throws Exception {
        AnchorMatcher matcher = matcherFor("Net", "Pay", "and", "Ending", "Balance");
        RulePack paystub = pack("PAYSTUB", 0.6, 10, literal("net-pay", "Net Pay", 2));
        RulePack bank = pack("BANK_STATEMENT", 0.6, 10, literal("end-balance", "Ending Balance", 2));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcher),
                                PageClassifier.evaluate(bank, matcher)));
        JsonNode evidence = JSON.readTree(PageClassifier.evidenceJson(decision));

        assertThat(evidence.get("anchors")).hasSize(2);
        List<String> anchorIds = new java.util.ArrayList<>();
        evidence.get("anchors").forEach(node -> anchorIds.add(node.get("anchorId").asText()));
        assertThat(anchorIds).containsExactlyInAnyOrder("net-pay", "end-balance");
    }

    @org.junit.jupiter.api.Test
    void a_pack_clearing_its_own_threshold_beats_a_higher_scorer_that_misses_its_own() {
        // The documented rule: "the highest score at or above ITS OWN pack's
        // threshold wins." Phase 4 review: the code picked the global best score
        // first and only then checked that pack's threshold — a strict-pack (min
        // 0.8) scoring 0.7 wrongly forced UNKNOWN even though a lenient pack (min
        // 0.6) legitimately cleared its own bar at 0.65.
        var strict = new PageClassifier.PackEvaluation(
                pack("STRICT_TYPE", 0.8, 10), 0.7, java.util.List.of());
        var lenient = new PageClassifier.PackEvaluation(
                pack("LENIENT_TYPE", 0.6, 10), 0.65, java.util.List.of());

        var decision = PageClassifier.decide(java.util.List.of(strict, lenient));

        org.assertj.core.api.Assertions.assertThat(decision.documentTypeCode())
                .isEqualTo("LENIENT_TYPE");
        org.assertj.core.api.Assertions.assertThat(decision.confidence()).isEqualTo(0.65);
    }

    @Test
    void a_score_exactly_at_the_threshold_qualifies() {
        // Pins a DECISION, not an accident (see the note in PageClassifier#decide): qualification
        // is `score >= minConfidence`, so an exact tie at the bar wins rather than being rejected.
        // The V10 investigation raised the question — a Form 1040 scored exactly the W2 pack's
        // 0.60 — and the answer was that the pack was mis-weighted, not that the comparator was.
        // A pack wanting a strict bar seeds min_confidence 0.6001; that is data, this is not.
        // No other test covered the boundary itself: the neighbours use 1.0 and 0.65 against 0.6.
        var exactlyAtBar =
                new PageClassifier.PackEvaluation(pack("W2", 0.6, 10), 0.6, java.util.List.of());

        var decision = PageClassifier.decide(java.util.List.of(exactlyAtBar));

        assertThat(decision.documentTypeCode()).isEqualTo("W2");
        assertThat(decision.confidence()).isEqualTo(0.6);
    }

    @org.junit.jupiter.api.Test
    void a_tie_between_two_QUALIFYING_packs_is_unknown() {
        var a = new PageClassifier.PackEvaluation(pack("TYPE_A", 0.6, 10), 0.9, java.util.List.of());
        var b = new PageClassifier.PackEvaluation(pack("TYPE_B", 0.6, 10), 0.9, java.util.List.of());

        var decision = PageClassifier.decide(java.util.List.of(a, b));

        org.assertj.core.api.Assertions.assertThat(decision.documentTypeCode())
                .isEqualTo(PageClassifier.UNKNOWN);
    }

    // ── co-qualification: the suspected multi-document sheet (Phase B, B4) ──

    /**
     * Two packs clearing their OWN thresholds on one page is the engine's only cheap evidence that
     * a sheet carries two documents — the processor's photocopy of a driver's licence beside a
     * Social Security card being the everyday case. It is meaningful precisely because
     * {@code CrossConfusionIT} fails the build if it happens on a clean single-document fixture.
     */
    @Test
    void two_packs_clearing_their_own_thresholds_are_reported_as_co_qualifying() {
        RulePack licence =
                pack("DRIVERS_LICENSE", 0.6, 10, literal("dl", "Driver License", 10));
        RulePack ssnCard = pack("SSN_CARD", 0.6, 10, literal("ssn", "Social Security", 6));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(licence, matcherFor("Driver", "License")),
                                PageClassifier.evaluate(
                                        ssnCard, matcherFor("Social", "Security"))));

        // The verdict is UNCHANGED — the engine cannot split within a page, so the stronger pack
        // still wins and the page still belongs to exactly one document.
        assertThat(decision.documentTypeCode()).isEqualTo("DRIVERS_LICENSE");
        // …but the second document is no longer invisible.
        assertThat(decision.coQualifyingTypes()).containsExactly("DRIVERS_LICENSE", "SSN_CARD");
    }

    @Test
    void a_single_qualifier_reports_nothing_however_many_packs_scored() {
        RulePack paystub = pack("PAYSTUB", 0.6, 10, literal("gross", "Gross Pay", 10));
        RulePack w2 = pack("W2", 0.6, 10, literal("wages", "Wages, tips", 10));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(paystub, matcherFor("Gross", "Pay")),
                                PageClassifier.evaluate(w2, matcherFor("Gross", "Pay"))));

        assertThat(decision.documentTypeCode()).isEqualTo("PAYSTUB");
        assertThat(decision.coQualifyingTypes()).isEmpty();
    }

    @Test
    void an_ambiguous_tie_still_reports_co_qualification_because_they_are_different_problems() {
        // "No type won" and "two documents are on this sheet" both land UNKNOWN today, and a
        // reviewer needs to tell them apart: one wants a type, the other wants a second document.
        RulePack licence = pack("DRIVERS_LICENSE", 0.6, 10, literal("dl", "Driver License", 10));
        RulePack ssnCard = pack("SSN_CARD", 0.6, 10, literal("ssn", "Social Security", 10));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(licence, matcherFor("Driver", "License")),
                                PageClassifier.evaluate(ssnCard, matcherFor("Social", "Security"))));

        assertThat(decision.documentTypeCode()).isEqualTo(PageClassifier.UNKNOWN);
        assertThat(decision.coQualifyingTypes()).containsExactly("DRIVERS_LICENSE", "SSN_CARD");
    }

    @Test
    void co_qualifying_types_reach_the_evidence_document() {
        RulePack licence = pack("DRIVERS_LICENSE", 0.6, 10, literal("dl", "Driver License", 10));
        RulePack ssnCard = pack("SSN_CARD", 0.6, 10, literal("ssn", "Social Security", 6));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(
                                PageClassifier.evaluate(licence, matcherFor("Driver", "License")),
                                PageClassifier.evaluate(ssnCard, matcherFor("Social", "Security"))));

        JsonNode evidence = readEvidence(PageClassifier.evidenceJson(decision));
        assertThat(evidence.path("coQualifyingTypes").isArray()).isTrue();
        assertThat(evidence.path("coQualifyingTypes").get(0).asText()).isEqualTo("DRIVERS_LICENSE");
        assertThat(evidence.path("coQualifyingTypes").get(1).asText()).isEqualTo("SSN_CARD");
    }

    @Test
    void the_evidence_key_is_absent_rather_than_empty_on_an_ordinary_page() {
        RulePack paystub = pack("PAYSTUB", 0.6, 10, literal("gross", "Gross Pay", 10));

        PageClassifier.Decision decision =
                PageClassifier.decide(
                        List.of(PageClassifier.evaluate(paystub, matcherFor("Gross", "Pay"))));

        assertThat(readEvidence(PageClassifier.evidenceJson(decision)).has("coQualifyingTypes"))
                .isFalse();
    }

    private static JsonNode readEvidence(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("evidence is not JSON", e);
        }
    }
}
