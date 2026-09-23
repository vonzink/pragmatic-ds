package com.pragmaticds.docengine.parsing.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Immutable raw stage output (docs/DATA_MODEL.md 4). The payload lives in blob storage; only the
 * digest sits in the row. BOTH OCR engines' raw outputs persist here when fallback runs,
 * regardless of which won reconciliation — layer 1 of the four value layers.
 */
@Entity
@Table(name = "parser_output")
public class ParserOutput extends TenantScopedEntity {

    @Column(name = "source_file_id", updatable = false)
    private UUID sourceFileId;

    @Column(name = "page_id", updatable = false)
    private UUID pageId;

    @Column(name = "stage", nullable = false, updatable = false)
    private String stage;

    @Column(name = "parser_name", nullable = false, updatable = false)
    private String parserName;

    @Column(name = "parser_version", nullable = false, updatable = false)
    private String parserVersion;

    @Column(name = "payload_storage_key", nullable = false, updatable = false)
    private String payloadStorageKey;

    @Column(name = "payload_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String payloadSha256;

    protected ParserOutput() {
        // JPA
    }

    public ParserOutput(
            UUID sourceFileId,
            UUID pageId,
            String stage,
            String parserName,
            String parserVersion,
            String payloadStorageKey,
            String payloadSha256) {
        this.sourceFileId = sourceFileId;
        this.pageId = pageId;
        this.stage = stage;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.payloadStorageKey = payloadStorageKey;
        this.payloadSha256 = payloadSha256;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getStage() {
        return stage;
    }

    public String getParserName() {
        return parserName;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getPayloadStorageKey() {
        return payloadStorageKey;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }
}
