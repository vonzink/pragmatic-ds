package com.pragmaticds.docengine.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * What the client gets back from an accepted upload: the package, the processing job to poll, the
 * per-file record (with the SNIFFED content type — the claim is audit data, not an answer), any
 * cross-package duplicate warnings, and — on a parse-once reuse hit — the {@code reused} marker.
 */
public record UploadResult(
        UUID packageId,
        UUID jobId,
        List<FileResult> files,
        List<DuplicateWarning> warnings,
        Reused reused) {

    public record FileResult(
            UUID id,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String sha256,
            Integer pageCount) {}

    /**
     * The same bytes already exist in another package of this org. Legitimate (the same paystub
     * appears in multiple loan files), so it warns instead of rejecting. Carries ONLY a sha
     * prefix: no filenames, no content, and — review finding — not the other package's file id;
     * an upload response is not a catalogue of where else a document lives.
     */
    public record DuplicateWarning(String shaPrefix) {}

    /**
     * These bytes were already parsed under the current behavior fingerprint, so the PRIOR package
     * is the answer: the top-level ids ARE that package's ids, restated here for explicitness so a
     * caller can tell a reuse from a fresh accept without diffing ids. Null on every non-reused
     * upload — additive, existing clients untouched (the {@link DuplicateWarning} precedent).
     * Deliberately ids-and-revision only: an upload response is not a catalogue of where else a
     * document lives, and the durable record is the PACKAGE_REUSE_SERVED audit event.
     */
    public record Reused(UUID packageId, UUID jobId, int engineResultRevision) {}
}
