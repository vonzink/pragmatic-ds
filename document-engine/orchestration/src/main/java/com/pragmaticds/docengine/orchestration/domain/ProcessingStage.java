package com.pragmaticds.docengine.orchestration.domain;

import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One attempt of one pipeline stage — the row that makes resume possible. Maps
 * {@code processing_stage} (V3) exactly; unique on (job, stage, attempt).
 *
 * <p>{@code errorDetail} and {@code parserVersions} are jsonb held as serialized-JSON Strings in
 * Phase 1: nothing queries inside them yet, and a typed mapping would guess at a Phase 2 worker
 * payload shape that does not exist. Whatever lands in {@code errorDetail} carries ONLY
 * non-sensitive parameters — the write seam ({@code StageRunner}) enforces it.
 */
@Entity
@Table(name = "processing_stage")
public class ProcessingStage extends TenantScopedEntity {

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, updatable = false)
    private ProcessingStatus stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StageStatus status = StageStatus.PENDING;

    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt = 1;

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Stable code from the PII-free taxonomy — never a free-text message. */
    @Enumerated(EnumType.STRING)
    @Column(name = "error_code")
    private ErrorCode errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "worker_version")
    private String workerVersion;

    /** Pinned library versions for this run — what makes a parse reproducible (Phase 2). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parser_versions")
    private String parserVersions;

    @Column(name = "output_digest", length = 64)
    private String outputDigest;

    protected ProcessingStage() {
        // JPA
    }

    public ProcessingStage(UUID jobId, ProcessingStatus stage, int attempt) {
        this.jobId = jobId;
        this.stage = stage;
        this.attempt = attempt;
    }

    public UUID getJobId() {
        return jobId;
    }

    public ProcessingStatus getStage() {
        return stage;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public String getWorkerVersion() {
        return workerVersion;
    }

    public void setWorkerVersion(String workerVersion) {
        this.workerVersion = workerVersion;
    }

    public String getParserVersions() {
        return parserVersions;
    }

    public void setParserVersions(String parserVersions) {
        this.parserVersions = parserVersions;
    }

    public String getOutputDigest() {
        return outputDigest;
    }

    public void setOutputDigest(String outputDigest) {
        this.outputDigest = outputDigest;
    }
}
