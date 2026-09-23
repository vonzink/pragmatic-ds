package com.pragmaticds.docengine.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Hashes a presented API key to the value stored in {@code api_key.key_hash}: {@code
 * HMAC-SHA256(salt, rawKey)}, lower-case hex. The salt is a server secret from {@code
 * docengine.apikey.salt} (env {@code DOCENGINE_APIKEY_SALT}) so a stolen database of hashes is not
 * offline-brute-forceable without also stealing the salt.
 *
 * <p>Fail-closed, exactly like {@link com.pragmaticds.docengine.platform.storage.SignedUrlService}: with no
 * configured salt the hasher refuses — {@link #isConfigured()} returns {@code false} and {@link
 * #hash(String)} throws — and it logs one WARN at construction so a misconfigured deployment is
 * visible in the logs at startup. An unsalted MAC would let a database leak become a skeleton key,
 * so the auth filter rejects every presented key rather than pretend.
 *
 * <p>Neither the raw key nor the computed hash is ever logged. A constant-time compare is not this
 * class's job: the lookup is a Postgres unique-index equality on the hash, not an in-process secret
 * comparison.
 *
 * <p>Bound to the non-local/test profiles, exactly like the {@link ApiKeyAuthFilter} it feeds:
 * service auth only runs on the JWT chain. Local/test authenticate via {@code DevAuthFilter} and are
 * left byte-identical (no bean, no startup log). The unit tests construct it directly.
 */
@Component
@Profile("!local & !test")
public class ApiKeyHasher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHasher.class);
    private static final String HMAC_ALG = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of(); // lower-case by default

    private final byte[] salt;

    public ApiKeyHasher(@Value("${docengine.apikey.salt:}") String salt) {
        this.salt = salt == null || salt.isBlank() ? null : salt.getBytes(StandardCharsets.UTF_8);
        if (this.salt == null) {
            log.warn(
                    "docengine.apikey.salt is not configured — API-key authentication is DISABLED"
                        + " (every X-DocEngine-Api-Key will be rejected). A deployment that uses"
                        + " service auth MUST set DOCENGINE_APIKEY_SALT.");
        }
    }

    /** Whether a salt is configured. When false, the filter must reject all presented keys. */
    public boolean isConfigured() {
        return salt != null;
    }

    /**
     * @return {@code HMAC-SHA256(salt, rawKey)} as lower-case hex
     * @throws IllegalStateException if no salt is configured (fail-closed) — callers must gate on
     *     {@link #isConfigured()} first and turn this state into a generic 401
     */
    public String hash(String rawKey) {
        if (salt == null) {
            throw new IllegalStateException(
                    "docengine.apikey.salt is not configured — refusing to hash");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(salt, HMAC_ALG));
            return HEX.formatHex(mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HMAC-SHA256 is always present and the salt is non-empty here; unrecoverable.
            throw new IllegalStateException("api-key MAC unavailable", e);
        }
    }
}
