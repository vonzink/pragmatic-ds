package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotManifest.CollectionEntry;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotManifest.DocumentEntry;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorpusSnapshotCodecTest {

    private static final UUID COLLECTION =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DOCUMENT =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String HASH = "a".repeat(64);
    private final LabManifestWriter writer = new LabManifestWriter();
    private final CorpusSnapshotCodec codec = new CorpusSnapshotCodec(writer);

    @Test
    void encodesCanonicalVersionOneManifestAndHashesItsExactUtf8Bytes() {
        CorpusSnapshotManifest manifest = manifest();

        CorpusSnapshotCodec.EncodedSnapshotManifest encoded = codec.encode(manifest);
        String json = new String(writer.canonicalize(encoded.manifest()), StandardCharsets.UTF_8);

        assertEquals("{\"collections\":[{\"collectionId\":\"33333333-3333-4333-8333-333333333333\","
                        + "\"collectionVersion\":2}],\"documents\":[{\"collectionId\":"
                        + "\"33333333-3333-4333-8333-333333333333\",\"contentSha256\":\""
                        + HASH + "\",\"documentId\":\"44444444-4444-4444-8444-444444444444\","
                        + "\"documentVersion\":\"2026.08\",\"effectiveDate\":\"2026-08-01\","
                        + "\"expirationDate\":null,\"trustLevel\":\"APPROVED\","
                        + "\"visibility\":\"INTERNAL\"}],\"manifestVersion\":1}", json);
        assertEquals("215b9abb42201fec3613be289ab7d9ba91fa8b973b126a1e08e13cb8cfb6a710",
                encoded.manifestSha256());
        assertEquals(encoded, codec.encode(manifest));
    }

    @Test
    void preservesCallerCollectionOrderBecauseItChangesSnapshotIdentity() {
        CollectionEntry second = new CollectionEntry(
                UUID.fromString("55555555-5555-4555-8555-555555555555"), 1);
        CorpusSnapshotManifest forward = new CorpusSnapshotManifest(
                List.of(manifest().collections().getFirst(), second), manifest().documents());
        CorpusSnapshotManifest reversed = new CorpusSnapshotManifest(
                List.of(second, manifest().collections().getFirst()), manifest().documents());

        assertNotEquals(codec.encode(forward).manifestSha256(),
                codec.encode(reversed).manifestSha256());
    }

    @Test
    void manifestDefensivelyCopiesItsOrderedFacts() {
        List<CollectionEntry> collections = new ArrayList<>(manifest().collections());
        List<DocumentEntry> documents = new ArrayList<>(manifest().documents());
        CorpusSnapshotManifest manifest = new CorpusSnapshotManifest(collections, documents);

        collections.clear();
        documents.clear();

        assertEquals(1, manifest.collections().size());
        assertEquals(1, manifest.documents().size());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.collections().add(new CollectionEntry(UUID.randomUUID(), 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> codec.encode(manifest).manifest().put("changed", true));
    }

    private static CorpusSnapshotManifest manifest() {
        return new CorpusSnapshotManifest(
                List.of(new CollectionEntry(COLLECTION, 2)),
                List.of(new DocumentEntry(COLLECTION, DOCUMENT, "2026.08", HASH,
                        SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                        LocalDate.of(2026, 8, 1), null)));
    }
}
