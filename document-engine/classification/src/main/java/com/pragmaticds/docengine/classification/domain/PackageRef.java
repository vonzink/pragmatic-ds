package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * Read-only reference mapping of {@code document_package} — just enough to org-guard the
 * documents endpoint with the house {@code findByIdAndOrgId} rule WITHOUT depending on
 * {@code :ingestion} (ARCHITECTURE.md 2.1 gives classification only {@code platform} and {@code
 * parsing}). The same pattern as {@code Page.packageId} denormalization: the module reads the
 * package's existence and tenancy, never its ingestion lifecycle. Ownership of the row — writes,
 * lifecycle, the full column set — stays with {@code :ingestion}'s DocumentPackage entity;
 * mapping the same table twice is safe because this side is {@link Immutable} and never persisted.
 */
@Entity(name = "ClassificationPackageRef")
@Table(name = "document_package")
@Immutable
public class PackageRef {

    @Id private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Soft delete (DATA_MODEL conventions): reads must treat a deleted package as absent. */
    @Column(name = "deleted_at", updatable = false)
    private Instant deletedAt;

    protected PackageRef() {
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
