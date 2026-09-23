package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A source document uploaded into the RAG knowledge base.
 */
@Entity
@Table(name = "brain_documents")
public class BrainDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private SourceVisibility visibility = SourceVisibility.INTERNAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20)
    private SourceTrustLevel trustLevel = SourceTrustLevel.APPROVED;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "s3_key", length = 1000)
    private String s3Key;

    @Column(name = "analyzer_scope", length = 40)
    private String analyzerScope;

    /**
     * The corpus-declared stable id from frontmatter {@code document_id:}, or null
     * when the source file declared none. Retrieval evals join on this — file
     * names drift on upload and titles are prose.
     */
    @Column(name = "external_doc_id", length = 200)
    private String externalDocId;

    @Column(name = "document_version", length = 50)
    private String documentVersion;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

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

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public SourceVisibility getVisibility() { return visibility; }
    public void setVisibility(SourceVisibility visibility) { this.visibility = visibility; }

    public SourceTrustLevel getTrustLevel() { return trustLevel; }
    public void setTrustLevel(SourceTrustLevel trustLevel) { this.trustLevel = trustLevel; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }

    public String getAnalyzerScope() { return analyzerScope; }
    public void setAnalyzerScope(String analyzerScope) { this.analyzerScope = analyzerScope; }

    public String getExternalDocId() { return externalDocId; }
    public void setExternalDocId(String externalDocId) { this.externalDocId = externalDocId; }

    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public LocalDate getExpirationDate() { return expirationDate; }
    public void setExpirationDate(LocalDate expirationDate) { this.expirationDate = expirationDate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
