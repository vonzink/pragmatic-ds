package com.pragmaticds.rag.lab.corpus.domain;

import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable document-version fact frozen into one snapshot collection. */
@Entity
@Immutable
@Table(name = "brain_corpus_snapshot_document")
public class CorpusSnapshotDocument {

    @EmbeddedId
    private Id id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "document_version", nullable = false, length = 50, updatable = false)
    private String documentVersion;

    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SourceVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20, updatable = false)
    private SourceTrustLevel trustLevel;

    @Column(name = "effective_date", updatable = false)
    private LocalDate effectiveDate;

    @Column(name = "expiration_date", updatable = false)
    private LocalDate expirationDate;

    protected CorpusSnapshotDocument() {}

    public CorpusSnapshotDocument(UUID snapshotId, UUID brainId, UUID collectionId,
                                  UUID documentId, String documentVersion, String contentSha256,
                                  SourceVisibility visibility, SourceTrustLevel trustLevel,
                                  LocalDate effectiveDate, LocalDate expirationDate) {
        this.id = new Id(snapshotId, collectionId, documentId);
        this.brainId = brainId;
        this.documentVersion = documentVersion;
        this.contentSha256 = contentSha256;
        this.visibility = visibility;
        this.trustLevel = trustLevel;
        this.effectiveDate = effectiveDate;
        this.expirationDate = expirationDate;
    }

    public Id getId() { return id; }
    public UUID getSnapshotId() { return id.snapshotId; }
    public UUID getBrainId() { return brainId; }
    public UUID getCollectionId() { return id.collectionId; }
    public UUID getDocumentId() { return id.documentId; }
    public String getDocumentVersion() { return documentVersion; }
    public String getContentSha256() { return contentSha256; }
    public SourceVisibility getVisibility() { return visibility; }
    public SourceTrustLevel getTrustLevel() { return trustLevel; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getExpirationDate() { return expirationDate; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "snapshot_id", nullable = false, updatable = false)
        private UUID snapshotId;

        @Column(name = "collection_id", nullable = false, updatable = false)
        private UUID collectionId;

        @Column(name = "document_id", nullable = false, updatable = false)
        private UUID documentId;

        protected Id() {}

        public Id(UUID snapshotId, UUID collectionId, UUID documentId) {
            this.snapshotId = snapshotId;
            this.collectionId = collectionId;
            this.documentId = documentId;
        }

        public UUID getSnapshotId() { return snapshotId; }
        public UUID getCollectionId() { return collectionId; }
        public UUID getDocumentId() { return documentId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Id that)) return false;
            return Objects.equals(snapshotId, that.snapshotId)
                    && Objects.equals(collectionId, that.collectionId)
                    && Objects.equals(documentId, that.documentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, collectionId, documentId);
        }
    }
}
