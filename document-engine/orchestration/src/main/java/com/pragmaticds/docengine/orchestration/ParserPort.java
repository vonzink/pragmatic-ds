package com.pragmaticds.docengine.orchestration;

import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The seam between the stage machine and whatever executes a stage.
 *
 * <p>Phase 1 shape: the only adapter is the in-process {@link StubParserAdapter}, so the whole
 * job/stage/retry/resume machine is exercisable without the Python worker running. Phase 2 replaces
 * the adapter with typed worker HTTP calls behind this same port — the orchestrator never learns
 * how a stage is executed, only whether it succeeded.
 */
public interface ParserPort {

    StageOutcome run(StageRequest request);

    /**
     * This adapter's identity as a parse-once behavior-fingerprint input, or EMPTY when a parse it
     * performed must never be stamped and therefore never reused.
     *
     * <p>The adapter decides what a stage actually does, so it belongs in the fingerprint as
     * plainly as the worker version does. Leaving it out was a live hazard rather than a
     * theoretical one: the stub is the DEFAULT adapter ({@code matchIfMissing = true}) and only
     * docker-compose exports {@code DOCENGINE_PROCESSING_ADAPTER=worker}, while the {@code
     * /version} probe answers from whatever worker is listening regardless of which adapter is
     * wired. A boot that missed the variable produced stub parses carrying full, healthy-looking
     * fingerprints — and once the configuration was fixed, re-uploads of those bytes were served
     * the stub's output.
     *
     * <p>Empty is the default because a new adapter must OPT IN to being reusable. An adapter
     * whose output is not a real parse of the bytes has no honest identity to give.
     */
    default java.util.Optional<String> behaviorIdentity() {
        return java.util.Optional.empty();
    }

    /**
     * Everything a stage execution may know. The idempotency key rides along so a replayed
     * dispatch can be detected by the executor, not just by the orchestrator.
     *
     * @param deadline when this run's wall-clock budget ends, or null for no budget. An adapter
     *     that loops over worker calls (one OCR call per page) checks {@link #pastDeadline} before
     *     each one and fails the stage {@code JOB_TIME_BUDGET_EXCEEDED} instead of starting work the
     *     budget cannot cover; the runner does the same before each worker-bound stage.
     * @param cancelRequested polled by the same loop checkpoints as {@link #pastDeadline} — a live
     *     read of {@code processing_job.cancel_requested_at}, never a snapshot taken when the
     *     request was built, so a cancel clicked mid-loop is seen on the very next page or file.
     */
    record StageRequest(
            UUID jobId,
            UUID packageId,
            ProcessingStatus stage,
            int attempt,
            String idempotencyKey,
            Instant deadline,
            java.util.function.BooleanSupplier cancelRequested) {

        /** No budget, no cancel poll — the shape every caller before the job budget existed uses. */
        public StageRequest(
                UUID jobId, UUID packageId, ProcessingStatus stage, int attempt, String idempotencyKey) {
            this(jobId, packageId, stage, attempt, idempotencyKey, null, () -> false);
        }

        /** No cancel poll — the shape every caller before the cancel feature existed uses. */
        public StageRequest(
                UUID jobId,
                UUID packageId,
                ProcessingStatus stage,
                int attempt,
                String idempotencyKey,
                Instant deadline) {
            this(jobId, packageId, stage, attempt, idempotencyKey, deadline, () -> false);
        }

        public boolean pastDeadline(Instant now) {
            return deadline != null && now.isAfter(deadline);
        }

        public boolean cancelled() {
            return cancelRequested != null && cancelRequested.getAsBoolean();
        }
    }

    /**
     * The outcome of one stage attempt.
     *
     * @param outputDigest sha256 of the stage's output on success — what makes a replayed stage
     *     detectable — null on failure
     * @param errorCode stable PII-free code on failure, null on success
     * @param detail non-sensitive parameters only (sizes, counts, attempt numbers) — NEVER document
     *     content; this map is serialised straight into {@code processing_stage.error_detail}
     * @param workerVersions the worker's version block on success (contract invariant 5) —
     *     persisted onto {@code processing_stage.worker_version}/{@code parser_versions}; null when
     *     the stage made no worker call (Phase 2 addition — detail is only persisted on FAILURE, so
     *     versions needed a first-class path)
     * @param retryable whether another identical stage attempt may reasonably succeed; permanent
     *     configuration, input, and provider failures set this false
     */
    record StageOutcome(
            boolean success,
            String outputDigest,
            ErrorCode errorCode,
            Map<String, Object> detail,
            WorkerVersions workerVersions,
            String skipReason,
            boolean retryable) {

        /** Pre-Phase-3 shape: an executed outcome, never a dynamic skip. */
        public StageOutcome(
                boolean success,
                String outputDigest,
                ErrorCode errorCode,
                Map<String, Object> detail,
                WorkerVersions workerVersions) {
            this(success, outputDigest, errorCode, detail, workerVersions, null, true);
        }

        /** Pre-Phase-2 shape: no worker call, no versions. */
        public StageOutcome(
                boolean success, String outputDigest, ErrorCode errorCode, Map<String, Object> detail) {
            this(success, outputDigest, errorCode, detail, null, null, true);
        }

        /** A deliberate no-op chosen by the executing adapter at runtime. */
        public static StageOutcome skipped(String reason) {
            return new StageOutcome(true, null, null, Map.of(), null, reason, false);
        }

        /** A permanent/configuration/content failure that another identical attempt cannot fix. */
        public static StageOutcome nonRetryableFailure(
                ErrorCode errorCode, Map<String, Object> detail) {
            return new StageOutcome(false, null, errorCode, detail, null, null, false);
        }

        public boolean skipped() {
            return skipReason != null;
        }
    }

    /** The worker semver plus pinned library versions — what makes a parse reproducible. */
    record WorkerVersions(String version, Map<String, String> libraries) {}
}
