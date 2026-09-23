package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCollection;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenDocument;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRetrievalServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SNAPSHOT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COLLECTION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DOCUMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String HASH = "a".repeat(64);
    private static final LocalDate EFFECTIVE = LocalDate.now().minusYears(1);
    private static final LocalDate EXPIRATION = LocalDate.now().plusYears(1);

    private CorpusSnapshotService snapshots;
    private com.pragmaticds.rag.repository.BrainDocumentRepository documents;
    private RetrievalService retrieval;
    private SnapshotRetrievalService service;

    @BeforeEach
    void setUp() {
        snapshots = mock(CorpusSnapshotService.class);
        documents = mock(com.pragmaticds.rag.repository.BrainDocumentRepository.class);
        retrieval = mock(RetrievalService.class);
        service = new DefaultSnapshotRetrievalService(snapshots, documents, retrieval);
    }

    @Test
    void validatesPinnedFactsBeforeDelegatingToSharedRetrievalPipeline() {
        FrozenCorpusSnapshot snapshot = snapshot();
        BrainDocument current = document(BRAIN, "v1", HASH, true,
                SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                EFFECTIVE, EXPIRATION);
        RetrievalResult expected = RetrievalResult.empty();
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(snapshot);
        when(documents.findAllById(List.of(DOCUMENT))).thenReturn(List.of(current));
        when(retrieval.retrieveSnapshot("calculate income", BRAIN, SNAPSHOT,
                SourceVisibility.INTERNAL, 8, true)).thenReturn(expected);

        RetrievalResult actual = service.retrieve(new SnapshotRetrievalService.SnapshotRetrievalRequest(
                BRAIN, SNAPSHOT, "calculate income", SourceVisibility.INTERNAL, 8, true));

        assertSame(expected, actual);
        verify(retrieval).retrieveSnapshot("calculate income", BRAIN, SNAPSHOT,
                SourceVisibility.INTERNAL, 8, true);
    }

    @Test
    void versionHashOrStatusDriftFailsBeforeEmbeddingOrSearch() {
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(snapshot());
        when(documents.findAllById(List.of(DOCUMENT))).thenReturn(List.of(
                document(BRAIN, "v2", "b".repeat(64), false,
                        SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                        EFFECTIVE, EXPIRATION)));

        SnapshotRetrievalService.SnapshotRetrievalException failure = assertThrows(
                SnapshotRetrievalService.SnapshotRetrievalException.class,
                () -> service.retrieve(request()));

        assertEquals(SnapshotRetrievalService.SnapshotRetrievalException.Code.CORPUS_SNAPSHOT_DRIFTED,
                failure.code());
        verify(retrieval, never()).retrieveSnapshot(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void concurrentDriftDuringExternalRetrievalCannotBecomeSilentPartialEvidence() {
        BrainDocument before = document(BRAIN, "v1", HASH, true,
                SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                EFFECTIVE, EXPIRATION);
        BrainDocument after = document(BRAIN, "v2", "b".repeat(64), true,
                SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                EFFECTIVE, EXPIRATION);
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(snapshot());
        when(documents.findAllById(List.of(DOCUMENT))).thenReturn(List.of(before), List.of(after));
        when(retrieval.retrieveSnapshot("calculate income", BRAIN, SNAPSHOT,
                SourceVisibility.INTERNAL, 8, true)).thenReturn(RetrievalResult.empty());

        SnapshotRetrievalService.SnapshotRetrievalException failure = assertThrows(
                SnapshotRetrievalService.SnapshotRetrievalException.class,
                () -> service.retrieve(request()));

        assertEquals(SnapshotRetrievalService.SnapshotRetrievalException.Code.CORPUS_SNAPSHOT_DRIFTED,
                failure.code());
        verify(retrieval).retrieveSnapshot("calculate income", BRAIN, SNAPSHOT,
                SourceVisibility.INTERNAL, 8, true);
    }

    @Test
    void visibilityTrustAndEffectiveWindowDriftFailClosed() {
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(snapshot());
        when(documents.findAllById(List.of(DOCUMENT))).thenReturn(List.of(
                document(BRAIN, "v1", HASH, true,
                        SourceVisibility.SECURE, SourceTrustLevel.BLOCKED,
                        EFFECTIVE.minusDays(1), EXPIRATION.minusDays(1))));

        SnapshotRetrievalService.SnapshotRetrievalException failure = assertThrows(
                SnapshotRetrievalService.SnapshotRetrievalException.class,
                () -> service.retrieve(request()));

        assertEquals(SnapshotRetrievalService.SnapshotRetrievalException.Code.CORPUS_SNAPSHOT_DRIFTED,
                failure.code());
        verify(retrieval, never()).retrieveSnapshot(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void missingOrCrossBrainDocumentFailsClosed() {
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(snapshot());
        when(documents.findAllById(List.of(DOCUMENT))).thenReturn(List.of(
                document(UUID.randomUUID(), "v1", HASH, true,
                        SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                        EFFECTIVE, EXPIRATION)));

        SnapshotRetrievalService.SnapshotRetrievalException failure = assertThrows(
                SnapshotRetrievalService.SnapshotRetrievalException.class,
                () -> service.retrieve(request()));

        assertEquals(SnapshotRetrievalService.SnapshotRetrievalException.Code.CORPUS_SNAPSHOT_DRIFTED,
                failure.code());
    }

    @Test
    void rejectsInvalidRequestWithoutResolvingSnapshot() {
        SnapshotRetrievalService.SnapshotRetrievalException failure = assertThrows(
                SnapshotRetrievalService.SnapshotRetrievalException.class,
                () -> service.retrieve(new SnapshotRetrievalService.SnapshotRetrievalRequest(
                        BRAIN, SNAPSHOT, " ", SourceVisibility.INTERNAL, 0, false)));

        assertEquals(SnapshotRetrievalService.SnapshotRetrievalException.Code.SNAPSHOT_REQUEST_INVALID,
                failure.code());
        verify(snapshots, never()).require(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static SnapshotRetrievalService.SnapshotRetrievalRequest request() {
        return new SnapshotRetrievalService.SnapshotRetrievalRequest(
                BRAIN, SNAPSHOT, "calculate income", SourceVisibility.INTERNAL, 8, true);
    }

    private static FrozenCorpusSnapshot snapshot() {
        return new FrozenCorpusSnapshot(SNAPSHOT, BRAIN, "c".repeat(64),
                List.of(new FrozenCollection(COLLECTION, 2)),
                List.of(new FrozenDocument(COLLECTION, DOCUMENT, "v1", HASH,
                        SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                        EFFECTIVE, EXPIRATION)));
    }

    private static BrainDocument document(
            UUID brainId, String version, String hash, boolean active,
            SourceVisibility visibility, SourceTrustLevel trust,
            LocalDate effective, LocalDate expiration) {
        BrainDocument document = new BrainDocument();
        ReflectionTestUtils.setField(document, "id", DOCUMENT);
        document.setBrainId(brainId);
        document.setDocumentVersion(version);
        document.setContentSha256(hash);
        document.setActive(active);
        document.setVisibility(visibility);
        document.setTrustLevel(trust);
        document.setEffectiveDate(effective);
        document.setExpirationDate(expiration);
        return document;
    }
}
