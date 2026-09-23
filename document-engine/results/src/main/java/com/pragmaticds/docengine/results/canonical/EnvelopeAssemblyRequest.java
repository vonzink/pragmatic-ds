package com.pragmaticds.docengine.results.canonical;

import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.util.Objects;
import java.util.UUID;

/** Stable generation inputs allocated outside the live machine projections. */
public record EnvelopeAssemblyRequest(
        UUID packageId,
        UUID processingJobId,
        int parseGeneration,
        int packageRevision,
        String envelopeVersion,
        String canonicalizationVersion,
        ReuseEligibility reuseEligibility) {

    public EnvelopeAssemblyRequest {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(processingJobId, "processingJobId");
        Objects.requireNonNull(envelopeVersion, "envelopeVersion");
        Objects.requireNonNull(canonicalizationVersion, "canonicalizationVersion");
        Objects.requireNonNull(reuseEligibility, "reuseEligibility");
        if (parseGeneration <= 0 || packageRevision <= 0) {
            throw new IllegalArgumentException("generation and revision must be positive");
        }
    }
}
