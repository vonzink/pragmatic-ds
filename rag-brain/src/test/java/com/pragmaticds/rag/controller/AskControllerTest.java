package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.BrainResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AskControllerTest {

    private final AskService askService = mock(AskService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final RagProperties properties = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("admin-key"),
            new RagProperties.Analyze(null),
            new RagProperties.RateLimit(10, 60, 120));
    private final AskController controller = new AskController(askService, brainResolver, properties);

    AskControllerTest() {
        when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage"));
    }

    @Test
    void adminApiKeyCanUseLegacyAskRouteWithInternalVisibility() {
        AskRequest request = new AskRequest(null, "session-1", "What is PMI?", null, null, "/", "INTERNAL");
        AskResponse response = new AskResponse(UUID.randomUUID(), "answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(eq(request), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL)))
                .thenReturn(response);

        assertEquals(response, controller.ask(request, null, "admin-key").getBody());
        verify(askService).ask(eq(request), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL));
    }

    @Test
    void legacyAskRejectsPublicTokenCallersAndNeverCallsAskService() {
        AskRequest request = new AskRequest(null, "session-1", "What is PMI?", null, null, "/", "INTERNAL");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.ask(request, null, null));

        assertEquals(401, ex.getStatusCode().value());
        assertEquals("Legacy /api/ai/{slug}/ask requires X-Admin-Api-Key; public callers must use /api/ai/public/{slug}/ask",
                ex.getReason());
        verifyNoInteractions(askService);
        verifyNoInteractions(brainResolver);
    }
}
