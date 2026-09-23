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
 * One idempotent, run-pinned discussion turn holding exactly two message slots.
 *
 * <p>The slots are <em>derived</em> from the turn's position in the transcript
 * ({@code userOrdinal = 2n - 1}, {@code assistantOrdinal = 2n}) and V34 checks that arithmetic, so
 * two concurrent keys cannot interleave or reorder a transcript: whoever claims sequence
 * {@code n} under the per-run lock owns both of its ordinals.
 *
 * <p>Shares {@link LabRun.Status} because an exchange has the same crash-honest lifecycle as a run:
 * a bounded lease while PROCESSING, one terminal transition, and {@code INTERRUPTED} for an expired
 * lease that must never be replayed against the model without an explicit new key.
 */
@Entity
@Table(name = "lab_discussion_exchange")
public class LabDiscussionExchange {

    /** Every exchange is explicitly marked as resting on still-live prototype dependencies. */
    public static final String PROTOTYPE_LIVE_DEPENDENCIES = "PROTOTYPE_LIVE_DEPENDENCIES";

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "user_ordinal", nullable = false)
    private int userOrdinal;

    @Column(name = "assistant_ordinal", nullable = false)
    private int assistantOrdinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LabRun.Status status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "prototype_limitations", nullable = false, length = 32)
    private String prototypeLimitations = PROTOTYPE_LIVE_DEPENDENCIES;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        // The stored ordinals are the derived slots; V34 refuses any other pair.
        userOrdinal = 2 * sequenceNumber - 1;
        assistantOrdinal = 2 * sequenceNumber;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public int getUserOrdinal() { return userOrdinal; }
    public void setUserOrdinal(int userOrdinal) { this.userOrdinal = userOrdinal; }
    public int getAssistantOrdinal() { return assistantOrdinal; }
    public void setAssistantOrdinal(int assistantOrdinal) { this.assistantOrdinal = assistantOrdinal; }
    public LabRun.Status getStatus() { return status; }
    public void setStatus(LabRun.Status status) { this.status = status; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getPrototypeLimitations() { return prototypeLimitations; }
    public void setPrototypeLimitations(String prototypeLimitations) { this.prototypeLimitations = prototypeLimitations; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getTerminalAt() { return terminalAt; }
    public void setTerminalAt(OffsetDateTime terminalAt) { this.terminalAt = terminalAt; }
}
