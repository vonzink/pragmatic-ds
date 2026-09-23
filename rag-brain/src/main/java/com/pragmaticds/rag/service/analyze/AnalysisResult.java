package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.dto.CitationDto;

import java.util.List;

/**
 * The engine-internal result of one analyze run. Mapped 1:1 to the HTTP response
 * by AnalyzeController. `findingsJson` is the parsed+validated per-analyzer findings
 * object serialized back to a compact JSON string (jsonb on the suite side).
 */
public record AnalysisResult(
        Status status,
        String reportMarkdown,
        String findingsJson,
        List<CitationDto> citations,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        double costUsd,
        int pageCount,
        List<SkippedDoc> skippedDocs,
        String reason,
        List<FilteredDoc> filtered,
        String runId
) {
    public AnalysisResult {
        filtered = filtered == null ? List.of() : List.copyOf(filtered);
    }

    /** Pre-runId signature — refine paths and legacy tests; runId = null. */
    public AnalysisResult(Status status, String reportMarkdown, String findingsJson,
                          List<CitationDto> citations, String provider, String model,
                          int inputTokens, int outputTokens, double costUsd, int pageCount,
                          List<SkippedDoc> skippedDocs, String reason, List<FilteredDoc> filtered) {
        this(status, reportMarkdown, findingsJson, citations, provider, model,
                inputTokens, outputTokens, costUsd, pageCount, skippedDocs, reason,
                filtered, null);
    }

    /** Convenience constructor for results with no page-trim reports (errors, refine). */
    public AnalysisResult(Status status, String reportMarkdown, String findingsJson,
                          List<CitationDto> citations, String provider, String model,
                          int inputTokens, int outputTokens, double costUsd, int pageCount,
                          List<SkippedDoc> skippedDocs, String reason) {
        this(status, reportMarkdown, findingsJson, citations, provider, model,
                inputTokens, outputTokens, costUsd, pageCount, skippedDocs, reason,
                List.of(), null);
    }

    /** A copy carrying a rewritten findings envelope. Every other component is unchanged. */
    public AnalysisResult withFindingsJson(String rewritten) {
        return new AnalysisResult(status, reportMarkdown, rewritten, citations, provider, model,
                inputTokens, outputTokens, costUsd, pageCount, skippedDocs, reason, filtered,
                runId);
    }

    public enum Status { SUCCESS, REFUSED, ERROR }
}
