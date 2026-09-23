package com.pragmaticds.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.Message;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.AnswerSourceRepository;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.MessageRepository;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.service.ai.AnswerValidationService;
import com.pragmaticds.rag.service.ai.Intent;
import com.pragmaticds.rag.service.ai.IntentRouterService;
import com.pragmaticds.rag.service.ai.ModelAnswer;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.OutputContractService;
import com.pragmaticds.rag.service.ai.PromptBuilderService;
import com.pragmaticds.rag.service.ai.QuestionCategory;
import com.pragmaticds.rag.service.ai.QuestionClassifierService;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.answer.ConversationContextService;
import com.pragmaticds.rag.service.answer.ModelAnswerParser;
import com.pragmaticds.rag.service.answer.PromptQuestionContextService;
import com.pragmaticds.rag.service.audit.AuditLogService;
import com.pragmaticds.rag.service.audit.RagTraceService;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalPlan;
import com.pragmaticds.rag.service.retrieval.RetrievalPlannerService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import com.pragmaticds.rag.service.retrieval.SourceKind;
import com.pragmaticds.rag.service.retrieval.VocabularyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.pragmaticds.rag.TestBrains;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the AskService pipeline and its citation-salvage helpers.
 *
 * Helper tests: when the answer model returns a grounded answer but omits the
 * citations array, the pipeline must attach the retrieved approved sources
 * rather than discard a correct answer and escalate.
 *
 * Pipeline tests: when the answer model REFUSES (flags escalation) despite
 * sufficient retrieval, the pipeline must return a single coherent refusal —
 * never the model's raw refusal text decorated with backfilled citations.
 */
class AskServiceTest {

    private final AnswerCitationService citationService = new AnswerCitationService();

    private RetrievedChunk chunk(String sourceName, String documentName,
                                 String section, Integer pageNumber, LocalDate effectiveDate) {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                "Some grounding content.",
                sourceName, "AGENCY_GUIDELINE",
                documentName, "Doc Title",
                section, pageNumber, effectiveDate,
                0.9, 0.7, 0.83);
    }

    // ---- Pipeline tests ------------------------------------------------

    /** Builds an AskService whose model returns exactly {@code modelJson}, with no answer-confidence floor. */
    private AskService askServiceReturning(String modelJson, List<RetrievedChunk> chunks) {
        return askServiceReturning(modelJson, chunks, null);
    }

    /**
     * A brain-profile repository answering with a profile carrying {@code floor},
     * or no profile at all when {@code floor} is null (the pre-feature shape).
     */
    private static BrainProfileRepository profilesWithFloor(Double floor) {
        BrainProfileRepository profiles = mock(BrainProfileRepository.class);
        if (floor == null) {
            when(profiles.findByBrainId(any())).thenReturn(Optional.empty());
        } else {
            BrainProfile profile = new BrainProfile();
            profile.setAnswerConfidenceFloor(floor);
            when(profiles.findByBrainId(any())).thenReturn(Optional.of(profile));
        }
        return profiles;
    }

    /** Builds an AskService whose model returns exactly {@code modelJson} for a brain with the given floor. */
    private AskService askServiceReturning(String modelJson, List<RetrievedChunk> chunks, Double answerConfidenceFloor) {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), any()))
                .thenReturn(new RetrievalResult(chunks, 1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        AiResponse aiResponse = new AiResponse(modelJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        return new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(answerConfidenceFloor));
    }

    /** Builds an AskService that classifies every question as {@code category}. */
    private AskService askServiceClassifying(QuestionCategory category) {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(category);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(RetrievalResult.empty());

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        return new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));
    }

    private record RetrievalPlannerServiceMocks(
            com.pragmaticds.rag.service.retrieval.RetrievalPlannerService planner) {}

    private AgenticRetrievalService agentic(IntentRouterService intentRouter,
                                            RetrievalPlannerService planner,
                                            VocabularyService vocabulary,
                                            RetrievalService retrieval) {
        return new AgenticRetrievalService(intentRouter, planner, vocabulary, retrieval);
    }

    private RetrievalPlannerServiceMocks plannerMocks() {
        var planner = mock(com.pragmaticds.rag.service.retrieval.RetrievalPlannerService.class);
        when(planner.plan(any(), any(), any()))
                .thenReturn(new RetrievalPlan(Set.of(SourceKind.CORPUS)));
        when(planner.collect(any(), any(), anyString(), any(), any()))
                .thenReturn(PlannedEvidence.empty());
        return new RetrievalPlannerServiceMocks(planner);
    }

    private RagTraceService traceService() {
        RagTraceService trace = mock(RagTraceService.class);
        RagTrace row = mock(RagTrace.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(trace.record(any(), any(), anyString(), any(), any(), any(), anyList(),
                any(), anyList(), anyString(), any(), anyBoolean(), any(), any(), any(), anyMap(), any(), anyString()))
                .thenReturn(row);
        return trace;
    }

    private AskRequest pmiQuestion() {
        return new AskRequest(null, "session-1", "What is PMI?", null, null);
    }

    @ParameterizedTest
    @EnumSource(value = QuestionCategory.class,
            names = {"LEGAL", "TAX", "LIVE_RATES", "FRAUD", "ELIGIBILITY", "PERSONAL_LOOKUP"})
    void categoryRefusalsReturnTheMatchingCannedAnswer(QuestionCategory category) {
        AskResponse response = askServiceClassifying(category).ask(pmiQuestion(), TestBrains.DEFAULT_ID);
        var canned = TestPacks.sample().guardrails().cannedAnswers();
        String expected = switch (category) {
            case LEGAL -> canned.legal();
            case TAX -> canned.tax();
            case LIVE_RATES -> canned.liveRates();
            case FRAUD -> canned.fraud();
            case ELIGIBILITY -> canned.escalation();
            case PERSONAL_LOOKUP -> canned.personalLookup();
            case EDUCATIONAL -> throw new IllegalStateException("not a refusal category");
        };
        assertEquals(expected, response.answer(), "wrong canned answer for " + category);
        assertTrue(response.humanEscalationRequired());
    }

    @Test
    void modelRefusalReturnsCleanRefusalWithoutBackfilledCitations() {
        // The model nondeterministically refuses despite strong retrieval:
        // empty citations, zero confidence, escalation flagged.
        String refusalJson = """
                {"answer":"I cannot find enough information in the approved source context to answer that.",
                 "citations":[],
                 "confidence":0.0,
                 "human_escalation_required":true,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(refusalJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertTrue(response.humanEscalationRequired(), "a refused answer must escalate");
        // The bug: backfilled citations were attached to a refusal, producing a
        // self-contradictory response (refusal text + 8 citations).
        assertTrue(response.citations().isEmpty(),
                "a model refusal must not be decorated with backfilled citations");
        assertEquals(TestPacks.sample().guardrails().cannedAnswers().noSource(), response.answer(),
                "a refusal must return the canned refusal text, never the model's raw refusal");
        assertEquals("pack-disclaimer", response.disclaimer(),
                "response disclaimer must come from the pack, not the model echo");
    }

    @Test
    void groundedAnswerWithoutCitationsStillGetsBackfilled() {
        // Guards that the refusal branch does not over-trigger: a genuine
        // grounded answer (escalation=false) that merely omits citations must
        // still be salvaged with the retrieved sources.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(groundedJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertFalse(response.humanEscalationRequired(), "a grounded answer must not escalate");
        assertEquals(2, response.citations().size(), "omitted citations must be backfilled");
        assertEquals("PMI is private mortgage insurance that may be required on conventional loans.",
                response.answer());
    }

    // ---- Per-brain answer-confidence floor ---------------------------------
    //
    // The retrieval threshold gates the top chunk score BEFORE the model runs.
    // The number the dashboard shows on every answer is the model's own
    // self-reported confidence, which nothing gated: prod served "grounded"
    // answers the model rated 0.30. The floor closes that gap per brain.

    private static final String LOW_CONFIDENCE_JSON = """
            {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
             "citations":[],
             "confidence":0.30,
             "human_escalation_required":false,
             "disclaimer":"d"}""";

    private List<RetrievedChunk> pmiChunks() {
        return List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));
    }

    @Test
    void answerBelowBrainConfidenceFloorEscalatesInsteadOfShipping() {
        AskResponse response = askServiceReturning(LOW_CONFIDENCE_JSON, pmiChunks(), 0.5)
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertTrue(response.humanEscalationRequired(), "0.30 under a 0.5 floor must escalate");
        assertEquals(TestPacks.sample().guardrails().cannedAnswers().escalation(), response.answer(),
                "the visitor gets the canned escalation, never the low-confidence text");
        assertTrue(response.citations().isEmpty(), "an escalation carries no citations");
    }

    @Test
    void answerAtBrainConfidenceFloorIsServed() {
        AskResponse response = askServiceReturning(LOW_CONFIDENCE_JSON, pmiChunks(), 0.30)
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertFalse(response.humanEscalationRequired(), "the floor is inclusive: 0.30 at a 0.30 floor ships");
        assertEquals(0.30, response.confidence(), 1e-9);
    }

    @Test
    void brainWithoutFloorKeepsShippingLowConfidenceAnswers() {
        // Default-off: a brain that never set a floor behaves exactly as before.
        AskResponse response = askServiceReturning(LOW_CONFIDENCE_JSON, pmiChunks(), null)
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertFalse(response.humanEscalationRequired());
        assertEquals(0.30, response.confidence(), 1e-9);
    }

    @Test
    void followUpQuestionRetrievesAndPromptsWithPriorTurnTopic() {
        // Screenshot case: after "where is the link for loan sifter", a bare
        // "how do i use it" must retrieve — and be answered — as if it named
        // Loan Sifter, instead of refusing because "it" grounds nothing.
        UUID convId = UUID.randomUUID();
        Conversation conversation = new Conversation();
        conversation.setUserSessionId("session-1");
        conversation.setBrainId(TestBrains.DEFAULT_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(conversation, "id", convId);

        Message priorUser = new Message();
        priorUser.setRole(Message.ROLE_USER);
        priorUser.setContent("where is the link for loan sifter");
        Message priorAssistant = new Message();
        priorAssistant.setRole(Message.ROLE_ASSISTANT);
        priorAssistant.setContent("The Loan Sifter link is in the Systems dropdown.");

        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(new RetrievalResult(List.of(
                chunk("Acme Dashboard", "site-links-and-actions.md", "Systems", 1, LocalDate.of(2026, 7, 22))),
                1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        AiResponse aiResponse = new AiResponse(
                "{\"answer\":\"Open Loan Sifter from the Systems dropdown.\",\"citations\":[],"
                + "\"confidence\":0.9,\"human_escalation_required\":false,\"disclaimer\":\"d\"}",
                "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findById(convId)).thenReturn(java.util.Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.findByConversationIdOrderByCreatedAtAsc(convId))
                .thenReturn(List.of(priorUser, priorAssistant));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), mock(AuditLogService.class),
                conversations, messages, sources,
                new OutputContractService(), traceService(),
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(),
                new AnswerCitationService(), mock(SpendGuardService.class), profilesWithFloor(null));

        AskRequest followUp = new AskRequest(convId, "session-1", "how do i use it", null, null);
        service.ask(followUp, TestBrains.DEFAULT_ID);

        ArgumentCaptor<String> retrievalQuery = ArgumentCaptor.forClass(String.class);
        verify(retrieval).retrieve(retrievalQuery.capture(), any(), any());
        assertEquals("where is the link for loan sifter how do i use it", retrievalQuery.getValue(),
                "follow-up must retrieve with the prior turn's topic prepended");

        ArgumentCaptor<String> promptQuestion = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).build(promptQuestion.capture(), anyList(), any(), any());
        assertTrue(promptQuestion.getValue().contains("loan sifter"),
                "the answer prompt must also carry the resolved topic, not a bare 'it'");
    }

    @Test
    void answerTraceIncludesResponseTypeVisibilityConfidenceReasonAndValidationOutcome() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        RetrievalResult retrievalResult = new RetrievalResult(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))
        ), 0.73, true);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(retrievalResult);

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenReturn("expanded question");
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> confidenceReason =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(trace).record(any(), eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("expanded question"),
                eq(Intent.GUIDELINE_QUESTION), any(), anyList(), any(), anyList(), eq("PMI is mortgage insurance."),
                eq(0.85), eq(false), eq(ResponseType.ANSWER), eq(ClarificationDecision.answer()),
                eq(SourceVisibility.INTERNAL), eq(Map.of()), confidenceReason.capture(),
                eq("valid"));
        assertEquals(0.73, confidenceReason.getValue().get("retrieval_confidence"));
        assertEquals(1, confidenceReason.getValue().get("source_count"));
        assertEquals("What is PMI?", confidenceReason.getValue().get("selected_query"));
        assertInstanceOf(List.class, confidenceReason.getValue().get("retrieval_attempts"));
    }

    @Test
    void answerTraceSetsCollectedFactsToEmptyMap() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        RetrievalResult retrievalResult = new RetrievalResult(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))
        ), 0.73, true);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(retrievalResult);

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenReturn("expanded question");
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL);

        verify(trace).record(any(), eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("expanded question"),
                eq(Intent.GUIDELINE_QUESTION), any(), anyList(), any(), anyList(), eq("PMI is mortgage insurance."),
                eq(0.85), eq(false), eq(ResponseType.ANSWER), eq(ClarificationDecision.answer()),
                eq(SourceVisibility.INTERNAL), eq(Map.of()), anyMap(), eq("valid"));
    }

    @Test
    void refusalTraceRecordsExplicitEscalationDecisionMetadata() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.LEGAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(RetrievalResult.empty());

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL);

        ArgumentCaptor<ClarificationDecision> decisionCaptor = ArgumentCaptor.forClass(ClarificationDecision.class);
        ArgumentCaptor<String> outcomeCaptor = ArgumentCaptor.forClass(String.class);

        verify(trace).record(any(), eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), any(), any(), any(), anyList(),
                any(), anyList(), eq(TestPacks.sample().guardrails().cannedAnswers().legal()), any(), eq(true),
                eq(ResponseType.ESCALATE), decisionCaptor.capture(), eq(SourceVisibility.INTERNAL), eq(Map.of()),
                eq(Map.of("reason", "classified as LEGAL")), outcomeCaptor.capture());

        ClarificationDecision decision = decisionCaptor.getValue();
        assertEquals(ResponseType.ESCALATE, decision.responseType());
        assertNull(decision.question());
        assertEquals(List.of(), decision.missingFacts());
        assertInstanceOf(Map.class, decision.reason());
        assertEquals(Map.of("decision", "escalate", "reason", "classified as LEGAL"), decision.reason());
        assertEquals("classified as LEGAL", outcomeCaptor.getValue());
    }

    @Test
    void askUsesExplicitVisibilityForRetrieval() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        RetrievalResult retrievalResult = new RetrievalResult(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))
        ), 1.0, true);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(retrievalResult);

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        verify(retrieval).retrieve(eq("What is PMI?"), eq(TestBrains.DEFAULT_ID), same(SourceVisibility.PUBLIC));
    }

    @Test
    void sideEvidenceIsCollectedBeforePromptAndPassedToPromptBuilder() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)));
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(new RetrievalResult(chunks, 1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        PlannedEvidence sideEvidence = PlannedEvidence.empty();
        when(promptBuilder.build(anyString(), anyList(), any(), same(sideEvidence))).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        var planner = mock(com.pragmaticds.rag.service.retrieval.RetrievalPlannerService.class);
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS));
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        when(planner.collect(eq(TestBrains.DEFAULT_ID), eq(plan), eq("What is PMI?"), any(), any()))
                .thenReturn(sideEvidence);
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, planner, vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        var order = inOrder(planner, promptBuilder, router);
        order.verify(planner).collect(eq(TestBrains.DEFAULT_ID), eq(plan), eq("What is PMI?"), any(), any());
        order.verify(promptBuilder).build(eq("What is PMI?"), eq(chunks), eq(TestBrains.DEFAULT_ID), same(sideEvidence));
        order.verify(router).generate(any(), eq(TestBrains.DEFAULT_ID));
    }

    @Test
    void weakInitialRetrievalRetriesWithRewrittenQueryBeforeRefusing() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)));
        when(retrieval.retrieve("PMI", TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC))
                .thenReturn(RetrievalResult.empty());
        when(retrieval.retrieve("private mortgage insurance", TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC))
                .thenReturn(new RetrievalResult(chunks, 0.88, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance.",
                 "citations":[],
                 "confidence":0.87,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route("PMI", "/learn", "PUBLIC")).thenReturn(Intent.PAGE_GUIDANCE);
        when(intentRouter.isCalculationRequest("PMI")).thenReturn(false);
        var planner = mock(com.pragmaticds.rag.service.retrieval.RetrievalPlannerService.class);
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE));
        when(planner.plan(Intent.PAGE_GUIDANCE, "/learn", "PUBLIC")).thenReturn(plan);
        when(planner.collect(TestBrains.DEFAULT_ID, plan, "private mortgage insurance", "/learn", "PUBLIC"))
                .thenReturn(PlannedEvidence.empty());
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(TestBrains.DEFAULT_ID, "PMI")).thenReturn("private mortgage insurance");
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, planner, vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        AskResponse response = service.ask(
                new AskRequest(null, "session-1", "PMI", null, null, "/learn", "PUBLIC"),
                TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        assertFalse(response.humanEscalationRequired());
        assertEquals("PMI is private mortgage insurance.", response.answer());
        verify(retrieval).retrieve("private mortgage insurance", TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);
        verify(promptBuilder).build(anyString(), eq(chunks), eq(TestBrains.DEFAULT_ID), any());
    }

    @Test
    void citationsFromChunksMapsAllFields() {
        List<CitationDto> citations = citationService.citationsFromChunks(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, citations.size());
        CitationDto c = citations.get(0);
        assertEquals("Fannie Mae Selling Guide", c.sourceName());
        assertEquals("selling-guide.pdf", c.documentName());
        assertEquals("B3-3.1-01", c.section());
        assertEquals("12", c.pageNumber());
        assertEquals("2026-01-01", c.effectiveDate());
    }

    @Test
    void citationsFromChunksLeavesMissingMetadataNull() {
        CitationDto c = citationService.citationsFromChunks(List.of(
                chunk("FHA Handbook", "4000.1.pdf", null, null, null))).get(0);

        assertEquals("FHA Handbook", c.sourceName());
        assertNull(c.section());
        assertNull(c.pageNumber());
        assertNull(c.effectiveDate());
    }

    @Test
    void citationsFromChunksMapsEveryChunk() {
        List<CitationDto> citations = citationService.citationsFromChunks(List.of(
                chunk("S1", "d1.pdf", "sec1", 1, LocalDate.of(2026, 1, 1)),
                chunk("S2", "d2.pdf", "sec2", 2, LocalDate.of(2026, 2, 1)),
                chunk("S3", "d3.pdf", "sec3", 3, LocalDate.of(2026, 3, 1))));

        assertEquals(3, citations.size());
    }

    @Test
    void ensureCitationsBackfillsWhenModelReturnsNull() {
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", null, 0.85, false, "d");

        ModelAnswer result = citationService.ensureCitations(answer, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, result.citations().size());
        // Salvage preserves the model's actual answer, not a refusal.
        assertEquals("PMI is mortgage insurance.", result.answer());
        assertEquals(0.85, result.confidence());
        assertFalse(result.humanEscalationRequired());
    }

    @Test
    void ensureCitationsBackfillsWhenModelReturnsEmptyList() {
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", List.of(), 0.85, false, "d");

        ModelAnswer result = citationService.ensureCitations(answer, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, result.citations().size());
    }

    @Test
    void filterToRetrievedKeepsCitationsMatchingADocumentName() {
        List<CitationDto> model = List.of(
                new CitationDto("Some label", "selling-guide.pdf", "B7", "1", null));
        List<CitationDto> kept = citationService.filterToRetrieved(model, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))));
        assertEquals(1, kept.size());
    }

    @Test
    void filterToRetrievedMatchesBySourceNameCaseInsensitively() {
        List<CitationDto> model = List.of(
                new CitationDto("fannie mae selling guide", "renamed.pdf", null, null, null));
        List<CitationDto> kept = citationService.filterToRetrieved(model, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))));
        assertEquals(1, kept.size());
    }

    @Test
    void filterToRetrievedDropsFabricatedCitations() {
        List<CitationDto> model = List.of(
                new CitationDto("Totally Made Up", "ghost.pdf", "Z", "9", null));
        List<CitationDto> kept = citationService.filterToRetrieved(model, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))));
        assertTrue(kept.isEmpty());
    }

    @Test
    void fabricatedModelCitationsAreReplacedWithRetrievedSources() {
        // The model returns a confident, grounded answer but cites a source that
        // was never retrieved — a fabricated citation that must not reach the user.
        String json = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[{"source_name":"Totally Made Up","document_name":"ghost.pdf","section":"Z","page_number":"1"}],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(json, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertFalse(response.humanEscalationRequired());
        assertEquals(1, response.citations().size(), "fabricated citation must be replaced by retrieved sources");
        assertEquals("Fannie Mae Selling Guide", response.citations().get(0).sourceName());
    }

    @Test
    void verifiableModelCitationsArePreservedAndFabricatedOnesDropped() {
        // Mixed: one citation matches a retrieved source, one is fabricated.
        String json = """
                {"answer":"PMI is private mortgage insurance.",
                 "citations":[
                   {"source_name":"Fannie Mae Selling Guide","document_name":"selling-guide.pdf","section":"B7-1","page_number":"1"},
                   {"source_name":"Totally Made Up","document_name":"ghost.pdf","section":"Z","page_number":"9"}],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";

        AskResponse response = askServiceReturning(json, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))))
                .ask(pmiQuestion(), TestBrains.DEFAULT_ID);

        assertEquals(1, response.citations().size());
        assertEquals("selling-guide.pdf", response.citations().get(0).documentName());
    }

    @Test
    void ensureCitationsKeepsModelProvidedCitations() {
        List<CitationDto> modelCitations = List.of(
                new CitationDto("Model Cited Source", "model.pdf", "sec", "5", "2026-01-01"));
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", modelCitations, 0.85, false, "d");

        ModelAnswer result = citationService.ensureCitations(answer, List.of(
                chunk("Retrieved Source", "retrieved.pdf", "other", 99, LocalDate.of(2026, 1, 1))));

        // The model cited its own sources; do not overwrite them with the chunks.
        assertEquals(modelCitations, result.citations());
    }

    // ---- #11 calculation guardrail -------------------------------------

    @Test
    void calculationRequestEscalatesBeforeRetrievalOrModel() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");
        ModelRouterService router = mock(ModelRouterService.class);
        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.CALCULATION);
        when(intentRouter.isCalculationRequest(anyString())).thenReturn(true);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        AskResponse response = service.ask(
                new AskRequest(null, "session-1", "Calculate my monthly payment", null, null),
                TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        assertTrue(response.humanEscalationRequired(), "a calculation request must escalate");
        assertEquals(TestPacks.sample().guardrails().cannedAnswers().escalation(), response.answer());
        // The guard must short-circuit before any embedding or LLM spend.
        verify(retrieval, never()).retrieve(anyString(), any(), any());
        verify(router, never()).generate(any(), any());
    }

    // ---- CB1 spend-cap circuit breaker ----------------------------------

    @Test
    void overBudgetRefusesBeforeRetrievalOrModelSpend() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");
        ModelRouterService router = mock(ModelRouterService.class);
        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        SpendGuardService spendGuard = mock(SpendGuardService.class);
        when(spendGuard.isOverBudget(TestBrains.DEFAULT_ID)).thenReturn(true);

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                spendGuard, profilesWithFloor(null));

        AskResponse response = service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        assertTrue(response.humanEscalationRequired(), "an over-budget brain must escalate");
        assertEquals(TestPacks.sample().guardrails().cannedAnswers().escalation(), response.answer());
        // The breaker must short-circuit before any embedding or LLM spend.
        verify(retrieval, never()).retrieve(anyString(), any(), any());
        verify(router, never()).generate(any(), any());
        verify(spendGuard, never()).recordSpend(any(), any(), any(), any());
    }

    @Test
    void underBudgetProceedsAndRecordsSpendAfterAGroundedAnswer() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)));
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(new RetrievalResult(chunks, 1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude-haiku-4-5", 123, 45);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        SpendGuardService spendGuard = mock(SpendGuardService.class);
        when(spendGuard.isOverBudget(TestBrains.DEFAULT_ID)).thenReturn(false);

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                spendGuard, profilesWithFloor(null));

        AskResponse response = service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        assertFalse(response.humanEscalationRequired());
        verify(spendGuard).recordSpend(TestBrains.DEFAULT_ID, "claude-haiku-4-5", 123L, 45L);
    }

    @Test
    void refusedPathNeverRecordsSpend() {
        // A refusal (insufficient evidence) must never charge the daily budget —
        // recordSpend is reached only via the successful-answer path.
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(RetrievalResult.empty());

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");
        ModelRouterService router = mock(ModelRouterService.class);
        AuditLogService audit = mock(AuditLogService.class);

        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        SpendGuardService spendGuard = mock(SpendGuardService.class);
        when(spendGuard.isOverBudget(TestBrains.DEFAULT_ID)).thenReturn(false);

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                spendGuard, profilesWithFloor(null));

        AskResponse response = service.ask(pmiQuestion(), TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        assertTrue(response.humanEscalationRequired());
        verify(spendGuard, never()).recordSpend(any(), any(), any(), any());
    }

    // ---- #9 clarification facts in the prompt --------------------------

    @Test
    void clarificationFactsAreAppendedToThePromptQuestion() {
        QuestionClassifierService classifier = mock(QuestionClassifierService.class);
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);

        RetrievalService retrieval = mock(RetrievalService.class);
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)));
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(new RetrievalResult(chunks, 1.0, true));

        PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
        when(promptBuilder.build(anyString(), anyList(), any(), any())).thenReturn("PROMPT");
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");

        ModelRouterService router = mock(ModelRouterService.class);
        String groundedJson = """
                {"answer":"PMI is mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AiResponse aiResponse = new AiResponse(groundedJson, "anthropic", "claude", 10, 10);
        when(router.generate(any(), any()))
                .thenReturn(new ModelRouterService.RoutedResponse(aiResponse, false));

        AuditLogService audit = mock(AuditLogService.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MessageRepository messages = mock(MessageRepository.class);
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IntentRouterService intentRouter = mock(IntentRouterService.class);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        RetrievalPlannerServiceMocks plannerMocks = plannerMocks();
        VocabularyService vocabulary = mock(VocabularyService.class);
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTraceService trace = traceService();

        AskService service = new AskService(TestPacks.registry(), classifier, promptBuilder, router,
                new AnswerValidationService(TestPacks.registry()), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                agentic(intentRouter, plannerMocks.planner(), vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                mock(SpendGuardService.class), profilesWithFloor(null));

        AskRequest request = new AskRequest(null, "session-1", "What is PMI?", null, null,
                null, null, Map.of("loan_type", "FHA"));
        service.ask(request, TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC);

        ArgumentCaptor<String> promptQuestion = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).build(promptQuestion.capture(), anyList(), any(), any());
        String captured = promptQuestion.getValue();
        assertTrue(captured.startsWith("What is PMI?"), "raw question must be preserved");
        assertTrue(captured.contains("loan_type: FHA"), "facts must be threaded into the prompt question");
        // Retrieval must still use the raw question, not the facts-augmented one.
        verify(retrieval).retrieve(eq("What is PMI?"), any(), any());
    }
}
