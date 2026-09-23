package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.DocumentChunk;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.CollectionVersionRef;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;
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
import com.pragmaticds.rag.repository.ChunkSearchResult;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.service.ingestion.EmbeddingService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Real pgvector proof that legacy {@code analyzer_scope} retrieval and an equivalent
 * frozen snapshot select the same evidence in the same order.
 *
 * <p>This is the Phase 2 equivalence gate: until it holds, snapshot-scoped retrieval
 * cannot replace {@code analyzer_scope}. The synthetic corpus is deliberately narrow so
 * the comparison is exact rather than statistical — one shared document
 * ({@code analyzer_scope IS NULL}), one in-scope {@code income} document, and one
 * foreign-scope {@code credit} control that must be invisible to both paths.
 *
 * <p>Every document is APPROVED and non-blocked on purpose. Legacy admin search
 * intentionally still returns blocked sources while snapshot execution intentionally
 * drops them, so a blocked row would prove nothing about scope equivalence.
 *
 * <p>The control ranks <em>first</em> under both scoring functions — nearest embedding
 * and densest keyword match. A scope or membership leak therefore fails loudly at the
 * head of the list instead of hiding in the tail.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CorpusScopeSnapshotEquivalenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    private static final UUID BRAIN = TestBrains.DEFAULT_ID;
    private static final String INCOME = "income";
    private static final String CREDIT = "credit";
    private static final String QUERY = "escrow";
    private static final int LIMIT = 10;

    @Autowired BrainDocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired CorpusCollectionRepository collections;
    @Autowired CorpusCollectionDocumentRepository memberships;
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
    void scopeAndSnapshotRetrievalSelectTheSameOrderedEvidence() {
        BrainDocument shared = documents.saveAndFlush(
                document("Shared escrow guidance", "shared.md", null, "a".repeat(64)));
        BrainDocument income = documents.saveAndFlush(
                document("Income escrow guidance", "income.md", INCOME, "b".repeat(64)));
        BrainDocument credit = documents.saveAndFlush(
                document("Credit escrow guidance", "credit.md", CREDIT, "c".repeat(64)));

        // Cover density differs by term count, so keyword ranking is strict, not a tie.
        chunks.saveAndFlush(chunk(shared,
                "escrow escrow escrow reserves", embedding(0.8f, 0.6f)));
        chunks.saveAndFlush(chunk(income,
                "escrow waiver analysis for qualifying borrowers with documented reserves",
                embedding(0.6f, 0.8f)));
        chunks.saveAndFlush(chunk(credit,
                "escrow escrow escrow escrow", embedding(1.0f, 0.0f)));

        UUID snapshotId = freezeEquivalentSnapshot(shared, income);

        String queryVector = EmbeddingService.toVectorLiteral(embedding(1.0f, 0.0f));
        List<ChunkSearchResult> legacyKeyword =
                chunks.searchByKeywordAdmin(QUERY, LIMIT, BRAIN, null, INCOME);
        List<ChunkSearchResult> snapshotKeyword =
                chunks.searchByKeywordSnapshot(QUERY, LIMIT, BRAIN, null, snapshotId);
        List<ChunkSearchResult> legacyVector =
                chunks.searchByVectorAdmin(queryVector, LIMIT, BRAIN, null, INCOME);
        List<ChunkSearchResult> snapshotVector =
                chunks.searchByVectorSnapshot(queryVector, LIMIT, BRAIN, null, snapshotId);

        assertOrderedEquivalence("keyword", legacyKeyword, snapshotKeyword);
        assertOrderedEquivalence("vector", legacyVector, snapshotVector);

        assertAbsent("legacy keyword", legacyKeyword, credit);
        assertAbsent("snapshot keyword", snapshotKeyword, credit);
        assertAbsent("legacy vector", legacyVector, credit);
        assertAbsent("snapshot vector", snapshotVector, credit);

        // The snapshot must reproduce the exact frozen bytes identity, not just the row.
        assertEquals(List.of("a".repeat(64), "b".repeat(64)),
                snapshotVector.stream().map(ChunkSearchResult::getContentSha256).toList(),
                "vector snapshot evidence must carry the frozen content hashes");
    }

    /** Builds shared + income collections that mirror the legacy scope and freezes them. */
    private UUID freezeEquivalentSnapshot(BrainDocument shared, BrainDocument income) {
        CollectionView sharedCollection = collectionService.create(
                BRAIN, "shared", "Shared", "create-shared");
        sharedCollection = collectionService.replaceMembership(new ReplaceMembershipCommand(
                BRAIN, sharedCollection.id(), sharedCollection.version(),
                List.of(shared.getId()), "members-shared"));

        CollectionView incomeCollection = collectionService.create(
                BRAIN, INCOME, "Income", "create-income");
        incomeCollection = collectionService.replaceMembership(new ReplaceMembershipCommand(
                BRAIN, incomeCollection.id(), incomeCollection.version(),
                List.of(income.getId()), "members-income"));

        FrozenCorpusSnapshot frozen = snapshotService.freeze(new SnapshotRequest(BRAIN, List.of(
                new CollectionVersionRef(sharedCollection.id(), sharedCollection.version()),
                new CollectionVersionRef(incomeCollection.id(), incomeCollection.version()))));
        assertEquals(2, frozen.documents().size(),
                "the snapshot must freeze exactly the shared and income documents");
        return frozen.id();
    }

    /**
     * Asserts both paths agree on ranking, not merely on membership. Comparing the ordered
     * chunk and document ids would still pass on an accidental tie, so this also requires
     * strictly separated scores.
     */
    private static void assertOrderedEquivalence(
            String path, List<ChunkSearchResult> legacy, List<ChunkSearchResult> snapshot) {
        assertEquals(2, legacy.size(), path + ": legacy scope must return both eligible chunks");
        assertEquals(ids(legacy), ids(snapshot),
                path + ": snapshot retrieval must return the same chunks in the same order");
        assertNotEquals(legacy.getFirst().getScore(), legacy.getLast().getScore(),
                path + ": ranking proof needs distinct scores, otherwise order is arbitrary");
        assertTrue(legacy.getFirst().getScore() > legacy.getLast().getScore(),
                path + ": results must come back best-first");
        assertEquals(legacy.stream().map(ChunkSearchResult::getScore).toList(),
                snapshot.stream().map(ChunkSearchResult::getScore).toList(),
                path + ": both paths must score identically");
    }

    private static void assertAbsent(
            String path, List<ChunkSearchResult> results, BrainDocument excluded) {
        assertFalse(results.stream()
                        .anyMatch(hit -> excluded.getId().equals(hit.getDocumentId())),
                path + ": foreign-scope control must never be eligible");
    }

    private static List<List<UUID>> ids(List<ChunkSearchResult> results) {
        return results.stream()
                .map(hit -> List.of(hit.getDocumentId(), hit.getChunkId()))
                .toList();
    }

    private static BrainDocument document(
            String title, String fileName, String analyzerScope, String hash) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(BRAIN);
        document.setTitle(title);
        document.setSourceName("Synthetic");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName(fileName);
        document.setDocumentVersion("2026.08");
        document.setContentSha256(hash);
        document.setAnalyzerScope(analyzerScope);
        document.setEffectiveDate(LocalDate.of(2020, 1, 1));
        return document;
    }

    private static DocumentChunk chunk(
            BrainDocument document, String content, float[] embedding) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(BRAIN);
        chunk.setDocument(document);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        chunk.setTokenCount(10);
        chunk.setMetadata(Map.of("section", "escrow"));
        chunk.setEmbedding(embedding);
        return chunk;
    }

    /** Unit-length synthetic embedding so cosine distance is exact and reproducible. */
    private static float[] embedding(float first, float second) {
        float[] vector = new float[1536];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }
}
