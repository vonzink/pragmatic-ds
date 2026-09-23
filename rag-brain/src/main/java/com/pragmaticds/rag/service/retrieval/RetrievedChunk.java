package com.pragmaticds.rag.service.retrieval;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A chunk selected by hybrid search, with all scores and citation metadata.
 *
 * @param externalDocId the corpus-declared stable id (frontmatter
 *                      {@code document_id:}) of the document this chunk came
 *                      from, or null when it declared none. Not used for
 *                      answering — it is what a retrieval eval joins on to tell
 *                      whether the doc that should have answered actually ranked.
 * @param contentSha256  exact source-content identity for durable snapshot evidence,
 *                      or null for legacy/unversioned sources
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String content,
        UUID parentChunkId,
        String parentContent,
        String hierarchyPath,
        String sourceName,
        String sourceType,
        String documentName,
        String documentTitle,
        String section,
        Integer pageNumber,
        LocalDate effectiveDate,
        double vectorScore,
        double keywordScore,
        double combinedScore,
        String externalDocId,
        String contentSha256
) {
    public RetrievedChunk(UUID chunkId, UUID documentId, String content,
                          String sourceName, String sourceType, String documentName,
                          String documentTitle, String section, Integer pageNumber,
                          LocalDate effectiveDate, double vectorScore, double keywordScore,
                          double combinedScore) {
        this(chunkId, documentId, content, null, null, null, sourceName, sourceType,
                documentName, documentTitle, section, pageNumber, effectiveDate,
                vectorScore, keywordScore, combinedScore, null, null);
    }

    /**
     * Hierarchy-aware form without an external id — kept so callers that predate
     * {@code externalDocId} (and tests that build chunks positionally) still
     * compile; the eval join simply sees null for those.
     */
    public RetrievedChunk(UUID chunkId, UUID documentId, String content,
                          UUID parentChunkId, String parentContent, String hierarchyPath,
                          String sourceName, String sourceType, String documentName,
                          String documentTitle, String section, Integer pageNumber,
                          LocalDate effectiveDate, double vectorScore, double keywordScore,
                          double combinedScore) {
        this(chunkId, documentId, content, parentChunkId, parentContent, hierarchyPath,
                sourceName, sourceType, documentName, documentTitle, section, pageNumber,
                effectiveDate, vectorScore, keywordScore, combinedScore, null, null);
    }

    /** Compatibility form retained for callers that predate snapshot content hashes. */
    public RetrievedChunk(UUID chunkId, UUID documentId, String content,
                          UUID parentChunkId, String parentContent, String hierarchyPath,
                          String sourceName, String sourceType, String documentName,
                          String documentTitle, String section, Integer pageNumber,
                          LocalDate effectiveDate, double vectorScore, double keywordScore,
                          double combinedScore, String externalDocId) {
        this(chunkId, documentId, content, parentChunkId, parentContent, hierarchyPath,
                sourceName, sourceType, documentName, documentTitle, section, pageNumber,
                effectiveDate, vectorScore, keywordScore, combinedScore, externalDocId, null);
    }
}
