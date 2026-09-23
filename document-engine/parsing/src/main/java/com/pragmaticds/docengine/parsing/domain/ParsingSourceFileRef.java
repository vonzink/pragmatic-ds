package com.pragmaticds.docengine.parsing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * Read-only reference mapping of {@code source_file} — just the sniffed content type, so the pages
 * endpoint can tell a client what KIND of file a page came from WITHOUT depending on {@code
 * :ingestion} (ARCHITECTURE.md 2.1 gives parsing only {@code platform}). Same shape and rationale
 * as {@link ParsingPackageRef}: {@link Immutable}, never persisted, ownership of the row stays with
 * {@code :ingestion}.
 *
 * <p>Why the UI needs it: the reviewer's viewer renders the ORIGINAL bytes with pdf.js and keeps
 * the server-rendered PNG as a fallback. For an image source there is no PDF to render, and
 * discovering that by handing a JPEG to pdf.js costs a fetch, a parse, and a fallback notice
 * apologising for a PDF that never existed — on the most common upload there is.
 */
@Entity(name = "ParsingSourceFileRef")
@Table(name = "source_file")
@Immutable
public class ParsingSourceFileRef {

    @Id private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Sniffed from magic bytes at upload, never the client's claim. */
    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    protected ParsingSourceFileRef() {
        // JPA — read-only, never constructed by code.
    }

    public UUID getId() {
        return id;
    }

    public String getContentType() {
        return contentType;
    }
}
