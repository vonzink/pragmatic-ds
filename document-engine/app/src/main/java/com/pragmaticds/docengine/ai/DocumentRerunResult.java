package com.pragmaticds.docengine.ai;

import java.util.UUID;

/**
 * What one per-document AI re-run (issue #66) did — counters only, never a field value or a word
 * of document text, so it can be served to the wire as it is.
 *
 * @param status {@code APPLIED} — the provider answered and the persist ran; {@code ERROR} — the
 *     provider call failed for the reason given (an {@code ERROR} ledger row records it); {@code
 *     NOT_ELIGIBLE} — nothing was called or recorded, because the package has no job, the AI
 *     stage is off, the document's type has no profile, or it has no deterministic rows to enrich
 * @param reason the ERROR or NOT_ELIGIBLE reason; null on APPLIED
 * @param fieldsInserted AI rows written by this re-run (first and second pass together)
 * @param conflicts deterministic rows the re-run disagreed with and flagged, never overwrote
 */
public record DocumentRerunResult(
        UUID documentId, Status status, String reason, int fieldsInserted, int conflicts) {

    public enum Status {
        APPLIED,
        ERROR,
        NOT_ELIGIBLE
    }

    static DocumentRerunResult applied(UUID documentId, int fieldsInserted, int conflicts) {
        return new DocumentRerunResult(documentId, Status.APPLIED, null, fieldsInserted, conflicts);
    }

    static DocumentRerunResult error(UUID documentId, String reason) {
        return new DocumentRerunResult(documentId, Status.ERROR, reason, 0, 0);
    }

    static DocumentRerunResult notEligible(UUID documentId, String reason) {
        return new DocumentRerunResult(documentId, Status.NOT_ELIGIBLE, reason, 0, 0);
    }
}
