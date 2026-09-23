package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.config.LearningProperties;

/**
 * Pure, side-effect-free weight math for the learning loop. Separated from the
 * scheduled service so the bounded-delta safety invariants (cap, decay, clamp)
 * are unit-testable without a database.
 *
 * Invariants guaranteed here (spec "Safety invariants"):
 *  - the result is always within [weightMin, weightMax];
 *  - the result never moves more than maxDeltaPerRun from {@code current};
 *  - with no net signal, only {@code decay} acts, pulling the weight toward 1.0.
 */
public final class LearningWeightMath {

    /** Neutral weight — the retrieval multiplier that changes nothing. */
    public static final double NEUTRAL = 1.0;

    private LearningWeightMath() {}

    /**
     * Computes the next weight for one (brain, document) from this run's vote
     * tallies. {@code up}/{@code down} are already admin-weighted by the caller
     * (an admin vote is counted {@code adminVoteWeight} times). Steps:
     *   1. decay the current weight toward NEUTRAL (stale signal fades);
     *   2. add a bounded push from the net up/down signal;
     *   3. cap the total move from {@code current} to ±maxDeltaPerRun;
     *   4. clamp to [weightMin, weightMax].
     */
    public static double computeNewWeight(double current, int up, int down, LearningProperties p) {
        // 1. Decay toward neutral.
        double decayed = NEUTRAL + (current - NEUTRAL) * p.decay();

        // 2. Signed push in [-1, 1] from the net signal, scaled to one run's max delta.
        int total = up + down;
        double signal = total == 0 ? 0.0 : (double) (up - down) / total;
        double target = decayed + signal * p.maxDeltaPerRun();

        // 3. Cap the total move from the ORIGINAL current to ±maxDeltaPerRun.
        double lowCap = current - p.maxDeltaPerRun();
        double highCap = current + p.maxDeltaPerRun();
        double capped = Math.max(lowCap, Math.min(highCap, target));

        // 4. Hard clamp to the allowed band.
        return Math.max(p.weightMin(), Math.min(p.weightMax(), capped));
    }

    /** Cumulative move from neutral — the quantity gated against reviewThreshold. */
    public static double proposedDelta(double newWeight) {
        return Math.abs(newWeight - NEUTRAL);
    }
}
