package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * {@code md5('')}: the value of the V46 {@code content_norm_md5} generated column
     * for a chunk whose text is empty after normalization (empty or all-whitespace).
     * Shared by the empty-chunk count and the duplicate-group count so the two
     * consumers of that column cannot drift apart.
     */
    String EMPTY_NORMALIZED_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    /**
     * Bulk-deletes all chunks for a document as an immediate DML statement.
     * This MUST be a {@code @Query} bulk delete (not a derived {@code deleteBy...}):
     * a derived delete queues per-row {@code em.remove()} actions that Hibernate
     * flushes AFTER pending inserts, so reindex's delete-then-reinsert (reusing
     * {@code chunk_index} 0..n) would collide with the not-yet-deleted old rows
     * under {@code UNIQUE (document_id, chunk_index)}. {@code flushAutomatically}
     * flushes prior changes first; the delete then executes before persistChunks.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from DocumentChunk c where c.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Vector similarity search (cosine) over chunks of active, currently
     * effective documents. The embedding is passed as a pgvector literal
     * string, e.g. "[0.12,-0.34,...]".
     */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND (:visibility IS NULL OR d.visibility = :visibility)
              AND (:scope IS NULL OR d.analyzer_scope IS NULL OR d.analyzer_scope = :scope)
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByVectorAdmin(@Param("embedding") String embedding,
                                                @Param("limit") int limit,
                                                @Param("brainId") UUID brainId,
                                                @Param("visibility") String visibility,
                                                @Param("scope") String scope);

    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND d.visibility = :visibility
              AND d.trust_level <> 'BLOCKED'
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByVector(@Param("embedding") String embedding,
                                           @Param("limit") int limit,
                                           @Param("brainId") UUID brainId,
                                           @Param("visibility") String visibility);

    /**
     * Full-text keyword search using websearch syntax (handles quoted phrases,
     * OR, minus). ts_rank_cd is normalized by document length (flag 32) so the
     * score lands in 0..1 territory, comparable to cosine similarity.
     */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', :query), 32) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND (:visibility IS NULL OR d.visibility = :visibility)
              AND (:scope IS NULL OR d.analyzer_scope IS NULL OR d.analyzer_scope = :scope)
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.content_tsv @@ websearch_to_tsquery('english', :query)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByKeywordAdmin(@Param("query") String query,
                                                 @Param("limit") int limit,
                                                 @Param("brainId") UUID brainId,
                                                 @Param("visibility") String visibility,
                                                 @Param("scope") String scope);

    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', :query), 32) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND d.visibility = :visibility
              AND d.trust_level <> 'BLOCKED'
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.content_tsv @@ websearch_to_tsquery('english', :query)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByKeyword(@Param("query") String query,
                                            @Param("limit") int limit,
                                            @Param("brainId") UUID brainId,
                                            @Param("visibility") String visibility);

    /** Vector search whose eligibility comes only from one immutable corpus snapshot. */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE EXISTS (
                SELECT 1
                FROM brain_corpus_snapshot_document sd
                WHERE sd.snapshot_id = :snapshotId
                  AND sd.document_id = c.document_id
                  AND sd.brain_id = c.brain_id
                  AND sd.document_version = d.document_version
                  AND sd.content_sha256 = d.content_sha256
                  AND sd.visibility = d.visibility
                  AND sd.trust_level = d.trust_level
                  AND sd.effective_date IS NOT DISTINCT FROM d.effective_date
                  AND sd.expiration_date IS NOT DISTINCT FROM d.expiration_date
            )
              AND d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND (:visibility IS NULL OR d.visibility = :visibility)
              AND d.trust_level <> 'BLOCKED'
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByVectorSnapshot(
            @Param("embedding") String embedding,
            @Param("limit") int limit,
            @Param("brainId") UUID brainId,
            @Param("visibility") String visibility,
            @Param("snapshotId") UUID snapshotId);

    /** Keyword search whose eligibility comes only from one immutable corpus snapshot. */
    @Query(value = """
            SELECT c.id                                            AS chunkId,
                   c.document_id                                   AS documentId,
                   c.content                                       AS content,
                   p.id                                            AS parentChunkId,
                   p.content                                       AS parentContent,
                   c.hierarchy_path                                AS hierarchyPath,
                   c.metadata::text                                AS metadataJson,
                   d.source_name                                   AS sourceName,
                   d.source_type                                   AS sourceType,
                   d.file_name                                     AS documentName,
                   d.title                                         AS documentTitle,
                   d.external_doc_id                               AS externalDocId,
                   d.content_sha256                                AS contentSha256,
                   d.effective_date                                AS effectiveDate,
                   ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', :query), 32) AS score
            FROM brain_document_chunks c
            LEFT JOIN brain_document_chunks p ON p.id = c.parent_chunk_id
            JOIN brain_documents d ON d.id = c.document_id
            WHERE EXISTS (
                SELECT 1
                FROM brain_corpus_snapshot_document sd
                WHERE sd.snapshot_id = :snapshotId
                  AND sd.document_id = c.document_id
                  AND sd.brain_id = c.brain_id
                  AND sd.document_version = d.document_version
                  AND sd.content_sha256 = d.content_sha256
                  AND sd.visibility = d.visibility
                  AND sd.trust_level = d.trust_level
                  AND sd.effective_date IS NOT DISTINCT FROM d.effective_date
                  AND sd.expiration_date IS NOT DISTINCT FROM d.expiration_date
            )
              AND d.is_active = TRUE
              AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
              AND (d.expiration_date IS NULL OR d.expiration_date >= CURRENT_DATE)
              AND (:visibility IS NULL OR d.visibility = :visibility)
              AND d.trust_level <> 'BLOCKED'
              AND c.brain_id = :brainId
              AND COALESCE(c.chunk_type, 'CHILD') = 'CHILD'
              AND c.content_tsv @@ websearch_to_tsquery('english', :query)
            ORDER BY score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChunkSearchResult> searchByKeywordSnapshot(
            @Param("query") String query,
            @Param("limit") int limit,
            @Param("brainId") UUID brainId,
            @Param("visibility") String visibility,
            @Param("snapshotId") UUID snapshotId);

    long countByBrainId(UUID brainId);

    /**
     * Per-document chunk-quality counts computed entirely in SQL (one grouped scan),
     * so the ingestion-quality report never hydrates chunk entities — and never their
     * 1536-float embeddings — into the heap. Child = chunk_type &lt;&gt; 'PARENT'
     * (case-insensitive; null defaults to CHILD), matching the previous Java logic.
     *
     * <p>emptyChunkCount reads the V46 generated column: an all-whitespace (or empty)
     * content normalizes to the empty string, whose md5 is the constant below. The
     * old {@code regexp_replace(content, '\s', '', 'g')} ran over every chunk on
     * every call and cost ~2 s on the mortgage brain by itself.
     */
    @Query(value = """
            SELECT c.document_id                                                    AS documentId,
                   COUNT(*)                                                          AS chunkCount,
                   COUNT(*) FILTER (WHERE c.embedding IS NOT NULL)                   AS embeddedChunkCount,
                   COUNT(*) FILTER (WHERE UPPER(COALESCE(c.chunk_type, 'CHILD')) <> 'PARENT'
                                      AND c.embedding IS NULL)                       AS missingEmbeddingCount,
                   COUNT(*) FILTER (WHERE UPPER(COALESCE(c.chunk_type, 'CHILD')) = 'PARENT') AS parentChunkCount,
                   COUNT(*) FILTER (WHERE UPPER(COALESCE(c.chunk_type, 'CHILD')) <> 'PARENT') AS childChunkCount,
                   COUNT(*) FILTER (WHERE UPPER(COALESCE(c.chunk_type, 'CHILD')) <> 'PARENT'
                                      AND c.parent_chunk_id IS NULL)                 AS orphanChildChunkCount,
            """
            + "       COUNT(*) FILTER (WHERE c.content_norm_md5 = '" + EMPTY_NORMALIZED_MD5 + "') AS emptyChunkCount,\n"
            + """
                   COUNT(*) FILTER (WHERE UPPER(COALESCE(c.chunk_type, 'CHILD')) <> 'PARENT'
                                      AND NOT (
                                          (c.metadata->>'section' IS NOT NULL AND length(btrim(c.metadata->>'section')) > 0)
                                       OR (c.metadata->>'heading' IS NOT NULL AND length(btrim(c.metadata->>'heading')) > 0)
                                       OR (c.metadata->>'hierarchy_path' IS NOT NULL AND length(btrim(c.metadata->>'hierarchy_path')) > 0)
                                       OR jsonb_exists(c.metadata, 'page_number')
                                       OR jsonb_exists(c.metadata, 'pageNumber')
                                      ))                                             AS missingCitationMetadataCount
            FROM brain_document_chunks c
            WHERE c.brain_id = :brainId
            GROUP BY c.document_id
            """, nativeQuery = true)
    List<DocumentChunkQualityCounts> aggregateQualityByDocument(@Param("brainId") UUID brainId);

    /**
     * Number of distinct normalized-content groups (lowercased, whitespace collapsed,
     * trimmed) that occur more than once among the chunks retrieval can actually
     * return — CHILD chunks of ACTIVE documents — computed in SQL to avoid loading
     * every chunk's content into memory.
     *
     * <p>Scoped this way on purpose. The hierarchical chunker materializes a PARENT
     * for every section, and a single-child section's parent is byte-identical to
     * its child; parents are never embedded or searched, so counting them reported
     * ~1,000 phantom "duplicates" on the mortgage brain. Likewise a superseded
     * (inactive) document keeps its chunks for snapshot pins but every search query
     * filters {@code is_active = TRUE}, so its text cannot inflate a live score.
     *
     * <p>The normalization is precomputed: {@code content_norm_md5} is a STORED
     * generated column (V46) holding md5 of the lowercased, whitespace-collapsed,
     * trimmed text, so this is an indexed GROUP BY rather than a regex over every
     * chunk (2.2 s on the mortgage brain before). {@code md5('')} marks text that
     * is empty after normalization and is excluded, matching the old length check.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT c.content_norm_md5
                FROM brain_document_chunks c
                JOIN brain_documents d ON d.id = c.document_id
                WHERE c.brain_id = :brainId
                  AND d.is_active = TRUE
                  AND UPPER(COALESCE(c.chunk_type, 'CHILD')) <> 'PARENT'
            """
            + "      AND c.content_norm_md5 <> '" + EMPTY_NORMALIZED_MD5 + "'\n"
            + """
                GROUP BY c.content_norm_md5
                HAVING COUNT(*) > 1
            ) g
            """, nativeQuery = true)
    long countDuplicateTextGroups(@Param("brainId") UUID brainId);
}
