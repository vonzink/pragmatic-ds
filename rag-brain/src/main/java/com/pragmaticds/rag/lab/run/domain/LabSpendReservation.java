package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The conservative maximum held against a brain's budget while one run is in flight.
 *
 * <p>Reserving the maximum rather than the estimate is what lets a budget rejection happen before
 * dispatch instead of after the bill. A run that comes in under its reservation releases the
 * difference when it consumes; a run that never dispatches releases the whole thing.
 */
@Entity
@Table(name = "lab_spend_reservation")
public class LabSpendReservation {

    /** One transition only, and never both: the money was either spent or given back. */
    public enum Status { RESERVED, CONSUMED, RELEASED }

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "reserved_max_usd", nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedMaxUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RESERVED;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    protected LabSpendReservation() {}

    public LabSpendReservation(UUID runId, UUID brainId, BigDecimal reservedMaxUsd) {
        this.runId = runId;
        this.brainId = brainId;
        this.reservedMaxUsd = reservedMaxUsd;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void settle(Status terminal) {
        if (terminal != Status.CONSUMED && terminal != Status.RELEASED) {
            throw new IllegalArgumentException("a reservation settles as CONSUMED or RELEASED");
        }
        this.status = terminal;
        this.terminalAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getBrainId() { return brainId; }
    public BigDecimal getReservedMaxUsd() { return reservedMaxUsd; }
    public Status getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getTerminalAt() { return terminalAt; }
}
