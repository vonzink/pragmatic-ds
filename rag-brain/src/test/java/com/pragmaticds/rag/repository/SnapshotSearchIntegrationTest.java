package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.DocumentChunk;
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
import com.pragmaticds.rag.service.ingestion.EmbeddingService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real pgvector proof that snapshot queries ignore later mutable membership changes. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SnapshotSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired BrainDocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired CorpusCollectionRepository collections;
    @Autowired CorpusCollectionDocumentRepository memberships;
    @Autowired CorpusSnapshotRepository snapshots;
    @Autowired CorpusSnapshotCollectionRepository snapshotCollections;
    @Autowired CorpusSnapshotDocumentRepository snapshotDocuments;

    @Test
    void vectorAndKeywordEligibilityRemainPinnedAfterMembershipReplacement() {
        BrainDocument frozen = documents.saveAndFlush(document(
                "Frozen source", "frozen.pdf", "v1", "a".repeat(64)));
        BrainDocument replacement = documents.saveAndFlush(document(
                "Replacement source", "replacement.pdf", "v1", "b".repeat(64)));
        chunks.saveAndFlush(chunk(frozen, 0,
                "frozen eligibility mortgage income", unitVector(0)));
        chunks.saveAndFlush(chunk(replacement, 0,
                "replacement eligibility mortgage income", unitVector(1)));

        CorpusCollection collection = collections.saveAndFlush(new CorpusCollection(
                TestBrains.DEFAULT_ID, "snapshot-search", "Snapshot Search", null));
        memberships.saveAndFlush(new CorpusCollectionDocument(
                collection.getId(), TestBrains.DEFAULT_ID, frozen.getId()));
        CorpusSnapshot snapshot = snapshots.saveAndFlush(new CorpusSnapshot(
                TestBrains.DEFAULT_ID, Map.of("manifestVersion", 1), "c".repeat(64)));
        snapshotCollections.saveAndFlush(new CorpusSnapshotCollection(
                snapshot.getId(), TestBrains.DEFAULT_ID, 0, collection.getId(), 1));
        snapshotDocuments.saveAndFlush(new CorpusSnapshotDocument(
                snapshot.getId(), TestBrains.DEFAULT_ID, collection.getId(), frozen.getId(),
                "v1", "a".repeat(64), SourceVisibility.INTERNAL,
                SourceTrustLevel.APPROVED, null, null));

        memberships.deleteAllByIdCollectionId(collection.getId());
        memberships.saveAndFlush(new CorpusCollectionDocument(
                collection.getId(), TestBrains.DEFAULT_ID, replacement.getId()));

        List<ChunkSearchResult> keyword = chunks.searchByKeywordSnapshot(
                "eligibility", 10, TestBrains.DEFAULT_ID,
                SourceVisibility.INTERNAL.name(), snapshot.getId());
        List<ChunkSearchResult> vector = chunks.searchByVectorSnapshot(
                EmbeddingService.toVectorLiteral(unitVector(1)), 10, TestBrains.DEFAULT_ID,
                SourceVisibility.INTERNAL.name(), snapshot.getId());

        assertEquals(List.of(frozen.getId()), keyword.stream()
                .map(ChunkSearchResult::getDocumentId).distinct().toList());
        assertEquals(List.of(frozen.getId()), vector.stream()
                .map(ChunkSearchResult::getDocumentId).distinct().toList());
        assertEquals("a".repeat(64), keyword.getFirst().getContentSha256());
        assertEquals("a".repeat(64), vector.getFirst().getContentSha256());
    }

    private static BrainDocument document(
            String title, String fileName, String version, String hash) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle(title);
        document.setSourceName(title);
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName(fileName);
        document.setDocumentVersion(version);
        document.setContentSha256(hash);
        return document;
    }

    private static DocumentChunk chunk(
            BrainDocument document, int index, String content, float[] embedding) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(TestBrains.DEFAULT_ID);
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(10);
        chunk.setMetadata(Map.of("section", "income"));
        chunk.setEmbedding(embedding);
        return chunk;
    }

    private static float[] unitVector(int dimension) {
        float[] vector = new float[1536];
        vector[dimension] = 1.0f;
        return vector;
    }
}
