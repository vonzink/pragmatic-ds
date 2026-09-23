package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollection;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollectionDocument;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionRepository;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusCollectionServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COLLECTION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DOCUMENT_A = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID DOCUMENT_B = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private CorpusCollectionRepository collections;
    private CorpusCollectionDocumentRepository memberships;
    private BrainDocumentRepository documents;
    private LabIdempotencyService idempotency;
    private LabAuditService audit;
    private CorpusCollectionService service;

    @BeforeEach
    void setUp() {
        collections = mock(CorpusCollectionRepository.class);
        memberships = mock(CorpusCollectionDocumentRepository.class);
        documents = mock(BrainDocumentRepository.class);
        idempotency = mock(LabIdempotencyService.class);
        audit = mock(LabAuditService.class);
        doAnswer(invocation -> invocation.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());
        service = new DefaultCorpusCollectionService(
                collections, memberships, documents, idempotency, audit);
    }

    @Test
    void createsActiveVersionOneCollectionThroughIdempotencyAndAuditsCountsOnly() {
        when(collections.existsByBrainIdAndSlug(BRAIN, "income")).thenReturn(false);
        when(collections.saveAndFlush(any())).thenAnswer(invocation -> persisted(
                invocation.getArgument(0), COLLECTION));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION))
                .thenReturn(List.of());

        CollectionView view = service.create(BRAIN, "income", "Income", "create-1");

        assertEquals(COLLECTION, view.id());
        assertEquals(BRAIN, view.brainId());
        assertEquals("ACTIVE", view.state());
        assertEquals(1, view.version());
        assertEquals(List.of(), view.documentIds());
        ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> command = idempotencyCommand();
        assertEquals("corpus.collection.create", command.getValue().operation());
        assertEquals("create-1", command.getValue().key());
        assertEquals(64, command.getValue().requestSha256().length());
        verify(audit).record(BRAIN, LabAuditService.COLLECTION_CREATE,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.COLLECTION,
                COLLECTION, null, java.util.Map.of(
                        "oldVersion", 0L, "newVersion", 1L, "documentCount", 0));
    }

    @Test
    void duplicateSlugFailsWithoutWritingACollection() {
        when(collections.existsByBrainIdAndSlug(BRAIN, "income")).thenReturn(true);

        CorpusCollectionService.CollectionException failure = assertThrows(
                CorpusCollectionService.CollectionException.class,
                () -> service.create(BRAIN, "income", "Income", "create-1"));

        assertEquals(CorpusCollectionService.CollectionException.Code.COLLECTION_SLUG_EXISTS,
                failure.code());
        verify(collections, never()).save(any());
    }

    @Test
    void replacesMembershipOnceAndIncrementsVersionExactlyOnce() {
        CorpusCollection collection = collection(CorpusCollection.State.ACTIVE, 1);
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN)).thenReturn(Optional.of(collection));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION))
                .thenReturn(List.of(new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_A)),
                        List.of(new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_B)));
        when(documents.findAllById(List.of(DOCUMENT_B))).thenReturn(List.of(document(DOCUMENT_B, BRAIN, true)));
        when(collections.saveAndFlush(collection)).thenReturn(collection);

        CollectionView view = service.replaceMembership(new ReplaceMembershipCommand(
                BRAIN, COLLECTION, 1, List.of(DOCUMENT_B), "replace-1"));

        assertEquals(2, view.version());
        assertEquals(List.of(DOCUMENT_B), view.documentIds());
        assertEquals(List.of(DOCUMENT_A), removedMemberships().stream()
                .map(CorpusCollectionDocument::getDocumentId).toList());
        verify(memberships).flush();
        assertEquals(List.of(DOCUMENT_B), membershipBatch().getValue().stream()
                .map(CorpusCollectionDocument::getDocumentId).toList());
        verify(audit).record(BRAIN, LabAuditService.COLLECTION_MEMBERSHIP_REPLACE,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.COLLECTION,
                COLLECTION, null, java.util.Map.of(
                        "oldVersion", 1L, "newVersion", 2L, "documentCount", 1));
    }

    @Test
    void identicalReplacementReturnsCurrentVersionWithoutMembershipWrites() {
        CorpusCollection collection = collection(CorpusCollection.State.ACTIVE, 4);
        CorpusCollectionDocument member = new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_A);
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN)).thenReturn(Optional.of(collection));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION))
                .thenReturn(List.of(member));
        when(documents.findAllById(List.of(DOCUMENT_A))).thenReturn(List.of(document(DOCUMENT_A, BRAIN, true)));

        CollectionView view = service.replaceMembership(new ReplaceMembershipCommand(
                BRAIN, COLLECTION, 4, List.of(DOCUMENT_A), "replace-same"));

        assertEquals(4, view.version());
        verify(memberships, never()).deleteAll(any());
        verify(memberships, never()).saveAll(any());
        verify(collections, never()).save(any());
    }

    @Test
    void staleVersionReportsTheCurrentVersionBeforeDocumentReads() {
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN))
                .thenReturn(Optional.of(collection(CorpusCollection.State.ACTIVE, 7)));

        CorpusCollectionService.CollectionException failure = assertThrows(
                CorpusCollectionService.CollectionException.class,
                () -> service.replaceMembership(new ReplaceMembershipCommand(
                        BRAIN, COLLECTION, 6, List.of(DOCUMENT_A), "stale")));

        assertEquals(CorpusCollectionService.CollectionException.Code.COLLECTION_VERSION_CONFLICT,
                failure.code());
        assertEquals(7, failure.currentVersion());
        verify(documents, never()).findAllById(any());
    }

    @Test
    void rejectsDuplicateCrossBrainAndInactiveDocuments() {
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN))
                .thenReturn(Optional.of(collection(CorpusCollection.State.ACTIVE, 1)));

        assertCode(CorpusCollectionService.CollectionException.Code.COLLECTION_DUPLICATE_DOCUMENT,
                () -> service.replaceMembership(new ReplaceMembershipCommand(
                        BRAIN, COLLECTION, 1, List.of(DOCUMENT_A, DOCUMENT_A), "duplicate")));

        when(documents.findAllById(List.of(DOCUMENT_A)))
                .thenReturn(List.of(document(DOCUMENT_A, OTHER_BRAIN, true)));
        assertCode(CorpusCollectionService.CollectionException.Code.COLLECTION_DOCUMENT_INVALID,
                () -> service.replaceMembership(new ReplaceMembershipCommand(
                        BRAIN, COLLECTION, 1, List.of(DOCUMENT_A), "cross-brain")));

        when(documents.findAllById(List.of(DOCUMENT_B)))
                .thenReturn(List.of(document(DOCUMENT_B, BRAIN, false)));
        assertCode(CorpusCollectionService.CollectionException.Code.COLLECTION_DOCUMENT_INACTIVE,
                () -> service.replaceMembership(new ReplaceMembershipCommand(
                        BRAIN, COLLECTION, 1, List.of(DOCUMENT_B), "inactive")));
    }

    @Test
    void cloneCopiesReferencesWithoutChangingSourceOrWritingDocuments() {
        CorpusCollection source = collection(CorpusCollection.State.ACTIVE, 5);
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN)).thenReturn(Optional.of(source));
        when(collections.existsByBrainIdAndSlug(BRAIN, "income-copy")).thenReturn(false);
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION))
                .thenReturn(List.of(
                        new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_A),
                        new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_B)));
        UUID cloneId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        when(collections.saveAndFlush(any())).thenAnswer(invocation -> persisted(
                invocation.getArgument(0), cloneId));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(cloneId))
                .thenReturn(List.of(
                        new CorpusCollectionDocument(cloneId, BRAIN, DOCUMENT_A),
                        new CorpusCollectionDocument(cloneId, BRAIN, DOCUMENT_B)));

        CollectionView clone = service.cloneByReference(
                BRAIN, COLLECTION, "income-copy", "Income Copy", "clone-1");

        assertEquals(cloneId, clone.id());
        assertEquals(COLLECTION, clone.clonedFromId());
        assertEquals(1, clone.version());
        assertEquals(List.of(DOCUMENT_A, DOCUMENT_B), clone.documentIds());
        assertEquals(5, source.getCollectionVersion());
        ArgumentCaptor<List<CorpusCollectionDocument>> copied = membershipBatch();
        assertEquals(List.of(DOCUMENT_A, DOCUMENT_B), copied.getValue().stream()
                .map(CorpusCollectionDocument::getDocumentId).toList());
        assertEquals(List.of(cloneId, cloneId), copied.getValue().stream()
                .map(CorpusCollectionDocument::getCollectionId).toList());
        verify(documents, never()).save(any());
        verify(audit).record(BRAIN, LabAuditService.COLLECTION_CLONE,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.COLLECTION,
                cloneId, null, java.util.Map.of(
                        "oldVersion", 0L, "newVersion", 1L, "documentCount", 2));
    }

    @Test
    void disableUsesExpectedVersionAndIncrementsOnce() {
        CorpusCollection collection = collection(CorpusCollection.State.ACTIVE, 2);
        when(collections.lockByIdAndBrainId(COLLECTION, BRAIN)).thenReturn(Optional.of(collection));
        when(collections.saveAndFlush(collection)).thenReturn(collection);
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(COLLECTION))
                .thenReturn(List.of(new CorpusCollectionDocument(COLLECTION, BRAIN, DOCUMENT_A)));

        CollectionView disabled = service.disable(BRAIN, COLLECTION, 2, "disable-1");

        assertEquals("DISABLED", disabled.state());
        assertEquals(3, disabled.version());
        verify(audit).record(BRAIN, LabAuditService.COLLECTION_DISABLE,
                LabAuditEvent.Status.SUCCEEDED, LabAuditEvent.SubjectType.COLLECTION,
                COLLECTION, null, java.util.Map.of(
                        "oldVersion", 2L, "newVersion", 3L, "documentCount", 1));
    }

    @Test
    void fullCanonicalCommandChangesWhenAnyMutationFieldChanges() {
        when(collections.existsByBrainIdAndSlug(eq(BRAIN), any())).thenReturn(false);
        when(collections.saveAndFlush(any())).thenAnswer(invocation -> persisted(
                invocation.getArgument(0), UUID.randomUUID()));
        when(memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(any())).thenReturn(List.of());

        service.create(BRAIN, "income", "Income", "key-a");
        service.create(BRAIN, "assets", "Assets", "key-b");

        ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> commands =
                ArgumentCaptor.forClass(LabIdempotencyService.IdempotentCommand.class);
        verify(idempotency, org.mockito.Mockito.times(2)).execute(commands.capture());
        assertNotEquals(commands.getAllValues().get(0).requestSha256(),
                commands.getAllValues().get(1).requestSha256());
    }

    private ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> idempotencyCommand() {
        ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> command =
                ArgumentCaptor.forClass(LabIdempotencyService.IdempotentCommand.class);
        verify(idempotency).execute(command.capture());
        return command;
    }

    private ArgumentCaptor<List<CorpusCollectionDocument>> membershipBatch() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CorpusCollectionDocument>> batch = ArgumentCaptor.forClass(List.class);
        verify(memberships).saveAll(batch.capture());
        return batch;
    }

    private List<CorpusCollectionDocument> removedMemberships() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CorpusCollectionDocument>> removed =
                ArgumentCaptor.forClass(Iterable.class);
        verify(memberships).deleteAll(removed.capture());
        List<CorpusCollectionDocument> values = new java.util.ArrayList<>();
        removed.getValue().forEach(values::add);
        return values;
    }

    private static CorpusCollection collection(CorpusCollection.State state, long version) {
        CorpusCollection collection = new CorpusCollection(BRAIN, "income", "Income", null);
        ReflectionTestUtils.setField(collection, "id", COLLECTION);
        ReflectionTestUtils.setField(collection, "state", state);
        ReflectionTestUtils.setField(collection, "collectionVersion", version);
        return collection;
    }

    private static CorpusCollection persisted(CorpusCollection collection, UUID id) {
        ReflectionTestUtils.setField(collection, "id", id);
        return collection;
    }

    private static BrainDocument document(UUID id, UUID brainId, boolean active) {
        BrainDocument document = new BrainDocument();
        ReflectionTestUtils.setField(document, "id", id);
        document.setBrainId(brainId);
        document.setActive(active);
        return document;
    }

    private static void assertCode(CorpusCollectionService.CollectionException.Code code,
                                   Runnable action) {
        CorpusCollectionService.CollectionException failure = assertThrows(
                CorpusCollectionService.CollectionException.class, action::run);
        assertEquals(code, failure.code());
    }
}
