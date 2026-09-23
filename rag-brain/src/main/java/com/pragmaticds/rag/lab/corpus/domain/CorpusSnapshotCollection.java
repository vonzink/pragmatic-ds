package com.pragmaticds.rag.lab.corpus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Immutable ordered reference to a collection version inside a snapshot. */
@Entity
@Immutable
@Table(name = "brain_corpus_snapshot_collection")
public class CorpusSnapshotCollection {

    @EmbeddedId
    private Id id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "collection_id", nullable = false, updatable = false)
    private UUID collectionId;

    @Column(name = "collection_version", nullable = false, updatable = false)
    private long collectionVersion;

    protected CorpusSnapshotCollection() {}

    public CorpusSnapshotCollection(UUID snapshotId, UUID brainId, int position,
                                    UUID collectionId, long collectionVersion) {
        this.id = new Id(snapshotId, position);
        this.brainId = brainId;
        this.collectionId = collectionId;
        this.collectionVersion = collectionVersion;
    }

    public Id getId() { return id; }
    public UUID getSnapshotId() { return id.snapshotId; }
    public UUID getBrainId() { return brainId; }
    public int getPosition() { return id.position; }
    public UUID getCollectionId() { return collectionId; }
    public long getCollectionVersion() { return collectionVersion; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "snapshot_id", nullable = false, updatable = false)
        private UUID snapshotId;

        @Column(nullable = false, updatable = false)
        private int position;

        protected Id() {}

        public Id(UUID snapshotId, int position) {
            this.snapshotId = snapshotId;
            this.position = position;
        }

        public UUID getSnapshotId() { return snapshotId; }
        public int getPosition() { return position; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Id that)) return false;
            return position == that.position && Objects.equals(snapshotId, that.snapshotId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, position);
        }
    }
}
