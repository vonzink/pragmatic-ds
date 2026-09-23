package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Safe admin-only corpus metadata; chunk bodies and embeddings are never represented. */
public final class CorpusCollectionDtos {
    private CorpusCollectionDtos() {}

    public record CreateCollectionRequest(String slug, String displayName) {}
    public record ReplaceMembershipRequest(long expectedVersion, List<UUID> documentIds) {}
    public record CloneCollectionRequest(String slug, String displayName) {}
    public record SnapshotCollectionRequest(UUID collectionId, long expectedVersion) {}
    public record CreateSnapshotRequest(List<SnapshotCollectionRequest> collections) {}

    public record DocumentMetadata(
            UUID documentId, String title, String documentVersion, String contentSha256) {}

    public record CollectionSummary(
            UUID id, UUID brainId, String slug, String displayName, String state,
            long version, UUID clonedFromId, int documentCount) {}

    public record CollectionDetail(
            UUID id, UUID brainId, String slug, String displayName, String state,
            long version, UUID clonedFromId, int documentCount,
            List<DocumentMetadata> documents) {}

    public record SnapshotCollectionMetadata(UUID collectionId, long collectionVersion) {}

    public record SnapshotDocumentMetadata(
            UUID collectionId, UUID documentId, String title, String documentVersion,
            String contentSha256, String visibility, String trustLevel,
            LocalDate effectiveDate, LocalDate expirationDate) {}

    public record SnapshotDetail(
            UUID snapshotId, UUID brainId, String manifestSha256,
            List<SnapshotCollectionMetadata> collections,
            List<SnapshotDocumentMetadata> documents) {}

    static CollectionSummary summary(CollectionView view) {
        return new CollectionSummary(view.id(), view.brainId(), view.slug(), view.displayName(),
                view.state(), view.version(), view.clonedFromId(), view.documentIds().size());
    }

    static CollectionDetail detail(CollectionView view, Map<UUID, BrainDocument> documents) {
        List<DocumentMetadata> metadata = view.documentIds().stream()
                .map(id -> document(id, documents)).toList();
        return new CollectionDetail(view.id(), view.brainId(), view.slug(), view.displayName(),
                view.state(), view.version(), view.clonedFromId(), metadata.size(), metadata);
    }

    static SnapshotDetail snapshot(
            FrozenCorpusSnapshot snapshot, Map<UUID, BrainDocument> documents) {
        List<SnapshotCollectionMetadata> collectionMetadata = snapshot.collections().stream()
                .map(collection -> new SnapshotCollectionMetadata(
                        collection.collectionId(), collection.collectionVersion()))
                .toList();
        List<SnapshotDocumentMetadata> documentMetadata = snapshot.documents().stream()
                .map(frozen -> new SnapshotDocumentMetadata(
                        frozen.collectionId(), frozen.documentId(),
                        require(frozen.documentId(), documents).getTitle(),
                        frozen.documentVersion(), frozen.contentSha256(),
                        frozen.visibility().name(), frozen.trustLevel().name(),
                        frozen.effectiveDate(), frozen.expirationDate()))
                .toList();
        return new SnapshotDetail(snapshot.id(), snapshot.brainId(), snapshot.manifestSha256(),
                collectionMetadata, documentMetadata);
    }

    private static DocumentMetadata document(UUID id, Map<UUID, BrainDocument> documents) {
        BrainDocument document = require(id, documents);
        return new DocumentMetadata(id, document.getTitle(), document.getDocumentVersion(),
                document.getContentSha256());
    }

    private static BrainDocument require(UUID id, Map<UUID, BrainDocument> documents) {
        BrainDocument document = documents.get(id);
        if (document == null) {
            throw new CorpusCollectionController.CorpusAdminException(
                    CorpusCollectionController.CorpusAdminException.Code.CORPUS_REQUEST_FAILED);
        }
        return document;
    }
}
