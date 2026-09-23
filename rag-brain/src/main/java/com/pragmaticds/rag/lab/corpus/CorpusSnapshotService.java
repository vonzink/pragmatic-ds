package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollection;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollectionDocument;
import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshot;
import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshotCollection;
import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshotDocument;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotCollectionRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotRepository;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Freezes and resolves immutable, brain-scoped corpus snapshots. */
public interface CorpusSnapshotService {
    FrozenCorpusSnapshot freeze(SnapshotRequest request);
    FrozenCorpusSnapshot require(UUID brainId, UUID snapshotId);

    record CollectionVersionRef(UUID collectionId, long expectedVersion) {
        public CollectionVersionRef {
            Objects.requireNonNull(collectionId, "collectionId");
        }
    }

    record SnapshotRequest(UUID brainId, List<CollectionVersionRef> collections) {
        public SnapshotRequest {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
        }
    }

    record FrozenCollection(UUID collectionId, long collectionVersion) {}

    record FrozenDocument(
            UUID collectionId,
            UUID documentId,
            String documentVersion,
            String contentSha256,
            SourceVisibility visibility,
            SourceTrustLevel trustLevel,
            LocalDate effectiveDate,
            LocalDate expirationDate) {}

    record FrozenCorpusSnapshot(
            UUID id,
            UUID brainId,
            String manifestSha256,
            List<FrozenCollection> collections,
            List<FrozenDocument> documents) {
        public FrozenCorpusSnapshot {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        }
    }

    final class SnapshotException extends RuntimeException {
        public enum Code {
            SNAPSHOT_REQUEST_INVALID,
            CORPUS_SNAPSHOT_NOT_FOUND,
            COLLECTION_NOT_FOUND,
            COLLECTION_DISABLED,
            COLLECTION_VERSION_CONFLICT,
            SNAPSHOT_DOCUMENT_INVALID,
            SNAPSHOT_DOCUMENT_INACTIVE,
            SNAPSHOT_DOCUMENT_NOT_VERSIONED
        }

        private final Code code;
        private final Long currentVersion;

        public SnapshotException(Code code) {
            this(code, null);
        }

        public SnapshotException(Code code, Long currentVersion) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
            this.currentVersion = currentVersion;
        }

        public Code code() { return code; }
        public Long currentVersion() { return currentVersion; }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultCorpusSnapshotService implements CorpusSnapshotService {
    private final CorpusCollectionRepository collections;
    private final CorpusCollectionDocumentRepository memberships;
    private final BrainDocumentRepository documents;
    private final CorpusSnapshotRepository snapshots;
    private final CorpusSnapshotCollectionRepository snapshotCollections;
    private final CorpusSnapshotDocumentRepository snapshotDocuments;
    private final CorpusSnapshotCodec codec;
    private final LabAuditService audit;

    DefaultCorpusSnapshotService(CorpusCollectionRepository collections,
                                 CorpusCollectionDocumentRepository memberships,
                                 BrainDocumentRepository documents,
                                 CorpusSnapshotRepository snapshots,
                                 CorpusSnapshotCollectionRepository snapshotCollections,
                                 CorpusSnapshotDocumentRepository snapshotDocuments,
                                 CorpusSnapshotCodec codec,
                                 LabAuditService audit) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotCollections = Objects.requireNonNull(snapshotCollections, "snapshotCollections");
        this.snapshotDocuments = Objects.requireNonNull(snapshotDocuments, "snapshotDocuments");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Freezes one immutable snapshot.
     *
     * <p>Correctness here rests on the pessimistic collection locks below, not on the declared
     * isolation level. Admin callers reach this inside {@code LabIdempotencyService}'s
     * REQUIRES_NEW transaction, and Spring silently drops an inner isolation level when a
     * REQUIRED method joins an existing transaction, so this actually runs at the database
     * default. Locking every requested collection in sorted id order is what serializes a
     * freeze against a concurrent membership replacement.
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FrozenCorpusSnapshot freeze(SnapshotRequest request) {
        validateRequest(request);
        UUID brainId = request.brainId();
        List<UUID> lockOrder = request.collections().stream()
                .map(CollectionVersionRef::collectionId).sorted().toList();
        List<CorpusCollection> locked = collections.lockAllByBrainIdAndIds(brainId, lockOrder);
        Map<UUID, CorpusCollection> byId = new HashMap<>();
        for (CorpusCollection collection : locked) {
            if (!brainId.equals(collection.getBrainId())) {
                throw failure(SnapshotException.Code.COLLECTION_NOT_FOUND);
            }
            byId.put(collection.getId(), collection);
        }
        if (byId.size() != request.collections().size()) {
            throw failure(SnapshotException.Code.COLLECTION_NOT_FOUND);
        }

        List<FrozenCollection> frozenCollections = new ArrayList<>();
        LinkedHashMap<UUID, UUID> firstCollectionByDocument = new LinkedHashMap<>();
        for (CollectionVersionRef requested : request.collections()) {
            CorpusCollection collection = byId.get(requested.collectionId());
            if (collection == null) {
                throw failure(SnapshotException.Code.COLLECTION_NOT_FOUND);
            }
            if (collection.getState() == CorpusCollection.State.DISABLED) {
                throw failure(SnapshotException.Code.COLLECTION_DISABLED);
            }
            if (collection.getCollectionVersion() != requested.expectedVersion()) {
                throw new SnapshotException(SnapshotException.Code.COLLECTION_VERSION_CONFLICT,
                        collection.getCollectionVersion());
            }
            frozenCollections.add(new FrozenCollection(
                    collection.getId(), collection.getCollectionVersion()));
            List<CorpusCollectionDocument> members = memberships
                    .findAllByIdCollectionIdOrderByIdDocumentIdAsc(collection.getId())
                    .stream().sorted(java.util.Comparator.comparing(
                            CorpusCollectionDocument::getDocumentId)).toList();
            for (CorpusCollectionDocument member : members) {
                if (!brainId.equals(member.getBrainId())
                        || !collection.getId().equals(member.getCollectionId())) {
                    throw failure(SnapshotException.Code.SNAPSHOT_DOCUMENT_INVALID);
                }
                firstCollectionByDocument.putIfAbsent(member.getDocumentId(), collection.getId());
            }
        }

        List<UUID> documentIds = firstCollectionByDocument.keySet().stream().sorted().toList();
        Map<UUID, BrainDocument> documentsById = new HashMap<>();
        if (!documentIds.isEmpty()) {
            for (BrainDocument document : documents.findAllById(documentIds)) {
                documentsById.put(document.getId(), document);
            }
        }
        if (documentsById.size() != documentIds.size()) {
            throw failure(SnapshotException.Code.SNAPSHOT_DOCUMENT_INVALID);
        }

        List<FrozenDocument> frozenDocuments = new ArrayList<>();
        for (FrozenCollection collection : frozenCollections) {
            firstCollectionByDocument.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(collection.collectionId()))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .forEach(documentId -> frozenDocuments.add(freezeDocument(
                            brainId, collection.collectionId(), documentsById.get(documentId))));
        }

        CorpusSnapshotManifest manifest = new CorpusSnapshotManifest(
                frozenCollections.stream().map(collection ->
                        new CorpusSnapshotManifest.CollectionEntry(
                                collection.collectionId(), collection.collectionVersion())).toList(),
                frozenDocuments.stream().map(document ->
                        new CorpusSnapshotManifest.DocumentEntry(
                                document.collectionId(), document.documentId(),
                                document.documentVersion(), document.contentSha256(),
                                document.visibility(), document.trustLevel(),
                                document.effectiveDate(), document.expirationDate())).toList());
        CorpusSnapshotCodec.EncodedSnapshotManifest encoded = codec.encode(manifest);

        return snapshots.findByBrainIdAndManifestSha256(brainId, encoded.manifestSha256())
                .map(existing -> require(brainId, existing.getId()))
                .orElseGet(() -> persist(brainId, encoded, frozenCollections, frozenDocuments));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FrozenCorpusSnapshot require(UUID brainId, UUID snapshotId) {
        if (brainId == null || snapshotId == null) {
            throw failure(SnapshotException.Code.SNAPSHOT_REQUEST_INVALID);
        }
        CorpusSnapshot snapshot = snapshots.findByIdAndBrainId(snapshotId, brainId)
                .orElseThrow(() -> failure(SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND));
        List<CorpusSnapshotCollection> collectionRows = snapshotCollections
                .findAllByIdSnapshotIdOrderByIdPositionAsc(snapshotId);
        Map<UUID, Integer> positions = new HashMap<>();
        List<FrozenCollection> frozenCollections = new ArrayList<>();
        for (CorpusSnapshotCollection row : collectionRows) {
            if (!brainId.equals(row.getBrainId())) {
                throw failure(SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND);
            }
            positions.put(row.getCollectionId(), row.getPosition());
            frozenCollections.add(new FrozenCollection(
                    row.getCollectionId(), row.getCollectionVersion()));
        }
        List<FrozenDocument> frozenDocuments = snapshotDocuments
                .findAllByIdSnapshotIdOrderByIdCollectionIdAscIdDocumentIdAsc(snapshotId)
                .stream()
                .sorted(java.util.Comparator
                        .comparingInt((CorpusSnapshotDocument row) ->
                                positions.getOrDefault(row.getCollectionId(), Integer.MAX_VALUE))
                        .thenComparing(CorpusSnapshotDocument::getDocumentId))
                .map(row -> {
                    if (!brainId.equals(row.getBrainId())
                            || !positions.containsKey(row.getCollectionId())) {
                        throw failure(SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND);
                    }
                    return new FrozenDocument(row.getCollectionId(), row.getDocumentId(),
                            row.getDocumentVersion(), row.getContentSha256(), row.getVisibility(),
                            row.getTrustLevel(), row.getEffectiveDate(), row.getExpirationDate());
                }).toList();
        return new FrozenCorpusSnapshot(snapshot.getId(), snapshot.getBrainId(),
                snapshot.getManifestSha256(), frozenCollections, frozenDocuments);
    }

    private FrozenCorpusSnapshot persist(
            UUID brainId,
            CorpusSnapshotCodec.EncodedSnapshotManifest encoded,
            List<FrozenCollection> frozenCollections,
            List<FrozenDocument> frozenDocuments) {
        CorpusSnapshot snapshot = snapshots.saveAndFlush(
                new CorpusSnapshot(brainId, encoded.manifest(), encoded.manifestSha256()));
        List<CorpusSnapshotCollection> collectionRows = new ArrayList<>();
        for (int position = 0; position < frozenCollections.size(); position++) {
            FrozenCollection collection = frozenCollections.get(position);
            collectionRows.add(new CorpusSnapshotCollection(snapshot.getId(), brainId, position,
                    collection.collectionId(), collection.collectionVersion()));
        }
        snapshotCollections.saveAll(collectionRows);
        snapshotDocuments.saveAll(frozenDocuments.stream().map(document ->
                new CorpusSnapshotDocument(snapshot.getId(), brainId, document.collectionId(),
                        document.documentId(), document.documentVersion(), document.contentSha256(),
                        document.visibility(), document.trustLevel(), document.effectiveDate(),
                        document.expirationDate())).toList());
        audit.record(brainId, LabAuditService.CORPUS_SNAPSHOT_FREEZE,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.SNAPSHOT,
                snapshot.getId(), null, Map.of("collectionCount", frozenCollections.size(),
                        "documentCount", frozenDocuments.size()));
        return new FrozenCorpusSnapshot(snapshot.getId(), brainId, encoded.manifestSha256(),
                frozenCollections, frozenDocuments);
    }

    private static FrozenDocument freezeDocument(
            UUID brainId, UUID collectionId, BrainDocument document) {
        if (document == null || !brainId.equals(document.getBrainId())) {
            throw failure(SnapshotException.Code.SNAPSHOT_DOCUMENT_INVALID);
        }
        if (!document.isActive()) {
            throw failure(SnapshotException.Code.SNAPSHOT_DOCUMENT_INACTIVE);
        }
        if (document.getDocumentVersion() == null || document.getDocumentVersion().isBlank()
                || document.getContentSha256() == null
                || !document.getContentSha256().matches("[0-9a-f]{64}")
                || document.getVisibility() == null || document.getTrustLevel() == null) {
            throw failure(SnapshotException.Code.SNAPSHOT_DOCUMENT_NOT_VERSIONED);
        }
        return new FrozenDocument(collectionId, document.getId(), document.getDocumentVersion(),
                document.getContentSha256(), document.getVisibility(), document.getTrustLevel(),
                document.getEffectiveDate(), document.getExpirationDate());
    }

    private static void validateRequest(SnapshotRequest request) {
        if (request == null || request.brainId() == null || request.collections().isEmpty()) {
            throw failure(SnapshotException.Code.SNAPSHOT_REQUEST_INVALID);
        }
        Set<UUID> ids = new HashSet<>();
        for (CollectionVersionRef collection : request.collections()) {
            if (collection == null || collection.collectionId() == null
                    || collection.expectedVersion() < 1 || !ids.add(collection.collectionId())) {
                throw failure(SnapshotException.Code.SNAPSHOT_REQUEST_INVALID);
            }
        }
    }

    private static SnapshotException failure(SnapshotException.Code code) {
        return new SnapshotException(code);
    }
}
