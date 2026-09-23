package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Canonical folder-brains analyze response. `findings` is emitted as raw JSON
 * (the per-analyzer shape); `citations` use the {sourceId,title,section,snippet}
 * shape the suite/console expect.
 */
public record AnalyzeResponse(
        String status,
        String reportMarkdown,
        JsonNode findings,
        List<AnalyzeCitation> citations,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        double costUsd,
        int pageCount,
        List<Skipped> skippedDocs,
        String reason,
        List<Filtered> filtered,
        String runId
) {
    public record AnalyzeCitation(String sourceId, String title, String section, String snippet) {}
    public record Skipped(String id, String fileName, String category, String reason) {}

    /**
     * A document whose pages were trimmed by page selection before the model saw it, so the
     * suite can show "kept 9 of 62 pages" and offer a re-run with disablePageFilter.
     */
    public record Filtered(String id, String fileName, int pagesTotal, int pagesKept,
                           List<String> matchedForms) {}
}
