package com.pragmaticds.rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * LLM-based reranker: scores hybrid-search candidates for actual relevance
 * to the question. Embedding similarity finds the neighborhood; the reranker
 * picks the right house. Fixes the classic failure where a dense, generic
 * chunk outranks the chunk containing the specific policy table.
 *
 * Fails open: any error returns the original ranking so a reranker outage
 * never takes down the Q&A pipeline.
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    /** Characters of each chunk shown to the reranker — enough to judge relevance. */
    private static final int EXCERPT_CHARS = 600;

    private static final String PROMPT_TEMPLATE = """
            You are a retrieval reranker for a source-grounded RAG system.

            Score how well each numbered source excerpt answers the user question.
            Scoring: 10 = directly states the specific rule/figure asked about;
            5 = related topic but does not contain the specific answer;
            0 = unrelated.

            User question:
            %s

            Source excerpts:
            %s

            Return ONLY a JSON array, no other text, one entry per excerpt:
            [{"index": 0, "score": 7}, {"index": 1, "score": 2}, ...]
            """;

    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;

    public RerankerService(ModelRouterService modelRouterService, ObjectMapper objectMapper) {
        this.modelRouterService = modelRouterService;
        this.objectMapper = objectMapper;
    }

    /**
     * Reranks candidates by LLM relevance score. Returned chunks carry the
     * rerank score (normalized 0..1) as their combinedScore so downstream
     * confidence thresholds work unchanged.
     */
    public List<RetrievedChunk> rerank(String question, List<RetrievedChunk> candidates, int topK,
                                       UUID brainId) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            String prompt = PROMPT_TEMPLATE.formatted(question, formatExcerpts(candidates));
            String response = modelRouterService
                    .generate(AiRequest.forUtility(prompt, 0.0, 800), brainId)
                    .response().content();

            double[] scores = parseScores(response, candidates.size());
            if (scores == null) {
                log.warn("Reranker returned unparseable response; keeping original ranking");
                return candidates.subList(0, Math.min(topK, candidates.size()));
            }

            List<RetrievedChunk> rescored = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                RetrievedChunk c = candidates.get(i);
                rescored.add(new RetrievedChunk(
                        c.chunkId(), c.documentId(), c.content(), c.parentChunkId(),
                        c.parentContent(), c.hierarchyPath(), c.sourceName(),
                        c.sourceType(), c.documentName(), c.documentTitle(), c.section(),
                        c.pageNumber(), c.effectiveDate(),
                        c.vectorScore(), c.keywordScore(),
                        scores[i] / 10.0, c.externalDocId(), c.contentSha256()));
            }
            rescored.sort(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed());
            return rescored.subList(0, Math.min(topK, rescored.size()));

        } catch (Exception e) {
            log.warn("Reranker failed ({}); keeping original ranking", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private String formatExcerpts(List<RetrievedChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String content = excerptSource(candidates.get(i).content());
            sb.append('[').append(i).append("] ")
              .append(content, 0, Math.min(EXCERPT_CHARS, content.length()))
              .append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * The text the reranker should judge for a candidate: the focused child chunk
     * when the content carries assembled parent-section context, otherwise the
     * content unchanged. Retrieval prepends a parent-section preamble to give the
     * answer model broader context, but that preamble is generic and would fill
     * the reranker's short {@link #EXCERPT_CHARS} window, hiding the specific
     * answer and collapsing the relevance score. Splitting on
     * {@link RetrievalService#FOCUSED_CHUNK_MARKER} restores the child as the
     * thing being scored — the same text that was embedded and retrieved.
     */
    static String excerptSource(String content) {
        if (content == null) {
            return "";
        }
        int marker = content.lastIndexOf(RetrievalService.FOCUSED_CHUNK_MARKER);
        if (marker < 0) {
            return content;
        }
        return content.substring(marker + RetrievalService.FOCUSED_CHUNK_MARKER.length());
    }

    /** Parses the JSON score array; null if malformed. */
    double[] parseScores(String response, int expectedCount) {
        try {
            String json = response.strip();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode array = objectMapper.readTree(json.substring(start, end + 1));
            if (!array.isArray()) {
                return null;
            }
            double[] scores = new double[expectedCount];
            int matched = 0;
            for (JsonNode entry : array) {
                int index = entry.path("index").asInt(-1);
                double score = entry.path("score").asDouble(0);
                if (index >= 0 && index < expectedCount) {
                    scores[index] = Math.max(0, Math.min(10, score));
                    matched++;
                }
            }
            // No usable entry (empty array, wrong keys, all indexes out of
            // range) -> treat as unparseable so the caller fails open to the
            // original ranking. Returning the all-zero array here would zero
            // the entire candidate pool and force a false "no source" refusal.
            if (matched == 0) {
                return null;
            }
            return scores;
        } catch (Exception e) {
            return null;
        }
    }
}
