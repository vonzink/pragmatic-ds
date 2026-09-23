package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.service.analyze.AnalysisContext;

import java.util.List;

/**
 * Body of POST /api/ai/{brain}/analyze/{slug}/refine — a text-only regenerate.
 * Carries no document bytes: the prior findings ARE the document-derived facts,
 * corrected/supplemented by the analyst notes (on {@code context.analystNotes()})
 * and the chat transcript.
 *
 * @param context             loan snapshot (incl. urlaIncome) + analystNotes; docs list is names only
 * @param priorFindings       the findings JSON from the last /analyze or /refine
 * @param priorReportMarkdown the last report markdown
 * @param transcript          recent chat turns (suite caps the count)
 */
public record RefineRequest(
        AnalysisContext context,
        JsonNode priorFindings,
        String priorReportMarkdown,
        List<ChatTurn> transcript
) {
    public record ChatTurn(String role, String content) {}
}
