package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verified against the published SHA-256 test vectors (FIPS 180-4) — the digest is what duplicate
 * detection and the storage audit trail hang off, so it must be the real algorithm, lowercase hex.
 */
class HashingServiceTest {

    private final HashingService hashing = new HashingService();

    @Test
    void hashes_abc_to_the_fips_test_vector() {
        byte[] content = "abc".getBytes(StandardCharsets.US_ASCII);

        assertThat(hashing.sha256Hex(content))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashes_the_empty_input_to_the_well_known_digest() {
        assertThat(hashing.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void digest_is_64_lowercase_hex_chars() {
        String digest = hashing.sha256Hex("mortgage package".getBytes(StandardCharsets.UTF_8));

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
    }
}
