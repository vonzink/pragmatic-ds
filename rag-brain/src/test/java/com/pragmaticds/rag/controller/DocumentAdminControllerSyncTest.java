package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.DocumentUpdateRequest;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.MetadataRefreshReport;
import com.pragmaticds.rag.service.sync.SyncReport;
import com.pragmaticds.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.pragmaticds.rag.TestBrains;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerSyncTest {

    private final SyncService syncService = mock(SyncService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final BrainDocumentRepository documentRepository = mock(BrainDocumentRepository.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            ingestionService,
            documentRepository,
            mock(RetrievalService.class),
            syncService,
            brainResolver);

    DocumentAdminControllerSyncTest() {
        com.pragmaticds.rag.domain.Brain brain = new com.pragmaticds.rag.domain.Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage");
        when(brainResolver.resolve(any())).thenReturn(brain);
    }

    @Test
    void syncPassesDryRunFlagThrough() {
        SyncReport report = new SyncReport(true, Map.of("skip", 1), List.of());
        when(syncService.sync(eq(true), eq(TestBrains.DEFAULT_ID), isNull(), eq(false)))
                .thenReturn(report);

        assertEquals(report, controller.sync(true, null, null, false));
        verify(syncService).sync(eq(true), eq(TestBrains.DEFAULT_ID), isNull(), eq(false));
    }

    @Test
    void syncDefaultsToExecute() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(eq(false), eq(TestBrains.DEFAULT_ID), isNull(), eq(false)))
                .thenReturn(report);

        assertEquals(report, controller.sync(false, null, null, false));
    }

    @Test
    void syncPassesScopeAndForceThrough() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(eq(false), eq(TestBrains.DEFAULT_ID), eq("income"), eq(true)))
                .thenReturn(report);

        assertEquals(report, controller.sync(false, null, "income", true));
        verify(syncService).sync(eq(false), eq(TestBrains.DEFAULT_ID), eq("income"), eq(true));
    }

    @Test
    void syncRejectsInvalidScope() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.sync(false, null, "INVALID!", false));
        org.mockito.Mockito.verifyNoInteractions(syncService);
    }

    @Test
    void syncAcceptsTheSharedScope() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(eq(false), eq(TestBrains.DEFAULT_ID), eq("shared"), eq(false)))
                .thenReturn(report);

        assertEquals(report, controller.sync(false, null, "shared", false));
    }

    // -------------------------------------------------------------------------
    // refresh-metadata: mirrors sync's param-passthrough conventions exactly.
    // -------------------------------------------------------------------------

    @Test
    void refreshMetadataPassesDryRunFlagThrough() {
        MetadataRefreshReport report = new MetadataRefreshReport(true, Map.of("unchanged", 1), List.of());
        when(syncService.refreshMetadata(eq(true), eq(TestBrains.DEFAULT_ID), isNull()))
                .thenReturn(report);

        assertEquals(report, controller.refreshMetadata(true, null, null));
        verify(syncService).refreshMetadata(eq(true), eq(TestBrains.DEFAULT_ID), isNull());
    }

    @Test
    void refreshMetadataDefaultsToExecute() {
        MetadataRefreshReport report = new MetadataRefreshReport(false, Map.of(), List.of());
        when(syncService.refreshMetadata(eq(false), eq(TestBrains.DEFAULT_ID), isNull()))
                .thenReturn(report);

        assertEquals(report, controller.refreshMetadata(false, null, null));
    }

    @Test
    void refreshMetadataPassesScopeThrough() {
        MetadataRefreshReport report = new MetadataRefreshReport(false, Map.of(), List.of());
        when(syncService.refreshMetadata(eq(false), eq(TestBrains.DEFAULT_ID), eq("income")))
                .thenReturn(report);

        assertEquals(report, controller.refreshMetadata(false, null, "income"));
        verify(syncService).refreshMetadata(eq(false), eq(TestBrains.DEFAULT_ID), eq("income"));
    }

    @Test
    void refreshMetadataRejectsInvalidScope() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.refreshMetadata(false, null, "INVALID!"));
        org.mockito.Mockito.verifyNoInteractions(syncService);
    }

    @Test
    void refreshMetadataAcceptsTheSharedScope() {
        MetadataRefreshReport report = new MetadataRefreshReport(false, Map.of(), List.of());
        when(syncService.refreshMetadata(eq(false), eq(TestBrains.DEFAULT_ID), eq("shared")))
                .thenReturn(report);

        assertEquals(report, controller.refreshMetadata(false, null, "shared"));
    }

    @Test
    void uploadPassesVisibilityAndTrustToIngestionAndDto() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.txt", "text/plain", "content".getBytes());
        BrainDocument saved = new BrainDocument();
        saved.setBrainId(TestBrains.DEFAULT_ID);
        saved.setTitle("Policy");
        saved.setSourceName("Internal");
        saved.setSourceType(SourceType.INTERNAL_POLICY);
        saved.setVisibility(SourceVisibility.INTERNAL);
        saved.setTrustLevel(SourceTrustLevel.REFERENCE);
        saved.setFileName("policy.txt");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        var response = controller.upload(file, "Policy", "Internal", SourceType.INTERNAL_POLICY,
                SourceVisibility.INTERNAL, SourceTrustLevel.REFERENCE,
                null, null, null, null);

        assertEquals(SourceVisibility.INTERNAL.name(), response.getBody().visibility());
        assertEquals(SourceTrustLevel.REFERENCE.name(), response.getBody().trustLevel());
        verify(ingestionService).ingest(eq("policy.txt"), eq("content".getBytes()),
                eq("Policy"), eq("Internal"), eq(SourceType.INTERNAL_POLICY),
                eq(SourceVisibility.INTERNAL), eq(SourceTrustLevel.REFERENCE),
                isNull(), isNull(), isNull(), eq(TestBrains.DEFAULT_ID), isNull());
    }

    @Test
    void updateSavesVisibilityAndTrustAndDtoReturnsThem() {
        UUID documentId = UUID.randomUUID();
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle("Old");
        document.setSourceName("Old Source");
        document.setSourceType(SourceType.AGENCY_GUIDELINE);
        document.setVisibility(SourceVisibility.PUBLIC);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName("old.txt");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(BrainDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = controller.update(documentId, new DocumentUpdateRequest(
                "New", "Internal", "INTERNAL_POLICY", "INTERNAL", "REFERENCE",
                "v2", null, null));

        assertEquals(SourceVisibility.INTERNAL, document.getVisibility());
        assertEquals(SourceTrustLevel.REFERENCE, document.getTrustLevel());
        assertEquals("INTERNAL", response.getBody().visibility());
        assertEquals("REFERENCE", response.getBody().trustLevel());
    }
}
