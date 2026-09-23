package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Read-only mapping of {@code document_type} (V6). Rows are DATA seeded by migration or inserted
 * by an operator — Phase 4 never writes them, hence {@link Immutable}.
 *
 * <p>Deliberately NOT a {@code TenantScopedEntity} and NOT {@code @TenantId}-scoped: {@code
 * org_id} is NULLABLE here — null marks a global built-in visible to every org — and Hibernate's
 * tenant filter would hide exactly those global rows. Visibility is enforced explicitly by {@code
 * DocumentTypeRepository#findActiveVisibleTo} (own org OR global), mirroring the V6 RLS policy.
 */
@Entity
@Table(name = "document_type")
@Immutable
public class DocumentType {

    @Id @GeneratedValue private UUID id;

    /** Null = global built-in. */
    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "display_name", nullable = false, updatable = false)
    private String displayName;

    @Column(name = "category", updatable = false)
    private String category;

    @Column(name = "is_active", nullable = false, updatable = false)
    private boolean active;

    /**
     * What this type LOOKS like at a document boundary — the taxonomy sentence the
     * boundary-extraction model receives (V27, Phase E). Null falls back to the display name.
     */
    @Column(name = "split_description", updatable = false)
    private String splitDescription;

    protected DocumentType() {
        // JPA
    }

    /** Hand-built rows for unit tests; production rows only ever come from the database. */
    public DocumentType(UUID orgId, String code, String displayName, String category, boolean active) {
        this(orgId, code, displayName, category, active, null);
    }

    /** Hand-built rows for unit tests; production rows only ever come from the database. */
    public DocumentType(
            UUID orgId,
            String code,
            String displayName,
            String category,
            boolean active,
            String splitDescription) {
        this.orgId = orgId;
        this.code = code;
        this.displayName = displayName;
        this.category = category;
        this.active = active;
        this.splitDescription = splitDescription;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public boolean isActive() {
        return active;
    }

    public String getSplitDescription() {
        return splitDescription;
    }
}
