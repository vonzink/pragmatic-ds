package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Document → page link (docs/DATA_MODEL.md 5): a join table rather than a page range so Spec 2's
 * human regrouping can produce non-contiguous documents without a migration. The table's unique
 * index on {@code page_id} enforces "a page belongs to AT MOST one document" — blank and
 * duplicate pages simply have no row here.
 *
 * <p>Not a {@code TenantScopedEntity}: composite natural key, no surrogate id, no timestamps —
 * the same reasoning as {@code LayoutElementSpan}. Still {@code @TenantId org_id}.
 */
@Entity
@Table(name = "logical_document_page")
@IdClass(LogicalDocumentPageId.class)
public class LogicalDocumentPage {

    @Id
    @Column(name = "logical_document_id", nullable = false, updatable = false)
    private UUID logicalDocumentId;

    @Id
    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Position of the page WITHIN the document, 0-based. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    protected LogicalDocumentPage() {
        // JPA
    }

    public LogicalDocumentPage(UUID logicalDocumentId, UUID pageId, int ordinal) {
        this.logicalDocumentId = logicalDocumentId;
        this.pageId = pageId;
        this.ordinal = ordinal;
    }

    public UUID getLogicalDocumentId() {
        return logicalDocumentId;
    }

    public UUID getPageId() {
        return pageId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public int getOrdinal() {
        return ordinal;
    }
}
