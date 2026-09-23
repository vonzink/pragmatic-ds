package com.pragmaticds.docengine.orchestration;

import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The job lifecycle: create (idempotent), inspect, resume.
 *
 * <p>Idempotency is enforced by the database, not by check-then-insert: we insert and let the
 * unique {@code (org_id, idempotency_key)} constraint decide. Two racing submits cannot both win a
 * constraint; a lost race returns the existing job untouched — no second dispatch, no second run.
 *
 * <p>Dispatch NEVER outruns the data (review finding, confirmed): when the caller has an open
 * transaction — exactly what the upload seam does — the job row is not yet visible to the async
 * runner, which would read nothing, throw, and strand the job in UPLOADED forever. So dispatch is
 * deferred to {@code afterCommit} whenever a transaction is active, and runs immediately only when
 * none is. If the caller's transaction rolls back, the job row vanishes with it and no dispatch
 * fires — nothing is stranded either way.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final StageRunner stageRunner;
    private final TaskExecutor executor;

    public JobService(
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages,
            StageRunner stageRunner,
            @Qualifier("processingExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.stages = stages;
        this.stageRunner = stageRunner;
        this.executor = executor;
    }

    /** A job plus its stage attempt rows in creation order — what the API serves. */
    public record JobDetails(ProcessingJob job, List<ProcessingStage> stages) {}

    /**
     * Creates the processing job for a package and dispatches the pipeline, or — when the
     * idempotency key replays — returns the existing job untouched, whatever state it is in.
     */
    public ProcessingJob createJob(UUID packageId, String idempotencyKey) {
        UUID orgId = TenantContext.require();
        ProcessingJob job;
        try {
            job = jobs.saveAndFlush(new ProcessingJob(packageId, idempotencyKey));
        } catch (DataIntegrityViolationException replay) {
            // Lost the (org, key) uniqueness race or replayed a finished submit: same answer.
            // NOTE: this recovery only works with no enclosing transaction — a unique violation
            // aborts an enclosing Postgres transaction outright (25P02). Transactional callers
            // must check findExisting BEFORE calling, which is what the upload seam does.
            return jobs.findByOrgIdAndIdempotencyKey(orgId, idempotencyKey)
                    .orElseThrow(
                            () ->
                                    DomainException.conflict(
                                            ErrorCode.CONFLICT, Map.of("reason", "IDEMPOTENCY_RACE")));
        }
        log.info("job created job={} package={}", job.getId(), packageId);
        dispatch(job.getId(), orgId);
        return job;
    }

    /** The idempotency pre-check for transactional callers — see {@link #createJob}. */
    public Optional<ProcessingJob> findExisting(String idempotencyKey) {
        return jobs.findByOrgIdAndIdempotencyKey(TenantContext.require(), idempotencyKey);
    }

    public JobDetails getJob(UUID id) {
        UUID orgId = TenantContext.require();
        ProcessingJob job =
                jobs.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return new JobDetails(job, stages.findByJobIdOrderByCreatedAtAsc(job.getId()));
    }

    /**
     * Re-runs a FAILED job from its first failed stage forward.
     *
     * <p>The claim is a single atomic UPDATE guarded on {@code status = FAILED} (review finding:
     * two concurrent resumes both passed a read-then-check and double-walked the pipeline). The
     * loser of the race updates zero rows and gets the same 409 a resume of a non-FAILED job gets.
     * Dispatch defers to afterCommit — this method is transactional, so an eager dispatch would
     * race its own claim.
     */
    @Transactional
    public JobDetails resume(UUID id) {
        UUID orgId = TenantContext.require();
        ProcessingJob job =
                jobs.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        int claimed = jobs.claimForResume(id, orgId);
        if (claimed == 0) {
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("status", job.getStatus().name()));
        }
        log.info("job resume job={}", id);
        dispatch(id, orgId);

        // Re-read: the claim was a bulk update, the entity above is stale.
        ProcessingJob claimedJob = jobs.findByIdAndOrgId(id, orgId).orElseThrow();
        return new JobDetails(claimedJob, stages.findByJobIdOrderByCreatedAtAsc(id));
    }

    /**
     * Re-runs the EXTRACTING stage for a package whose grouping a reviewer changed. Deletes
     * EXTRACTING, AI_EXTRACTION, FINALIZING, and downstream placeholder stage rows and flips the job
     * HUMAN_REVIEW_REQUIRED -> UPLOADED, then dispatches: the runner skips the still-SUCCEEDED
     * RENDERING..SPLITTING, re-runs deterministic and AI extraction, and appends a new immutable
     * result generation.
     *
     * <p>Mirrors {@link #resume}: the claim is a single atomic UPDATE guarded on the terminal
     * status plus the initially observed attempt/generation, so two concurrent or stale regroups
     * cannot both re-kick — the loser affects zero rows and gets a 409. Both mutations run inside
     * this transaction, and dispatch defers to afterCommit, so a lost claim (or any failure) rolls
     * the stage-row delete back with it.
     */
    @Transactional
    public void reExtract(UUID packageId) {
        UUID orgId = TenantContext.require();
        ProcessingJob job =
                jobs.findByPackageIdAndOrgId(packageId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        stages.deleteFromExtractingOnward(job.getId());
        int claimed =
                jobs.claimForReExtract(
                        job.getId(), orgId, job.getAttempt(), job.getParseGeneration());
        if (claimed == 0) {
            // Not in a re-extractable state (still processing, or a concurrent re-kick won).
            throw DomainException.conflict(
                    ErrorCode.CONFLICT, Map.of("status", job.getStatus().name()));
        }
        log.info("job re-extract job={} package={}", job.getId(), packageId);
        dispatch(job.getId(), orgId);
    }

    /**
     * Ask the runner to stop this job at its next checkpoint. 409 when it is already terminal.
     *
     * <p>Idempotent on a second click: {@code requestCancel} updates 0 rows both when the job is
     * terminal AND when a cancel is already pending on it (the query excludes an already-set
     * flag too). Those two zero-row cases must not be told apart the same way — a second cancel
     * on a job that is still running is not an error, it is the same request landing twice. So on
     * 0 rows we re-read the job and only throw the 409 when it is actually terminal; otherwise we
     * return its current details, same as a first successful cancel would have.
     */
    @Transactional
    public JobDetails cancel(UUID id) {
        UUID orgId = TenantContext.require();
        ProcessingJob job = jobs.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        if (jobs.requestCancel(id, orgId) == 0) {
            ProcessingJob current = jobs.findByIdAndOrgId(id, orgId).orElseThrow();
            if (isTerminal(current.getStatus())) {
                throw DomainException.conflict(
                        ErrorCode.CONFLICT, Map.of("status", current.getStatus().name()));
            }
            log.info("job cancel already requested job={}", id);
            return new JobDetails(current, stages.findByJobIdOrderByCreatedAtAsc(id));
        }
        log.info("job cancel requested job={}", id);
        return new JobDetails(jobs.findByIdAndOrgId(id, orgId).orElseThrow(), stages.findByJobIdOrderByCreatedAtAsc(id));
    }

    private static boolean isTerminal(ProcessingStatus status) {
        return status == ProcessingStatus.COMPLETED
                || status == ProcessingStatus.FAILED
                || status == ProcessingStatus.HUMAN_REVIEW_REQUIRED;
    }

    /**
     * The executor thread has NO TenantContext of its own — a bare runnable would write rows as
     * the NO_TENANT sentinel. The org is captured HERE, on the caller's thread, and bound inside
     * the runnable; the previous binding is restored (not blindly cleared) so a same-thread test
     * executor does not wipe the caller's context.
     */
    private void dispatch(UUID jobId, UUID orgId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(jobId, orgId);
                        }
                    });
        } else {
            submit(jobId, orgId);
        }
    }

    private void submit(UUID jobId, UUID orgId) {
        executor.execute(
                () -> {
                    Optional<UUID> previous = TenantContext.current();
                    TenantContext.set(orgId);
                    try {
                        stageRunner.run(jobId);
                    } catch (RuntimeException e) {
                        // Ids only; an exception message may quote document content. The runner
                        // has already marked the job FAILED before rethrowing.
                        log.error(
                                "pipeline run aborted job={} exception={}",
                                jobId,
                                e.getClass().getSimpleName());
                    } finally {
                        previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
                    }
                });
    }
}
