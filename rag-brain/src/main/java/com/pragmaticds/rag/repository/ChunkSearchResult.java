package com.pragmaticds.rag.repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Projection for hybrid search results coming back from native queries.
 * Carries the chunk content plus everything needed to build a citation.
 */
public interface ChunkSearchResult {

    UUID getChunkId();

    UUID getDocumentId();

    String getContent();

    UUID getParentChunkId();

    String getParentContent();

    String getHierarchyPath();

    String getMetadataJson();

    String getSourceName();

    String getSourceType();

    String getDocumentName();

    String getDocumentTitle();

    /**
     * The corpus-declared stable id (frontmatter {@code document_id:}), or null
     * when the source document declared none. Retrieval evals join on this.
     */
    String getExternalDocId();

    /** Exact source bytes identity, pinned for snapshot-backed evidence. */
    String getContentSha256();

    LocalDate getEffectiveDate();

    /** Cosine similarity (vector search) or normalized ts_rank (keyword search). */
    Double getScore();
}
