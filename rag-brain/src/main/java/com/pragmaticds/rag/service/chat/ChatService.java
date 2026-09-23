package com.pragmaticds.rag.service.chat;

import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.AgenticRetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * One-shot corpus-RAG chat for the suite's folder-brain chat surface
 * (POST /api/ai/{brain}/chat, behind {@code AnalyzeApiKeyFilter} on the
 * analyze key). Reuses the EXACT retrieval chain {@code AskService} uses —
 * {@link AgenticRetrievalService#plan} then {@link AgenticRetrievalService#retrieve},
 * surface {@code "INTERNAL"} / visibility {@link SourceVisibility#INTERNAL} — so
 * folder chat sees the same corpus AskService's staff surface does. Everything
 * downstream of retrieval is deliberately NOT forked from AskService: chat
 * composes one plain prompt string (folder context + flattened transcript +
 * guideline excerpts + question) and calls the model directly, with no
 * conversation/message/audit-log/RAG-trace persistence (the suite owns the
 * ephemeral transcript, replayed on every call) and no compliance
 * classification / calculation guardrail — chat is an advisory internal
 * surface behind the S2S key, not the public-answer compliance pipeline.
 */
@Service
public class ChatService {

    private final AgenticRetrievalService agenticRetrievalService;
    private final ModelRouterService modelRouterService;
    private final AnswerCitationService answerCitationService;
    private final DomainPackRegistry packRegistry;

    public ChatService(AgenticRetrievalService agenticRetrievalService,
                       ModelRouterService modelRouterService,
                       AnswerCitationService answerCitationService,
                       DomainPackRegistry packRegistry) {
        this.agenticRetrievalService = agenticRetrievalService;
        this.modelRouterService = modelRouterService;
        this.answerCitationService = answerCitationService;
        this.packRegistry = packRegistry;
    }

    /** The existing suite-facing path: verbose router, unchanged behaviour. */
    public ChatResponse answer(ChatRequest request, UUID brainId) {
        return answer(request, brainId, null);
    }

    /**
     * The Income Lab's discussion path. Identical retrieval, prompt, and citations — the ONLY
     * difference is which router entry point runs the model call.
     *
     * <p>{@code generateSanitized} raises a
     * {@link ModelRouterService.SanitizedProviderException} carrying a code, the provider name, the
     * failure's class, and this correlation id, and nothing else. The default
     * {@link ModelRouterService#generate} path instead lets a provider exception — whose message
     * routinely quotes the request URI and the provider's own response body — travel outward. On a
     * Lab surface that body could contain borrower facts, so the Lab may never use it.
     *
     * @param correlationId the id every sanitized failure and log line carries instead of a message
     */
    public ChatResponse answerSanitized(ChatRequest request, UUID brainId, String correlationId) {
        return answer(request, brainId,
                java.util.Objects.requireNonNull(correlationId, "correlationId"));
    }

    private ChatResponse answer(ChatRequest request, UUID brainId, String correlationId) {
        String question = request.question();

        // Same retrieval chain AskService.ask uses: plan() then retrieve(), with
        // no page route (chat is not page-anchored) and surface "INTERNAL" /
        // visibility INTERNAL (staff-only corpus, matching the legacy admin ask
        // route's internal visibility).
        AgenticRetrievalService.AgenticPlan plan =
                agenticRetrievalService.plan(question, brainId, null, "INTERNAL");
        AgenticRetrievalService.AgenticRetrievalResult retrieved = agenticRetrievalService.retrieve(
                plan, question, brainId, null, "INTERNAL", SourceVisibility.INTERNAL);
        List<RetrievedChunk> chunks = retrieved.retrieval().chunks();

        String prompt = buildPrompt(request, chunks, brainId);
        AiRequest aiRequest = AiRequest.forGuidelineAnswer(prompt);
        String content = correlationId == null
                ? modelRouterService.generate(aiRequest, brainId).response().content()
                : modelRouterService.generateSanitized(aiRequest, brainId, correlationId)
                        .response().content();

        List<CitationDto> citations = answerCitationService.citationsFromChunks(chunks);
        return new ChatResponse(content, citations);
    }

    private String buildPrompt(ChatRequest request, List<RetrievedChunk> chunks, UUID brainId) {
        String companyName = packRegistry.bundle(brainId).pack().companyName();

        StringBuilder sb = new StringBuilder();
        sb.append("You are the ").append(companyName)
                .append(" document-folder assistant for mortgage staff. Ground answers in the\n")
                .append("guideline excerpts and the folder context. If the context does not contain the answer, say so.\n\n");

        sb.append("## Folder context (supplied by the LOS - treat as ground truth about the loan file)\n");
        sb.append(blankIfMissing(request.context())).append("\n\n");

        sb.append("## Conversation so far\n");
        sb.append(renderTranscript(request.transcript())).append("\n\n");

        sb.append("## Guideline excerpts\n");
        sb.append(renderChunks(chunks)).append("\n\n");

        sb.append("## Question\n").append(request.question());
        return sb.toString();
    }

    private static String renderTranscript(List<ChatRequest.Turn> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "(no prior turns)";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatRequest.Turn turn : transcript) {
            sb.append(blankIfMissing(turn.role())).append(": ")
                    .append(blankIfMissing(turn.content())).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * Formats retrieved chunks the same way {@code PromptBuilderService}
     * renders them ({@code [Source N]} blocks with source/document/section/
     * page/effective-date metadata) so the model sees a consistent citation
     * shape across the ask and chat surfaces.
     */
    private static String renderChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "(no guideline excerpts found)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("[Source ").append(n++).append("]\n");
            sb.append("source_name: ").append(chunk.sourceName()).append('\n');
            sb.append("document_name: ").append(chunk.documentName()).append('\n');
            if (chunk.section() != null) {
                sb.append("section: ").append(chunk.section()).append('\n');
            }
            if (chunk.pageNumber() != null) {
                sb.append("page_number: ").append(chunk.pageNumber()).append('\n');
            }
            if (chunk.effectiveDate() != null) {
                sb.append("effective_date: ").append(chunk.effectiveDate()).append('\n');
            }
            sb.append("content:\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String blankIfMissing(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
