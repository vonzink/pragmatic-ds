package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit record of one source-weight change (nothing silent) and the review queue.
 * newWeight is null while PENDING; proposedWeight holds what the job wanted for a
 * PENDING row. status is a {@link WeightEventStatus} name. document_id has no FK so
 * the audit trail survives document deletion.
 */
@Entity
@Table(name = "brain_source_weight_events")
public class BrainSourceWeightEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "old_weight")
    private Double oldWeight;

    @Column(name = "new_weight")
    private Double newWeight;

    @Column(name = "proposed_weight")
    private Double proposedWeight;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BrainSourceWeightEvent() {}

    public BrainSourceWeightEvent(UUID brainId,
                                  UUID documentId,
                                  Double oldWeight,
                                  Double newWeight,
                                  Double proposedWeight,
                                  int evidenceCount,
                                  String status,
                                  String reason,
                                  String actor) {
        this.brainId = brainId;
        this.documentId = documentId;
        this.oldWeight = oldWeight;
        this.newWeight = newWeight;
        this.proposedWeight = proposedWeight;
        this.evidenceCount = evidenceCount;
        this.status = status;
        this.reason = reason;
        this.actor = actor;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public UUID getDocumentId() { return documentId; }
    public Double getOldWeight() { return oldWeight; }
    public Double getNewWeight() { return newWeight; }
    public void setNewWeight(Double newWeight) { this.newWeight = newWeight; }
    public Double getProposedWeight() { return proposedWeight; }
    public int getEvidenceCount() { return evidenceCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
