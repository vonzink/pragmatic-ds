package com.pragmaticds.docengine.results.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Immutable descriptor for the exact canonical bytes of one successful parse generation. */
@Entity
@Immutable
@Table(
        name = "engine_result",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "engine_result_org_job_generation_key",
                    columnNames = {"org_id", "processing_job_id", "parse_generation"}),
            @UniqueConstraint(
                    name = "engine_result_org_package_revision_key",
                    columnNames = {"org_id", "package_id", "revision"})
        })
public class EngineResult {

    @Id @GeneratedValue private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    @Column(name = "processing_job_id", nullable = false, updatable = false)
    private UUID processingJobId;

    @Column(name = "parse_generation", nullable = false, updatable = false)
    private int parseGeneration;

    @Column(name = "materialized_job_attempt", nullable = false, updatable = false)
    private int materializedJobAttempt;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "supersedes_result_id", updatable = false)
    private UUID supersedesResultId;

    @Column(name = "envelope_schema_version", nullable = false, updatable = false)
    private String envelopeSchemaVersion;

    @Column(name = "canonicalization_version", nullable = false, updatable = false)
    private String canonicalizationVersion;

    @Column(name = "canonical_media_type", nullable = false, updatable = false)
    private String canonicalMediaType;

    @Column(name = "source_set_sha256", nullable = false, updatable = false, length = 64)
    private String sourceSetSha256;

    @Column(name = "provenance_sha256", nullable = false, updatable = false, length = 64)
    private String provenanceSha256;

    @Column(name = "envelope_storage_key", nullable = false, updatable = false)
    private String envelopeStorageKey;

    @Column(name = "envelope_sha256", nullable = false, updatable = false, length = 64)
    private String envelopeSha256;

    @Column(name = "envelope_size_bytes", nullable = false, updatable = false)
    private long envelopeSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "reuse_eligibility", nullable = false, updatable = false)
    private ReuseEligibility reuseEligibility;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected EngineResult() {
        // JPA
    }

    public EngineResult(
            UUID packageId,
            UUID processingJobId,
            int parseGeneration,
            int materializedJobAttempt,
            int revision,
            UUID supersedesResultId,
            String envelopeSchemaVersion,
            String canonicalizationVersion,
            String canonicalMediaType,
            String sourceSetSha256,
            String provenanceSha256,
            String envelopeStorageKey,
            String envelopeSha256,
            long envelopeSizeBytes,
            ReuseEligibility reuseEligibility) {
        this.packageId = packageId;
        this.processingJobId = processingJobId;
        this.parseGeneration = parseGeneration;
        this.materializedJobAttempt = materializedJobAttempt;
        this.revision = revision;
        this.supersedesResultId = supersedesResultId;
        this.envelopeSchemaVersion = envelopeSchemaVersion;
        this.canonicalizationVersion = canonicalizationVersion;
        this.canonicalMediaType = canonicalMediaType;
        this.sourceSetSha256 = sourceSetSha256;
        this.provenanceSha256 = provenanceSha256;
        this.envelopeStorageKey = envelopeStorageKey;
        this.envelopeSha256 = envelopeSha256;
        this.envelopeSizeBytes = envelopeSizeBytes;
        this.reuseEligibility = reuseEligibility;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public UUID getProcessingJobId() {
        return processingJobId;
    }

    public int getParseGeneration() {
        return parseGeneration;
    }

    public int getMaterializedJobAttempt() {
        return materializedJobAttempt;
    }

    public int getRevision() {
        return revision;
    }

    public UUID getSupersedesResultId() {
        return supersedesResultId;
    }

    public String getEnvelopeSchemaVersion() {
        return envelopeSchemaVersion;
    }

    public String getCanonicalizationVersion() {
        return canonicalizationVersion;
    }

    public String getCanonicalMediaType() {
        return canonicalMediaType;
    }

    public String getSourceSetSha256() {
        return sourceSetSha256;
    }

    public String getProvenanceSha256() {
        return provenanceSha256;
    }

    public String getEnvelopeStorageKey() {
        return envelopeStorageKey;
    }

    public String getEnvelopeSha256() {
        return envelopeSha256;
    }

    public long getEnvelopeSizeBytes() {
        return envelopeSizeBytes;
    }

    public ReuseEligibility getReuseEligibility() {
        return reuseEligibility;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
