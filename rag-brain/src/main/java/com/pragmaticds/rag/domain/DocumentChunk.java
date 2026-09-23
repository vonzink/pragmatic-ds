package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One chunk of text extracted from a guideline document, with its embedding.
 * The content_tsv column is database-generated (full-text search) and is
 * intentionally not mapped here.
 */
@Entity
@Table(name = "brain_document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private BrainDocument document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "chunk_type", nullable = false, length = 20)
    private String chunkType = "CHILD";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_chunk_id")
    private DocumentChunk parentChunk;

    @Column(name = "hierarchy_path", length = 1000)
    private String hierarchyPath;

    @Column(name = "hierarchy_level")
    private Integer hierarchyLevel;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    /**
     * Section, page number, headings — everything needed to build a citation.
     *
     * <p><b>Value types are load-bearing.</b> Chunks have a legitimate post-insert update path,
     * so this entity cannot take the {@code @Immutable} treatment the append-only entities got
     * after commit {@code 6517c35} — but that commit's hazard applies here too: Hibernate
     * dirty-checks this map by round-tripping it through the Jackson format mapper, and any
     * value that does not survive that trip ({@code Long}, {@code BigDecimal}, temporal types)
     * makes every managed chunk permanently "dirty", silently rewriting the row (and bumping
     * {@code updated_at}) on unrelated flushes. The ingestion writer only stores {@code String}
     * and {@code Integer} values today ({@code DocumentIngestionService#toEntity}); keep it that
     * way, or normalize on write, before adding new keys. A structural fix (custom mutability
     * plan) is deferred until a real symptom shows.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1536)
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }

    public BrainDocument getDocument() { return document; }
    public void setDocument(BrainDocument document) { this.document = document; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public DocumentChunk getParentChunk() { return parentChunk; }
    public void setParentChunk(DocumentChunk parentChunk) { this.parentChunk = parentChunk; }

    public String getHierarchyPath() { return hierarchyPath; }
    public void setHierarchyPath(String hierarchyPath) { this.hierarchyPath = hierarchyPath; }

    public Integer getHierarchyLevel() { return hierarchyLevel; }
    public void setHierarchyLevel(Integer hierarchyLevel) { this.hierarchyLevel = hierarchyLevel; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
