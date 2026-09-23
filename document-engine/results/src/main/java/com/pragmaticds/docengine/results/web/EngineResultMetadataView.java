package com.pragmaticds.docengine.results.web;

import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.domain.ReuseEligibility;
import java.time.Instant;
import java.util.UUID;

/** Approved descriptor metadata; storage identity and machine content are intentionally absent. */
public record EngineResultMetadataView(
        int revision,
        UUID processingJobId,
        int parseGeneration,
        int materializedJobAttempt,
        String envelopeSchemaVersion,
        String sourceSetSha256,
        String provenanceSha256,
        String envelopeSha256,
        long envelopeSizeBytes,
        ReuseEligibility reuseEligibility,
        Instant createdAt) {

    static EngineResultMetadataView from(EngineResult result) {
        return new EngineResultMetadataView(
                result.getRevision(),
                result.getProcessingJobId(),
                result.getParseGeneration(),
                result.getMaterializedJobAttempt(),
                result.getEnvelopeSchemaVersion(),
                result.getSourceSetSha256(),
                result.getProvenanceSha256(),
                result.getEnvelopeSha256(),
                result.getEnvelopeSizeBytes(),
                result.getReuseEligibility(),
                result.getCreatedAt());
    }
}
