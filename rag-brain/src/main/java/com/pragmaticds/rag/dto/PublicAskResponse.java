package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.UUID;

public record PublicAskResponse(
        String responseType,
        String message,
        String answer,
        String clarifyingQuestion,
        List<String> missingFacts,
        List<CitationDto> citations,
        List<PublicRecommendedPageDto> recommendedPages,
        double confidence,
        String nextAction,
        UUID conversationId,
        String disclaimer,
        boolean humanEscalationRequired,
        UUID traceId
) {
}
