package com.pragmaticds.rag.service.publicapi;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.PublicAskRequest;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.audit.RagTraceService;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.clarification.ClarificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicAskServiceTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final PublicAccessService access = mock(PublicAccessService.class);
    private final ClarificationService clarification = mock(ClarificationService.class);
    private final AskService ask = mock(AskService.class);
    private final RagTraceService traceService = mock(RagTraceService.class);
    private final PublicAskService service = new PublicAskService(brains, access, clarification, ask, traceService);

    @Test
    void clarifyResponseDoesNotCallAnswerModel() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I use FHA?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "Is this for a primary residence?", List.of("occupancy"), Map.of("rule", "eligibility")));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I use FHA?", "/", "PUBLIC", Map.of()));

        assertEquals("CLARIFY", response.responseType());
        assertEquals("Is this for a primary residence?", response.clarifyingQuestion());
        verify(ask, never()).ask(any(), any());
        verifyNoMoreInteractions(ask);
    }

    @Test
    void clarificationRecordsPublicTrace() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I qualify?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "What is the occupancy?", List.of("occupancy"), Map.of("topic", "eligibility")));

        service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I qualify?", "/", "PUBLIC", Map.of()));

        verify(traceService).recordPublicDecision(eq(TestBrains.DEFAULT_ID), eq("s1"),
                eq("Can I qualify?"), eq(Map.of()), any(), eq(SourceVisibility.PUBLIC));
    }

    @Test
    void clarificationTraceIncludesSuppliedFactsAndSessionId() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        RagTrace trace = mock(RagTrace.class);
        when(traceService.recordPublicDecision(any(), any(), any(), any(), any(), any()))
                .thenReturn(trace);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I qualify?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "What is the occupancy?", List.of("occupancy"), Map.of("topic", "eligibility")));

        service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I qualify?", "/", "PUBLIC",
                        Map.of("loan_type", "FHA", "state", "CO")));

        verify(traceService).recordPublicDecision(eq(TestBrains.DEFAULT_ID), eq("s1"),
                eq("Can I qualify?"),
                argThat(facts -> facts.equals(Map.of("loan_type", "FHA", "state", "CO"))),
                any(), eq(SourceVisibility.PUBLIC));
    }

    @Test
    void navigateResponseRecordsPublicDecisionTraceBeforeAnswering() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        RagTrace trace = mock(RagTrace.class);
        when(trace.getId()).thenReturn(UUID.randomUUID());
        when(traceService.recordPublicDecision(any(), any(), any(), any(), any(), any()))
                .thenReturn(trace);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Where should I go next?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.NAVIGATE,
                        "Go to purchase loans.", List.of(), Map.of("reason", "matched route")));
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                UUID.randomUUID(), "Go to purchase loans.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Where should I go next?", "/", "PUBLIC",
                        Map.of("current_page", "/")));

        assertEquals("NAVIGATE", response.responseType());
        verify(traceService).recordPublicDecision(eq(TestBrains.DEFAULT_ID), eq("s1"),
                eq("Where should I go next?"),
                argThat(facts -> facts.equals(Map.of("current_page", "/"))),
                eq(new ClarificationDecision(ResponseType.NAVIGATE,
                        "Go to purchase loans.", List.of(), Map.of("reason", "matched route"))),
                eq(SourceVisibility.PUBLIC));
        verify(ask).ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
    }

    @Test
    void navigateDecisionMapsDownstreamEscalationToPublicEscalateResponse() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        RagTrace trace = mock(RagTrace.class);
        when(trace.getId()).thenReturn(UUID.randomUUID());
        when(traceService.recordPublicDecision(any(), any(), any(), any(), any(), any()))
                .thenReturn(trace);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        ClarificationDecision decision = new ClarificationDecision(ResponseType.NAVIGATE,
                "Go to purchase loans.", List.of(), Map.of("reason", "matched route"));
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Where should I go next?"), eq("PUBLIC"), any()))
                .thenReturn(decision);
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                UUID.randomUUID(), "Please contact support.", List.of(), 0.41, true,
                "disclaimer", null, List.of(), "talk-to-human", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Where should I go next?", "/", "PUBLIC",
                        Map.of("current_page", "/")));

        assertEquals("ESCALATE", response.responseType());
        assertEquals("Please contact support.", response.answer());
        verify(traceService).recordPublicDecision(eq(TestBrains.DEFAULT_ID), eq("s1"),
                eq("Where should I go next?"),
                argThat(facts -> facts.equals(Map.of("current_page", "/"))),
                eq(decision),
                eq(SourceVisibility.PUBLIC));
        verify(ask).ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
    }

    @Test
    void answerResponseMapsExistingAskResponse() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "PUBLIC", Map.of()));

        assertEquals("ANSWER", response.responseType());
        assertEquals("PMI is mortgage insurance.", response.answer());
        assertEquals(0.94, response.confidence());
        assertEquals("disclaimer", response.disclaimer());
        assertFalse(response.humanEscalationRequired());
    }

    @Test
    void answerResponsePropagatesDisclaimerAndEscalationFlag() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Do I qualify for a custom rate?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                UUID.randomUUID(), "Please contact a loan officer.", List.of(), 0.30, true,
                "Educational use only.", null, List.of(), "talk-to-human", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Do I qualify for a custom rate?", "/", "PUBLIC", Map.of()));

        assertEquals("ESCALATE", response.responseType());
        assertEquals("Educational use only.", response.disclaimer());
        assertEquals(true, response.humanEscalationRequired());
    }

    @Test
    void publicAskResponseContractExposesTraceId() {
        boolean hasTraceIdComponent = Arrays.stream(com.pragmaticds.rag.dto.PublicAskResponse.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("traceId"));

        assertTrue(hasTraceIdComponent);
    }

    @Test
    void answerResponseCarriesTraceIdFromRagTraceSoFeedbackCanBeSubmitted() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", traceId));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "PUBLIC", Map.of()));

        assertEquals(traceId, response.traceId());
    }

    @Test
    void clarifyResponseCarriesTraceIdFromRecordedPublicDecision() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        RagTrace trace = mock(RagTrace.class);
        UUID traceId = UUID.randomUUID();
        when(trace.getId()).thenReturn(traceId);
        when(traceService.recordPublicDecision(any(), any(), any(), any(), any(), any()))
                .thenReturn(trace);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I use FHA?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "Is this for a primary residence?", List.of("occupancy"), Map.of("rule", "eligibility")));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I use FHA?", "/", "PUBLIC", Map.of()));

        assertEquals(traceId, response.traceId());
    }

    @Test
    void publicRequestForcesPublicSurfaceAndVisibilityEvenWhenCallerSuppliesInternal() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "INTERNAL", Map.of()));

        ArgumentCaptor<AskRequest> requestCaptor = ArgumentCaptor.forClass(AskRequest.class);
        verify(ask, times(1)).ask(requestCaptor.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
        assertEquals("PUBLIC", requestCaptor.getValue().surface());
    }

    @Test
    void publicRequestThreadsConversationIdToAskRequest() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        service.ask("generic", "token", "https://example.com",
                new PublicAskRequest(conversationId, "s1", "What is PMI?", "/", "PUBLIC", Map.of()));

        ArgumentCaptor<AskRequest> requestCaptor = ArgumentCaptor.forClass(AskRequest.class);
        verify(ask).ask(requestCaptor.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
        assertEquals(conversationId, requestCaptor.getValue().conversationId());
    }
}
