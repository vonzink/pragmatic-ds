package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.service.storage.StorageService;
import com.pragmaticds.rag.TestBrains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceTest {

    private DocumentIngestionService service;
    private BrainDocumentRepository documentRepository;

    @BeforeEach
    void setUp() {
        StorageService storageService = mock(StorageService.class);
        TextExtractionService textExtractionService = mock(TextExtractionService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        documentRepository = mock(BrainDocumentRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        when(storageService.store(any(), any())).thenReturn("stored-key");
        when(textExtractionService.extract(any(), any())).thenReturn("Extracted body text");
        when(chunkingService.chunkHierarchical(any()))
                .thenReturn(List.of(new TextChunk(0, "chunk", 3, null)));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[]{0.1f}));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DocumentIngestionService(storageService, textExtractionService,
                chunkingService, embeddingService, documentRepository, chunkRepository, txManager);
    }

    private BrainDocument ingest(String fileName, String content, SourceVisibility visibility) {
        return service.ingest(fileName, content.getBytes(StandardCharsets.UTF_8),
                "Title", "Source", SourceType.INTERNAL_POLICY,
                visibility, SourceTrustLevel.APPROVED,
                null, null, null, TestBrains.DEFAULT_ID, null);
    }

    @Test
    void adoptsExternalDocIdFromMarkdownFrontmatter() {
        String md = """
                ---
                document_id: suite_page_pipeline_board
                doc_type: page_reference
                ---
                # Pipeline board
                """;
        BrainDocument doc = ingest("pipeline-board.md", md, SourceVisibility.INTERNAL);
        assertEquals("suite_page_pipeline_board", doc.getExternalDocId());
    }

    @Test
    void leavesExternalDocIdNullWhenFrontmatterDeclaresNone() {
        BrainDocument doc = ingest("plain.md", "# No frontmatter\n", SourceVisibility.INTERNAL);
        assertNull(doc.getExternalDocId());
    }

    @Test
    void nullVisibilityAdoptsMarkdownFrontmatterDeclaration() {
        String md = """
                ---
                doc_type: faq
                visibility: PUBLIC
                ---
                # Corpus doc
                """;
        BrainDocument doc = ingest("faq/hubs.md", md, null);
        assertEquals(SourceVisibility.PUBLIC, doc.getVisibility());
    }

    @Test
    void nullVisibilityWithoutFrontmatterFallsBackToInternal() {
        BrainDocument doc = ingest("notes.md", "# No frontmatter here\n", null);
        assertEquals(SourceVisibility.INTERNAL, doc.getVisibility());
    }

    @Test
    void explicitVisibilityBeatsFrontmatter() {
        String md = """
                ---
                visibility: PUBLIC
                ---
                # Doc
                """;
        BrainDocument doc = ingest("doc.md", md, SourceVisibility.SECURE);
        assertEquals(SourceVisibility.SECURE, doc.getVisibility());
    }

    // A corpus snapshot refuses an unversioned document, so an unversioned ingest could never be
    // pinned by an instance release. The version is the edition date when one is known, else a
    // content-derived label — never invented, and a stated version always wins.

    private BrainDocument ingestVersioned(String content, String version, LocalDate effective) {
        return service.ingest("v.md", content.getBytes(StandardCharsets.UTF_8),
                "Title", "Source", SourceType.INTERNAL_POLICY,
                SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                version, effective, null, TestBrains.DEFAULT_ID, null);
    }

    @Test
    void aStatedVersionIsKept() {
        BrainDocument doc = ingestVersioned("# a\n", "2026-06-03", LocalDate.of(2025, 1, 1));
        assertEquals("2026-06-03", doc.getDocumentVersion());
    }

    @Test
    void anUnversionedDocumentWithAnEditionDateIsVersionedByThatDate() {
        BrainDocument doc = ingestVersioned("# a\n", "  ", LocalDate.of(2026, 4, 1));
        assertEquals("2026-04-01", doc.getDocumentVersion());
    }

    @Test
    void anUnversionedUndatedDocumentIsVersionedByItsContent() {
        BrainDocument doc = ingestVersioned("# a\n", null, null);
        assertEquals("sha-" + doc.getContentSha256().substring(0, 12), doc.getDocumentVersion());
    }
}
