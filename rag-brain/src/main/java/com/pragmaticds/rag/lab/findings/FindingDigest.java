package com.pragmaticds.rag.lab.findings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one way a finding's identity values are hashed.
 *
 * <p>Length-prefixed rather than delimiter-joined, because a group key is free text from a
 * borrower document: any separator this could pick is a separator a key could contain, and a
 * collision between two different problems is a waiver applied to the wrong one. {@code ("ab",
 * "c")} and {@code ("a", "bc")} must differ, and a null part must differ from the literal
 * {@code "null"}, so nulls get their own marker rather than a substituted string.
 *
 * <p>Takes no borrower values in its output: a digest is not reversible and is safe to store and
 * return. The inputs may be borrower values, so nothing here logs.
 */
public final class FindingDigest {

    private static final String PREFIX = "sha256:";

    private FindingDigest() {}

    /** Hashes parts in order; null is a distinct value, not an absent one. */
    public static String of(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                canonical.append("-|");
            } else {
                canonical.append(part.length()).append(':').append(part).append('|');
            }
        }
        return PREFIX + sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
