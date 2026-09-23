package com.pragmaticds.docengine.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only mapping of {@code extraction_schema} (V7): the versioned extraction schemas, loaded
 * as DATA — adding a field is a schema-version bump, never a code change. The {@code definition}
 * jsonb stays a String here — parsing belongs to {@code ExtractionSchemaLoader}, and a raw
 * definition must never leak into logs or errors.
 *
 * <p>Same tenancy stance as {@code ClassificationRulePack}: nullable {@code org_id} (null =
 * global built-in), no {@code @TenantId} — it would hide the global org_id-NULL rows. Visibility
 * is the repository's explicit own-org-or-global query; shadowing (an org schema hides global
 * schemas of the same type wholesale) is the loader's rule.
 */
@Entity
@Table(name = "extraction_schema")
@Immutable
public class ExtractionSchema {

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

    @Column(name = "is_active", nullable = false, updatable = false)
    private boolean active;

    protected ExtractionSchema() {
        // JPA
    }

    /** Hand-built rows for unit tests; production rows only ever come from the database. */
    public ExtractionSchema(
            UUID orgId, String documentTypeCode, String version, String definition, boolean active) {
        this.orgId = orgId;
        this.documentTypeCode = documentTypeCode;
        this.version = version;
        this.definition = definition;
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

    public boolean isActive() {
        return active;
    }
}
