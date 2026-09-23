package com.pragmaticds.docengine.classification.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link LogicalDocumentPage}: {@code (logical_document_id, page_id)}. */
public class LogicalDocumentPageId implements Serializable {

    private UUID logicalDocumentId;
    private UUID pageId;

    public LogicalDocumentPageId() {
        // JPA
    }

    public LogicalDocumentPageId(UUID logicalDocumentId, UUID pageId) {
        this.logicalDocumentId = logicalDocumentId;
        this.pageId = pageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LogicalDocumentPageId that)) {
            return false;
        }
        return Objects.equals(logicalDocumentId, that.logicalDocumentId)
                && Objects.equals(pageId, that.pageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalDocumentId, pageId);
    }
}
