package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Citation trail: records exactly which source chunk supported an AI answer.
 * Source fields are denormalized so the citation survives even if the
 * document is later deactivated or re-chunked. Compliance requirement.
 */
@Entity
@Table(name = "ai_answer_sources")
public class AnswerSource {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "chunk_id")
    private UUID chunkId;

    @Column(name = "similarity_score")
    private Double similarityScore;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "document_name", length = 500)
    private String documentName;

    @Column(length = 255)
    private String section;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getChunkId() { return chunkId; }
    public void setChunkId(UUID chunkId) { this.chunkId = chunkId; }

    public Double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
