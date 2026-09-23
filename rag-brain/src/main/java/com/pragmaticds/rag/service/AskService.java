package com.pragmaticds.rag.service;

import com.pragmaticds.rag.domain.AnswerSource;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.Message;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.repository.AnswerSourceRepository;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.MessageRepository;
import com.pragmaticds.rag.service.ai.AnswerValidationService;
import com.pragmaticds.rag.service.ai.Intent;
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
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService.AgenticPlan;
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService.AgenticRetrievalResult;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievalPlan;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * End-to-end question pipeline:
 * retrieve -> build prompt -> generate -> validate -> cite -> persist -> audit.
 *
 * Two refusal paths, both compliance-required:
 * 1. Insufficient evidence  -> "could not find enough information" response.
 * 2. Failed validation      -> escalation response (never the raw model text).
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final DomainPackRegistry packRegistry;

    private final QuestionClassifierService questionClassifierService;
    private final PromptBuilderService promptBuilderService;
    private final ModelRouterService modelRouterService;
    private final AnswerValidationService answerValidationService;
    private final AuditLogService auditLogService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final OutputContractService outputContractService;
    private final RagTraceService ragTraceService;
    private final AgenticRetrievalService agenticRetrievalService;
    private final ModelAnswerParser modelAnswerParser;
    private final PromptQuestionContextService promptQuestionContextService;
    private final ConversationContextService conversationContextService;
    private final AnswerCitationService answerCitationService;
    private final SpendGuardService spendGuardService;
    private final BrainProfileRepository brainProfileRepository;

    public AskService(DomainPackRegistry packRegistry,
                      QuestionClassifierService questionClassifierService,
                      PromptBuilderService promptBuilderService,
                      ModelRouterService modelRouterService,
                      AnswerValidationService answerValidationService,
                      AuditLogService auditLogService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      AnswerSourceRepository answerSourceRepository,
                      OutputContractService outputContractService,
                      RagTraceService ragTraceService,
                      AgenticRetrievalService agenticRetrievalService,
                      ModelAnswerParser modelAnswerParser,
                      PromptQuestionContextService promptQuestionContextService,
                      ConversationContextService conversationContextService,
                      AnswerCitationService answerCitationService,
                      SpendGuardService spendGuardService,
                      BrainProfileRepository brainProfileRepository) {
        this.packRegistry = packRegistry;
        this.questionClassifierService = questionClassifierService;
        this.promptBuilderService = promptBuilderService;
        this.modelRouterService = modelRouterService;
        this.answerValidationService = answerValidationService;
        this.auditLogService = auditLogService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.outputContractService = outputContractService;
        this.ragTraceService = ragTraceService;
        this.agenticRetrievalService = agenticRetrievalService;
        this.modelAnswerParser = modelAnswerParser;
        this.promptQuestionContextService = promptQuestionContextService;
        this.conversationContextService = conversationContextService;
        this.answerCitationService = answerCitationService;
        this.spendGuardService = spendGuardService;
        this.brainProfileRepository = brainProfileRepository;
    }

    public AskResponse ask(AskRequest request, UUID brainId) {
        return ask(request, brainId, SourceVisibility.PUBLIC);
    }

    // NOTE: deliberately NOT @Transactional at the method level. This pipeline
    // makes blocking external calls (embedding + answer model); a method-wide
    // transaction would pin a JDBC connection across that I/O. Each persistence
    // step runs in its own short transaction (repository defaults, plus the
    // REQUIRES_NEW audit/trace writes). Provider failures are converted to a
    // compliance escalation below rather than bubbling up as a 500.
    public AskResponse ask(AskRequest request, UUID brainId, SourceVisibility visibility) {
        Conversation conversation = resolveConversation(request, brainId);
        // Resolve an anaphoric follow-up ("how do I use it") against the earlier
        // turns so retrieval and the model both carry the referenced topic.
        // Read BEFORE the current turn is persisted so the question is never
        // prepended to itself; a self-contained question or the first turn of a
        // conversation is returned unchanged.
        String contextualizedQuestion = conversationContextService.contextualize(
                request.question(), priorUserQuestions(conversation));
        saveMessage(conversation, Message.ROLE_USER, request.question(), null);

        DomainPack.CannedAnswers canned =
                packRegistry.bundle(brainId).pack().guardrails().cannedAnswers();

        // 0. Pre-retrieval guardrail: questions we must not answer are caught
        //    here before any embedding or LLM spend.
        QuestionCategory category = questionClassifierService.classify(request.question(), brainId);
        if (category != QuestionCategory.EDUCATIONAL) {
            return refuse(conversation, request, brainId, RetrievalResult.empty(),
                    categoryAnswer(category, canned), null, "classified as " + category,
                    null, null, null, PlannedEvidence.empty(), visibility);
        }

        AgenticPlan agenticPlan = agenticRetrievalService.plan(
                contextualizedQuestion, brainId, request.pageRoute(), request.surface());
        Intent intent = agenticPlan.intent();
        RetrievalPlan plan = agenticPlan.retrievalPlan();
        String rewrittenQuestion = agenticPlan.rewrittenQuestion();

        // 0b. Calculation guardrail: questions that ask the assistant to produce a
        //     specific number must not be answered from retrieved prose (numeric
        //     hallucination risk). Escalate to a human before any LLM spend.
        if (agenticPlan.calculationRequest()) {
            return refuse(conversation, request, brainId, RetrievalResult.empty(),
                    canned.escalation(), null, "calculation request", rewrittenQuestion,
                    intent, plan, PlannedEvidence.empty(), visibility);
        }

        // 0c. Spend-cap circuit breaker: refuse before any embedding or LLM spend
        //     once the brain has met/exceeded its resolved daily budget. Opt-in
        //     and OFF by default — see SpendGuardService.isOverBudget.
        if (spendGuardService.isOverBudget(brainId)) {
            return refuse(conversation, request, brainId, RetrievalResult.empty(),
                    canned.escalation(), null, "daily spend cap reached", rewrittenQuestion,
                    intent, plan, PlannedEvidence.empty(), visibility);
        }

        // 1. Retrieve approved source context. A transient embedding/provider
        //    error becomes a graceful escalation, not a 500. A genuine
        //    misconfiguration ("not configured") still surfaces as 503.
        AgenticRetrievalResult agenticRetrieval;
        try {
            agenticRetrieval = agenticRetrievalService.retrieve(agenticPlan, contextualizedQuestion, brainId,
                    request.pageRoute(), request.surface(), visibility);
        } catch (RuntimeException e) {
            if (isNotConfigured(e)) {
                throw e;
            }
            log.error("Retrieval/embedding provider error for conversation {}: {}",
                    conversation.getId(), e.getMessage());
            return refuse(conversation, request, brainId, RetrievalResult.empty(), canned.escalation(),
                    null, "retrieval provider error", rewrittenQuestion, intent, plan,
                    PlannedEvidence.empty(), visibility);
        }
        RetrievalResult retrieval = agenticRetrieval.retrieval();
        PlannedEvidence sideEvidence = agenticRetrieval.sideEvidence();

        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), null,
                    "insufficient evidence", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 3. Build the locked prompt and call the model (with fallback). When the
        //    answer provider (and its fallback) fail, return a compliance
        //    escalation instead of surfacing a 500 to the visitor. A genuine
        //    misconfiguration ("not configured") still surfaces as 503.
        // Tailor the prompt with any user-provided facts (e.g. collected during a
        // public clarification turn) so the answer reflects the visitor's stated
        // context. The prompt uses the same contextualized question as retrieval
        // so a follow-up's resolved topic reaches the model; the model is told
        // the facts are context, not source truth.
        String promptQuestion = promptQuestionContextService.appendFacts(contextualizedQuestion, request.facts());
        String prompt = promptBuilderService.build(promptQuestion, retrieval.chunks(), brainId, sideEvidence);
        ModelRouterService.RoutedResponse routed;
        try {
            routed = modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt), brainId);
        } catch (RuntimeException e) {
            if (isNotConfigured(e)) {
                throw e;
            }
            log.error("Answer provider error for conversation {}: {}",
                    conversation.getId(), e.getMessage());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "answer provider error", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4. Parse the model's JSON answer.
        ModelAnswer modelAnswer = modelAnswerParser.parse(routed.response().content());
        if (modelAnswer == null) {
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "unparseable model response", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4a. A model refusal must surface as one coherent refusal. When the
        //     model flags escalation AND cites nothing, it did not ground an
        //     answer — backfilling citations would decorate refusal text with
        //     sources it never used. Escalations WITH citations pass through:
        //     a cited answer plus an escalation flag is a meaningful response.
        if (Boolean.TRUE.equals(modelAnswer.humanEscalationRequired())
                && (modelAnswer.citations() == null || modelAnswer.citations().isEmpty())) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), prompt,
                    "model escalated without citations", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4b. Compliance content gate on the model's RAW answer, BEFORE any
        //     citation backfill. Running this first means post-processing can
        //     never mask a prohibited-phrase / non-compliant answer behind
        //     attached sources.
        var contentValidation = answerValidationService.validateContent(modelAnswer, brainId);
        if (!contentValidation.valid()) {
            log.warn("Answer rejected by content validator: {}", contentValidation.failureReason());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    contentValidation.failureReason(), rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4c. Citation integrity: drop any model citation that does not match a
        //     retrieved source. The model can only honestly cite the [Source N]
        //     blocks it was given (their source/document names are exactly what
        //     we put in the prompt), so a citation to anything else is fabricated
        //     and must not be shown.
        List<CitationDto> verifiedCitations =
                answerCitationService.filterToRetrieved(modelAnswer.citations(), retrieval.chunks());
        int suppliedCitations = modelAnswer.citations() == null ? 0 : modelAnswer.citations().size();
        if (suppliedCitations > verifiedCitations.size()) {
            log.warn("Dropped {} model citation(s) not matching any retrieved source",
                    suppliedCitations - verifiedCitations.size());
        }
        modelAnswer = answerCitationService.withCitations(modelAnswer, verifiedCitations);

        // 4d. Salvage grounded answers that end up citation-less (model omitted
        //     citations, or all of them were fabricated and filtered out). Attach
        //     the approved source chunks rather than discard a correct, grounded
        //     answer. We only reach here when retrieval evidence was sufficient,
        //     so the chunks are the exact sources the prompt was grounded in (and
        //     they are already persisted as the answer trail).
        if (verifiedCitations.isEmpty()) {
            log.info("Model answer had no verifiable citations; attaching {} retrieved source(s)",
                    retrieval.chunks().size());
        }
        modelAnswer = answerCitationService.ensureCitations(modelAnswer, retrieval.chunks());

        // 5. Final compliance backstop (incl. citation presence) — failed answers
        //     are never shown.
        var validation = answerValidationService.validate(modelAnswer, true, brainId);
        if (!validation.valid()) {
            log.warn("Answer rejected by validator: {}", validation.failureReason());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    validation.failureReason(), rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 5a. Per-brain answer-confidence floor. The retrieval threshold gated the
        //     top chunk score before the model ran; this gates the model's OWN
        //     confidence, which is the number persisted and shown on every answer.
        //     Nothing compared it before, so a brain could serve an answer the
        //     model rated 0.30 as "grounded". Null floor = no gate (default).
        Double floor = brainProfileRepository.findByBrainId(brainId)
                .map(BrainProfile::getAnswerConfidenceFloor)
                .orElse(null);
        if (floor != null && modelAnswer.confidence() != null && modelAnswer.confidence() < floor) {
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "model confidence " + modelAnswer.confidence() + " below brain floor " + floor,
                    rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        boolean escalate = Boolean.TRUE.equals(modelAnswer.humanEscalationRequired());
        double confidence = modelAnswer.confidence() != null
                ? modelAnswer.confidence()
                : retrieval.confidence();
        List<CitationDto> citations = modelAnswer.citations() == null
                ? List.of()
                : modelAnswer.citations();

        // 6. Persist the assistant message and its citation trail.
        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT,
                modelAnswer.answer(), routed.response());
        saveAnswerSources(assistantMessage, retrieval.chunks());

        // 6a. Accumulate this call's usage/cost against the brain's daily spend
        //     cap (design: spend-cap circuit breaker). Recorded after a
        //     successful, validated answer only — refused/escalated paths never
        //     reach here, so a blocked request cannot be double-charged.
        spendGuardService.recordSpend(brainId, routed.response().modelName(),
                asLong(routed.response().promptTokens()), asLong(routed.response().completionTokens()));

        // 7. Audit log (own transaction, survives rollbacks). brainId is passed
        //    explicitly: record() runs in a REQUIRES_NEW transaction where
        //    ambient request context is unreliable.
        auditLogService.record(conversation.getId(), conversation.getBrainId(), request.question(),
                retrieval.chunks(), prompt, modelAnswer.answer(), routed.response().providerName(),
                routed.response().modelName(), confidence, routed.fallbackUsed(), escalate);

        OutputContractService.OutputContract contract = outputContractService.build(sideEvidence);
        UUID traceId = ragTraceService.record(conversation.getId(), conversation.getBrainId(),
                request.question(), rewrittenQuestion, intent, plan, retrieval.chunks(), sideEvidence,
                citations, modelAnswer.answer(), confidence, escalate,
                ResponseType.ANSWER, ClarificationDecision.answer(), visibility, Map.of(),
                agenticRetrieval.confidenceReason(),
                "valid").getId();

        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, promptBuilderService.disclaimer(brainId),
                contract.recommendedPage(), contract.links(), contract.nextAction(), traceId);
    }

    // ------------------------------------------------------------------

    /**
     * An operator misconfiguration (no provider/embedding key) is signalled with
     * an IllegalStateException whose message contains "not configured"; those must
     * surface as 503 (handled globally), not be masked as a routine escalation.
     */
    private static boolean isNotConfigured(Throwable e) {
        return e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().contains("not configured");
    }

    /** Widens an AiResponse token count (Integer, provider-reported) to the Long SpendGuardService takes. */
    private static Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private String categoryAnswer(QuestionCategory category, DomainPack.CannedAnswers canned) {
        return switch (category) {
            case ELIGIBILITY -> canned.escalation();
            case LEGAL -> canned.legal();
            case TAX -> canned.tax();
            case LIVE_RATES -> canned.liveRates();
            case FRAUD -> canned.fraud();
            case PERSONAL_LOOKUP -> canned.personalLookupOrEscalation();
            case EDUCATIONAL -> throw new IllegalStateException(
                    "EDUCATIONAL questions must go through the RAG pipeline");
        };
    }

    private AskResponse refuse(Conversation conversation,
                               AskRequest request,
                               UUID brainId,
                               RetrievalResult retrieval,
                               String answerText,
                               String prompt,
                               String reason,
                               String rewrittenQuestion,
                               Intent intent,
                               RetrievalPlan plan,
                               PlannedEvidence sideEvidence,
                               SourceVisibility visibility) {
        log.info("Refusal/escalation for conversation {}: {}", conversation.getId(), reason);

        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT, answerText, null);
        saveAnswerSources(assistantMessage, retrieval.chunks());

        auditLogService.record(conversation.getId(), conversation.getBrainId(), request.question(),
                retrieval.chunks(), prompt, answerText, null, null, retrieval.confidence(), false, true);

        UUID traceId = ragTraceService.record(conversation.getId(), conversation.getBrainId(),
                request.question(), rewrittenQuestion, intent, plan, retrieval.chunks(), sideEvidence,
                List.of(), answerText, retrieval.confidence(), true,
                ResponseType.ESCALATE, new ClarificationDecision(
                        ResponseType.ESCALATE,
                        null,
                        List.of(),
                        Map.of("decision", "escalate", "reason", reason)),
                visibility, Map.of(),
                Map.of("reason", reason), reason).getId();

        return new AskResponse(conversation.getId(), answerText, List.of(),
                retrieval.confidence(), true, promptBuilderService.disclaimer(brainId),
                null, List.of(), null, traceId);
    }

    private Conversation resolveConversation(AskRequest request, UUID brainId) {
        if (request.conversationId() != null) {
            Conversation existing = conversationRepository
                    .findById(request.conversationId()).orElse(null);
            // The conversation must belong to the same website session AND the
            // same brain — prevents one visitor from reading or extending
            // another's chat, and prevents cross-brain conversation sharing.
            if (existing != null
                    && Objects.equals(existing.getUserSessionId(), request.sessionId())
                    && Objects.equals(existing.getBrainId(), brainId)) {
                return existing;
            }
        }
        Conversation conversation = new Conversation();
        conversation.setUserSessionId(request.sessionId());
        conversation.setSource("website");
        conversation.setBrainId(brainId);
        return conversationRepository.save(conversation);
    }

    /**
     * The user questions asked earlier in this conversation, oldest→newest.
     * Empty for a brand-new conversation (never persisted, so no messages yet).
     */
    private List<String> priorUserQuestions(Conversation conversation) {
        if (conversation.getId() == null) {
            return List.of();
        }
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                .filter(m -> Message.ROLE_USER.equals(m.getRole()))
                .map(Message::getContent)
                .toList();
    }

    private Message saveMessage(Conversation conversation, String role, String content,
                                com.pragmaticds.rag.provider.AiResponse aiResponse) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setBrainId(conversation.getBrainId());
        message.setRole(role);
        message.setContent(content);
        if (aiResponse != null) {
            message.setModelProvider(aiResponse.providerName());
            message.setModelName(aiResponse.modelName());
            message.setPromptTokens(aiResponse.promptTokens());
            message.setCompletionTokens(aiResponse.completionTokens());
        }
        return messageRepository.save(message);
    }

    private void saveAnswerSources(Message message, List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            AnswerSource source = new AnswerSource();
            source.setBrainId(message.getBrainId());
            source.setMessageId(message.getId());
            source.setDocumentId(chunk.documentId());
            source.setChunkId(chunk.chunkId());
            source.setSimilarityScore(chunk.combinedScore());
            source.setSourceName(chunk.sourceName());
            source.setDocumentName(chunk.documentName());
            source.setSection(chunk.section());
            source.setPageNumber(chunk.pageNumber());
            source.setEffectiveDate(chunk.effectiveDate());
            answerSourceRepository.save(source);
        }
    }

}
