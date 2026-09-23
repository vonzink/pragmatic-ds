package com.pragmaticds.rag.repository;

import java.util.UUID;

/**
 * Projection for per-document chunk-quality counts aggregated in SQL by
 * {@link DocumentChunkRepository#aggregateQualityByDocument(UUID)}. Keeps the
 * ingestion-quality report off the heap: counts are computed in one grouped
 * scan instead of hydrating every {@code DocumentChunk} (and its embedding).
 */
public interface DocumentChunkQualityCounts {

    UUID getDocumentId();

    long getChunkCount();

    long getEmbeddedChunkCount();

    /** Child chunks with no embedding. */
    long getMissingEmbeddingCount();

    long getParentChunkCount();

    long getChildChunkCount();

    /** Child chunks with no parent chunk. */
    long getOrphanChildChunkCount();

    /** Chunks whose content is null or entirely whitespace. */
    long getEmptyChunkCount();

    /** Child chunks lacking any citation metadata (section/heading/hierarchy_path/page_number). */
    long getMissingCitationMetadataCount();
}
