package com.pragmaticds.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed tuning knobs for the adaptive-retrieval learning loop, bound from
 * ragbrain.rag.learning.* in application.yml. All values are safe-by-construction
 * bounds: the loop clamps weights to [weightMin, weightMax], caps a single run's
 * move to maxDeltaPerRun, ignores documents below minEvidence, counts an admin
 * vote as adminVoteWeight end-user votes, routes cumulative moves beyond
 * reviewThreshold to the admin queue, and decays every weight toward 1.0 by decay
 * per run so stale signal fades.
 *
 * <p>{@code job-cron} and {@code globally-disabled} live in yml but are read
 * directly by the job service ({@code @Scheduled(cron=...)} and
 * {@code @Value("${ragbrain.rag.learning.globally-disabled:false}")}), not here.
 */
@ConfigurationProperties(prefix = "ragbrain.rag.learning")
public record LearningProperties(
        double weightMin,
        double weightMax,
        double maxDeltaPerRun,
        int minEvidence,
        int adminVoteWeight,
        double reviewThreshold,
        double decay
) {
    public LearningProperties {
        if (weightMin <= 0 || weightMax < weightMin) {
            throw new IllegalArgumentException(
                    "learning weight bounds invalid: min=" + weightMin + " max=" + weightMax);
        }
    }
}
