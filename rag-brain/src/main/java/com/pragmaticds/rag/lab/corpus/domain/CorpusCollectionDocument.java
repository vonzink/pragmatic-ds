package com.pragmaticds.rag.lab.corpus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** One document reference in a mutable collection membership list. */
@Entity
@Table(name = "brain_corpus_collection_document")
public class CorpusCollectionDocument {

    @EmbeddedId
    private Id id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private OffsetDateTime addedAt;

    protected CorpusCollectionDocument() {}

    public CorpusCollectionDocument(UUID collectionId, UUID brainId, UUID documentId) {
        this.id = new Id(collectionId, documentId);
        this.brainId = brainId;
    }

    @PrePersist
    void onCreate() {
        if (addedAt == null) {
            addedAt = OffsetDateTime.now();
        }
    }

    public Id getId() { return id; }
    public UUID getCollectionId() { return id.collectionId; }
    public UUID getBrainId() { return brainId; }
    public UUID getDocumentId() { return id.documentId; }
    public OffsetDateTime getAddedAt() { return addedAt; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "collection_id", nullable = false, updatable = false)
        private UUID collectionId;

        @Column(name = "document_id", nullable = false, updatable = false)
        private UUID documentId;

        protected Id() {}

        public Id(UUID collectionId, UUID documentId) {
            this.collectionId = collectionId;
            this.documentId = documentId;
        }

        public UUID getCollectionId() { return collectionId; }
        public UUID getDocumentId() { return documentId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Id that)) return false;
            return Objects.equals(collectionId, that.collectionId)
                    && Objects.equals(documentId, that.documentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(collectionId, documentId);
        }
    }
}
