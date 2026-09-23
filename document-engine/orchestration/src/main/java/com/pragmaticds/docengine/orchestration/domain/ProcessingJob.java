package com.pragmaticds.docengine.orchestration.domain;

import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One processing run of a document package — the aggregate the stage machine mutates as it moves.
 * Maps {@code processing_job} (V3) exactly.
 *
 * <p>{@code attempt} counts whole-job dispatch attempts, including resume and deliberate
 * re-extraction. {@code parseGeneration} changes only for deliberate machine-row regeneration;
 * per-stage retry attempts live on {@link ProcessingStage} rows.
 */
@Entity
@Table(name = "processing_job")
public class ProcessingJob extends TenantScopedEntity {

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Unique per (org, key): a replayed submit returns the existing job instead of a duplicate. */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProcessingStatus status = ProcessingStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage")
    private ProcessingStatus currentStage;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    /** Deliberate machine-row generations; unlike attempt, ordinary resume does not increment it. */
    @Column(name = "parse_generation", nullable = false)
    private int parseGeneration = 1;

    /**
     * SHA-256 (lowercase hex) of the canonical behavior document — engine release, parser adapter,
     * worker /version, and the org's winning packs and schemas — that this parse ACTUALLY
     * EXECUTED under (V19).
     *
     * <p>Written once, at successful FINALIZING, by the run that produced the rows, and NULL until
     * then. It deliberately does NOT record what admitted the upload: the pipeline runs
     * asynchronously and from caches, so an admission-time value can describe a behavior the parse
     * never ran under, and one such value could describe two different outputs. NULLED again by
     * {@code claimForReExtract} and {@code claimForResume}, because a regrouped or resumed
     * generation blends the behavior of the stages it inherited with the behavior of the stages it
     * replayed. NULL never matches a reuse probe, so pre-V19 jobs, undescribable parses, and mixed
     * generations all stay non-reusable.
     */
    @Column(name = "behavior_fingerprint", length = 64)
    private String behaviorFingerprint;

    /**
     * Set by {@link ProcessingJobRepository#requestCancel} when the user cancels a non-terminal
     * job. The runner (StageRunner, WorkerParserAdapter) reads it at its next checkpoint — before a
     * budgeted stage, between OCR pages or layout files — never mid-call.
     */
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ProcessingJob() {
        // JPA
    }

    public ProcessingJob(UUID packageId, String idempotencyKey) {
        this.packageId = packageId;
        this.idempotencyKey = idempotencyKey;
    }

    public String getBehaviorFingerprint() {
        return behaviorFingerprint;
    }

    /**
     * Records what the completed run executed under, or {@code null} when it cannot be described.
     *
     * <p>There is deliberately no way to set this at construction: a job that has not run yet has
     * no behavior to describe, and offering the field to the upload path is what let an
     * admission-time guess be stored as a fact.
     */
    public void stampBehaviorFingerprint(String behaviorFingerprint) {
        this.behaviorFingerprint = behaviorFingerprint;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessingStatus status) {
        this.status = status;
    }

    public ProcessingStatus getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(ProcessingStatus currentStage) {
        this.currentStage = currentStage;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getParseGeneration() {
        return parseGeneration;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(Instant cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }
}
