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
 * One append-only trace of an ask pipeline run — plan, retrieved context, answer, and the
 * decisions in between. Written once by {@code RagTraceService}; the feedback services only read
 * it (brain/conversation ownership checks) and attach their own rows.
 *
 * <p><b>Immutable in the mapping, deliberately.</b> The eight JSON-mapped fields below are
 * mutable {@code Map}/{@code List} values behind {@code @JdbcTypeCode(SqlTypes.JSON)}: Hibernate
 * builds its dirty-check snapshot by round-tripping them through the Jackson format mapper
 * ({@code Long}→{@code Integer}, {@code BigDecimal}→{@code Double}), so the snapshot never equals
 * the live value and every flush after the insert — including a feedback transaction that merely
 * loaded the trace — composed a spurious full-row {@code UPDATE} (commit {@code 6517c35}).
 * {@code @Immutable} plus {@code updatable = false} stops that {@code UPDATE} being composed at
 * all.
 */
@Entity
@Immutable
@Table(name = "rag_traces")
public class RagTrace {

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

    @Column(updatable = false, length = 80)
    private String intent;

    @Column(name = "response_type", updatable = false, length = 40)
    private String responseType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_plan", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> retrievalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_context", updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> retrievedContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "side_evidence", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> sideEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> citations;

    @Column(name = "final_answer", updatable = false, columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "confidence_score", updatable = false)
    private Double confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "clarification_decision", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> clarificationDecision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_facts", updatable = false, columnDefinition = "jsonb")
    private List<String> missingFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collected_facts", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> collectedFacts;

    @Column(name = "visibility_filter", updatable = false, length = 20)
    private String visibilityFilter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confidence_reason", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> confidenceReason;

    @Column(name = "validation_outcome", updatable = false, length = 120)
    private String validationOutcome;

    @Column(name = "human_escalation_required", nullable = false, updatable = false)
    private boolean humanEscalationRequired;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }
    public String getRewrittenQuestion() { return rewrittenQuestion; }
    public void setRewrittenQuestion(String rewrittenQuestion) { this.rewrittenQuestion = rewrittenQuestion; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getResponseType() { return responseType; }
    public void setResponseType(String responseType) { this.responseType = responseType; }
    public Map<String, Object> getRetrievalPlan() { return retrievalPlan; }
    public void setRetrievalPlan(Map<String, Object> retrievalPlan) { this.retrievalPlan = retrievalPlan; }
    public List<Map<String, Object>> getRetrievedContext() { return retrievedContext; }
    public void setRetrievedContext(List<Map<String, Object>> retrievedContext) { this.retrievedContext = retrievedContext; }
    public Map<String, Object> getSideEvidence() { return sideEvidence; }
    public void setSideEvidence(Map<String, Object> sideEvidence) { this.sideEvidence = sideEvidence; }
    public List<Map<String, Object>> getCitations() { return citations; }
    public void setCitations(List<Map<String, Object>> citations) { this.citations = citations; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }
    public Map<String, Object> getClarificationDecision() { return clarificationDecision; }
    public void setClarificationDecision(Map<String, Object> clarificationDecision) { this.clarificationDecision = clarificationDecision; }
    public List<String> getMissingFacts() { return missingFacts; }
    public void setMissingFacts(List<String> missingFacts) { this.missingFacts = missingFacts; }
    public Map<String, Object> getCollectedFacts() { return collectedFacts; }
    public void setCollectedFacts(Map<String, Object> collectedFacts) { this.collectedFacts = collectedFacts; }
    public String getVisibilityFilter() { return visibilityFilter; }
    public void setVisibilityFilter(String visibilityFilter) { this.visibilityFilter = visibilityFilter; }
    public Map<String, Object> getConfidenceReason() { return confidenceReason; }
    public void setConfidenceReason(Map<String, Object> confidenceReason) { this.confidenceReason = confidenceReason; }
    public String getValidationOutcome() { return validationOutcome; }
    public void setValidationOutcome(String validationOutcome) { this.validationOutcome = validationOutcome; }
    public boolean isHumanEscalationRequired() { return humanEscalationRequired; }
    public void setHumanEscalationRequired(boolean humanEscalationRequired) { this.humanEscalationRequired = humanEscalationRequired; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
