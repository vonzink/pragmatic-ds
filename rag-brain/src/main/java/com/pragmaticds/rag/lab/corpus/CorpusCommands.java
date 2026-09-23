package com.pragmaticds.rag.lab.corpus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Value-only commands and views for collection administration. */
public final class CorpusCommands {
    private CorpusCommands() {}

    public record ReplaceMembershipCommand(
            UUID brainId, UUID collectionId, long expectedVersion,
            List<UUID> documentIds, String idempotencyKey) {
        public ReplaceMembershipCommand {
            documentIds = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
        }
    }

    public record CollectionView(
            UUID id, UUID brainId, String slug, String displayName, String state,
            long version, UUID clonedFromId, List<UUID> documentIds) {
        public CollectionView {
            documentIds = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
        }
    }
}
