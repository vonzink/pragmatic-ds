package com.pragmaticds.rag.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FederationDtos {
    private FederationDtos() {}

    public record BrainSummary(UUID id, String slug, String displayName, boolean active) {}

    public record FederationAskRequest(
            UUID conversationId,
            String sessionId,
            String message,
            String pageRoute,
            Map<String, Object> facts
    ) {}

    public record FederationRetrieveRequest(String question) {}

    public record FederationRetrieveResponse(
            List<RetrievedChunkDto> chunks,
            double confidence,
            boolean sufficientEvidence
    ) {}

    public record RetrievedChunkDto(
            UUID chunkId,
            UUID documentId,
            String content,
            UUID parentChunkId,
            String hierarchyPath,
            String sourceName,
            String sourceType,
            String documentName,
            String documentTitle,
            String section,
            Integer pageNumber,
            LocalDate effectiveDate,
            double combinedScore
    ) {}
}
