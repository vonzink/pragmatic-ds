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
 * One captured rating (👍/👎) on an answer identified by its trace. Written by
 * FeedbackService; capture runs regardless of the per-brain learning switch so
 * signal accumulates while learning is off. Idempotent per (trace_id, session_id).
 */
@Entity
@Table(name = "rag_answer_feedback")
public class RagAnswerFeedback {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(nullable = false, length = 8)
    private String rating;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * When the learning job consumed this row into a weight-adjustment decision.
     * NULL until aggregated; set exactly once so a row can never be re-tallied
     * (V27). The job selects only rows where this is NULL.
     */
    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    protected RagAnswerFeedback() {}

    public RagAnswerFeedback(UUID traceId,
                             UUID brainId,
                             String rating,
                             String source,
                             String reason,
                             String sessionId,
                             String createdBy) {
        this.traceId = traceId;
        this.brainId = brainId;
        this.rating = rating;
        this.source = source;
        this.reason = reason;
        this.sessionId = sessionId;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getTraceId() { return traceId; }
    public UUID getBrainId() { return brainId; }
    public String getRating() { return rating; }
    public String getSource() { return source; }
    public String getReason() { return reason; }
    public String getSessionId() { return sessionId; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime processedAt) { this.processedAt = processedAt; }
}
