package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "brain_profiles")
public class BrainProfile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false, unique = true)
    private UUID brainId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BrainMode mode = BrainMode.PUBLIC_SITE;

    @Column(nullable = false, columnDefinition = "text")
    private String purpose = "Answer questions from approved sources.";

    @Column(nullable = false, length = 120)
    private String audience = "public visitor";

    @Column(nullable = false, columnDefinition = "text")
    private String personality = "Conversational, concise, source-grounded assistant.";

    @Column(nullable = false, length = 80)
    private String tone = "professional";

    @Column(name = "expertise_level", nullable = false, length = 80)
    private String expertiseLevel = "intermediate";

    @Column(name = "answer_length", nullable = false, length = 40)
    private String answerLength = "balanced";

    @Column(name = "confidence_target", nullable = false)
    private double confidenceTarget = 0.90;

    @Column(name = "retrieval_confidence_threshold")
    private Double retrievalConfidenceThreshold; // nullable: null => global default

    /**
     * Floor on the model's self-reported confidence, applied after the answer
     * comes back. Nullable: null => no gate. Distinct from the retrieval threshold
     * above, which gates the top chunk score before the model runs.
     */
    @Column(name = "answer_confidence_floor")
    private Double answerConfidenceFloor;

    @Column(name = "clarification_policy", nullable = false, columnDefinition = "text")
    private String clarificationPolicy = "Ask one focused clarifying question when required facts are missing.";

    @Column(name = "escalation_policy", nullable = false, columnDefinition = "text")
    private String escalationPolicy = "Escalate personalized, unsupported, sensitive, or low-confidence requests.";

    @Column(name = "citation_policy", nullable = false, length = 80)
    private String citationPolicy = "required_when_sources_used";

    @Column(name = "cta_policy", nullable = false, columnDefinition = "text")
    private String ctaPolicy = "Recommend relevant pages or a human handoff when useful.";

    @Column(nullable = false, columnDefinition = "text")
    private String disclaimer = "This answer is generated from approved source context and may be incomplete.";

    @Column(name = "public_enabled", nullable = false)
    private boolean publicEnabled = false;

    @Column(name = "public_token_hash", length = 128)
    private String publicTokenHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_domains", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedDomains = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public BrainMode getMode() { return mode; }
    public void setMode(BrainMode mode) { this.mode = mode; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }
    public String getExpertiseLevel() { return expertiseLevel; }
    public void setExpertiseLevel(String expertiseLevel) { this.expertiseLevel = expertiseLevel; }
    public String getAnswerLength() { return answerLength; }
    public void setAnswerLength(String answerLength) { this.answerLength = answerLength; }
    public double getConfidenceTarget() { return confidenceTarget; }
    public void setConfidenceTarget(double confidenceTarget) { this.confidenceTarget = confidenceTarget; }
    public Double getRetrievalConfidenceThreshold() { return retrievalConfidenceThreshold; }
    public void setRetrievalConfidenceThreshold(Double retrievalConfidenceThreshold) { this.retrievalConfidenceThreshold = retrievalConfidenceThreshold; }
    public Double getAnswerConfidenceFloor() { return answerConfidenceFloor; }
    public void setAnswerConfidenceFloor(Double answerConfidenceFloor) { this.answerConfidenceFloor = answerConfidenceFloor; }
    public String getClarificationPolicy() { return clarificationPolicy; }
    public void setClarificationPolicy(String clarificationPolicy) { this.clarificationPolicy = clarificationPolicy; }
    public String getEscalationPolicy() { return escalationPolicy; }
    public void setEscalationPolicy(String escalationPolicy) { this.escalationPolicy = escalationPolicy; }
    public String getCitationPolicy() { return citationPolicy; }
    public void setCitationPolicy(String citationPolicy) { this.citationPolicy = citationPolicy; }
    public String getCtaPolicy() { return ctaPolicy; }
    public void setCtaPolicy(String ctaPolicy) { this.ctaPolicy = ctaPolicy; }
    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }
    public boolean isPublicEnabled() { return publicEnabled; }
    public void setPublicEnabled(boolean publicEnabled) { this.publicEnabled = publicEnabled; }
    public String getPublicTokenHash() { return publicTokenHash; }
    public void setPublicTokenHash(String publicTokenHash) { this.publicTokenHash = publicTokenHash; }
    public List<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(List<String> allowedDomains) { this.allowedDomains = allowedDomains; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
