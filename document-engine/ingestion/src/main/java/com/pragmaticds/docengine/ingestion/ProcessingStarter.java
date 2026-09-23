package com.pragmaticds.docengine.ingestion;

import java.util.Optional;
import java.util.UUID;

/**
 * Port through which ingestion hands a validated package to processing.
 *
 * <p>Ingestion depends only on {@code platform}; orchestration owns jobs. This seam keeps that
 * boundary honest — the app module supplies the adapter that delegates to orchestration's
 * JobService, the same cross-module pattern host-app uses.
 */
public interface ProcessingStarter {

    /**
     * Create (or return, when the idempotency key replays) the processing job for a package.
     *
     * <p>The parse-once behavior fingerprint deliberately does NOT travel through here. Upload can
     * only compute what a parse started now WOULD execute under, and the pipeline runs
     * asynchronously and from caches; stamping that prediction as fact is what let one stored
     * fingerprint describe two different outputs. The run stamps itself at FINALIZING instead, and
     * upload's prediction is used only to match candidates.
     *
     * @return the job id
     */
    UUID startJob(UUID packageId, String idempotencyKey);

    /**
     * The pre-flight idempotency check. Upload runs inside a transaction, where a unique-violation
     * recovery is impossible (a violated constraint aborts the whole Postgres transaction, 25P02 —
     * review finding, confirmed). So a replayed key must be detected BEFORE any work: no new
     * package, no new blobs, the original result returned instead.
     */
    Optional<ExistingJob> findExisting(String idempotencyKey);

    /** The job a replayed idempotency key resolves to. */
    record ExistingJob(UUID jobId, UUID packageId) {}
}
