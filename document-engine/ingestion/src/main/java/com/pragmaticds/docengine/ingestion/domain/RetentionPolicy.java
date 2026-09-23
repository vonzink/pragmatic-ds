package com.pragmaticds.docengine.ingestion.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An org's retention rule (docs/DATA_MODEL.md 2, V2__ingestion.sql). {@code document_category} NULL
 * is the org-wide default policy; a per-category row would override it (categories are not yet
 * used). {@code purge_after_days} is what soft delete adds to now to set a package's
 * {@code purge_after}. The table predates any JPA entity — this maps the existing V2 columns; no
 * migration is needed.
 */
@Entity
@Table(name = "retention_policy")
public class RetentionPolicy extends TenantScopedEntity {

    /** NULL = the org-wide default policy (the row soft delete reads). */
    @Column(name = "document_category")
    private String documentCategory;

    @Column(name = "retain_days", nullable = false)
    private int retainDays;

    @Column(name = "purge_after_days", nullable = false)
    private int purgeAfterDays;

    protected RetentionPolicy() {
        // JPA
    }

    public String getDocumentCategory() {
        return documentCategory;
    }

    public int getRetainDays() {
        return retainDays;
    }

    public int getPurgeAfterDays() {
        return purgeAfterDays;
    }
}
