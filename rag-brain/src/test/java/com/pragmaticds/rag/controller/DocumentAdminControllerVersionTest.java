package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.dto.DocumentUpdateRequest;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A metadata PATCH must never leave a document unversioned. A corpus snapshot refuses such a
 * document and a snapshot already holding it stops resolving, so a visibility-only PATCH that
 * omitted the version (the dashboard form sends null for an empty field) would silently drop the
 * document out of every pinned release.
 */
class DocumentAdminControllerVersionTest {

    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String SHA = "ab".repeat(32);

    private final BrainDocumentRepository documents = mock(BrainDocumentRepository.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class), documents, mock(RetrievalService.class),
            mock(SyncService.class), mock(BrainResolver.class));

    private BrainDocument stored(String version) {
        BrainDocument doc = new BrainDocument();
        doc.setDocumentVersion(version);
        doc.setContentSha256(SHA);
        when(documents.findById(ID)).thenReturn(Optional.of(doc));
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return doc;
    }

    private static DocumentUpdateRequest patch(String version, LocalDate effective) {
        return new DocumentUpdateRequest("Title", "Source", "INTERNAL_POLICY", "PUBLIC",
                "APPROVED", version, effective, null);
    }

    @Test
    void aStatedVersionReplacesTheStoredOne() {
        BrainDocument doc = stored("2026-01-01");
        controller.update(ID, patch("2026-06-03", null));
        assertEquals("2026-06-03", doc.getDocumentVersion());
    }

    @Test
    void anOmittedVersionKeepsTheStoredOne() {
        BrainDocument doc = stored("2026-01-01");
        controller.update(ID, patch(null, LocalDate.of(2026, 9, 1)));
        assertEquals("2026-01-01", doc.getDocumentVersion());
    }

    @Test
    void anOmittedVersionOnAnUnversionedDocumentIsDerived() {
        BrainDocument doc = stored(null);
        controller.update(ID, patch("  ", null));
        assertEquals("sha-" + SHA.substring(0, 12), doc.getDocumentVersion());
    }
}
