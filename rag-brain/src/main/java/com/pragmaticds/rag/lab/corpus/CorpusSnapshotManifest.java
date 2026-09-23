package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed, value-free version-one corpus snapshot manifest. */
public record CorpusSnapshotManifest(
        int manifestVersion,
        List<CollectionEntry> collections,
        List<DocumentEntry> documents) {

    public static final int CURRENT_VERSION = 1;

    public CorpusSnapshotManifest(List<CollectionEntry> collections, List<DocumentEntry> documents) {
        this(CURRENT_VERSION, collections, documents);
    }

    public CorpusSnapshotManifest {
        if (manifestVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported corpus manifestVersion");
        }
        collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }

    public record CollectionEntry(UUID collectionId, long collectionVersion) {
        public CollectionEntry {
            Objects.requireNonNull(collectionId, "collectionId");
            if (collectionVersion < 1) {
                throw new IllegalArgumentException("collectionVersion must be positive");
            }
        }
    }

    public record DocumentEntry(
            UUID collectionId,
            UUID documentId,
            String documentVersion,
            String contentSha256,
            SourceVisibility visibility,
            SourceTrustLevel trustLevel,
            LocalDate effectiveDate,
            LocalDate expirationDate) {
        public DocumentEntry {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(trustLevel, "trustLevel");
            if (documentVersion == null || documentVersion.isBlank()) {
                throw new IllegalArgumentException("documentVersion is required");
            }
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentSha256 is invalid");
            }
        }
    }
}
