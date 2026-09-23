package com.pragmaticds.docengine.orchestration;

import java.util.Objects;
import java.util.UUID;

/** Orchestration-owned boundary for durably finalizing one parse generation. */
public interface EngineResultFinalizerPort {

    FinalizationResult finalizeResult(
            UUID jobId, UUID packageId, int parseGeneration, int materializingJobAttempt);

    record FinalizationResult(String envelopeSha256, long envelopeSizeBytes) {
        public FinalizationResult {
            Objects.requireNonNull(envelopeSha256, "envelopeSha256");
            if (envelopeSizeBytes <= 0) {
                throw new IllegalArgumentException("envelope size must be positive");
            }
        }
    }
}
