package com.pragmaticds.rag.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for {@link BrainSourceWeight}: (brainId, documentId). */
public class BrainSourceWeightId implements Serializable {

    private UUID brainId;
    private UUID documentId;

    public BrainSourceWeightId() {}

    public BrainSourceWeightId(UUID brainId, UUID documentId) {
        this.brainId = brainId;
        this.documentId = documentId;
    }

    public UUID getBrainId() { return brainId; }
    public UUID getDocumentId() { return documentId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrainSourceWeightId that)) return false;
        return Objects.equals(brainId, that.brainId)
                && Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(brainId, documentId);
    }
}
