package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only revision of an owner-editable rule block. NULL content = revert-to-pack marker. */
@Entity
@Table(name = "brain_rule_revisions")
public class RuleRevision {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "rule_key", length = 32, nullable = false)
    private String ruleKey;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    protected RuleRevision() {}

    public RuleRevision(UUID brainId, String ruleKey, String content, String createdBy) {
        this.brainId = brainId;
        this.ruleKey = ruleKey;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getRuleKey() { return ruleKey; }
    public String getContent() { return content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
