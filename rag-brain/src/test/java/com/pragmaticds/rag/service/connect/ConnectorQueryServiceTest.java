package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.FederationDtos;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.clarification.ClarificationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorQueryServiceTest {

    private final ClarificationService clarification = mock(ClarificationService.class);
    private final AskService ask = mock(AskService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ConnectorQueryService service = new ConnectorQueryService(clarification, ask, retrieval);

    @Test
    void askUsesPublicVisibilityAndPublicSafeResponse() {
        Brain brain = brain();
        UUID conversationId = UUID.randomUUID();
        FederationDtos.FederationAskRequest req = new FederationDtos.FederationAskRequest(
                conversationId, "s1", "What is PMI?", "/", Map.of("loanType", "FHA"));
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC)))
                .thenReturn(new AskResponse(conversationId, "PMI answer", List.of(), 0.92,
                        false, "disclaimer", null, List.of(), null, UUID.randomUUID()));

        var response = service.ask(brain, req);

        assertEquals("ANSWER", response.responseType());
        assertEquals("PMI answer", response.answer());
        ArgumentCaptor<AskRequest> request = ArgumentCaptor.forClass(AskRequest.class);
        verify(ask).ask(request.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
        assertEquals("PUBLIC", request.getValue().surface());
        assertEquals("FHA", request.getValue().facts().get("loanType"));
    }

    @Test
    void retrieveUsesPublicVisibilityAndMapsChunks() {
        Brain brain = brain();
        FederationDtos.FederationRetrieveRequest req = new FederationDtos.FederationRetrieveRequest("What is PMI?");
        RetrievedChunk chunk = new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                "PMI content", "Agency", "AGENCY_GUIDELINE", "guide.pdf", "Guide",
                "Insurance", 4, LocalDate.of(2026, 1, 1), 0.9, 0.8, 0.88);
        when(retrieval.retrieve("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC))
                .thenReturn(new RetrievalResult(List.of(chunk), 0.88, true));

        var response = service.retrieve(brain, req);

        assertEquals(0.88, response.confidence());
        assertEquals(true, response.sufficientEvidence());
        assertEquals("PMI content", response.chunks().get(0).content());
        verify(retrieval).retrieve("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);
    }

    private static Brain brain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic Brain");
        brain.setActive(true);
        return brain;
    }
}
