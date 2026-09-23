package com.pragmaticds.docengine.ingestion.domain;

/**
 * Human-review lifecycle of a {@code document_package}. Stored as text (V2 check constraint), so
 * names here must match the database exactly.
 */
public enum ReviewStatus {
    NOT_REVIEWED,
    IN_REVIEW,
    REVIEWED
}
