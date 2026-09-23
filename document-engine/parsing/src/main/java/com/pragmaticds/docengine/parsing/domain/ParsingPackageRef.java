package com.pragmaticds.docengine.parsing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * Read-only reference mapping of {@code document_package} — just enough to org-guard the pages
 * endpoints with the house {@code findByIdAndOrgId} rule WITHOUT depending on {@code :ingestion}
 * (ARCHITECTURE.md 2.1 gives parsing only {@code platform}). Identical in shape and rationale to
 * classification's {@code PackageRef}: this side is {@link Immutable} and never persisted, so
 * mapping the same table twice is safe and ownership of the row stays with {@code :ingestion}.
 *
 * <p>Guarding on the package rather than inferring absence from an empty page list matters: a
 * package that has been uploaded but not yet rendered legitimately HAS no pages, and the UI polls
 * it while the pipeline runs. Empty-means-missing would answer 404 to a perfectly valid package.
 */
@Entity(name = "ParsingPackageRef")
@Table(name = "document_package")
@Immutable
public class ParsingPackageRef {

    @Id private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Soft delete (DATA_MODEL conventions): reads must treat a deleted package as absent. */
    @Column(name = "deleted_at", updatable = false)
    private Instant deletedAt;

    protected ParsingPackageRef() {
        // JPA — read-only, never constructed by code.
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
