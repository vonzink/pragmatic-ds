package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only mapping of {@code classification_rule_pack} (V6): the versioned mortgage domain
 * knowledge, loaded as DATA. The {@code definition} jsonb stays a String here — parsing belongs to
 * {@code RulePackLoader}, and a raw definition must never leak into logs or errors.
 *
 * <p>Same tenancy stance as {@link DocumentType}: nullable {@code org_id} (null = global
 * built-in), no {@code @TenantId} — visibility is the repository's explicit own-org-or-global
 * query, and shadowing (org pack hides global packs of the same type) is the loader's rule.
 */
@Entity
@Table(name = "classification_rule_pack")
@Immutable
public class ClassificationRulePack {

    @Id @GeneratedValue private UUID id;

    /** Null = global built-in. */
    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "document_type_code", nullable = false, updatable = false)
    private String documentTypeCode;

    @Column(name = "version", nullable = false, updatable = false)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, updatable = false)
    private String definition;

    @Column(name = "min_confidence", nullable = false, updatable = false)
    private BigDecimal minConfidence;

    @Column(name = "is_active", nullable = false, updatable = false)
    private boolean active;

    protected ClassificationRulePack() {
        // JPA
    }

    /** Hand-built rows for unit tests; production rows only ever come from the database. */
    public ClassificationRulePack(
            UUID orgId,
            String documentTypeCode,
            String version,
            String definition,
            BigDecimal minConfidence,
            boolean active) {
        this.orgId = orgId;
        this.documentTypeCode = documentTypeCode;
        this.version = version;
        this.definition = definition;
        this.minConfidence = minConfidence;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getDocumentTypeCode() {
        return documentTypeCode;
    }

    public String getVersion() {
        return version;
    }

    public String getDefinition() {
        return definition;
    }

    public BigDecimal getMinConfidence() {
        return minConfidence;
    }

    public boolean isActive() {
        return active;
    }
}
