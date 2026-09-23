package com.pragmaticds.rag.lab.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Dispatch limits, read only while instance execution is switched on.
 *
 * <p><b>Four scopes, because they protect four different things.</b> The deployment limit protects
 * this process; the per-brain limit stops one tenant starving the others; the per-provider limit
 * is what keeps a provider's rate limit from being the thing that discovers the problem; and the
 * per-instance limit stops one comparison group monopolising every slot. A single global number
 * cannot express any of those.
 *
 * <p>Like {@code InstanceModelProperties}, this record does not validate in its constructor: a
 * malformed limit must fail the dispatcher rather than prevent RAG Brain from starting, and with
 * execution switched off no dispatcher is constructed at all.
 */
@ConfigurationProperties(prefix = "ragbrain.instances.execution")
public record InstanceExecutionProperties(
        boolean enabled,
        Integer maxConcurrentRuns,
        Integer maxConcurrentRunsPerBrain,
        Integer maxConcurrentRunsPerProvider,
        Integer maxConcurrentRunsPerInstance,
        Duration pollInterval,
        Duration leaseDuration) {

    /**
     * The values a deployment gets when it enables execution without tuning it.
     *
     * <p>Deliberately small. A too-low limit slows a queue down visibly; a too-high one discovers
     * a provider's rate limit in production, and the second failure is much harder to read.
     */
    public InstanceExecutionProperties {
        maxConcurrentRuns = orDefault(maxConcurrentRuns, 4);
        maxConcurrentRunsPerBrain = orDefault(maxConcurrentRunsPerBrain, 2);
        maxConcurrentRunsPerProvider = orDefault(maxConcurrentRunsPerProvider, 2);
        maxConcurrentRunsPerInstance = orDefault(maxConcurrentRunsPerInstance, 1);
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(10) : leaseDuration;
    }

    /** Checks the limits, or says which one is unusable. Called by the dispatcher, not at startup. */
    public void validate() {
        if (maxConcurrentRuns < 1 || maxConcurrentRunsPerBrain < 1
                || maxConcurrentRunsPerProvider < 1 || maxConcurrentRunsPerInstance < 1
                || pollInterval.isNegative() || pollInterval.isZero()
                || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalStateException("instance execution limits must all be positive");
        }
    }

    private static Integer orDefault(Integer configured, int fallback) {
        return configured == null ? fallback : configured;
    }
}
