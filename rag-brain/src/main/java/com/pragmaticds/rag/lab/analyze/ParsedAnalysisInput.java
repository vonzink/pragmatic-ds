package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.engine.DocumentEngineClient;

import java.util.Objects;
import java.util.UUID;

/**
 * Everything a parsed Lab run is allowed to analyze, and nothing else.
 *
 * <p>This record is the structural half of the no-raw-fallback rule. There is no member for an
 * upload, a byte array, a stream source, a filename, or a supplier that could fetch one — so a
 * future edit cannot quietly hand the analyzer an original document, because there is nowhere to
 * put it. The only evidence carried here is {@code verified}: an engine revision whose bytes were
 * already checked against the engine's quoted digest and length and then strictly parsed.
 *
 * <p>{@code descriptor} travels beside it on purpose. A verified envelope proves the BYTES are
 * intact; only the descriptor the caller selected proves they are the bytes of the revision this
 * run intended to pin. {@link DocumentEngineClient.RevisionDescriptor#describes} is that equality,
 * and a run whose two halves disagree is stale rather than analyzable.
 *
 * @param analysisRunId the caller-allocated {@code analysis_runs.id}; the Lab request and the
 *     analyzer manifest share this one identity rather than inventing a parallel analyzer id
 * @param correlationId the bounded id that appears in every sanitized log line and failure
 */
public record ParsedAnalysisInput(
        UUID brainId,
        UUID analysisRunId,
        UUID packageId,
        DocumentEngineClient.RevisionDescriptor descriptor,
        DocumentEngineClient.VerifiedEnvelope verified,
        String correlationId,
        String reviewSnapshotSha256) {

    public ParsedAnalysisInput {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Preserved for callers pinning no reviewed-values snapshot; leaves it null. */
    public ParsedAnalysisInput(
            UUID brainId,
            UUID analysisRunId,
            UUID packageId,
            DocumentEngineClient.RevisionDescriptor descriptor,
            DocumentEngineClient.VerifiedEnvelope verified,
            String correlationId) {
        this(brainId, analysisRunId, packageId, descriptor, verified, correlationId, null);
    }

    /** Identifiers only — never the envelope, its bytes, or any parsed value. */
    @Override
    public String toString() {
        return "ParsedAnalysisInput[brainId=" + brainId
                + ", analysisRunId=" + analysisRunId
                + ", packageId=" + packageId
                + ", revision=" + descriptor.revision()
                + ", correlationId=" + correlationId + "]";
    }
}
