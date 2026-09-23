package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One revision of an analyzer's base prompt for one brain. PUBLISHED rows are append-only
 * (NULL content = revert-to-pack marker); the single DRAFT row is replaced on every save.
 */
@Entity
@Table(name = "brain_analyzer_prompt_revisions")
public class AnalyzerPromptRevision {

    public enum State { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "analyzer_slug", length = 64, nullable = false)
    private String analyzerSlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private State state;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    protected AnalyzerPromptRevision() {}

    public AnalyzerPromptRevision(UUID brainId, String analyzerSlug, State state,
                                  String content, String createdBy) {
        this.brainId = brainId;
        this.analyzerSlug = analyzerSlug;
        this.state = state;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getAnalyzerSlug() { return analyzerSlug; }
    public State getState() { return state; }
    public String getContent() { return content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
