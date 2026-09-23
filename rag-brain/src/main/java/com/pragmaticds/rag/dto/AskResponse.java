package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * Public website answer response (rag.md format).
 */
public record AskResponse(
        UUID conversationId,
        String answer,
        List<CitationDto> citations,
        double confidence,
        boolean humanEscalationRequired,
        String disclaimer,
        RecommendedPageDto recommendedPage,
        List<LinkDto> links,
        String nextAction,
        UUID traceId
) {
}
