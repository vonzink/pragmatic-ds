package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One caller-visible submission and the runs it produced.
 *
 * <p>The group owns the external idempotency key; its members own execution. That split is what
 * lets two releases of the same instance coexist in one comparison: each member carries an
 * internal run key derived from the group, so V34's per-instance run-key uniqueness is never
 * violated by a caller submitting one key for several runs.
 *
 * <p>A comparison additionally records the digest of everything it holds equal. "These runs differ
 * only by model" is then a checkable claim rather than a description in a comment, and V39 refuses
 * a comparison that does not state its basis.
 */
@Entity
@Table(name = "lab_run_group")
public class LabRunGroup {

    /** What varies across members. INDEPENDENT groups vary nothing and declare nothing. */
    public enum Mode { INDEPENDENT, COMPARISON }

    /** The dimension a comparison varies; every other input is pinned equal and hashed. */
    public enum ComparisonDimension { MODEL, RELEASE, INSTANCE }

    /**
     * Rolled up from members, never assigned by one of them. PARTIAL is a real outcome rather
     * than a failure: a comparison where one model errored still tells you about the others.
     */
    public enum Status { QUEUED, PROCESSING, SUCCEEDED, PARTIAL, FAILED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode = Mode.INDEPENDENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_dimension", length = 16)
    private ComparisonDimension comparisonDimension;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;

    @Column(name = "comparison_basis_sha256", length = 64)
    private String comparisonBasisSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.QUEUED;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    @Column(name = "cancellation_requested_at")
    private OffsetDateTime cancellationRequestedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public ComparisonDimension getComparisonDimension() { return comparisonDimension; }
    public void setComparisonDimension(ComparisonDimension d) { this.comparisonDimension = d; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestSha256() { return requestSha256; }
    public void setRequestSha256(String requestSha256) { this.requestSha256 = requestSha256; }
    public String getComparisonBasisSha256() { return comparisonBasisSha256; }
    public void setComparisonBasisSha256(String sha) { this.comparisonBasisSha256 = sha; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(OffsetDateTime terminalAt) { this.terminalAt = terminalAt; }
    public OffsetDateTime getCancellationRequestedAt() { return cancellationRequestedAt; }
    public void setCancellationRequestedAt(OffsetDateTime at) { this.cancellationRequestedAt = at; }
}
