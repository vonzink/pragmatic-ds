package com.pragmaticds.rag.lab.corpus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mutable brain-scoped identity for a reusable corpus membership list. */
@Entity
@Table(name = "brain_corpus_collection")
public class CorpusCollection {

    public enum State {
        ACTIVE,
        DISABLED
    }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(nullable = false, length = 48, updatable = false)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.ACTIVE;

    @Column(name = "collection_version", nullable = false)
    private long collectionVersion = 1;

    @Column(name = "cloned_from_id", updatable = false)
    private UUID clonedFromId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CorpusCollection() {}

    public CorpusCollection(UUID brainId, String slug, String displayName, UUID clonedFromId) {
        this.brainId = brainId;
        this.slug = slug;
        this.displayName = displayName;
        this.clonedFromId = clonedFromId;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public State getState() { return state; }
    public long getCollectionVersion() { return collectionVersion; }
    public UUID getClonedFromId() { return clonedFromId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** Advances one successful semantic membership change. */
    public void advanceVersion() {
        collectionVersion++;
    }

    /** Disabling is itself a versioned semantic change. */
    public void disableAndAdvanceVersion() {
        state = State.DISABLED;
        collectionVersion++;
    }
}
