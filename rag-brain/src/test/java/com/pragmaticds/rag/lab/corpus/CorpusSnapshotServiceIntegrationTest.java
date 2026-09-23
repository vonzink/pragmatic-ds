package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.CollectionVersionRef;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.SnapshotRequest;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotCollectionRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusSnapshotRepository;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL proof that frozen snapshot identity is stable and version-sensitive. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CorpusSnapshotServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired CorpusCollectionRepository collections;
    @Autowired CorpusCollectionDocumentRepository memberships;
    @Autowired BrainDocumentRepository documents;
    @Autowired CorpusSnapshotRepository snapshots;
    @Autowired CorpusSnapshotCollectionRepository snapshotCollections;
    @Autowired CorpusSnapshotDocumentRepository snapshotDocuments;

    private CorpusCollectionService collectionService;
    private CorpusSnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        LabIdempotencyService passThrough = new LabIdempotencyService() {
            @Override
            public <T> T execute(IdempotentCommand<T> command) {
                return command.action().get();
            }

            /** A pass-through stores no receipt, so there is never a prior request to differ from. */
            @Override
            public void requireUnusedOrMatching(
                    UUID brainId, String operation, String key, String requestSha256) {
            }
        };
        LabAuditService audit = mock(LabAuditService.class);
        collectionService = new DefaultCorpusCollectionService(
                collections, memberships, documents, passThrough, audit);
        snapshotService = new DefaultCorpusSnapshotService(
                collections, memberships, documents, snapshots, snapshotCollections,
                snapshotDocuments, new CorpusSnapshotCodec(new LabManifestWriter()), audit);
    }

    @Test
    void sameManifestReusesIdentityWhileMembershipVersionCreatesNewSnapshot() {
        BrainDocument first = documents.saveAndFlush(document("first.md", "a".repeat(64)));
        BrainDocument second = documents.saveAndFlush(document("second.md", "b".repeat(64)));

        var collection = collectionService.create(
                TestBrains.DEFAULT_ID, "snapshot-test", "Snapshot Test", "create");
        collection = collectionService.replaceMembership(new ReplaceMembershipCommand(
                TestBrains.DEFAULT_ID, collection.id(), collection.version(),
                List.of(first.getId()), "members-v1"));

        SnapshotRequest versionTwo = new SnapshotRequest(TestBrains.DEFAULT_ID,
                List.of(new CollectionVersionRef(collection.id(), collection.version())));
        var firstFreeze = snapshotService.freeze(versionTwo);
        var replay = snapshotService.freeze(versionTwo);

        assertEquals(firstFreeze.id(), replay.id());
        assertEquals(1, snapshots.count());
        assertEquals(List.of(first.getId()), firstFreeze.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::documentId).toList());

        collection = collectionService.replaceMembership(new ReplaceMembershipCommand(
                TestBrains.DEFAULT_ID, collection.id(), collection.version(),
                List.of(first.getId(), second.getId()), "members-v2"));
        var changed = snapshotService.freeze(new SnapshotRequest(TestBrains.DEFAULT_ID,
                List.of(new CollectionVersionRef(collection.id(), collection.version()))));

        assertNotEquals(firstFreeze.id(), changed.id());
        assertNotEquals(firstFreeze.manifestSha256(), changed.manifestSha256());
        assertEquals(2, snapshots.count());
        assertEquals(2, snapshotCollections.count());
        assertEquals(3, snapshotDocuments.count());
        assertTrue(snapshotService.require(TestBrains.DEFAULT_ID, firstFreeze.id()).documents()
                .stream().allMatch(frozen -> frozen.documentId().equals(first.getId())));
    }

    private static BrainDocument document(String fileName, String hash) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle(fileName);
        document.setSourceName("Synthetic");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName(fileName);
        document.setDocumentVersion("2026.08");
        document.setContentSha256(hash);
        return document;
    }
}
