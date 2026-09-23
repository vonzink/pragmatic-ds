package com.pragmaticds.rag.lab.engine;

import java.util.Map;
import java.util.Objects;

/**
 * What a run consumed from the read model, with no values in it: one digest over the fields
 * bytes in document ordinal order, and how many occurrences carried each review state. Stored
 * beside the run so a later read can say whether reviewer state moved, and by how much.
 */
public record ReviewSnapshot(
        String sha256,
        int documentCount,
        int machineCount,
        int correctedCount,
        int rejectedCount,
        Map<java.util.UUID, String> schemaVersions) {

    public ReviewSnapshot {
        Objects.requireNonNull(sha256, "sha256");
        schemaVersions = Map.copyOf(schemaVersions);
    }
}
