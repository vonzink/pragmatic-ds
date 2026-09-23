package com.pragmaticds.docengine.orchestration.domain;

import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    /** The house rule: load tenant-scoped entities by id AND org — never findById. */
    Optional<ProcessingJob> findByIdAndOrgId(UUID id, UUID orgId);

    /** The idempotency lookup: a replayed submit resolves to the existing job. */
    Optional<ProcessingJob> findByOrgIdAndIdempotencyKey(UUID orgId, String idempotencyKey);

    /** The package's job — the entry point for a regroup's re-extraction re-kick. */
    Optional<ProcessingJob> findByPackageIdAndOrgId(UUID packageId, UUID orgId);

    /**
     * The per-document AI re-run's guard (issue #66): the same job row the run, resume and
     * re-extract claims update, taken under a row lock instead of an UPDATE because the re-run
     * changes nothing about the job. Holding the lock for the caller's transaction means a
     * concurrent {@link #claimForRun}, {@link #claimForResume} or {@link #claimForReExtract}
     * blocks until the re-run commits, and a claim that already committed is what the caller's
     * status check sees — so "is this package being processed?" is answered atomically, not by a
     * read-then-check.
     *
     * <p>Lock timeout ZERO — {@code FOR UPDATE NOWAIT} on Postgres. {@code StageRunner} writes the
     * job row inside each stage's REQUIRES_NEW transaction BEFORE the port call, so during a
     * running stage this row stays locked for as long as the stage does (up to the OCR timeout).
     * A waiting lock would park a reviewer's HTTP thread for minutes before it could say 409;
     * NOWAIT fails at once, and the caller turns the lock failure into the same 409 an unsettled
     * status gets. The status predicate is the caller's, and it is deliberately wider than
     * {@link #claimForReExtract}'s: that claim admits only HUMAN_REVIEW_REQUIRED because it
     * flips the job back to UPLOADED and carries an ABA guard; the re-run only needs to know
     * that no pipeline is running.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select j from ProcessingJob j where j.packageId = :packageId and j.orgId = :orgId")
    Optional<ProcessingJob> lockByPackageIdAndOrgId(
            @Param("packageId") UUID packageId, @Param("orgId") UUID orgId);

    /**
     * Re-extract claim: a package whose pipeline already finished (HUMAN_REVIEW_REQUIRED) is put
     * back to UPLOADED so the runner replays forward. Guarded on the terminal status and the
     * caller's initially observed attempt/generation, so a stale claimant cannot win after another
     * regroup completes and returns the job to HUMAN_REVIEW_REQUIRED (the ABA case).
     *
     * <p>The claim also NULLs {@code behavior_fingerprint}: the regenerated rows re-ran only
     * EXTRACTING under possibly-newer loaders, so the stored fingerprint no longer describes what
     * produced them — a mixed-behavior artifact is never a reuse source (NULL never matches).
     *
     * <p>Also NULLs {@code cancelRequestedAt} for the same reason {@link #claimForResume} does: a
     * re-extract is a fresh run and must not inherit a stale cancel flag from whatever run last
     * touched this job.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update ProcessingJob j
               set j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.UPLOADED,
                   j.attempt = j.attempt + 1,
                   j.parseGeneration = j.parseGeneration + 1,
                   j.behaviorFingerprint = null,
                   j.cancelRequestedAt = null
             where j.id = :id and j.orgId = :orgId
               and j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.HUMAN_REVIEW_REQUIRED
               and j.attempt = :expectedAttempt
               and j.parseGeneration = :expectedParseGeneration
            """)
    int claimForReExtract(
            @Param("id") UUID id,
            @Param("orgId") UUID orgId,
            @Param("expectedAttempt") int expectedAttempt,
            @Param("expectedParseGeneration") int expectedParseGeneration);

    /**
     * Atomic resume claim: flips FAILED back to UPLOADED (queued) and bumps the attempt in ONE
     * statement, guarded on the current status. Concurrent resumes cannot both update the row —
     * the loser affects zero rows and the caller turns that into a 409. A read-then-check here
     * was the double-dispatch bug the Phase 1 review confirmed.
     *
     * <p>The claim also NULLs {@code behavior_fingerprint}, for the same reason the re-extract
     * claim does. A resume re-runs only from the failed stage forward: the stages that already
     * SUCCEEDED keep the output of the worker, packs and schemas of the earlier run, while the
     * replayed ones execute under whatever is current hours or days later. The finished rows are a
     * blend of two behaviors and no single fingerprint describes a blend, so the resumed
     * generation is never a reuse source. Nulling at the CLAIM (rather than leaving it to
     * FINALIZING, which also refuses to stamp a continuation) means a resume that crashes
     * midway cannot leave the old stamp standing over half-new rows.
     *
     * <p>Also NULLs {@code cancelRequestedAt}: a job FAILED with {@code JOB_CANCELLED} still has
     * the flag set from the run that ended it, and a resume that inherited it would fail again at
     * its very first budgeted checkpoint — the resume would never actually run. A resume is a new
     * user decision to keep going; it gets a fresh, un-cancelled attempt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update ProcessingJob j
               set j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.UPLOADED,
                   j.attempt = j.attempt + 1,
                   j.finishedAt = null,
                   j.behaviorFingerprint = null,
                   j.cancelRequestedAt = null
             where j.id = :id and j.orgId = :orgId
               and j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.FAILED
            """)
    int claimForResume(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Atomic run claim: only ONE runner may take a queued (UPLOADED) job. A duplicate dispatch —
     * whatever produced it — affects zero rows and exits without walking the pipeline.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update ProcessingJob j
               set j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.VALIDATING
             where j.id = :id and j.orgId = :orgId
               and j.status = com.pragmaticds.docengine.orchestration.ProcessingStatus.UPLOADED
            """)
    int claimForRun(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** Sets the cancel flag on a job that is still running; 0 rows = terminal or another org's. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update ProcessingJob j set j.cancelRequestedAt = CURRENT_TIMESTAMP
             where j.id = :id and j.orgId = :orgId and j.cancelRequestedAt is null
               and j.status not in (com.pragmaticds.docengine.orchestration.ProcessingStatus.COMPLETED,
                                    com.pragmaticds.docengine.orchestration.ProcessingStatus.FAILED,
                                    com.pragmaticds.docengine.orchestration.ProcessingStatus.HUMAN_REVIEW_REQUIRED)
            """)
    int requestCancel(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select (j.cancelRequestedAt is not null) from ProcessingJob j where j.id = :id and j.orgId = :orgId")
    boolean isCancelRequested(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /**
     * Retention purge: a package's jobs, deleted AFTER their stage rows (plain FK) and before the
     * package root. Explicit org guard — bulk JPQL does not travel through {@code @TenantId}
     * filtering. Returns the deleted count for the purge audit.
     */
    @Modifying
    @Query("delete from ProcessingJob j where j.packageId = :packageId and j.orgId = :orgId")
    int deleteByPackageIdAndOrgId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
