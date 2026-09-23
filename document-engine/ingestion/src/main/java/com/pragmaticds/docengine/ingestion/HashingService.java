package com.pragmaticds.docengine.ingestion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of uploaded bytes, as lowercase hex — the identity that drives duplicate detection
 * (unique per package, warning across packages) and lets an auditor prove a stored blob is the
 * exact bytes the client sent.
 */
@Component
public class HashingService {

    public String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // Every conformant JVM ships SHA-256; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
