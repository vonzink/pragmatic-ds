package com.pragmaticds.docengine.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Local filesystem adapter for {@link BlobStoragePort} — the MVP driver behind the same port the
 * S3 adapter will implement. Keys are opaque to callers; traversal is the attack to kill here.
 */
class LocalObjectStorageAdapterTest {

    @TempDir Path root;

    private BlobStoragePort storage() {
        return new LocalObjectStorageAdapter(root);
    }

    @Test
    void publishes_immutable_bytes_exactly() {
        BlobStoragePort storage = storage();
        byte[] content = "immutable content".getBytes(StandardCharsets.UTF_8);

        storage.putImmutable(
                "org1/pkg1/result.json",
                content,
                "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229");

        assertThat(storage.get("org1/pkg1/result.json")).isEqualTo(content);
    }

    @Test
    void rejects_a_supplied_digest_that_does_not_match_the_bytes() {
        BlobStoragePort storage = storage();

        assertThatThrownBy(
                        () ->
                                storage.putImmutable(
                                        "org1/pkg1/result.json",
                                        "immutable content".getBytes(StandardCharsets.UTF_8),
                                        "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("immutable blob digest mismatch: org1/pkg1/result.json");
        assertThat(storage.exists("org1/pkg1/result.json")).isFalse();
    }

    @Test
    void rejects_a_digest_that_is_not_lowercase_sha256() {
        BlobStoragePort storage = storage();

        assertThatThrownBy(
                        () ->
                                storage.putImmutable(
                                        "org1/pkg1/result.json",
                                        "immutable content".getBytes(StandardCharsets.UTF_8),
                                        "E07A2EE52809331131735E7CE9EB07641C5C06068B0D26D2A0CBB860663CF229"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid immutable blob digest: org1/pkg1/result.json");
        assertThat(storage.exists("org1/pkg1/result.json")).isFalse();
    }

    @Test
    void repeating_the_same_immutable_create_is_an_idempotent_no_op() {
        BlobStoragePort storage = storage();
        byte[] content = "immutable content".getBytes(StandardCharsets.UTF_8);
        String digest = "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229";

        storage.putImmutable("org1/pkg1/result.json", content, digest);
        storage.putImmutable("org1/pkg1/result.json", content, digest);

        assertThat(storage.get("org1/pkg1/result.json")).isEqualTo(content);
    }

    @Test
    void rejects_different_bytes_at_an_existing_immutable_key_without_overwrite() {
        BlobStoragePort storage = storage();
        byte[] original = "immutable content".getBytes(StandardCharsets.UTF_8);
        byte[] different = "different content".getBytes(StandardCharsets.UTF_8);
        storage.putImmutable(
                "org1/pkg1/result.json",
                original,
                "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229");

        assertThatThrownBy(
                        () ->
                                storage.putImmutable(
                                        "org1/pkg1/result.json",
                                        different,
                                        "9d9d56051b7344b869e54a8ecebf1b39f21fe2449222bbdd135e9a608b216738"))
                .isInstanceOf(ImmutableBlobConflictException.class)
                .hasMessage("immutable blob conflict: org1/pkg1/result.json")
                .hasMessageNotContaining("different content")
                .hasNoCause();
        assertThat(storage.get("org1/pkg1/result.json")).isEqualTo(original);
    }

    @Test
    void concurrent_immutable_creators_produce_one_complete_winner() throws Exception {
        BlobStoragePort storage = storage();
        byte[] first = new byte[1024 * 1024];
        byte[] second = new byte[1024 * 1024];
        Arrays.fill(first, (byte) 0x2a);
        Arrays.fill(second, (byte) 0x6b);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Throwable> firstOutcome =
                    executor.submit(() -> publishAfter(start, storage, first, sha256(first)));
            Future<Throwable> secondOutcome =
                    executor.submit(() -> publishAfter(start, storage, second, sha256(second)));

            start.countDown();
            Throwable firstFailure = firstOutcome.get(10, TimeUnit.SECONDS);
            Throwable secondFailure = secondOutcome.get(10, TimeUnit.SECONDS);

            assertThat((firstFailure == null ? 1 : 0) + (secondFailure == null ? 1 : 0))
                    .isEqualTo(1);
            assertThat(firstFailure == null ? secondFailure : firstFailure)
                    .isInstanceOf(ImmutableBlobConflictException.class);
            byte[] stored = storage.get("org1/pkg1/result.json");
            assertThat(Arrays.equals(stored, first) || Arrays.equals(stored, second)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void immutable_readers_never_observe_temporary_bytes_at_the_final_key() throws Exception {
        BlobStoragePort storage = storage();
        byte[] content = new byte[16 * 1024 * 1024];
        Arrays.fill(content, (byte) 0x5c);
        String digest = sha256(content);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> writer =
                    executor.submit(
                            () -> {
                                await(start);
                                storage.putImmutable("org1/pkg1/result.json", content, digest);
                            });
            Future<Boolean> observer =
                    executor.submit(
                            () -> {
                                await(start);
                                while (!writer.isDone()) {
                                    if (storage.exists("org1/pkg1/result.json")
                                            && !Arrays.equals(
                                                    storage.get("org1/pkg1/result.json"), content)) {
                                        return false;
                                    }
                                }
                                return Arrays.equals(
                                        storage.get("org1/pkg1/result.json"), content);
                            });

            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(observer.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successful_immutable_publication_removes_its_temporary_sibling() throws Exception {
        BlobStoragePort storage = storage();
        byte[] original = "immutable content".getBytes(StandardCharsets.UTF_8);
        storage.putImmutable(
                "org1/pkg1/result.json",
                original,
                "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229");

        assertNoTemporarySiblings();
    }

    @Test
    void conflicting_immutable_publication_removes_its_temporary_sibling() throws Exception {
        BlobStoragePort storage = storage();
        byte[] original = "immutable content".getBytes(StandardCharsets.UTF_8);
        byte[] different = "different content".getBytes(StandardCharsets.UTF_8);
        storage.putImmutable(
                "org1/pkg1/result.json",
                original,
                "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229");
        assertThatThrownBy(
                        () ->
                                storage.putImmutable(
                                        "org1/pkg1/result.json",
                                        different,
                                        "9d9d56051b7344b869e54a8ecebf1b39f21fe2449222bbdd135e9a608b216738"))
                .isInstanceOf(ImmutableBlobConflictException.class);

        assertNoTemporarySiblings();
    }

    @Test
    void immutable_publication_fails_closed_when_hard_links_are_unsupported() throws Exception {
        Path archive = root.resolve("unsupported-links.zip");
        URI archiveUri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem =
                FileSystems.newFileSystem(archiveUri, Map.of("create", "true"))) {
            Path storageRoot = fileSystem.getPath("/blobs");
            BlobStoragePort storage = new LocalObjectStorageAdapter(storageRoot);
            byte[] content = "immutable content".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(
                            () ->
                                    storage.putImmutable(
                                            "org1/pkg1/result.json",
                                            content,
                                            "e07a2ee52809331131735e7ce9eb07641c5c06068b0d26d2a0cbb860663cf229"))
                    .isInstanceOfAny(UnsupportedOperationException.class, UncheckedIOException.class)
                    .hasMessageNotContaining("immutable content");
            assertThat(storage.exists("org1/pkg1/result.json")).isFalse();
            assertNoTemporarySiblings(storageRoot);
        }
    }

    private void assertNoTemporarySiblings() throws Exception {
        assertNoTemporarySiblings(root);
    }

    private static void assertNoTemporarySiblings(Path storageRoot) throws Exception {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isEmpty();
        }
    }

    private static Throwable publishAfter(
            CountDownLatch start, BlobStoragePort storage, byte[] content, String digest) {
        try {
            start.await();
            storage.putImmutable("org1/pkg1/result.json", content, digest);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void stores_and_reads_back_bytes() {
        BlobStoragePort storage = storage();
        byte[] content = "%PDF-1.7 synthetic".getBytes();

        storage.put("org1/pkg1/original.pdf", content);

        assertThat(storage.get("org1/pkg1/original.pdf")).isEqualTo(content);
    }

    @Test
    void exists_reflects_reality() {
        BlobStoragePort storage = storage();

        assertThat(storage.exists("nope")).isFalse();
        storage.put("yes", new byte[] {1});
        assertThat(storage.exists("yes")).isTrue();
    }

    @Test
    void delete_removes_the_blob() {
        BlobStoragePort storage = storage();
        storage.put("gone", new byte[] {1});

        storage.delete("gone");

        assertThat(storage.exists("gone")).isFalse();
    }

    @Test
    void get_of_a_missing_key_throws_not_returns_null() {
        assertThatThrownBy(() -> storage().get("missing"))
                .isInstanceOf(BlobNotFoundException.class);
    }

    @Test
    void rejects_path_traversal_in_keys() {
        BlobStoragePort storage = storage();

        assertThatThrownBy(() -> storage.put("../outside", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get("a/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("/absolute", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keys_may_use_nested_directories() {
        BlobStoragePort storage = storage();

        storage.put("org/package/file/deep.bin", new byte[] {7});

        assertThat(storage.get("org/package/file/deep.bin")).containsExactly(7);
    }
}
