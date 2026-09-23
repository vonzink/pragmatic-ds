package com.pragmaticds.docengine.platform.storage;

import java.util.UUID;

/**
 * What a signed download token authorizes: a single blob, named by KIND + id, PLUS the org that
 * owns it. The org travels inside the signed material on purpose — a token can only ever reach the
 * one org baked into it, so a valid token for org A can never be repurposed to fetch org B's bytes
 * (the download endpoint loads the object by {@code (id, orgId)} and rejects a mismatch).
 *
 * @param type {@link #TYPE_PAGE_RENDER}, {@link #TYPE_FILE_CONTENT} or {@link #TYPE_DOCUMENT_PDF}
 * @param id the page id, source-file id, or logical-document id
 * @param orgId the owning organization; the download endpoint binds this org and no other
 */
public record SignedObjectRef(String type, UUID id, UUID orgId) {

    /** A rendered page raster ({@code /v1/pages/{id}/render}). */
    public static final String TYPE_PAGE_RENDER = "PAGE_RENDER";

    /** An original uploaded file's bytes ({@code /v1/files/{id}/content}). */
    public static final String TYPE_FILE_CONTENT = "FILE_CONTENT";

    /** A logical document burst to its own PDF ({@code /v1/documents/{id}/pdf}). */
    public static final String TYPE_DOCUMENT_PDF = "DOCUMENT_PDF";

    public SignedObjectRef {
        if (!TYPE_PAGE_RENDER.equals(type)
                && !TYPE_FILE_CONTENT.equals(type)
                && !TYPE_DOCUMENT_PDF.equals(type)) {
            throw new IllegalArgumentException("unknown signed object type");
        }
        if (id == null) {
            throw new IllegalArgumentException("signed object requires an id");
        }
        if (orgId == null) {
            throw new IllegalArgumentException("signed object requires an org");
        }
    }
}
