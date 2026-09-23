package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The learned per-(brain, document) retrieval-score multiplier. weight is clamped
 * to [weight-min, weight-max] by the learning job; default 1.0 is neutral. The
 * primary key is the (brain_id, document_id) pair — see {@link BrainSourceWeightId}.
 */
@Entity
@Table(name = "brain_source_weights")
@IdClass(BrainSourceWeightId.class)
public class BrainSourceWeight {

    @Id
    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Id
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private double weight = 1.0;

    @Column(name = "feedback_count", nullable = false)
    private int feedbackCount;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    protected BrainSourceWeight() {}

    public BrainSourceWeight(UUID brainId,
                             UUID documentId,
                             double weight,
                             int feedbackCount,
                             String updatedBy) {
        this.brainId = brainId;
        this.documentId = documentId;
        this.weight = weight;
        this.feedbackCount = feedbackCount;
        this.updatedBy = updatedBy;
    }

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getBrainId() { return brainId; }
    public UUID getDocumentId() { return documentId; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    public int getFeedbackCount() { return feedbackCount; }
    public void setFeedbackCount(int feedbackCount) { this.feedbackCount = feedbackCount; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
