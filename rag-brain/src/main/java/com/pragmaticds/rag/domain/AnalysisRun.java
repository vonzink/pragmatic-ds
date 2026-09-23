package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable manifest of one analyze run — the audit/reproducibility record the
 * log-only v1 trace never provided. Metadata only by default: ids, hashes,
 * method names, counts, and tokens are always written. {@code findings},
 * calculation inputs/values, calculation display names ({@code calc_audit[].
 * name/inputs/value}), and document filenames ({@code docs[]/skipped[].
 * fileName}) are written only behind ragbrain.rag.analyze.persist-findings
 * (see {@code AnalysisRunRecorder}, the redaction boundary) — document bytes
 * are never stored (statelessness invariant, narrowed).
 *
 * <p><b>Immutable in the mapping, not only in this javadoc.</b> The JSON-mapped
 * collections below are mutable {@code List}/{@code Map} values behind
 * {@code @JdbcTypeCode(SqlTypes.JSON)}: Hibernate builds its dirty-check snapshot by
 * round-tripping them through the Jackson format mapper, which demotes {@code Long} to
 * {@code Integer} and {@code BigDecimal} to {@code Double}, so the snapshot never equals
 * the live value and every flush after the insert composed a spurious full-row
 * {@code UPDATE} — silent write amplification against a record whose whole point is that
 * it never changes (commit {@code 6517c35}: the same mechanism rolled whole transactions
 * back on trigger-protected Lab tables). {@code @Immutable} plus {@code updatable = false}
 * stops that {@code UPDATE} being composed at all. Nothing updates this row legitimately:
 * it is written once by {@code AnalysisRunRecorder} and afterwards only read, existence-checked,
 * or deleted by retention (deletes still work on an {@code @Immutable} entity).
 */
@Entity
@Immutable
@Table(name = "analysis_runs")
public class AnalysisRun {

    @Id
    @Column(updatable = false)
    private UUID id;                       // assigned: the runId returned to the caller

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "analyzer_slug", nullable = false, updatable = false, length = 64)
    private String analyzerSlug;

    @Column(name = "envelope_version", nullable = false, updatable = false, length = 8)
    private String envelopeVersion;

    @Column(nullable = false, updatable = false, length = 16)
    private String status;

    @Column(name = "error_reason", updatable = false, columnDefinition = "text")
    private String errorReason;

    @Column(updatable = false, length = 40)
    private String provider;

    @Column(updatable = false, length = 80)
    private String model;

    @Column(name = "prompt_sha256", updatable = false, length = 64)
    private String promptSha256;

    @Column(nullable = false, updatable = false)
    private int attempts = 1;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, updatable = false)
    private double costUsd;

    @Column(name = "doc_count", nullable = false, updatable = false)
    private int docCount;

    @Column(name = "page_count", nullable = false, updatable = false)
    private int pageCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> docs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> skipped;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> filtered;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_chunk_ids", updatable = false, columnDefinition = "jsonb")
    private List<String> retrievedChunkIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calc_audit", updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> calcAudit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> findings;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getAnalyzerSlug() { return analyzerSlug; }
    public void setAnalyzerSlug(String analyzerSlug) { this.analyzerSlug = analyzerSlug; }
    public String getEnvelopeVersion() { return envelopeVersion; }
    public void setEnvelopeVersion(String envelopeVersion) { this.envelopeVersion = envelopeVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptSha256() { return promptSha256; }
    public void setPromptSha256(String promptSha256) { this.promptSha256 = promptSha256; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public double getCostUsd() { return costUsd; }
    public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
    public int getDocCount() { return docCount; }
    public void setDocCount(int docCount) { this.docCount = docCount; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public List<Map<String, Object>> getDocs() { return docs; }
    public void setDocs(List<Map<String, Object>> docs) { this.docs = docs; }
    public List<Map<String, Object>> getSkipped() { return skipped; }
    public void setSkipped(List<Map<String, Object>> skipped) { this.skipped = skipped; }
    public List<Map<String, Object>> getFiltered() { return filtered; }
    public void setFiltered(List<Map<String, Object>> filtered) { this.filtered = filtered; }
    public List<String> getRetrievedChunkIds() { return retrievedChunkIds; }
    public void setRetrievedChunkIds(List<String> retrievedChunkIds) { this.retrievedChunkIds = retrievedChunkIds; }
    public List<Map<String, Object>> getCalcAudit() { return calcAudit; }
    public void setCalcAudit(List<Map<String, Object>> calcAudit) { this.calcAudit = calcAudit; }
    public Map<String, Object> getFindings() { return findings; }
    public void setFindings(Map<String, Object> findings) { this.findings = findings; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
