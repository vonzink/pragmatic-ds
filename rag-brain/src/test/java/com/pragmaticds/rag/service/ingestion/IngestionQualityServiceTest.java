package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.DocumentChunk;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.dto.IngestionQualityDto;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test proving the SQL-aggregate ingestion-quality report produces
 * the same metrics the previous in-heap computation did — against real Postgres,
 * so the chunk/embedding/hierarchy/citation/duplicate SQL is exercised for real
 * (and never loads chunk embeddings into memory). Fixture mirrors the original
 * unit test one-for-one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IngestionQualityServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainRepository brains;

    @Autowired
    BrainDocumentRepository documents;

    @Autowired
    DocumentChunkRepository chunks;

    private static float[] embedding() {
        float[] vector = new float[1536];
        vector[0] = 0.1f;
        return vector;
    }

    @Test
    void evaluatesDocumentChunkEmbeddingHierarchyAndCitationHealth() {
        Brain brain = brains.save(new Brain(UUID.randomUUID(), "quality-brain", "Quality Brain"));
        UUID brainId = brain.getId();

        BrainDocument guideline = documents.save(document(brainId, "Guideline"));
        documents.save(document(brainId, "Empty"));
        BrainDocument superseded = documents.save(document(brainId, "Superseded"));
        superseded.setActive(false);
        superseded = documents.save(superseded);

        DocumentChunk parent = chunks.save(chunk(brainId, guideline, 0, "PARENT", null, "PMI section",
                null, Map.of("section", "Overview")));
        chunks.save(chunk(brainId, guideline, 1, "CHILD", parent, "PMI coverage rules",
                embedding(), Map.of("page_number", 4)));
        chunks.save(chunk(brainId, guideline, 2, "CHILD", null, "PMI coverage rules",
                null, Map.of()));
        chunks.save(chunk(brainId, guideline, 3, "CHILD", parent, "   ",
                embedding(), Map.of("section", "Blank")));

        // A single-child section: the hierarchical chunker materializes a PARENT whose
        // text is byte-identical to its only CHILD. Parents are never embedded or
        // retrieved, so this pair must NOT count as a duplicate group.
        DocumentChunk reserves = chunks.save(chunk(brainId, guideline, 4, "PARENT", null,
                "Reserve requirements", null, Map.of("section", "Reserves")));
        chunks.save(chunk(brainId, guideline, 5, "CHILD", reserves, "Reserve requirements",
                embedding(), Map.of("section", "Reserves")));

        // A superseded (inactive) document keeps its chunks for snapshot pins, but
        // retrieval filters is_active = TRUE — its text must NOT count as a duplicate
        // of the active copy either.
        DocumentChunk staleReserves = chunks.save(chunk(brainId, superseded, 0, "PARENT", null,
                "Reserve requirements", null, Map.of("section", "Reserves")));
        chunks.save(chunk(brainId, superseded, 1, "CHILD", staleReserves, "Reserve requirements",
                embedding(), Map.of("section", "Reserves")));

        // Punctuation-only text is real content, not an empty chunk. The empty-chunk
        // count and the duplicate-group count both read the V46 normalized hash; if
        // that normalization ever starts stripping punctuation, this chunk would
        // collapse to md5('') and the report would flag real content as empty.
        chunks.save(chunk(brainId, guideline, 6, "CHILD", reserves, "---",
                embedding(), Map.of("section", "Divider")));

        IngestionQualityService service = new IngestionQualityService(documents, chunks);
        IngestionQualityDto quality = service.evaluate(brainId);

        assertEquals(brainId, quality.brainId());
        assertEquals(3, quality.documentCount());
        assertEquals(2, quality.activeDocumentCount());
        assertEquals(9, quality.chunkCount());
        assertEquals(5, quality.embeddedChunkCount());
        assertEquals(1, quality.chunksMissingEmbeddingCount());
        assertEquals(3, quality.parentChunkCount());
        assertEquals(6, quality.childChunkCount());
        assertEquals(1, quality.orphanChildChunkCount());
        // Only the "   " chunk: the "---" divider must not count.
        assertEquals(1, quality.emptyChunkCount());
        // Only the two active CHILD "PMI coverage rules" chunks form a real group.
        assertEquals(1, quality.duplicateChunkTextGroups());
        assertEquals(1, quality.chunksMissingCitationMetadata());
        assertEquals(3, quality.documents().size());
        assertTrue(quality.warnings().contains("Documents without chunks: 1"));
        assertTrue(quality.warnings().contains("Child chunks missing embeddings: 1"));
        assertTrue(quality.warnings().contains("Orphan child chunks: 1"));
        assertTrue(quality.warnings().contains("Duplicate chunk text groups: 1"));
        assertTrue(quality.documents().stream()
                .filter(doc -> doc.title().equals("Empty"))
                .findFirst()
                .orElseThrow()
                .warnings()
                .contains("Document has no chunks"));
    }

    private static BrainDocument document(UUID brainId, String title) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(brainId);
        document.setTitle(title);
        document.setSourceName("Source");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setFileName(title.toLowerCase() + ".pdf");
        document.setActive(true);
        return document;
    }

    private static DocumentChunk chunk(UUID brainId,
                                       BrainDocument document,
                                       int index,
                                       String type,
                                       DocumentChunk parent,
                                       String content,
                                       float[] embedding,
                                       Map<String, Object> metadata) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(brainId);
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setChunkType(type);
        chunk.setParentChunk(parent);
        chunk.setContent(content);
        chunk.setTokenCount(content == null ? 0 : content.length());
        chunk.setEmbedding(embedding);
        chunk.setMetadata(metadata);
        return chunk;
    }
}
