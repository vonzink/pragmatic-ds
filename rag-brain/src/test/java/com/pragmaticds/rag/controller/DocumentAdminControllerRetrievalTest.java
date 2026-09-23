package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.DocumentIngestionService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerRetrievalTest {

    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class),
            mock(BrainDocumentRepository.class),
            retrievalService,
            mock(SyncService.class),
            brainResolver);

    DocumentAdminControllerRetrievalTest() {
        when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage"));
    }

    @Test
    void testRetrievalDefaultsToAdminWideVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null, null)).thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, null, null));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null, null);
    }

    @Test
    void testRetrievalCanTargetInternalVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL, null))
                .thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, SourceVisibility.INTERNAL, null));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL, null);
    }

    @Test
    void testRetrievalCanTargetAnAnalyzerScope() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("reserves?", TestBrains.DEFAULT_ID,
                SourceVisibility.INTERNAL, "assets")).thenReturn(result);

        assertEquals(result, controller.testRetrieval("reserves?", null, SourceVisibility.INTERNAL, "assets"));
        verify(retrievalService).retrieveAdmin("reserves?", TestBrains.DEFAULT_ID,
                SourceVisibility.INTERNAL, "assets");
    }
}
