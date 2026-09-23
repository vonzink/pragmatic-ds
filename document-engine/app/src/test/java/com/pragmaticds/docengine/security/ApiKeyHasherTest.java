package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiKeyHasher}: the stored-hash contract is exactly {@code HMAC-SHA256(salt, rawKey)} in
 * lower-case hex, it is deterministic and salt-dependent, and an unconfigured salt fails closed.
 */
class ApiKeyHasherTest {

    private static final String SALT = "a-server-side-salt";
    private static final String RAW = "pds_live_deadbeefcafef00d";

    @Test
    void hashing_is_deterministic_for_a_given_salt_and_key() {
        ApiKeyHasher hasher = new ApiKeyHasher(SALT);
        assertThat(hasher.hash(RAW)).isEqualTo(hasher.hash(RAW));
    }

    @Test
    void the_hash_is_exactly_hmac_sha256_of_the_key_under_the_salt_in_lowercase_hex()
            throws Exception {
        String actual = new ApiKeyHasher(SALT).hash(RAW);

        // Independently computed reference — pins the algorithm, encoding, and case.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected =
                HexFormat.of().formatHex(mac.doFinal(RAW.getBytes(StandardCharsets.UTF_8)));

        assertThat(actual).isEqualTo(expected).hasSize(64).matches("[0-9a-f]{64}");
    }

    /**
     * The known-answer vector {@code tools/issue_api_key.py} pins on its side.
     *
     * <p>That script is a SECOND implementation of this hash contract — it has to be, because it
     * mints keys without a JVM — and a second implementation is exactly where a contract goes
     * quietly wrong. A key hashed under a drifted algorithm inserts cleanly, looks right in the
     * table, and then authenticates nowhere; the operator sees an opaque 401 and blames the key.
     *
     * <p>So both sides assert the SAME triple. This test fails if Java drifts;
     * {@code issue_api_key.py --self-test} fails if Python does. Change the vector only if you
     * intend to change the contract, and change it in both places.
     */
    @Test
    void the_hash_matches_the_vector_the_issuance_tool_pins() {
        assertThat(new ApiKeyHasher(SALT).hash(RAW))
                .isEqualTo("bba5b41eb9141d793eaed928d60605dc56d412492533d1e6f11ad1ff8f2c91b9");
    }

    @Test
    void a_different_salt_yields_a_different_hash() {
        assertThat(new ApiKeyHasher(SALT).hash(RAW))
                .isNotEqualTo(new ApiKeyHasher("a-different-salt").hash(RAW));
    }

    @Test
    void an_empty_or_blank_salt_is_not_configured_and_refuses_to_hash() {
        for (String blank : new String[] {"", "   ", null}) {
            ApiKeyHasher hasher = new ApiKeyHasher(blank);
            assertThat(hasher.isConfigured()).isFalse();
            assertThatThrownBy(() -> hasher.hash(RAW)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void a_configured_salt_reports_configured() {
        assertThat(new ApiKeyHasher(SALT).isConfigured()).isTrue();
    }
}
