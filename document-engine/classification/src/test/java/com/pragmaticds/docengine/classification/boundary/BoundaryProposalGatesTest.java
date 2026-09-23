package com.pragmaticds.docengine.classification.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.boundary.BoundaryProposalGates.PageFacts;
import com.pragmaticds.docengine.classification.boundary.BoundaryWindowPlanner.Window;
import com.pragmaticds.docengine.classification.domain.BoundaryProposal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gates are the design (§6): a proposal earns belief only by surviving all of them, and the
 * verdict names the FIRST refusal so the ledger reads as a reason, not a boolean. The adversarial
 * case — a plausible boundary whose quote appears nowhere on the page — is the one that makes the
 * whole feature trustworthy, and it must die at QUOTE_MATCH, never be "downgraded and kept".
 */
class BoundaryProposalGatesTest {

    private static final List<Window> WINDOWS = List.of(new Window(2, 6, "UNKNOWN_RUN"));
    private static final BigDecimal FLOOR = new BigDecimal("0.75");
    private static final String PAGE_TOP = "SCHEDULE E (Form 1040) Supplemental Income and Loss";

    private static PageFacts ordinaryPage() {
        return new PageFacts(false, false, PAGE_TOP);
    }

    private static String verdict(
            int index, String confidence, String quote, PageFacts facts) {
        return BoundaryProposalGates.verdict(
                index,
                confidence == null ? null : new BigDecimal(confidence),
                quote,
                WINDOWS,
                facts,
                FLOOR);
    }

    @Test
    void a_proposal_that_survives_every_gate_is_accepted() {
        assertThat(verdict(4, "0.90", "Schedule E (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_ACCEPTED);
    }

    @Test
    void the_adversarial_case_a_quote_appearing_nowhere_is_refused_not_downgraded() {
        assertThat(verdict(4, "0.99", "Uniform Residential Loan Application", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH);
    }

    @Test
    void the_quote_match_is_lenient_about_case_punctuation_and_spacing_only() {
        assertThat(verdict(4, "0.90", "schedule e   form: 1040", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_ACCEPTED);
        // Word-level difference is not a formatting difference.
        assertThat(verdict(4, "0.90", "Schedule F (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH);
    }

    @Test
    void an_empty_or_whitespace_quote_matches_nothing() {
        // The model must have READ something; an empty quote would trivially "match" every page.
        assertThat(verdict(4, "0.90", "", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH);
        assertThat(verdict(4, "0.90", "  ,.;  ", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH);
        assertThat(verdict(4, "0.90", null, ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_QUOTE_MATCH);
    }

    @Test
    void a_page_outside_every_window_is_a_hallucination_by_definition() {
        assertThat(verdict(9, "0.99", "Schedule E (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_OUT_OF_WINDOW);
        // A page index that names no real page at all is the same class of claim.
        assertThat(verdict(4, "0.99", "Schedule E (Form 1040)", null))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_OUT_OF_WINDOW);
    }

    @Test
    void a_transparent_page_is_refused_before_the_quote_is_even_consulted() {
        // A blank page has no spans; falling through to the quote gate would record
        // REJECTED_QUOTE_MATCH when the true refusal is boundary-invisibility.
        assertThat(verdict(4, "0.90", "anything", new PageFacts(true, false, "")))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_TRANSPARENT);
    }

    @Test
    void a_page_that_already_starts_a_document_is_an_override_refusal() {
        // Precedence: the deterministic split already cuts here. The AI must not relabel a
        // proven boundary as its own.
        assertThat(verdict(4, "0.90", "Schedule E (Form 1040)", new PageFacts(false, true, PAGE_TOP)))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_OVERRIDE);
    }

    @Test
    void confidence_below_the_floor_is_missing_over_wrong_applied_to_boundaries() {
        assertThat(verdict(4, "0.60", "Schedule E (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_CONFIDENCE_FLOOR);
        assertThat(verdict(4, null, "Schedule E (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_REJECTED_CONFIDENCE_FLOOR);
        assertThat(verdict(4, "0.75", "Schedule E (Form 1040)", ordinaryPage()))
                .isEqualTo(BoundaryProposal.VERDICT_ACCEPTED);
    }
}
