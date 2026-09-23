package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The exact immutable engine-result identity one Lab run was executed against.
 *
 * <p>Every field here is already carried by the adapter's verified artifact — the envelope's
 * package id and generation ({@code processingJobId}, {@code parseGeneration},
 * {@code packageRevision}, {@code sourceSetSha256}, {@code reuseEligibility}) plus the exact
 * received bytes' digest and length. Nothing is invented, and nothing derived from document
 * content is stored: no bytes, filename, source URL, storage key, or parsed value.
 *
 * <p>Note that {@code packageRevision} comes from the envelope's own generation block rather than
 * from the engine's metadata view, which carries no package id at all. Revision agreement is
 * therefore established by the envelope itself.
 *
 * <p>Written once when a run reaches its terminal state; a {@code BEFORE UPDATE} trigger refuses
 * any later mutation.
 *
 * <p><b>Append-only in the mapping, not only in the database.</b> Because that trigger refuses a
 * rewrite with {@code LAB_ROW_IMMUTABLE}, a mutation of a managed instance would not merely be
 * wrong — it would roll back the whole surrounding transaction. {@code @Immutable} plus
 * {@code updatable = false} keeps Hibernate from composing an {@code UPDATE} at all; see commit
 * {@code 6517c35} and {@link LabInstanceRelease} for the dirty-check round-trip that made this
 * declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_run_document")
public class LabRunDocument {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "registration_id", nullable = false, updatable = false)
    private UUID registrationId;

    @Column(name = "engine_package_id", nullable = false, updatable = false)
    private UUID enginePackageId;

    @Column(name = "package_revision", nullable = false, updatable = false)
    private int packageRevision;

    @Column(name = "processing_job_id", nullable = false, updatable = false)
    private UUID processingJobId;

    @Column(name = "parse_generation", nullable = false, updatable = false)
    private int parseGeneration;

    @Column(name = "envelope_version", nullable = false, updatable = false, length = 16)
    private String envelopeVersion;

    @Column(name = "canonicalization_version", nullable = false, updatable = false, length = 32)
    private String canonicalizationVersion;

    @Column(name = "envelope_sha256", nullable = false, updatable = false, length = 64)
    private String envelopeSha256;

    @Column(name = "envelope_size_bytes", nullable = false, updatable = false)
    private long envelopeSizeBytes;

    @Column(name = "source_set_sha256", nullable = false, updatable = false, length = 64)
    private String sourceSetSha256;

    @Column(name = "reuse_eligibility", nullable = false, updatable = false, length = 32)
    private String reuseEligibility;

    @Column(name = "document_count", nullable = false, updatable = false)
    private int documentCount;

    @Column(name = "page_count", nullable = false, updatable = false)
    private int pageCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID getRegistrationId() { return registrationId; }
    public void setRegistrationId(UUID registrationId) { this.registrationId = registrationId; }
    public UUID getEnginePackageId() { return enginePackageId; }
    public void setEnginePackageId(UUID enginePackageId) { this.enginePackageId = enginePackageId; }
    public int getPackageRevision() { return packageRevision; }
    public void setPackageRevision(int packageRevision) { this.packageRevision = packageRevision; }
    public UUID getProcessingJobId() { return processingJobId; }
    public void setProcessingJobId(UUID processingJobId) { this.processingJobId = processingJobId; }
    public int getParseGeneration() { return parseGeneration; }
    public void setParseGeneration(int parseGeneration) { this.parseGeneration = parseGeneration; }
    public String getEnvelopeVersion() { return envelopeVersion; }
    public void setEnvelopeVersion(String envelopeVersion) { this.envelopeVersion = envelopeVersion; }
    public String getCanonicalizationVersion() { return canonicalizationVersion; }
    public void setCanonicalizationVersion(String canonicalizationVersion) { this.canonicalizationVersion = canonicalizationVersion; }
    public String getEnvelopeSha256() { return envelopeSha256; }
    public void setEnvelopeSha256(String envelopeSha256) { this.envelopeSha256 = envelopeSha256; }
    public long getEnvelopeSizeBytes() { return envelopeSizeBytes; }
    public void setEnvelopeSizeBytes(long envelopeSizeBytes) { this.envelopeSizeBytes = envelopeSizeBytes; }
    public String getSourceSetSha256() { return sourceSetSha256; }
    public void setSourceSetSha256(String sourceSetSha256) { this.sourceSetSha256 = sourceSetSha256; }
    public String getReuseEligibility() { return reuseEligibility; }
    public void setReuseEligibility(String reuseEligibility) { this.reuseEligibility = reuseEligibility; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
