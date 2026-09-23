package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainDocument;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Admin view of a guideline document.
 */
public record DocumentDto(
        UUID id,
        String title,
        String sourceName,
        String sourceType,
        String visibility,
        String trustLevel,
        String fileName,
        String analyzerScope,
        String documentVersion,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        boolean active
) {

    public static DocumentDto from(BrainDocument doc) {
        return new DocumentDto(
                doc.getId(),
                doc.getTitle(),
                doc.getSourceName(),
                doc.getSourceType().name(),
                doc.getVisibility().name(),
                doc.getTrustLevel().name(),
                doc.getFileName(),
                doc.getAnalyzerScope(),
                doc.getDocumentVersion(),
                doc.getEffectiveDate(),
                doc.getExpirationDate(),
                doc.isActive()
        );
    }
}
