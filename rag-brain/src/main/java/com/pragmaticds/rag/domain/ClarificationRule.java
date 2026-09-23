package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "clarification_rules")
public class ClarificationRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(nullable = false, length = 120)
    private String topic;

    @Column(nullable = false, length = 80)
    private String intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_facts", nullable = false, columnDefinition = "jsonb")
    private List<String> requiredFacts = new ArrayList<>();

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(nullable = false)
    private int priority = 100;

    @Column(name = "required_for_public", nullable = false)
    private boolean requiredForPublic = true;

    @Column(name = "optional_for_general", nullable = false)
    private boolean optionalForGeneral;

    @Column(nullable = false)
    private boolean active = true;

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
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<String> getRequiredFacts() { return requiredFacts; }
    public void setRequiredFacts(List<String> requiredFacts) { this.requiredFacts = requiredFacts; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isRequiredForPublic() { return requiredForPublic; }
    public void setRequiredForPublic(boolean requiredForPublic) { this.requiredForPublic = requiredForPublic; }
    public boolean isOptionalForGeneral() { return optionalForGeneral; }
    public void setOptionalForGeneral(boolean optionalForGeneral) { this.optionalForGeneral = optionalForGeneral; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
