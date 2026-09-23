package com.pragmaticds.rag.service.chat;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.Intent;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievalPlan;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import com.pragmaticds.rag.service.retrieval.SourceKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retrieval reuse: {@link ChatService} must drive the exact same
 * {@link AgenticRetrievalService#plan}/{@link AgenticRetrievalService#retrieve}
 * chain {@code AskService} uses (surface "INTERNAL", visibility
 * {@link SourceVisibility#INTERNAL}, no page route), then compose ONE prompt
 * string and call the model router directly — no persistence, no compliance
 * classification.
 */
class ChatServiceTest {

    private static final UUID BRAIN_ID = TestBrains.DEFAULT_ID;

    private final AgenticRetrievalService retrievalService = mock(AgenticRetrievalService.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AnswerCitationService citationService = new AnswerCitationService();
    private final DomainPackRegistry packRegistry = TestPacks.registry();

    private final ChatService chatService =
            new ChatService(retrievalService, router, citationService, packRegistry);

    private RetrievedChunk chunk() {
        return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), "Some grounding content.",
                "Selling Guide", "AGENCY_GUIDELINE", "Doc.pdf", "Doc Title",
                "3.2", 4, LocalDate.of(2026, 1, 1), 0.9, 0.7, 0.83);
    }

    private AgenticRetrievalService.AgenticPlan stubRetrieval(List<RetrievedChunk> chunks) {
        AgenticRetrievalService.AgenticPlan plan = new AgenticRetrievalService.AgenticPlan(
                Intent.GUIDELINE_QUESTION, new RetrievalPlan(Set.of(SourceKind.CORPUS)), "rewritten", false);
        when(retrievalService.plan(anyString(), eq(BRAIN_ID), isNull(), eq("INTERNAL"))).thenReturn(plan);

        AgenticRetrievalService.AgenticRetrievalResult result = new AgenticRetrievalService.AgenticRetrievalResult(
                plan.intent(), plan.retrievalPlan(), plan.rewrittenQuestion(), "selected-query",
                new RetrievalResult(chunks, chunks.isEmpty() ? 0.0 : 0.9, !chunks.isEmpty()),
                PlannedEvidence.empty(), List.of(), "initial");
        when(retrievalService.retrieve(eq(plan), anyString(), eq(BRAIN_ID), isNull(),
                eq("INTERNAL"), eq(SourceVisibility.INTERNAL))).thenReturn(result);
        return plan;
    }

    private void stubRouter(String content) {
        AiResponse response = new AiResponse(content, "anthropic", "claude", 100, 40);
        when(router.generate(any(), eq(BRAIN_ID))).thenReturn(new ModelRouterService.RoutedResponse(response, false));
    }

    @Test
    void reusesAgenticRetrievalWithInternalSurfaceAndVisibility() {
        stubRetrieval(List.of(chunk()));
        stubRouter("answer");

        chatService.answer(new ChatRequest("What is the minimum credit score?", null, null), BRAIN_ID);

        verify(retrievalService).plan("What is the minimum credit score?", BRAIN_ID, null, "INTERNAL");
        verify(retrievalService).retrieve(any(), eq("What is the minimum credit score?"),
                eq(BRAIN_ID), isNull(), eq("INTERNAL"), eq(SourceVisibility.INTERNAL));
    }

    @Test
    void answersWithModelContentAndCitationsFromRetrievedChunks() {
        RetrievedChunk chunk = chunk();
        stubRetrieval(List.of(chunk));
        stubRouter("Here is the guideline answer.");

        ChatRequest request = new ChatRequest("What is the minimum credit score?", "Folder: Income", List.of());
        ChatResponse response = chatService.answer(request, BRAIN_ID);

        assertEquals("Here is the guideline answer.", response.answer());
        assertEquals(1, response.citations().size());
        assertEquals("Selling Guide", response.citations().get(0).sourceName());
        assertEquals("Doc.pdf", response.citations().get(0).documentName());
    }

    @Test
    void answersWithNoCitationsWhenRetrievalIsEmpty() {
        stubRetrieval(List.of());
        stubRouter("I could not find that in the guidelines.");

        ChatRequest request = new ChatRequest("Some obscure question", null, null);
        ChatResponse response = chatService.answer(request, BRAIN_ID);

        assertTrue(response.citations().isEmpty());
        assertEquals("I could not find that in the guidelines.", response.answer());
    }

    @Test
    void promptFlattensTranscriptFolderContextAndGuidelineExcerpts() {
        stubRetrieval(List.of(chunk()));
        stubRouter("answer");

        List<ChatRequest.Turn> transcript = List.of(
                new ChatRequest.Turn("user", "What about FHA?"),
                new ChatRequest.Turn("assistant", "FHA allows lower scores."));
        ChatRequest request = new ChatRequest("And VA?", "Folder: Income, 2 documents", transcript);

        chatService.answer(request, BRAIN_ID);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(BRAIN_ID));
        String prompt = captor.getValue().prompt();

        assertTrue(prompt.contains("Folder: Income, 2 documents"), "folder context must appear in the prompt");
        assertTrue(prompt.contains("user: What about FHA?"), "prior user turn must be flattened into the prompt");
        assertTrue(prompt.contains("assistant: FHA allows lower scores."),
                "prior assistant turn must be flattened into the prompt");
        assertTrue(prompt.contains("And VA?"), "the current question must appear in the prompt");
        assertTrue(prompt.contains("Selling Guide"), "retrieved chunk source name must appear as a guideline excerpt");
        assertTrue(prompt.contains("Some grounding content."),
                "retrieved chunk content must appear as a guideline excerpt");
        assertEquals(1500, captor.getValue().maxTokens());
    }

    @Test
    void emptyTranscriptAndContextRenderPlaceholders() {
        stubRetrieval(List.of());
        stubRouter("answer");

        chatService.answer(new ChatRequest("A question", null, null), BRAIN_ID);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(BRAIN_ID));
        String prompt = captor.getValue().prompt();

        assertTrue(prompt.contains("(no prior turns)"));
        assertTrue(prompt.contains("(no guideline excerpts found)"));
    }
}
