package com.pragmaticds.docengine.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The audit IP hash must be KEYED, so a stored {@code ip_hash} is not reversible over the tiny
 * (2^32) IPv4 space — the Phase 7b review finding that an unsalted SHA-256 was reversible.
 */
class AuditIpHashTest {

    private static final byte[] KEY = "server-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "different-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void the_same_ip_under_the_same_key_correlates() {
        // Correlation — the whole point of storing a hash — is preserved.
        assertThat(AuditService.keyedIpHash(KEY, "203.0.113.7"))
                .isEqualTo(AuditService.keyedIpHash(KEY, "203.0.113.7"))
                .isNotBlank();
    }

    @Test
    void the_key_actually_participates_so_a_rainbow_table_needs_the_secret() {
        // Without the server key an attacker cannot precompute the table: a different key yields a
        // different hash for the same address. This is what a bare SHA-256 could not offer.
        assertThat(AuditService.keyedIpHash(KEY, "203.0.113.7"))
                .isNotEqualTo(AuditService.keyedIpHash(OTHER_KEY, "203.0.113.7"));
    }

    @Test
    void distinct_addresses_hash_distinctly() {
        assertThat(AuditService.keyedIpHash(KEY, "203.0.113.7"))
                .isNotEqualTo(AuditService.keyedIpHash(KEY, "203.0.113.8"));
    }

    @Test
    void no_key_means_no_hash_rather_than_a_reversible_one() {
        // Fail closed: better to store nothing than a hash that does not deliver the privacy claim.
        assertThat(AuditService.keyedIpHash(null, "203.0.113.7")).isNull();
    }

    @Test
    void a_blank_address_yields_no_hash() {
        assertThat(AuditService.keyedIpHash(KEY, "")).isNull();
        assertThat(AuditService.keyedIpHash(KEY, null)).isNull();
    }
}
