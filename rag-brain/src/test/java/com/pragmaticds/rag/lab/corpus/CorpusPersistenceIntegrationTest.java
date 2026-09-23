package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
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
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Proves the five new JPA mappings can persist and reconstruct a frozen corpus graph. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CorpusPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired CorpusCollectionRepository collections;
    @Autowired CorpusCollectionDocumentRepository memberships;
    @Autowired CorpusSnapshotRepository snapshots;
    @Autowired CorpusSnapshotCollectionRepository snapshotCollections;
    @Autowired CorpusSnapshotDocumentRepository snapshotDocuments;
    @Autowired BrainDocumentRepository documents;

    @Test
    void persistsMembershipAndReconstructsFrozenSnapshotFacts() {
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle("Synthetic versioned document");
        document.setSourceName("Synthetic");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName("synthetic.md");
        document.setDocumentVersion("2026.08");
        document.setContentSha256("a".repeat(64));
        document = documents.saveAndFlush(document);

        CorpusCollection collection = collections.saveAndFlush(new CorpusCollection(
                TestBrains.DEFAULT_ID, "mapping-test", "Mapping Test", null));
        memberships.saveAndFlush(new CorpusCollectionDocument(
                collection.getId(), TestBrains.DEFAULT_ID, document.getId()));

        CorpusSnapshot snapshot = snapshots.saveAndFlush(new CorpusSnapshot(
                TestBrains.DEFAULT_ID, Map.of("manifestVersion", 1), "b".repeat(64)));
        snapshotCollections.saveAndFlush(new CorpusSnapshotCollection(
                snapshot.getId(), TestBrains.DEFAULT_ID, 0, collection.getId(), 1));
        snapshotDocuments.saveAndFlush(new CorpusSnapshotDocument(
                snapshot.getId(), TestBrains.DEFAULT_ID, collection.getId(), document.getId(),
                "2026.08", "a".repeat(64), SourceVisibility.INTERNAL,
                SourceTrustLevel.APPROVED, null, null));

        CorpusSnapshot reloaded = snapshots.findByIdAndBrainId(
                snapshot.getId(), TestBrains.DEFAULT_ID).orElseThrow();
        assertEquals(1, reloaded.getManifest().get("manifestVersion"));
        assertNotNull(reloaded.getCreatedAt());
        assertEquals(1, snapshotCollections
                .findAllByIdSnapshotIdOrderByIdPositionAsc(snapshot.getId()).size());
        assertEquals("2026.08", snapshotDocuments
                .findAllByIdSnapshotIdOrderByIdCollectionIdAscIdDocumentIdAsc(snapshot.getId())
                .getFirst().getDocumentVersion());
        assertEquals(document.getId(), memberships
                .findAllByIdCollectionIdOrderByIdDocumentIdAsc(collection.getId())
                .getFirst().getDocumentId());
    }
}
