package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.DocumentChunk;
import com.pragmaticds.rag.domain.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the document-reindex duplicate-key bug: deleting a
 * document's chunks and re-inserting new chunks that reuse chunk_index 0..n
 * within ONE transaction. With a derived {@code deleteBy...} (per-row em.remove
 * flushed after the inserts) this threw a duplicate-key violation on
 * UNIQUE (document_id, chunk_index); the bulk {@code @Query} delete executes
 * immediately so the swap succeeds.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DocumentChunkReindexRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    DocumentChunkRepository chunks;

    @Autowired
    BrainDocumentRepository docs;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void reindexSwapReusingChunkIndexesInOneTransactionSucceeds() {
        // Opt out of the @DataJpaTest rollback-only transaction so the service's
        // own single-transaction swap semantics are exercised against real commits.
        TestTransaction.end();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        UUID documentId = tx.execute(status -> {
            BrainDocument document = docs.save(document());
            chunks.saveAll(List.of(chunk(document, 0), chunk(document, 1), chunk(document, 2)));
            return document.getId();
        });

        // The reindex pattern: delete then re-insert reusing indexes 0..2 in one tx.
        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> {
            chunks.deleteByDocumentId(documentId);
            BrainDocument document = docs.findById(documentId).orElseThrow();
            chunks.saveAll(List.of(chunk(document, 0), chunk(document, 1), chunk(document, 2)));
        }));

        Integer remaining = tx.execute(status -> chunks.findByDocumentIdOrderByChunkIndex(documentId).size());
        assertEquals(3, remaining, "reindex should leave exactly the freshly-inserted chunks");
    }

    private static BrainDocument document() {
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle("Handbook");
        document.setSourceName("Source");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setFileName("handbook.pdf");
        document.setActive(true);
        return document;
    }

    private static DocumentChunk chunk(BrainDocument document, int index) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(TestBrains.DEFAULT_ID);
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setChunkType("CHILD");
        chunk.setContent("chunk " + index);
        chunk.setTokenCount(2);
        return chunk;
    }
}
