package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.UUID;

public record IngestionQualityDto(
        UUID brainId,
        int documentCount,
        int activeDocumentCount,
        int chunkCount,
        int embeddedChunkCount,
        int chunksMissingEmbeddingCount,
        int parentChunkCount,
        int childChunkCount,
        int orphanChildChunkCount,
        int emptyChunkCount,
        int duplicateChunkTextGroups,
        int chunksMissingCitationMetadata,
        List<DocumentQualityDto> documents,
        List<String> warnings
) {

    public record DocumentQualityDto(
            UUID documentId,
            String title,
            String fileName,
            boolean active,
            int chunkCount,
            int embeddedChunkCount,
            int chunksMissingEmbeddingCount,
            int parentChunkCount,
            int childChunkCount,
            int orphanChildChunkCount,
            int emptyChunkCount,
            int chunksMissingCitationMetadata,
            List<String> warnings
    ) {}
}
