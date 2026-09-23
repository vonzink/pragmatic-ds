package com.pragmaticds.docengine.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Filesystem-backed {@link BlobStoragePort}. Not a Spring bean by itself — the app module wires it
 * with the configured root, mirroring how host-app constructs its storage adapters.
 *
 * <p>Key discipline: relative, normalized, and provably inside the root. A key that escapes the
 * root is an attack, not a mistake, so it throws rather than being silently sanitised.
 */
public class LocalObjectStorageAdapter implements BlobStoragePort {

    private final Path root;

    public LocalObjectStorageAdapter(Path root) {
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("storage root unavailable: " + root, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("invalid blob key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("invalid blob key");
        }
        return resolved;
    }

    @Override
    public void put(String key, byte[] content) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("blob write failed: " + key, e);
        }
    }

    @Override
    public void putImmutable(String key, byte[] content, String expectedSha256) {
        Path path = resolve(key);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid immutable blob digest: " + key);
        }
        if (!sha256(content).equals(expectedSha256)) {
            throw new IllegalArgumentException("immutable blob digest mismatch: " + key);
        }
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".immutable-", ".tmp");
            Files.write(temporary, content);
            if (!sha256(temporary).equals(expectedSha256)) {
                throw new IllegalStateException("immutable blob temporary digest mismatch: " + key);
            }
            try {
                Files.createLink(path, temporary);
            } catch (FileAlreadyExistsException ignored) {
                if (!sha256(path).equals(expectedSha256)) {
                    throw new ImmutableBlobConflictException(key);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("immutable blob write failed: " + key, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    throw new UncheckedIOException("immutable blob cleanup failed: " + key, e);
                }
            }
        }
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(newSha256().digest(content));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public byte[] get(String key) {
        Path path = resolve(key);
        if (!Files.exists(path)) {
            throw new BlobNotFoundException(key);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("blob read failed: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("blob delete failed: " + key, e);
        }
    }
}
