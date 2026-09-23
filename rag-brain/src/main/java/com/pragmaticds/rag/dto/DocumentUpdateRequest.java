package com.pragmaticds.rag.dto;

import java.time.LocalDate;

/**
 * Editable metadata for an existing document. The file itself is never
 * changed here — re-upload + reindex handles content changes.
 * sourceType is a string so an unknown value yields a clean 400 via
 * SourceType.valueOf(...) rather than a Jackson 500.
 */
public record DocumentUpdateRequest(
        String title,
        String sourceName,
        String sourceType,
        String visibility,
        String trustLevel,
        String documentVersion,
        LocalDate effectiveDate,
        LocalDate expirationDate
) {}
