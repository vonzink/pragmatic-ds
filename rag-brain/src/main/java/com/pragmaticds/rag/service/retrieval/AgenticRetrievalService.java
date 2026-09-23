package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.service.ai.Intent;
import com.pragmaticds.rag.service.ai.IntentRouterService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Query-understanding and retrieval orchestration for the answer pipeline.
 *
 * <p>The loop is bounded and deterministic: it can retry weak retrieval with
 * the editable vocabulary rewrite, and it can gap-fill already sufficient
 * evidence when all top chunks come from the same document. Answer generation
 * and compliance validation stay outside this retrieval layer.
 */
@Service
public class AgenticRetrievalService {

    private final IntentRouterService intentRouterService;
    private final RetrievalPlannerService retrievalPlannerService;
    private final VocabularyService vocabularyService;
    private final RetrievalService retrievalService;

    public AgenticRetrievalService(IntentRouterService intentRouterService,
                                   RetrievalPlannerService retrievalPlannerService,
                                   VocabularyService vocabularyService,
                                   RetrievalService retrievalService) {
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
        this.vocabularyService = vocabularyService;
        this.retrievalService = retrievalService;
    }

    public record AgenticPlan(
            Intent intent,
            RetrievalPlan retrievalPlan,
            String rewrittenQuestion,
            boolean calculationRequest) {}

    public record RetrievalAttempt(
            String step,
            String query,
            double confidence,
            int sourceCount,
            boolean sufficient) {

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step);
            row.put("query", query);
            row.put("confidence", confidence);
            row.put("source_count", sourceCount);
            row.put("sufficient", sufficient);
            return Map.copyOf(row);
        }
    }

    public record AgenticRetrievalResult(
            Intent intent,
            RetrievalPlan retrievalPlan,
            String rewrittenQuestion,
            String selectedQuery,
            RetrievalResult retrieval,
            PlannedEvidence sideEvidence,
            List<RetrievalAttempt> attempts,
            String strategy) {

        public Map<String, Object> confidenceReason() {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("retrieval_confidence", retrieval.confidence());
            reason.put("source_count", retrieval.chunks().size());
            reason.put("distinct_document_count", distinctDocumentCount(retrieval));
            reason.put("selected_query", selectedQuery);
            reason.put("strategy", strategy);
            reason.put("retrieval_attempts", attempts.stream().map(RetrievalAttempt::toMap).toList());
            return Map.copyOf(reason);
        }
    }

    public AgenticPlan plan(String question, UUID brainId, String pageRoute, String surface) {
        Intent intent = intentRouterService.route(question, pageRoute, surface);
        RetrievalPlan plan = retrievalPlannerService.plan(intent, pageRoute, surface);
        String rewrittenQuestion = vocabularyService.previewExpansion(brainId, question);
        boolean calculationRequest = intentRouterService.isCalculationRequest(question);
        return new AgenticPlan(intent, plan, rewrittenQuestion, calculationRequest);
    }

    public AgenticRetrievalResult retrieve(AgenticPlan plan,
                                           String question,
                                           UUID brainId,
                                           String pageRoute,
                                           String surface,
                                           SourceVisibility visibility) {
        RetrievalResult initial = retrievalService.retrieve(question, brainId, visibility);
        RetrievalResult selected = initial;
        String selectedQuery = question;
        String strategy = "initial";
        List<RetrievalAttempt> attempts = new ArrayList<>();
        attempts.add(attempt("initial", question, initial));

        if (!initial.sufficientEvidence() && materiallyDifferent(question, plan.rewrittenQuestion())) {
            RetrievalResult retry = retrievalService.retrieve(plan.rewrittenQuestion(), brainId, visibility);
            attempts.add(attempt("rewrite_retry", plan.rewrittenQuestion(), retry));
            if (preferRetry(initial, retry)) {
                selected = retry;
                selectedQuery = plan.rewrittenQuestion();
                strategy = "rewrite_retry";
            }
        }

        if (shouldGapFill(selected)) {
            String gapFillQuery = gapFillQuery(selectedQuery);
            RetrievalResult gapFill = retrievalService.retrieve(gapFillQuery, brainId, visibility);
            attempts.add(attempt("gap_fill", gapFillQuery, gapFill));
            RetrievalResult merged = merge(selected, gapFill);
            if (distinctDocumentCount(merged) > distinctDocumentCount(selected)) {
                selected = merged;
                strategy = "gap_fill";
            }
        }

        PlannedEvidence sideEvidence = selected.sufficientEvidence()
                ? retrievalPlannerService.collect(brainId, plan.retrievalPlan(), selectedQuery, pageRoute, surface)
                : PlannedEvidence.empty();

        return new AgenticRetrievalResult(plan.intent(), plan.retrievalPlan(), plan.rewrittenQuestion(),
                selectedQuery, selected, sideEvidence, List.copyOf(attempts), strategy);
    }

    private static RetrievalAttempt attempt(String step, String query, RetrievalResult result) {
        return new RetrievalAttempt(step, query, result.confidence(), result.chunks().size(), result.sufficientEvidence());
    }

    private static boolean preferRetry(RetrievalResult initial, RetrievalResult retry) {
        if (retry.sufficientEvidence() && !initial.sufficientEvidence()) {
            return true;
        }
        return retry.confidence() > initial.confidence();
    }

    private static boolean shouldGapFill(RetrievalResult selected) {
        return selected.sufficientEvidence()
                && selected.chunks().size() >= 2
                && distinctDocumentCount(selected) == 1;
    }

    private static String gapFillQuery(String selectedQuery) {
        return selectedQuery + " supporting source evidence";
    }

    private static RetrievalResult merge(RetrievalResult base, RetrievalResult extra) {
        Map<UUID, RetrievedChunk> byChunkId = new LinkedHashMap<>();
        for (RetrievedChunk chunk : base.chunks()) {
            byChunkId.put(chunk.chunkId(), chunk);
        }
        for (RetrievedChunk chunk : extra.chunks()) {
            byChunkId.putIfAbsent(chunk.chunkId(), chunk);
        }
        List<RetrievedChunk> merged = byChunkId.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed())
                .toList();
        return new RetrievalResult(merged, Math.max(base.confidence(), extra.confidence()),
                base.sufficientEvidence() || extra.sufficientEvidence());
    }

    private static int distinctDocumentCount(RetrievalResult retrieval) {
        return (int) retrieval.chunks().stream()
                .map(RetrievedChunk::documentId)
                .distinct()
                .count();
    }

    private static boolean materiallyDifferent(String original, String rewritten) {
        if (rewritten == null || rewritten.isBlank()) {
            return false;
        }
        return !normalize(original).equals(normalize(rewritten));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }
}
