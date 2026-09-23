package com.pragmaticds.rag.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the word-boundary cue tightening and the stricter
 * {@link IntentRouterService#isCalculationRequest(String)} guard predicate.
 */
class IntentRouterServiceTest {

    private final IntentRouterService router = new IntentRouterService();

    // ---- route() word-boundary cues ------------------------------------

    @Test
    void calculationCuesMatchOnWordBoundaryNotSubstring() {
        // "rate" must fire as a whole word...
        assertEquals(Intent.CALCULATION, router.route("what is the rate", null, null));
        // ...but not inside "separate" (the old substring bug).
        assertEquals(Intent.GUIDELINE_QUESTION, router.route("can we separate these escrow items", null, null));
    }

    @Test
    void externalReferenceCuesStillResolve() {
        assertEquals(Intent.EXTERNAL_REFERENCE, router.route("link me the official handbook", null, null));
    }

    @Test
    void pageRouteAlwaysWinsOverCues() {
        assertEquals(Intent.PAGE_GUIDANCE, router.route("calculate my payment", "/loans", null));
    }

    // ---- isCalculationRequest() guard ----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Calculate my monthly payment",
            "compute the loan amount",
            "How much can I borrow?",
            "what is my DTI if I make 5000 a month",
            "what will my monthly payment be"
    })
    void computeRequestsAreGuarded(String question) {
        assertTrue(router.isCalculationRequest(question), "should guard: " + question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is DTI?",
            "What is an interest rate?",
            "Explain how PMI is calculated",
            "What is amortization?",
            "How does loan-to-value work?"
    })
    void definitionalQuestionsAreNotGuarded(String question) {
        assertFalse(router.isCalculationRequest(question), "should NOT guard: " + question);
    }

    @Test
    void nullOrBlankIsNotACalculationRequest() {
        assertFalse(router.isCalculationRequest(null));
        assertFalse(router.isCalculationRequest("   "));
    }
}
