package com.pragmaticds.rag.lab.corpus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Immutable canonical manifest for one exact corpus selection. */
@Entity
@Immutable
@Table(name = "brain_corpus_snapshot")
public class CorpusSnapshot {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> manifest;

    @Column(name = "manifest_sha256", nullable = false, length = 64, updatable = false)
    private String manifestSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CorpusSnapshot() {}

    public CorpusSnapshot(UUID brainId, Map<String, Object> manifest, String manifestSha256) {
        this.brainId = brainId;
        this.manifest = Map.copyOf(manifest);
        this.manifestSha256 = manifestSha256;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public Map<String, Object> getManifest() { return Map.copyOf(manifest); }
    public String getManifestSha256() { return manifestSha256; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
