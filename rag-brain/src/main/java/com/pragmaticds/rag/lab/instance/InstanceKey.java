package com.pragmaticds.rag.lab.instance;

import java.util.Objects;
import java.util.UUID;

/** Immutable, brain-scoped instance identity. */
public record InstanceKey(UUID brainId, String slug) {
    public InstanceKey {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(slug, "slug");
    }
}
