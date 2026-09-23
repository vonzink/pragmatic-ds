package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The sizing rule is the whole point of the fix, so it is pinned as pure arithmetic rather than
 * only observed through a live pool: processing concurrency is DERIVED from the connection pool,
 * never chosen independently of it.
 */
class ProcessingExecutorSizingTest {

    @Test
    void unsetConcurrencyDerivesFromThePoolMinusTheApiHeadroom() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(0, 20, 8)).isEqualTo(12);
    }

    @Test
    void aNegativeConfiguredValueAlsoMeansDerive() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(-1, 20, 8)).isEqualTo(12);
    }

    @Test
    void anExplicitConcurrencyInsideTheCeilingIsHonoured() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(5, 20, 8)).isEqualTo(5);
    }

    /**
     * The incident in one assertion: 21 processing threads against a 10-connection pool. An
     * operator may not opt back into it — the ceiling is a hard clamp, and more concurrency is
     * bought by raising the POOL, which keeps the two numbers coupled.
     */
    @Test
    void anExplicitConcurrencyAboveTheCeilingIsClampedToIt() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(21, 10, 4)).isEqualTo(6);
        assertThat(ProcessingExecutorSizing.wouldClamp(21, 10, 4)).isTrue();
        assertThat(ProcessingExecutorSizing.wouldClamp(5, 10, 4)).isFalse();
        assertThat(ProcessingExecutorSizing.wouldClamp(0, 10, 4)).isFalse();
    }

    @Test
    void headroomLargerThanThePoolStillLeavesOneWorkerRatherThanZero() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(0, 4, 10)).isEqualTo(1);
        assertThat(ProcessingExecutorSizing.resolveConcurrency(0, 4, 4)).isEqualTo(1);
    }

    @Test
    void nonsensePoolAndHeadroomValuesDegradeToOneWorkerInsteadOfThrowing() {
        assertThat(ProcessingExecutorSizing.resolveConcurrency(0, 0, 0)).isEqualTo(1);
        assertThat(ProcessingExecutorSizing.resolveConcurrency(0, -5, -5)).isEqualTo(1);
    }
}
