package com.pragmaticds.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.TestPacks;
import com.pragmaticds.rag.repository.AnswerSourceRepository;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.MessageRepository;
import com.pragmaticds.rag.service.ai.AnswerValidationService;
import com.pragmaticds.rag.service.ai.Intent;
import com.pragmaticds.rag.service.ai.IntentRouterService;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.OutputContractService;
import com.pragmaticds.rag.service.ai.PromptBuilderService;
import com.pragmaticds.rag.service.ai.QuestionCategory;
import com.pragmaticds.rag.service.ai.QuestionClassifierService;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.answer.ModelAnswerParser;
import com.pragmaticds.rag.service.answer.ConversationContextService;
import com.pragmaticds.rag.service.answer.PromptQuestionContextService;
import com.pragmaticds.rag.service.audit.AuditLogService;
import com.pragmaticds.rag.service.audit.RagTraceService;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalPlan;
import com.pragmaticds.rag.service.retrieval.RetrievalPlannerService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.SourceKind;
import com.pragmaticds.rag.service.retrieval.VocabularyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Brain-awareness of the write path: the resolved brainId must reach the
 * REQUIRES_NEW audit record (explicit-param carrier), and conversation reuse
 * must require a brain match (no cross-brain conversation sharing).
 */
class AskServiceBrainTest {

    private static final UUID BRAIN_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRAIN_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final QuestionClassifierService classifier = mock(QuestionClassifierService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final AnswerSourceRepository sources = mock(AnswerSourceRepository.class);
    private final IntentRouterService intentRouter = mock(IntentRouterService.class);
    private final RetrievalPlannerService planner = mock(RetrievalPlannerService.class);
    private final VocabularyService vocabulary = mock(VocabularyService.class);
    private final RagTraceService trace = mock(RagTraceService.class);
    private final SpendGuardService spendGuard = mock(SpendGuardService.class);
    private final com.pragmaticds.rag.repository.BrainProfileRepository profiles =
            mock(com.pragmaticds.rag.repository.BrainProfileRepository.class);

    private AskService askService() {
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);
        // Insufficient evidence → short refusal path: still saves conversation,
        // message, sources, and records the audit row — all the writers we care about.
        when(retrieval.retrieve(anyString(), any(), any())).thenReturn(RetrievalResult.empty());
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        when(planner.plan(any(), any(), any())).thenReturn(new RetrievalPlan(Set.of(SourceKind.CORPUS)));
        when(planner.collect(any(), any(), anyString(), any(), any())).thenReturn(PlannedEvidence.empty());
        when(vocabulary.previewExpansion(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RagTrace row = mock(RagTrace.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(trace.record(any(), any(), anyString(), any(), any(), any(), anyList(),
                any(), anyList(), anyString(), any(), anyBoolean(), any(), any(), any(), anyMap(), any(), anyString()))
                .thenReturn(row);
        when(conversations.save(any())).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            if (c.getId() == null) {
                ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            }
            return c;
        });
        DomainPackRegistry registry = TestPacks.registryFor(java.util.Map.of(
                BRAIN_A, TestPacks.sample(), BRAIN_B, TestPacks.sample()));
        return new AskService(registry, classifier, promptBuilder, router,
                new AnswerValidationService(registry), audit,
                conversations, messages, sources,
                new OutputContractService(), trace,
                new AgenticRetrievalService(intentRouter, planner, vocabulary, retrieval),
                new ModelAnswerParser(new ObjectMapper()),
                new PromptQuestionContextService(), new ConversationContextService(), new AnswerCitationService(),
                spendGuard, profiles);
    }

    private AskRequest request(UUID conversationId) {
        return new AskRequest(conversationId, "session-1", "What is PMI?", null, null);
    }

    @Test
    void auditRecordCarriesResolvedBrainId() {
        askService().ask(request(null), BRAIN_A);

        ArgumentCaptor<UUID> brainArg = ArgumentCaptor.forClass(UUID.class);
        verify(audit).record(any(), brainArg.capture(), anyString(), anyList(),
                any(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
        assertEquals(BRAIN_A, brainArg.getValue(),
                "the brainId resolved at the controller must reach the REQUIRES_NEW audit record");
    }

    @Test
    void newConversationIsStampedWithResolvedBrain() {
        askService().ask(request(null), BRAIN_A);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertEquals(BRAIN_A, saved.getValue().getBrainId(),
                "a new conversation must be stamped with the resolved brain");
    }

    @Test
    void existingConversationFromAnotherBrainIsNotReused() {
        UUID conversationId = UUID.randomUUID();
        Conversation other = new Conversation();
        other.setUserSessionId("session-1");
        other.setBrainId(BRAIN_B);                       // belongs to a DIFFERENT brain
        ReflectionTestUtils.setField(other, "id", conversationId);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(other));

        askService().ask(request(conversationId), BRAIN_A);

        // The cross-brain conversation must NOT be extended; a new one is created.
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertEquals(BRAIN_A, saved.getValue().getBrainId());
        assertNotEquals(conversationId, saved.getValue().getId(),
                "a conversation from another brain must not be reused");
    }

    @Test
    void existingConversationFromSameBrainIsReused() {
        UUID conversationId = UUID.randomUUID();
        Conversation same = new Conversation();
        same.setUserSessionId("session-1");
        same.setBrainId(BRAIN_A);                        // same brain, same session
        ReflectionTestUtils.setField(same, "id", conversationId);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(same));

        askService().ask(request(conversationId), BRAIN_A);

        // Same-brain, same-session conversation is reused: no new conversation saved.
        verify(conversations, never()).save(any());
    }
}
