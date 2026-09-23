package com.pragmaticds.rag.lab.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The exact received engine-result artifact: the canonical bytes as they arrived plus the
 * SHA-256 computed over those received bytes.
 *
 * <p>Identity is the received bytes themselves — never a reserialization. The engine's quoted
 * ETag and {@code Content-Length} must equal {@link #sha256()} and {@link #byteCount()}.
 */
public final class EngineArtifactDescriptor {

    /** The engine's versioned canonical media type for immutable result reads. */
    public static final String CANONICAL_MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    private final byte[] bytes;
    private final String sha256;

    private EngineArtifactDescriptor(byte[] bytes, String sha256) {
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    /** Captures the received bytes (defensively copied) and hashes exactly those bytes. */
    public static EngineArtifactDescriptor of(byte[] receivedBytes) {
        Objects.requireNonNull(receivedBytes, "receivedBytes");
        byte[] copy = receivedBytes.clone();
        return new EngineArtifactDescriptor(copy, sha256Hex(copy));
    }

    /** The exact received bytes; every call returns a fresh defensive copy. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Lowercase hexadecimal SHA-256 over the received bytes. */
    public String sha256() {
        return sha256;
    }

    public int byteCount() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EngineArtifactDescriptor that
                && sha256.equals(that.sha256)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return sha256.hashCode();
    }

    /** Payload-free: digest and count only, never the bytes. */
    @Override
    public String toString() {
        return "EngineArtifactDescriptor[sha256=" + sha256 + ", byteCount=" + bytes.length + "]";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
