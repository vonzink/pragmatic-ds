package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.CollectionVersionRef;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.SnapshotRequest;
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
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusSnapshotServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COLLECTION_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID COLLECTION_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID DOCUMENT_A = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID DOCUMENT_B = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID DOCUMENT_C = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SNAPSHOT = UUID.fromString("88888888-8888-4888-8888-888888888888");

    private CorpusCollectionRepository collections;
    private CorpusCollectionDocumentRepository memberships;
    private BrainDocumentRepository documents;
    private CorpusSnapshotRepository snapshots;
    private CorpusSnapshotCollectionRepository snapshotCollections;
    private CorpusSnapshotDocumentRepository snapshotDocuments;
    private LabAuditService audit;
    private CorpusSnapshotService service;

    @BeforeEach
    void setUp() {
        collections = mock(CorpusCollectionRepository.class);
        memberships = mock(CorpusCollectionDocumentRepository.class);
        documents = mock(BrainDocumentRepository.class);
        snapshots = mock(CorpusSnapshotRepository.class);
        snapshotCollections = mock(CorpusSnapshotCollectionRepository.class);
        snapshotDocuments = mock(CorpusSnapshotDocumentRepository.class);
        audit = mock(LabAuditService.class);
        service = new DefaultCorpusSnapshotService(collections, memberships, documents,
                snapshots, snapshotCollections, snapshotDocuments,
                new CorpusSnapshotCodec(new LabManifestWriter()), audit);
    }

    @Test
    void freezesCallerOrderDeduplicatesSharedDocumentsAndPinsExactFacts() {
        CorpusCollection a = collection(COLLECTION_A, BRAIN, 2, CorpusCollection.State.ACTIVE);
        CorpusCollection b = collection(COLLECTION_B, BRAIN, 3, CorpusCollection.State.ACTIVE);
        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_A, COLLECTION_B)))
                .thenReturn(List.of(a, b));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION_B))
                .thenReturn(List.of(member(COLLECTION_B, DOCUMENT_B), member(COLLECTION_B, DOCUMENT_A)));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION_A))
                .thenReturn(List.of(member(COLLECTION_A, DOCUMENT_C), member(COLLECTION_A, DOCUMENT_B)));
        when(documents.findAllById(List.of(DOCUMENT_A, DOCUMENT_B, DOCUMENT_C)))
                .thenReturn(List.of(
                        document(DOCUMENT_C, BRAIN, "v3", "c".repeat(64), true),
                        document(DOCUMENT_A, BRAIN, "v1", "a".repeat(64), true),
                        document(DOCUMENT_B, BRAIN, "v2", "b".repeat(64), true)));
        when(snapshots.findByBrainIdAndManifestSha256(any(), any())).thenReturn(Optional.empty());
        when(snapshots.saveAndFlush(any())).thenAnswer(invocation -> persisted(
                invocation.getArgument(0), SNAPSHOT));

        FrozenCorpusSnapshot frozen = service.freeze(new SnapshotRequest(BRAIN, List.of(
                new CollectionVersionRef(COLLECTION_B, 3),
                new CollectionVersionRef(COLLECTION_A, 2))));

        assertEquals(SNAPSHOT, frozen.id());
        assertEquals(List.of(COLLECTION_B, COLLECTION_A), frozen.collections().stream()
                .map(CorpusSnapshotService.FrozenCollection::collectionId).toList());
        assertEquals(List.of(DOCUMENT_A, DOCUMENT_B, DOCUMENT_C), frozen.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::documentId).toList());
        assertEquals(List.of(COLLECTION_B, COLLECTION_B, COLLECTION_A), frozen.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::collectionId).toList());
        assertEquals(List.of("v1", "v2", "v3"), frozen.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::documentVersion).toList());
        assertEquals(64, frozen.manifestSha256().length());
        assertEquals(2, snapshotCollectionRows().getValue().size());
        assertEquals(3, snapshotDocumentRows().getValue().size());
        verify(audit).record(BRAIN, LabAuditService.CORPUS_SNAPSHOT_FREEZE,
                com.pragmaticds.rag.lab.domain.LabAuditEvent.Status.SUCCEEDED,
                com.pragmaticds.rag.lab.domain.LabAuditEvent.SubjectType.SNAPSHOT,
                SNAPSHOT, null, java.util.Map.of("collectionCount", 2, "documentCount", 3));
    }

    @Test
    void staleCollectionVersionFailsBeforeMembershipOrDocumentReads() {
        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_A)))
                .thenReturn(List.of(collection(COLLECTION_A, BRAIN, 4, CorpusCollection.State.ACTIVE)));

        CorpusSnapshotService.SnapshotException failure = assertThrows(
                CorpusSnapshotService.SnapshotException.class,
                () -> service.freeze(new SnapshotRequest(BRAIN,
                        List.of(new CollectionVersionRef(COLLECTION_A, 3)))));

        assertEquals(CorpusSnapshotService.SnapshotException.Code.COLLECTION_VERSION_CONFLICT,
                failure.code());
        assertEquals(4, failure.currentVersion());
        verify(memberships, never()).findAllByIdCollectionIdOrderByIdDocumentIdAsc(any());
        verify(documents, never()).findAllById(any());
    }

    @Test
    void duplicateCrossBrainAndDisabledCollectionsFailClosed() {
        assertCode(CorpusSnapshotService.SnapshotException.Code.SNAPSHOT_REQUEST_INVALID,
                () -> service.freeze(new SnapshotRequest(BRAIN, List.of(
                        new CollectionVersionRef(COLLECTION_A, 1),
                        new CollectionVersionRef(COLLECTION_A, 1)))));

        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_A)))
                .thenReturn(List.of(collection(COLLECTION_A, OTHER_BRAIN, 1, CorpusCollection.State.ACTIVE)));
        assertCode(CorpusSnapshotService.SnapshotException.Code.COLLECTION_NOT_FOUND,
                () -> service.freeze(new SnapshotRequest(BRAIN,
                        List.of(new CollectionVersionRef(COLLECTION_A, 1)))));

        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_B)))
                .thenReturn(List.of(collection(COLLECTION_B, BRAIN, 1, CorpusCollection.State.DISABLED)));
        assertCode(CorpusSnapshotService.SnapshotException.Code.COLLECTION_DISABLED,
                () -> service.freeze(new SnapshotRequest(BRAIN,
                        List.of(new CollectionVersionRef(COLLECTION_B, 1)))));
    }

    @Test
    void refusesInactiveOrUnversionedDocumentsBeforeCreatingSnapshot() {
        CorpusCollection collection = collection(
                COLLECTION_A, BRAIN, 1, CorpusCollection.State.ACTIVE);
        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_A)))
                .thenReturn(List.of(collection));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION_A))
                .thenReturn(List.of(member(COLLECTION_A, DOCUMENT_A)));

        when(documents.findAllById(List.of(DOCUMENT_A)))
                .thenReturn(List.of(document(DOCUMENT_A, BRAIN, "v1", "a".repeat(64), false)));
        assertCode(CorpusSnapshotService.SnapshotException.Code.SNAPSHOT_DOCUMENT_INACTIVE,
                () -> freezeOne());

        when(documents.findAllById(List.of(DOCUMENT_A)))
                .thenReturn(List.of(document(DOCUMENT_A, BRAIN, null, "a".repeat(64), true)));
        assertCode(CorpusSnapshotService.SnapshotException.Code.SNAPSHOT_DOCUMENT_NOT_VERSIONED,
                () -> freezeOne());

        when(documents.findAllById(List.of(DOCUMENT_A)))
                .thenReturn(List.of(document(DOCUMENT_A, BRAIN, "v1", "ABC", true)));
        assertCode(CorpusSnapshotService.SnapshotException.Code.SNAPSHOT_DOCUMENT_NOT_VERSIONED,
                () -> freezeOne());
        verify(snapshots, never()).save(any());
    }

    @Test
    void sameManifestReturnsExistingImmutableSnapshotWithoutWritingChildren() {
        CorpusCollection collection = collection(
                COLLECTION_A, BRAIN, 1, CorpusCollection.State.ACTIVE);
        BrainDocument document = document(
                DOCUMENT_A, BRAIN, "v1", "a".repeat(64), true);
        when(collections.lockAllByBrainIdAndIds(BRAIN, List.of(COLLECTION_A)))
                .thenReturn(List.of(collection));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION_A))
                .thenReturn(List.of(member(COLLECTION_A, DOCUMENT_A)));
        when(documents.findAllById(List.of(DOCUMENT_A))).thenReturn(List.of(document));
        CorpusSnapshot existing = snapshot(SNAPSHOT, BRAIN, "d".repeat(64));
        when(snapshots.findByBrainIdAndManifestSha256(
                org.mockito.ArgumentMatchers.eq(BRAIN), any()))
                .thenAnswer(invocation -> {
                    ReflectionTestUtils.setField(existing, "manifestSha256", invocation.getArgument(1));
                    return Optional.of(existing);
                });
        when(snapshots.findByIdAndBrainId(SNAPSHOT, BRAIN)).thenReturn(Optional.of(existing));
        when(snapshotCollections.findAllByIdSnapshotIdOrderByIdPositionAsc(SNAPSHOT))
                .thenReturn(List.of(new CorpusSnapshotCollection(
                        SNAPSHOT, BRAIN, 0, COLLECTION_A, 1)));
        when(snapshotDocuments.findAllByIdSnapshotIdOrderByIdCollectionIdAscIdDocumentIdAsc(SNAPSHOT))
                .thenReturn(List.of(snapshotDocument(COLLECTION_A, document)));

        FrozenCorpusSnapshot first = freezeOne();
        FrozenCorpusSnapshot second = freezeOne();

        assertEquals(SNAPSHOT, first.id());
        assertEquals(first, second);
        verify(snapshots, never()).save(any());
        verify(snapshotCollections, never()).saveAll(any());
        verify(snapshotDocuments, never()).saveAll(any());
    }

    @Test
    void collectionVersionChangeProducesDifferentManifestIdentity() {
        CorpusSnapshotManifest one = new CorpusSnapshotManifest(
                List.of(new CorpusSnapshotManifest.CollectionEntry(COLLECTION_A, 1)), List.of());
        CorpusSnapshotManifest two = new CorpusSnapshotManifest(
                List.of(new CorpusSnapshotManifest.CollectionEntry(COLLECTION_A, 2)), List.of());
        CorpusSnapshotCodec codec = new CorpusSnapshotCodec(new LabManifestWriter());

        assertNotEquals(codec.encode(one).manifestSha256(), codec.encode(two).manifestSha256());
    }

    @Test
    void requireNeverReturnsAnotherBrainsSnapshot() {
        when(snapshots.findByIdAndBrainId(SNAPSHOT, BRAIN)).thenReturn(Optional.empty());

        assertCode(CorpusSnapshotService.SnapshotException.Code.CORPUS_SNAPSHOT_NOT_FOUND,
                () -> service.require(BRAIN, SNAPSHOT));
    }

    private FrozenCorpusSnapshot freezeOne() {
        return service.freeze(new SnapshotRequest(BRAIN,
                List.of(new CollectionVersionRef(COLLECTION_A, 1))));
    }

    private ArgumentCaptor<List<CorpusSnapshotCollection>> snapshotCollectionRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CorpusSnapshotCollection>> rows = ArgumentCaptor.forClass(List.class);
        verify(snapshotCollections).saveAll(rows.capture());
        return rows;
    }

    private ArgumentCaptor<List<CorpusSnapshotDocument>> snapshotDocumentRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CorpusSnapshotDocument>> rows = ArgumentCaptor.forClass(List.class);
        verify(snapshotDocuments).saveAll(rows.capture());
        return rows;
    }

    private static CorpusCollection collection(UUID id, UUID brainId, long version,
                                               CorpusCollection.State state) {
        CorpusCollection collection = new CorpusCollection(brainId, "collection", "Collection", null);
        ReflectionTestUtils.setField(collection, "id", id);
        ReflectionTestUtils.setField(collection, "collectionVersion", version);
        ReflectionTestUtils.setField(collection, "state", state);
        return collection;
    }

    private static CorpusCollectionDocument member(UUID collectionId, UUID documentId) {
        return new CorpusCollectionDocument(collectionId, BRAIN, documentId);
    }

    private static BrainDocument document(UUID id, UUID brainId, String version,
                                          String hash, boolean active) {
        BrainDocument document = new BrainDocument();
        ReflectionTestUtils.setField(document, "id", id);
        document.setBrainId(brainId);
        document.setDocumentVersion(version);
        document.setContentSha256(hash);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setEffectiveDate(LocalDate.of(2026, 8, 1));
        document.setActive(active);
        return document;
    }

    private static CorpusSnapshot persisted(CorpusSnapshot snapshot, UUID id) {
        ReflectionTestUtils.setField(snapshot, "id", id);
        return snapshot;
    }

    private static CorpusSnapshot snapshot(UUID id, UUID brainId, String hash) {
        CorpusSnapshot snapshot = new CorpusSnapshot(
                brainId, java.util.Map.of("manifestVersion", 1), hash);
        ReflectionTestUtils.setField(snapshot, "id", id);
        return snapshot;
    }

    private static CorpusSnapshotDocument snapshotDocument(
            UUID collectionId, BrainDocument document) {
        return new CorpusSnapshotDocument(SNAPSHOT, BRAIN, collectionId, document.getId(),
                document.getDocumentVersion(), document.getContentSha256(),
                document.getVisibility(), document.getTrustLevel(),
                document.getEffectiveDate(), document.getExpirationDate());
    }

    private static void assertCode(CorpusSnapshotService.SnapshotException.Code code,
                                   Runnable action) {
        CorpusSnapshotService.SnapshotException failure = assertThrows(
                CorpusSnapshotService.SnapshotException.class, action::run);
        assertEquals(code, failure.code());
    }
}
