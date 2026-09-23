package com.pragmaticds.docengine.classification.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.ai.AiClassificationGates.PageFacts;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.Refusal;
import com.pragmaticds.docengine.classification.ai.AiClassificationGates.Verdict;
import com.pragmaticds.docengine.classification.match.AnchorMatcher;
import com.pragmaticds.docengine.classification.match.AnchorSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gates ARE the feature's trustworthiness: the model is allowed to retype a page only by
 * pointing at text the engine can find on that page itself. Every rule is exercised here, without
 * a database, for the same reason {@code BoundaryProposalGatesTest} drives its gates directly —
 * the adversarial case (a confident type with an invented quote) must die at the quote gate, not
 * be "downgraded and kept".
 */
class AiClassificationGatesTest {

    private static final BigDecimal FLOOR = new BigDecimal("0.60");

    /** Two printed rows: "EARNINGS STATEMENT" over "Employee’s Gross Pay" (curly apostrophe). */
    private static AnchorMatcher page() {
        return AnchorMatcher.forSpans(
                List.of(
                        span(1L, "EARNINGS", 0, 0),
                        span(2L, "STATEMENT", 55, 0),
                        span(3L, "Employee’s", 0, 20),
                        span(4L, "Gross", 60, 20),
                        span(5L, "Pay", 100, 20)));
    }

    private static AnchorSpan span(long id, String text, int x, int y) {
        return new AnchorSpan(
                id,
                text,
                BigDecimal.valueOf(x),
                BigDecimal.valueOf(y),
                BigDecimal.valueOf(50),
                BigDecimal.TEN);
    }

    /** The types the request offered the model — the same set the stage passes as its floor. */
    private static final Set<String> TAXONOMY = Set.of("PAYSTUB", "W2");

    private static Verdict verdict(String type, String confidence, String quote) {
        return verdict(type, confidence, quote, TAXONOMY);
    }

    private static Verdict verdict(
            String type, String confidence, String quote, Set<String> allowedTypeCodes) {
        return AiClassificationGates.verdict(
                type,
                confidence == null ? null : new BigDecimal(confidence),
                quote,
                page(),
                FLOOR,
                allowedTypeCodes);
    }

    private static PageFacts unknownPage(int index) {
        return new PageFacts(UUID.randomUUID(), index, AiClassificationGates.UNKNOWN, false);
    }

    // ── eligibility ─────────────────────────────────────────────────────────

    @Test
    void only_pages_the_deterministic_classifier_left_unknown_are_ever_sent() {
        PageFacts unknown = unknownPage(0);
        PageFacts typed = new PageFacts(UUID.randomUUID(), 1, "PAYSTUB", false);

        assertThat(AiClassificationGates.eligible(List.of(unknown, typed), 40))
                .containsExactly(unknown);
    }

    @Test
    void blank_and_duplicate_pages_are_never_sent_to_the_model() {
        PageFacts unknown = unknownPage(0);
        PageFacts transparent =
                new PageFacts(UUID.randomUUID(), 1, AiClassificationGates.UNKNOWN, true);

        assertThat(AiClassificationGates.eligible(List.of(unknown, transparent), 40))
                .containsExactly(unknown);
    }

    @Test
    void a_page_the_classifier_never_judged_at_all_is_not_a_fallback_candidate() {
        PageFacts unjudged = new PageFacts(UUID.randomUUID(), 0, null, false);

        assertThat(AiClassificationGates.eligible(List.of(unjudged), 40)).isEmpty();
    }

    @Test
    void the_page_cap_takes_the_lowest_page_indexes_whatever_order_the_rows_arrive_in() {
        PageFacts third = unknownPage(9);
        PageFacts first = unknownPage(2);
        PageFacts second = unknownPage(4);

        assertThat(AiClassificationGates.eligible(List.of(third, first, second), 2))
                .containsExactly(first, second);
    }

    @Test
    void a_non_positive_cap_spends_nothing() {
        assertThat(AiClassificationGates.eligible(List.of(unknownPage(0)), 0)).isEmpty();
    }

    // ── the quote gate ──────────────────────────────────────────────────────

    @Test
    void a_verified_quote_earns_the_type_and_reports_the_spans_it_matched() {
        Verdict verdict = verdict("PAYSTUB", "0.91", "EARNINGS STATEMENT");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.refusal()).isEqualTo(Refusal.NONE);
        assertThat(verdict.evidence().spanIds()).containsExactly(1L, 2L);
        assertThat(verdict.evidence().rangeStart()).isZero();
        assertThat(verdict.evidence().rangeEnd()).isEqualTo("EARNINGS STATEMENT".length());
    }

    @Test
    void the_adversarial_case_a_quote_appearing_nowhere_is_refused_not_downgraded() {
        Verdict verdict = verdict("PAYSTUB", "0.99", "Uniform Residential Loan Application");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.refusal()).isEqualTo(Refusal.QUOTE_UNVERIFIED);
        assertThat(verdict.evidence()).isNull();
    }

    @Test
    void an_empty_quote_proves_nothing_and_is_refused() {
        assertThat(verdict("PAYSTUB", "0.99", "   ").refusal()).isEqualTo(Refusal.QUOTE_UNVERIFIED);
        assertThat(verdict("PAYSTUB", "0.99", null).refusal()).isEqualTo(Refusal.QUOTE_UNVERIFIED);
    }

    @Test
    void the_quote_is_matched_through_the_modules_own_punctuation_seam() {
        // The page prints U+2019; the model returns the ASCII apostrophe (or the reverse). One
        // normalization, TextFold's, so the matcher and this gate cannot disagree.
        assertThat(verdict("PAYSTUB", "0.91", "Employee's Gross Pay").accepted()).isTrue();
        assertThat(verdict("PAYSTUB", "0.91", "employee’s gross pay").accepted()).isTrue();
    }

    @Test
    void whitespace_the_model_invented_between_words_does_not_refuse_a_real_quote() {
        assertThat(verdict("PAYSTUB", "0.91", "  EARNINGS   STATEMENT ").accepted()).isTrue();
    }

    @Test
    void a_quote_that_differs_by_a_WORD_is_not_a_formatting_difference() {
        assertThat(verdict("PAYSTUB", "0.91", "EARNINGS SUMMARY").refusal())
                .isEqualTo(Refusal.QUOTE_UNVERIFIED);
    }

    // ── the other refusals ──────────────────────────────────────────────────

    @Test
    void confidence_below_the_floor_is_refused_even_with_a_perfect_quote() {
        assertThat(verdict("PAYSTUB", "0.59", "EARNINGS STATEMENT").refusal())
                .isEqualTo(Refusal.BELOW_MIN_CONFIDENCE);
        assertThat(verdict("PAYSTUB", null, "EARNINGS STATEMENT").refusal())
                .isEqualTo(Refusal.BELOW_MIN_CONFIDENCE);
    }

    @Test
    void confidence_exactly_at_the_floor_qualifies() {
        // `>=`, the same reading of a MINIMUM the rule packs' min_confidence has (PageClassifier).
        assertThat(verdict("PAYSTUB", "0.60", "EARNINGS STATEMENT").accepted()).isTrue();
    }

    @Test
    void an_unknown_proposal_is_the_models_honest_abstention_and_is_refused_as_such() {
        Verdict verdict = verdict(AiClassificationGates.UNKNOWN, "0.99", "EARNINGS STATEMENT");

        assertThat(verdict.refusal()).isEqualTo(Refusal.MODEL_ABSTAINED);
    }

    @Test
    void a_missing_or_blank_type_code_is_an_abstention_too() {
        assertThat(verdict(null, "0.99", "EARNINGS STATEMENT").refusal())
                .isEqualTo(Refusal.MODEL_ABSTAINED);
        assertThat(verdict(" ", "0.99", "EARNINGS STATEMENT").refusal())
                .isEqualTo(Refusal.MODEL_ABSTAINED);
    }

    @Test
    void abstention_is_reported_ahead_of_the_gates_it_could_never_pass() {
        // An UNKNOWN proposal with an invented quote is still an abstention: reporting it as a
        // hallucinated quote would slander a model that told the truth about not knowing.
        assertThat(verdict(AiClassificationGates.UNKNOWN, "0.10", "nowhere on this page").refusal())
                .isEqualTo(Refusal.MODEL_ABSTAINED);
    }

    // ── the taxonomy floor ──────────────────────────────────────────────────

    @Test
    void a_type_the_request_never_offered_is_refused_here_and_not_only_in_the_adapter() {
        // document_type_code is bare text with no foreign key, so a code no document_type row
        // defines would be written as a CURRENT classification, find no extraction schema, and
        // open a document of an invented type. The adapter drops it; this is the stage's own
        // floor under the same claim, for the adapter that forgets.
        Verdict verdict = verdict("INVENTED_TYPE", "0.99", "EARNINGS STATEMENT");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.refusal()).isEqualTo(Refusal.TYPE_NOT_IN_TAXONOMY);
        assertThat(verdict.evidence()).isNull();
    }

    @Test
    void an_empty_taxonomy_believes_nothing() {
        assertThat(verdict("PAYSTUB", "0.99", "EARNINGS STATEMENT", Set.of()).refusal())
                .isEqualTo(Refusal.TYPE_NOT_IN_TAXONOMY);
        assertThat(verdict("PAYSTUB", "0.99", "EARNINGS STATEMENT", null).refusal())
                .isEqualTo(Refusal.TYPE_NOT_IN_TAXONOMY);
    }

    @Test
    void UNKNOWN_is_an_abstention_and_never_needs_to_be_in_the_taxonomy() {
        // UNKNOWN is the abstention token, not a type: it is never a document_type row, so a
        // taxonomy check placed ahead of the abstention read would mis-report an honest answer.
        assertThat(verdict(AiClassificationGates.UNKNOWN, "0.99", "EARNINGS STATEMENT").refusal())
                .isEqualTo(Refusal.MODEL_ABSTAINED);
    }

    @Test
    void the_taxonomy_floor_is_read_before_confidence_and_before_the_quote() {
        // A code nothing defines is not a low-confidence answer and not a bad quote: it is
        // unusable whatever the numbers say, and counting it as either would mis-tune the floor.
        assertThat(verdict("INVENTED_TYPE", "0.10", "nowhere on this page").refusal())
                .isEqualTo(Refusal.TYPE_NOT_IN_TAXONOMY);
    }
}
