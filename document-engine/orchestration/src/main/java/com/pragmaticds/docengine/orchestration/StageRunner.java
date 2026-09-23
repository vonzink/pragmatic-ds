package com.pragmaticds.docengine.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ParserPort.StageRequest;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.behavior.BehaviorViewScope;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives a job through the pipeline in {@link ProcessingStatus} enum order.
 *
 * <p>EVERY stage attempt runs in its own committed transaction ({@link TransactionTemplate},
 * REQUIRES_NEW): the attempt row — including its error — is durable BEFORE control leaves the
 * attempt (the Phase 1 plan rule "stage row commits its error before the exception propagates").
 * A crash mid-pipeline therefore leaves an accurate trail, which is exactly what resume reads.
 *
 * <p>Resume falls out of the same walk: a stage with a SUCCEEDED or SKIPPED row is simply not
 * re-run; an exhausted optional enrichment failure is also terminal but non-fatal. A re-run stage
 * numbers its new attempts after the existing maximum (unique {@code (job_id, stage, attempt)}).
 *
 * <p>Log statements carry ids, stage names, and counts only — never borrower data.
 */
@Component
public class StageRunner {

    private static final Logger log = LoggerFactory.getLogger(StageRunner.class);

    /** The stages a Phase 1 run walks, in execution order (terminal states are not stages). */
    private static final List<ProcessingStatus> PIPELINE =
            List.of(
                    ProcessingStatus.VALIDATING,
                    ProcessingStatus.NORMALIZING,
                    ProcessingStatus.RENDERING,
                    ProcessingStatus.TEXT_EXTRACTION,
                    ProcessingStatus.OCR_PROCESSING,
                    ProcessingStatus.PARSING,
                    ProcessingStatus.CLASSIFYING,
                    ProcessingStatus.SPLITTING,
                    ProcessingStatus.BOUNDARY_EXTRACTION,
                    ProcessingStatus.EXTRACTING,
                    ProcessingStatus.AI_EXTRACTION,
                    ProcessingStatus.FINALIZING,
                    ProcessingStatus.VALIDATING_DATA,
                    ProcessingStatus.AI_REVIEW);

    /**
     * Instant success in Phase 1: VALIDATING, because upload already validated every file (MIME
     * sniffing, caps, encryption, corruption checks happen at ingestion) — the work genuinely
     * happened, just earlier. It still writes its SUCCEEDED row: the stage trail must be complete
     * for resume to reason over it.
     */
    private static final Set<ProcessingStatus> INSTANT_STAGES =
            EnumSet.of(ProcessingStatus.VALIDATING);

    /**
     * Optional enrichment stages may record an exhausted failure without blocking the trusted
     * deterministic pipeline. Their failed attempt trail is terminal for resume purposes.
     */
    private static final Set<ProcessingStatus> NON_FATAL_STAGES =
            EnumSet.of(ProcessingStatus.AI_EXTRACTION, ProcessingStatus.BOUNDARY_EXTRACTION);

    /**
     * Stages whose work is not implemented yet: recorded SKIPPED with an explicit reason. Review
     * finding: NORMALIZING used to record SUCCEEDED for work that never ran — a stage row must
     * never claim success for nothing. It moves here until the Phase 2 worker does the work.
     */
    private static final Map<ProcessingStatus, String> SKIPPED_STAGES =
            Map.of(
                    ProcessingStatus.NORMALIZING, "PHASE_2_NOT_IMPLEMENTED",
                    ProcessingStatus.VALIDATING_DATA, "SPEC_4_NOT_IMPLEMENTED",
                    ProcessingStatus.AI_REVIEW, "SPEC_5_NOT_IMPLEMENTED");

    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final ParserPort parserPort;
    private final EngineResultFinalizerPort engineResultFinalizer;
    private final BehaviorFingerprintPort behaviorFingerprint;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final int maxStageAttempts;
    private final long retryBackoffMs;
    private final long jobTimeBudgetSeconds;
    private final long jobTimeBudgetSecondsPerPage;
    private final JdbcTemplate pages;

    /**
     * Stages the wall-clock budget is checked before: the ones that call the worker. The tail
     * after OCR (classify, split, extract, finalize) is seconds of local work and always runs to
     * completion — failing a job there would throw away the expensive part it already paid for.
     */
    private static final Set<ProcessingStatus> BUDGETED_STAGES =
            EnumSet.of(
                    ProcessingStatus.RENDERING,
                    ProcessingStatus.TEXT_EXTRACTION,
                    ProcessingStatus.OCR_PROCESSING,
                    ProcessingStatus.PARSING);

    public StageRunner(
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages,
            ParserPort parserPort,
            EngineResultFinalizerPort engineResultFinalizer,
            BehaviorFingerprintPort behaviorFingerprint,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${docengine.processing.max-stage-attempts:3}") int maxStageAttempts,
            @Value("${docengine.processing.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${docengine.processing.job-time-budget-seconds:0}") long jobTimeBudgetSeconds,
            @Value("${docengine.processing.job-time-budget-seconds-per-page:0}")
                    long jobTimeBudgetSecondsPerPage,
            DataSource dataSource) {
        this.jobTimeBudgetSeconds = Math.max(0, jobTimeBudgetSeconds);
        this.jobTimeBudgetSecondsPerPage = Math.max(0, jobTimeBudgetSecondsPerPage);
        if (this.jobTimeBudgetSecondsPerPage > 0 && this.jobTimeBudgetSeconds == 0) {
            // The per-page allowance only ever extends a deadline that the flat budget first
            // establishes (see extendForPages / run) — with no flat budget there is no deadline to
            // extend, so this configuration silently does nothing.
            log.warn(
                    "docengine.processing.job-time-budget-seconds-per-page={} is set but"
                            + " docengine.processing.job-time-budget-seconds=0 — the per-page"
                            + " allowance is inert without a flat budget",
                    this.jobTimeBudgetSecondsPerPage);
        }
        this.jobs = jobs;
        this.stages = stages;
        this.parserPort = parserPort;
        this.engineResultFinalizer = engineResultFinalizer;
        this.behaviorFingerprint = behaviorFingerprint;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: each attempt commits on its own even if a caller opened a transaction.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxStageAttempts = maxStageAttempts;
        this.retryBackoffMs = retryBackoffMs;
        // Orchestration does not see the parsing module's PageRepository — a direct JdbcTemplate
        // query, scoped by org_id, is the only way to count a package's pages from here.
        this.pages = new JdbcTemplate(dataSource);
    }

    /**
     * Runs (or resumes) the pipeline for a job. Requires a bound {@link TenantContext} — the
     * dispatcher owns propagating the org onto this thread.
     */
    public void run(UUID jobId) {
        UUID orgId = TenantContext.require();

        // Atomic claim: only one runner may take a queued job. A duplicate dispatch — double
        // resume, replayed message, whatever produced it — updates zero rows and exits here
        // without walking a single stage.
        Integer claimed = transaction.execute(tx -> jobs.claimForRun(jobId, orgId));
        if (claimed == null || claimed == 0) {
            log.warn("run claim lost job={} — another runner owns it or it is not queued", jobId);
            return;
        }

        ProcessingJob job =
                jobs.findByIdAndOrgId(jobId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        List<ProcessingStage> existing = stages.findByJobIdOrderByCreatedAtAsc(jobId);

        // A run that inherits ANY finished stage — a resume from a failure, a regroup's
        // re-extraction — produces rows that are a blend of two behaviors: the inherited stages
        // kept the output of the worker, packs and schemas of the earlier run, while the replayed
        // ones execute under whatever is current now. No single fingerprint describes a blend, so
        // such a run is never stamped, whatever its FINALIZING goes on to do.
        boolean continuation = PIPELINE.stream().anyMatch(stage -> isDone(existing, stage));

        // The budget is per RUN, from now: a resume of a FAILED job gets a fresh budget rather
        // than inheriting an exhausted one from hours ago. Null = unbounded (the default).
        Instant runStart = Instant.now();
        Instant deadline = jobTimeBudgetSeconds > 0 ? runStart.plusSeconds(jobTimeBudgetSeconds) : null;
        if (deadline != null
                && jobTimeBudgetSecondsPerPage > 0
                && isDone(existing, ProcessingStatus.RENDERING)) {
            // A continuation run (resume, regroup re-extraction) never walks RENDERING itself —
            // it hits the isDone `continue` below and would otherwise never see the per-page
            // extension. The pages already exist, so extend right away.
            deadline = extendForPages(runStart, job, orgId);
        }

        ProcessingStatus walking = null;
        try {
            // The run's behavior views are recorded on THIS thread as the loaders serve them. The
            // scope opens INSIDE this try so that even a failure to open one lands the job
            // terminal, and closes on every exit — including the failure return below — so a
            // pooled thread never carries one job's views into the next job's run.
            try (BehaviorViewScope.Scope views = BehaviorViewScope.open()) {
                for (ProcessingStatus stage : PIPELINE) {
                    walking = stage;
                    if (isDone(existing, stage)) {
                        continue;
                    }
                    if (deadline != null
                            && BUDGETED_STAGES.contains(stage)
                            && Instant.now().isAfter(deadline)) {
                        // One document may not hold the queue past its budget. The stage row says
                        // why the job ended; the job is FAILED like any other exhausted stage.
                        recordBudgetExhausted(job, stage, maxAttemptOf(existing, stage) + 1, deadline);
                        markJobFailed(job, stage);
                        return;
                    }
                    if (BUDGETED_STAGES.contains(stage) && jobs.isCancelRequested(job.getId(), orgId)) {
                        recordFailureInOwnTransaction(job, stage, maxAttemptOf(existing, stage) + 1, Instant.now(),
                                StageOutcome.nonRetryableFailure(ErrorCode.JOB_CANCELLED, Map.of("checkpoint", "before " + stage.name())));
                        markJobFailed(job, stage);
                        return;
                    }
                    boolean succeeded;
                    if (SKIPPED_STAGES.containsKey(stage)) {
                        recordSkipped(job, stage, SKIPPED_STAGES.get(stage));
                        succeeded = true;
                    } else if (INSTANT_STAGES.contains(stage)) {
                        succeeded =
                                runAttempt(job, stage, 1, true, continuation, deadline).success();
                    } else {
                        succeeded =
                                runWithRetry(
                                        job,
                                        stage,
                                        maxAttemptOf(existing, stage),
                                        continuation,
                                        deadline);
                    }
                    if (!succeeded && !NON_FATAL_STAGES.contains(stage)) {
                        markJobFailed(job, stage);
                        return;
                    }
                    if (stage == ProcessingStatus.RENDERING
                            && deadline != null
                            && jobTimeBudgetSecondsPerPage > 0) {
                        // Pages are known now: the per-page allowance, never less than the flat floor.
                        deadline = extendForPages(runStart, job, orgId);
                    }
                }
            }
        } catch (RuntimeException e) {
            // A crash in the machine ITSELF (not a port failure — those become outcomes). The
            // job must still land terminal: a job stuck forever in a non-terminal status is a
            // silent failure with no API path forward. Mark FAILED in a fresh transaction, then
            // rethrow for the dispatcher's log line.
            markJobFailed(job, walking);
            throw e;
        }
        markJobAwaitingReview(job);
    }

    /**
     * The per-page extension: the configured per-page allowance times the page count, never less
     * than the flat floor. Called once the pages are known to exist — either RENDERING just
     * succeeded in THIS run, or (a continuation) it succeeded in an earlier run and this one
     * resumes past it.
     */
    private Instant extendForPages(Instant runStart, ProcessingJob job, UUID orgId) {
        Integer count =
                pages.queryForObject(
                        "select count(*) from page where package_id = ? and org_id = ?",
                        Integer.class,
                        job.getPackageId(),
                        orgId);
        long perPage = jobTimeBudgetSecondsPerPage * (count == null ? 0 : count);
        return runStart.plusSeconds(Math.max(jobTimeBudgetSeconds, perPage));
    }

    private void recordBudgetExhausted(
            ProcessingJob job, ProcessingStatus stage, int attempt, Instant deadline) {
        long overBySeconds = Duration.between(deadline, Instant.now()).toSeconds();
        recordFailureInOwnTransaction(
                job,
                stage,
                attempt,
                Instant.now(),
                StageOutcome.nonRetryableFailure(
                        ErrorCode.JOB_TIME_BUDGET_EXCEEDED,
                        Map.of(
                                "budgetSeconds", jobTimeBudgetSeconds,
                                "overBySeconds", overBySeconds,
                                "checkpoint", "before " + stage.name())));
    }

    private boolean runWithRetry(
            ProcessingJob job,
            ProcessingStatus stage,
            int baseAttempt,
            boolean continuation,
            Instant deadline) {
        for (int i = 1; i <= maxStageAttempts; i++) {
            if (i > 1 && retryBackoffMs > 0) {
                // Exponential with jitter (ARCHITECTURE.md 10 rule 2): base * 2^(retry-1), plus
                // up to 50% random jitter so synchronized failures do not retry in lockstep.
                long exponential = retryBackoffMs * (1L << (i - 2));
                long jitter = ThreadLocalRandom.current().nextLong(exponential / 2 + 1);
                try {
                    Thread.sleep(exponential + jitter);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            AttemptResult result =
                    runAttempt(job, stage, baseAttempt + i, false, continuation, deadline);
            if (result.success()) {
                return true;
            }
            if (!result.retryable()) {
                return false;
            }
        }
        return false;
    }

    /**
     * One attempt, one committed transaction: job status transition plus the attempt row with its
     * outcome. A port exception is converted to a FAILED row INSIDE the transaction — its class
     * name only, never its message, which could quote document content.
     */
    private AttemptResult runAttempt(
            ProcessingJob job,
            ProcessingStatus stage,
            int attempt,
            boolean instant,
            boolean continuation,
            Instant deadline) {
        UUID orgId = TenantContext.require();
        // Stage work often runs in a @Transactional domain service (PageClassifier,
        // PackageSplitter, FieldExtractionService), which JOINS this transaction. When such a
        // service throws, Spring marks the shared transaction rollback-only and the FAILED row
        // written below dies with it — the audit record of the failure disappears entirely
        // (Phase 5 review, confirmed-by-test). Every outcome is stashed here so a later
        // transaction failure cannot erase completed stage work from the audit trail.
        AtomicReference<StageOutcome> completed = new AtomicReference<>();
        AtomicReference<Instant> startedAt = new AtomicReference<>();
        try {
            return runAttemptTransactionally(
                    job, stage, attempt, instant, continuation, deadline, orgId, completed, startedAt);
        } catch (RuntimeException e) {
            StageOutcome outcome = completed.get();
            if (outcome == null) {
                throw e;
            }
            StageOutcome durableOutcome =
                    outcome.success()
                            ? new StageOutcome(
                                    false,
                                    null,
                                    ErrorCode.INTERNAL,
                                    Map.of(
                                            "exception", e.getClass().getSimpleName(),
                                            "attempt", attempt))
                            : outcome;
            recordFailureInOwnTransaction(job, stage, attempt, startedAt.get(), durableOutcome);
            if (e instanceof UnexpectedRollbackException && !outcome.success()) {
                return new AttemptResult(false, outcome.retryable());
            }
            throw e;
        }
    }

    /**
     * Re-records a failed attempt after its work transaction rolled back. The domain writes are
     * gone — correctly, they were partial — but the stage row is an audit artifact with a
     * different durability requirement, so it commits on its own.
     */
    private void recordFailureInOwnTransaction(
            ProcessingJob job,
            ProcessingStatus stage,
            int attempt,
            Instant started,
            StageOutcome outcome) {
        transaction.executeWithoutResult(
                tx -> {
                    boolean attemptAlreadyCommitted =
                            stages.findByJobIdOrderByCreatedAtAsc(job.getId()).stream()
                                    .anyMatch(
                                            row ->
                                                    row.getStage() == stage
                                                            && row.getAttempt() == attempt);
                    if (attemptAlreadyCommitted) {
                        return;
                    }
                    ProcessingStage row = new ProcessingStage(job.getId(), stage, attempt);
                    Instant begun = started != null ? started : Instant.now();
                    Instant finished = Instant.now();
                    row.setStartedAt(begun);
                    row.setFinishedAt(finished);
                    row.setDurationMs(Duration.between(begun, finished).toMillis());
                    row.setStatus(StageStatus.FAILED);
                    row.setErrorCode(outcome.errorCode());
                    row.setErrorDetail(toJson(outcome.detail()));
                    stages.save(row);
                });
        log.warn(
                "stage failed job={} stage={} attempt={} code={} (work transaction rolled back)",
                job.getId(),
                stage,
                attempt,
                outcome.errorCode());
    }

    private AttemptResult runAttemptTransactionally(
            ProcessingJob job,
            ProcessingStatus stage,
            int attempt,
            boolean instant,
            boolean continuation,
            Instant deadline,
            UUID orgId,
            AtomicReference<StageOutcome> completed,
            AtomicReference<Instant> startedAt) {
        AttemptResult result =
                transaction.execute(
                        tx -> {
                            ProcessingJob current =
                                    jobs.findByIdAndOrgId(job.getId(), orgId).orElseThrow();
                            moveTo(current, stage);

                            ProcessingStage row = new ProcessingStage(job.getId(), stage, attempt);
                            Instant started = Instant.now();
                            startedAt.set(started);
                            row.setStartedAt(started);
                            row.setStatus(StageStatus.RUNNING);

                            StageOutcome outcome =
                                    instant
                                            ? new StageOutcome(true, null, null, Map.of())
                                            : callPort(
                                                    current, stage, attempt, continuation, deadline);
                            completed.set(outcome);

                            Instant finished = Instant.now();
                            row.setFinishedAt(finished);
                            row.setDurationMs(Duration.between(started, finished).toMillis());
                            if (outcome.skipped()) {
                                row.setStatus(StageStatus.SKIPPED);
                                row.setSkipReason(outcome.skipReason());
                            } else if (outcome.success()) {
                                row.setStatus(StageStatus.SUCCEEDED);
                                row.setOutputDigest(outcome.outputDigest());
                                if (outcome.workerVersions() != null) {
                                    // Contract invariant 5: the version block every worker success
                                    // carries — what makes this stage's parse reproducible.
                                    row.setWorkerVersion(outcome.workerVersions().version());
                                    row.setParserVersions(
                                            toJson(
                                                    new java.util.HashMap<>(
                                                            outcome.workerVersions().libraries())));
                                }
                            } else {
                                row.setStatus(StageStatus.FAILED);
                                row.setErrorCode(outcome.errorCode());
                                row.setErrorDetail(toJson(outcome.detail()));
                                log.warn(
                                        "stage failed job={} stage={} attempt={} code={}",
                                        job.getId(),
                                        stage,
                                        attempt,
                                        outcome.errorCode());
                            }
                            stages.save(row);
                            return new AttemptResult(outcome.success(), outcome.retryable());
                        });
        return java.util.Objects.requireNonNull(result);
    }

    private record AttemptResult(boolean success, boolean retryable) {}

    private StageOutcome callPort(
            ProcessingJob job,
            ProcessingStatus stage,
            int attempt,
            boolean continuation,
            Instant deadline) {
        if (stage == ProcessingStatus.FINALIZING) {
            return finalizeResult(job, attempt, continuation);
        }
        UUID orgId = TenantContext.require();
        try {
            return parserPort.run(
                    new StageRequest(
                            job.getId(),
                            job.getPackageId(),
                            stage,
                            attempt,
                            job.getIdempotencyKey(),
                            deadline,
                            () -> jobs.isCancelRequested(job.getId(), orgId)));
        } catch (RuntimeException e) {
            // Class name only: an arbitrary exception message may quote document content.
            return new StageOutcome(
                    false,
                    null,
                    ErrorCode.INTERNAL,
                    Map.of("exception", e.getClass().getSimpleName(), "attempt", attempt));
        }
    }

    /**
     * FINALIZING, plus the one write that makes parse-once reuse SOUND: the behavior fingerprint.
     *
     * <p>It is stamped HERE, not at job creation, because only here is there a parse to describe.
     * An admission-time stamp records what the loaders would have said when the upload arrived,
     * while the pipeline runs asynchronously and from caches — so one stored fingerprint could
     * describe two different outputs, and reuse under it served a package the engine would not
     * produce today. The stamp instead comes from what THIS run recorded as it ran, and the port
     * returns empty rather than guess whenever anything is unaccounted for.
     *
     * <p>The stamp commits in the SAME transaction as the {@code engine_result} row, so a reuse
     * candidate and its description are never separately durable. It is written unconditionally —
     * value or null — so no earlier value can survive underneath a run that may not be described.
     *
     * <p>NOTE for a future stage: only SKIPPED stages follow FINALIZING today. Any stage added
     * after it that CHANGES the served output must either move this stamp behind it or null it.
     */
    private StageOutcome finalizeResult(
            ProcessingJob job, int stageAttempt, boolean continuation) {
        try {
            EngineResultFinalizerPort.FinalizationResult result =
                    engineResultFinalizer.finalizeResult(
                            job.getId(),
                            job.getPackageId(),
                            job.getParseGeneration(),
                            job.getAttempt());
            stampBehaviorFingerprint(job, continuation);
            return new StageOutcome(true, result.envelopeSha256(), null, Map.of());
        } catch (DomainException failure) {
            return new StageOutcome(false, null, failure.code(), failure.params());
        } catch (RuntimeException failure) {
            // Class name and attempt only: arbitrary exception messages may contain document data.
            return new StageOutcome(
                    false,
                    null,
                    ErrorCode.INTERNAL,
                    Map.of(
                            "exception", failure.getClass().getSimpleName(),
                            "attempt", stageAttempt));
        }
    }

    /**
     * Writes (or clears) the job's behavior fingerprint inside the FINALIZING transaction.
     *
     * <p>A continuation run never even asks: its rows are a blend of the behavior of the stages it
     * inherited and the behavior of the stages it replayed, and no single fingerprint describes a
     * blend. Otherwise the port decides, and its empty answer is written through as NULL — NULL
     * never matches a reuse probe, so an undescribable parse is simply never served again.
     */
    private void stampBehaviorFingerprint(ProcessingJob job, boolean continuation) {
        UUID orgId = TenantContext.require();
        ProcessingJob current = jobs.findByIdAndOrgId(job.getId(), orgId).orElseThrow();
        String fingerprint =
                continuation
                        ? null
                        : behaviorFingerprint
                                .fingerprintForCompletedRun(workerVersionsUsedBy(job.getId()))
                                .orElse(null);
        current.stampBehaviorFingerprint(fingerprint);
        jobs.save(current);
        if (fingerprint == null) {
            log.info(
                    "job={} left unstamped — its parse is not describable, so it can never be"
                            + " reused",
                    job.getId());
        }
    }

    /**
     * Every distinct worker version this job's SUCCEEDED stage rows recorded — the record of which
     * worker actually served the parse, as against the {@code /version} probe's answer for NOW.
     */
    private Set<String> workerVersionsUsedBy(UUID jobId) {
        return stages.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(row -> row.getStatus() == StageStatus.SUCCEEDED)
                .map(ProcessingStage::getWorkerVersion)
                .filter(version -> version != null)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void recordSkipped(ProcessingJob job, ProcessingStatus stage, String reason) {
        UUID orgId = TenantContext.require();
        transaction.executeWithoutResult(
                tx -> {
                    ProcessingJob current = jobs.findByIdAndOrgId(job.getId(), orgId).orElseThrow();
                    moveTo(current, stage);
                    ProcessingStage row = new ProcessingStage(job.getId(), stage, 1);
                    row.setStatus(StageStatus.SKIPPED);
                    row.setSkipReason(reason);
                    stages.save(row);
                });
    }

    private void markJobFailed(ProcessingJob job, ProcessingStatus failedStage) {
        UUID orgId = TenantContext.require();
        transaction.executeWithoutResult(
                tx -> {
                    ProcessingJob current = jobs.findByIdAndOrgId(job.getId(), orgId).orElseThrow();
                    current.setStatus(ProcessingStatus.FAILED);
                    current.setCurrentStage(failedStage);
                    current.setFinishedAt(Instant.now());
                    jobs.save(current);
                });
        log.warn("job failed job={} stage={}", job.getId(), failedStage);
    }

    /**
     * Success end state for Phase 1 is HUMAN_REVIEW_REQUIRED — COMPLETED only arrives with the
     * review flow in Phase 7. finished_at marks the end of automated processing, not of review.
     */
    private void markJobAwaitingReview(ProcessingJob job) {
        UUID orgId = TenantContext.require();
        transaction.executeWithoutResult(
                tx -> {
                    ProcessingJob current = jobs.findByIdAndOrgId(job.getId(), orgId).orElseThrow();
                    current.setStatus(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
                    current.setCurrentStage(null);
                    current.setFinishedAt(Instant.now());
                    jobs.save(current);
                });
    }

    private void moveTo(ProcessingJob job, ProcessingStatus stage) {
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        job.setStatus(stage);
        job.setCurrentStage(stage);
        jobs.save(job);
    }

    /** A successful, skipped, or exhausted non-fatal stage is done — resume never re-runs it. */
    private static boolean isDone(List<ProcessingStage> existing, ProcessingStatus stage) {
        return existing.stream()
                .anyMatch(
                        row ->
                                row.getStage() == stage
                                        && (row.getStatus() == StageStatus.SUCCEEDED
                                                || row.getStatus() == StageStatus.SKIPPED
                                                || (NON_FATAL_STAGES.contains(stage)
                                                        && row.getStatus()
                                                                == StageStatus.FAILED)));
    }

    /** Highest existing attempt number for a stage — where a resumed run continues numbering. */
    private static int maxAttemptOf(List<ProcessingStage> existing, ProcessingStatus stage) {
        return existing.stream()
                .filter(row -> row.getStage() == stage)
                .mapToInt(ProcessingStage::getAttempt)
                .max()
                .orElse(0);
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // The detail is diagnostic sugar; losing it must never lose the FAILED row itself.
            return null;
        }
    }
}
