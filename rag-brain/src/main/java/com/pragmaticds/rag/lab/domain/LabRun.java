package com.pragmaticds.rag.lab.domain;

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
 * One Lab run: an idempotency-keyed claim to analyze one exact engine revision under one exact
 * stored release.
 *
 * <p>The Lab does not invent a parallel analyzer identity. On success {@link #getAnalysisRunId()}
 * pins the exact existing {@code analysis_runs.id} the analyzer wrote (V33), which is why the
 * column is nullable while {@link Status#PROCESSING} — the analyzer row does not exist yet —
 * unique when present, and mandatory once {@link Status#SUCCEEDED}. V34 enforces all three.
 *
 * <p>Crash-honest rather than exactly-once: a {@link Status#PROCESSING} run always holds a bounded
 * lease, and an expired lease becomes {@link Status#INTERRUPTED} without replaying the analyzer. A
 * database trigger refuses a second terminal transition and refuses any change to the run's brain,
 * instance, idempotency key, release, or registration.
 */
@Entity
@Table(name = "lab_run")
public class LabRun {

    /**
     * The bounded Lab lifecycle. {@code INTERRUPTED} is a first-class terminal state, not an
     * error dressed up as one: it means an expired lease was reclaimed without ever knowing
     * whether the provider ran, so a retry must use an explicit new key.
     */
    public enum Status {
        /** Created by a run group and waiting to be claimed; holds no lease and spends nothing. */
        QUEUED,
        PROCESSING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED,
        /** Cancelled before dispatch. A PROCESSING member is never cancelled — see the javadoc. */
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "instance_slug", nullable = false, length = 32)
    private String instanceSlug;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    @Column(name = "registration_id", nullable = false)
    private UUID registrationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** A stable, value-free failure code. Never a provider message, body, or cause. */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "analysis_run_id")
    private UUID analysisRunId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    /**
     * Group membership. Every run is a member of exactly one group, including the prototype's
     * single runs and every historical run V39 backfilled: one shape rather than two.
     */
    @Column(name = "run_group_id", nullable = false)
    private UUID runGroupId;

    @Column(name = "member_index", nullable = false)
    private int memberIndex;

    /** The frozen corpus this run retrieved from; null for a legacy scope-retrieval run. */
    @Column(name = "corpus_snapshot_id")
    private UUID corpusSnapshotId;

    /**
     * What the caller asked to run against, recorded separately from what answered. A run that
     * silently answered from another model must be visible here rather than inferred.
     */
    @Column(name = "requested_provider", length = 40)
    private String requestedProvider;

    @Column(name = "requested_model", length = 160)
    private String requestedModel;

    /** The immutable price list this run is costed against; never a live lookup. */
    @Column(name = "pricing_version_id", nullable = false)
    private UUID pricingVersionId;

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
    public String getInstanceSlug() { return instanceSlug; }
    public void setInstanceSlug(String instanceSlug) { this.instanceSlug = instanceSlug; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public UUID getRegistrationId() { return registrationId; }
    public void setRegistrationId(UUID registrationId) { this.registrationId = registrationId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public UUID getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(UUID analysisRunId) { this.analysisRunId = analysisRunId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(OffsetDateTime terminalAt) { this.terminalAt = terminalAt; }
    public UUID getRunGroupId() { return runGroupId; }
    public void setRunGroupId(UUID runGroupId) { this.runGroupId = runGroupId; }
    public int getMemberIndex() { return memberIndex; }
    public void setMemberIndex(int memberIndex) { this.memberIndex = memberIndex; }
    public UUID getCorpusSnapshotId() { return corpusSnapshotId; }
    public void setCorpusSnapshotId(UUID corpusSnapshotId) { this.corpusSnapshotId = corpusSnapshotId; }
    public String getRequestedProvider() { return requestedProvider; }
    public void setRequestedProvider(String requestedProvider) { this.requestedProvider = requestedProvider; }
    public String getRequestedModel() { return requestedModel; }
    public void setRequestedModel(String requestedModel) { this.requestedModel = requestedModel; }
    public UUID getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(UUID pricingVersionId) { this.pricingVersionId = pricingVersionId; }
}
