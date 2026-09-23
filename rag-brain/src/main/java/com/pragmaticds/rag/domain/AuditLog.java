package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full compliance/debugging audit record for every AI interaction.
 * The user question is stored AFTER PII redaction — see PiiRedactionService.
 *
 * <p><b>Immutable in the mapping, deliberately.</b> {@code retrievedContext} is a mutable
 * collection behind {@code @JdbcTypeCode(SqlTypes.JSON)}: Hibernate builds its dirty-check
 * snapshot by round-tripping it through the Jackson format mapper ({@code Long}→{@code Integer},
 * {@code BigDecimal}→{@code Double}), so the snapshot never equals the live value and every
 * flush after the insert composed a spurious full-row {@code UPDATE} — a compliance record
 * silently rewriting itself (commit {@code 6517c35}). {@code @Immutable} plus
 * {@code updatable = false} stops that {@code UPDATE} being composed at all. The only writer is
 * {@code AuditLogService}, which inserts and never edits.
 */
@Entity
@Immutable
@Table(name = "ai_audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "user_question", nullable = false, updatable = false, columnDefinition = "text")
    private String userQuestion;

    @Column(name = "rewritten_question", updatable = false, columnDefinition = "text")
    private String rewrittenQuestion;

    /** The retrieved chunks with their scores, as sent to the prompt builder. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_context", updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> retrievedContext;

    @Column(name = "final_prompt", updatable = false, columnDefinition = "text")
    private String finalPrompt;

    @Column(name = "final_answer", updatable = false, columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "model_provider", updatable = false, length = 50)
    private String modelProvider;

    @Column(name = "model_name", updatable = false, length = 100)
    private String modelName;

    @Column(name = "confidence_score", updatable = false)
    private Double confidenceScore;

    @Column(name = "fallback_used", nullable = false, updatable = false)
    private boolean fallbackUsed;

    @Column(name = "human_escalation_required", nullable = false, updatable = false)
    private boolean humanEscalationRequired;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }

    public String getRewrittenQuestion() { return rewrittenQuestion; }
    public void setRewrittenQuestion(String rewrittenQuestion) { this.rewrittenQuestion = rewrittenQuestion; }

    public List<Map<String, Object>> getRetrievedContext() { return retrievedContext; }
    public void setRetrievedContext(List<Map<String, Object>> retrievedContext) { this.retrievedContext = retrievedContext; }

    public String getFinalPrompt() { return finalPrompt; }
    public void setFinalPrompt(String finalPrompt) { this.finalPrompt = finalPrompt; }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }

    public boolean isHumanEscalationRequired() { return humanEscalationRequired; }
    public void setHumanEscalationRequired(boolean humanEscalationRequired) { this.humanEscalationRequired = humanEscalationRequired; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
