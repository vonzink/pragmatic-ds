package com.pragmaticds.docengine.results.canonical;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * THE source-set digest: SHA-256 over the DOCENGINE-C14N-1 bytes of the ordinal-ordered array
 * {@code [{contentSha256, contentType, ordinal, sizeBytes}]} (C14N owns key order).
 *
 * <p>One algorithm, two callers: {@link EngineResultEnvelopeAssembler} embeds it in the immutable
 * envelope (and the finalizer persists it as {@code engine_result.source_set_sha256}), and the
 * upload-time reuse probe recomputes it prospectively from validated files before anything is
 * persisted. Matching the whole SET — ordinal, content hash, size, and SNIFFED content type per
 * file — makes multi-file packages exact: a subset, superset, or reorder never matches. Filenames,
 * storage keys, declared MIME types, and database UUIDs deliberately do not influence the digest
 * (immutable-engine-result design 6.1).
 */
public final class SourceSetIdentity {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SourceSetIdentity() {}

    /** One file's identity contribution. {@code contentType} is the SNIFFED type, never the claim. */
    public record Entry(int ordinal, String contentSha256, long sizeBytes, String contentType) {
        public Entry {
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    /** Lowercase-hex SHA-256 of the canonical source-set identity. Order is owned by the ordinal. */
    public static String digest(List<Entry> entries) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(Entry::ordinal));
        ArrayNode array = JSON.arrayNode();
        for (Entry entry : ordered) {
            ObjectNode node = JSON.objectNode();
            node.put("ordinal", entry.ordinal());
            node.put("contentSha256", entry.contentSha256());
            node.put("sizeBytes", entry.sizeBytes());
            node.put("contentType", entry.contentType());
            array.add(node);
        }
        return new CanonicalJsonWriter().write(array).sha256();
    }
}
