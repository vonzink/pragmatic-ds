package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.lab.release.LabManifestWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical encoder for immutable corpus snapshot identity. */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public final class CorpusSnapshotCodec {
    private final LabManifestWriter writer;

    public CorpusSnapshotCodec(LabManifestWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public EncodedSnapshotManifest encode(CorpusSnapshotManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        List<Map<String, Object>> collectionValues = new ArrayList<>();
        for (CorpusSnapshotManifest.CollectionEntry collection : manifest.collections()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("collectionId", collection.collectionId().toString());
            value.put("collectionVersion", collection.collectionVersion());
            collectionValues.add(value);
        }

        List<Map<String, Object>> documentValues = new ArrayList<>();
        for (CorpusSnapshotManifest.DocumentEntry document : manifest.documents()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("collectionId", document.collectionId().toString());
            value.put("documentId", document.documentId().toString());
            value.put("documentVersion", document.documentVersion());
            value.put("contentSha256", document.contentSha256());
            value.put("visibility", document.visibility().name());
            value.put("trustLevel", document.trustLevel().name());
            value.put("effectiveDate", document.effectiveDate() == null
                    ? null : document.effectiveDate().toString());
            value.put("expirationDate", document.expirationDate() == null
                    ? null : document.expirationDate().toString());
            documentValues.add(value);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifestVersion", manifest.manifestVersion());
        root.put("collections", collectionValues);
        root.put("documents", documentValues);
        Map<String, Object> immutable = immutableMap(root);
        return new EncodedSnapshotManifest(
                immutable, writer.sha256Hex(writer.canonicalize(immutable)));
    }

    public record EncodedSnapshotManifest(Map<String, Object> manifest, String manifestSha256) {
        public EncodedSnapshotManifest {
            manifest = immutableMap(Objects.requireNonNull(manifest, "manifest"));
            if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("manifestSha256 is invalid");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, nested) -> typed.put((String) key, nested));
            return immutableMap(typed);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream()
                    .map(CorpusSnapshotCodec::immutableValue).toList());
        }
        return value;
    }
}
