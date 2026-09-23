package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.config.LearningProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningWeightMathTest {

    /** Matches the confirmed starting values in the spec / application.yml. */
    private static LearningProperties props() {
        return new LearningProperties(0.8, 1.2, 0.05, 5, 3, 0.10, 0.98);
    }

    @Test
    void noEvidenceKeepsWeightNeutralAfterDecayOnly() {
        // current 1.0, no votes -> decay pulls toward 1.0, so it stays exactly 1.0
        double next = LearningWeightMath.computeNewWeight(1.0, 0, 0, props());
        assertEquals(1.0, next, 1e-9);
    }

    @Test
    void decayPullsAnAboveNeutralWeightTowardOne() {
        // No votes, current 1.10 -> decay 0.98 pulls it toward 1.0:
        // 1.0 + (1.10 - 1.0) * 0.98 = 1.098
        double next = LearningWeightMath.computeNewWeight(1.10, 0, 0, props());
        assertEquals(1.098, next, 1e-9);
    }

    @Test
    void upvotesRaiseWeightButCapDeltaAtMaxPerRun() {
        // Strong up signal from neutral: raw target would exceed +maxDelta,
        // so the move is capped to +0.05 -> 1.05 (before clamp; still within band).
        double next = LearningWeightMath.computeNewWeight(1.0, 100, 0, props());
        assertEquals(1.05, next, 1e-9);
    }

    @Test
    void downvotesLowerWeightButCapDeltaAtMaxPerRun() {
        double next = LearningWeightMath.computeNewWeight(1.0, 0, 100, props());
        assertEquals(0.95, next, 1e-9);
    }

    @Test
    void weightNeverExceedsMax() {
        // Already at the ceiling, more up votes cannot push past weightMax.
        double next = LearningWeightMath.computeNewWeight(1.2, 100, 0, props());
        assertTrue(next <= 1.2 + 1e-9, "must not exceed weightMax");
        assertEquals(1.2, next, 1e-9);
    }

    @Test
    void weightNeverDropsBelowMin() {
        double next = LearningWeightMath.computeNewWeight(0.8, 0, 100, props());
        assertTrue(next >= 0.8 - 1e-9, "must not drop below weightMin");
        assertEquals(0.8, next, 1e-9);
    }

    @Test
    void balancedVotesLeaveWeightAtNeutralAfterDecay() {
        // Equal up/down -> net signal 0 -> only decay acts; from 1.0 stays 1.0.
        double next = LearningWeightMath.computeNewWeight(1.0, 10, 10, props());
        assertEquals(1.0, next, 1e-9);
    }

    @Test
    void proposedDeltaExposesCumulativeMoveFromNeutral() {
        // proposedDelta = |newWeight - 1.0|, used by the caller to gate review.
        double delta = LearningWeightMath.proposedDelta(1.12);
        assertEquals(0.12, delta, 1e-9);
    }
}
