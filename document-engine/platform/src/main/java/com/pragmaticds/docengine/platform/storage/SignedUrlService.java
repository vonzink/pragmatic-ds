package com.pragmaticds.docengine.platform.storage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies capability tokens for blob downloads. Local storage has no native presign, so
 * the application IS the signer: {@link #sign} mints a token carrying the {@link SignedObjectRef}
 * and an expiry, HMAC-SHA256'd under a server secret; {@link #verify} recomputes the MAC and, only
 * if it matches and the token is unexpired, returns the object it authorizes.
 *
 * <p>Token shape: {@code base64url(payload) + "." + base64url(hmac(payload))} where {@code payload}
 * is {@code v1|TYPE|id|org|expiryEpochSeconds}. The org is INSIDE the signed payload, so it cannot
 * be altered without the secret — the structural half of cross-tenant safety; the download endpoint
 * enforces the other half by loading the object under exactly that org.
 *
 * <p>Fail-closed: with no configured secret an unkeyed MAC would let anyone mint tokens, so signing
 * and verifying both refuse rather than pretend — the same stance {@code AuditService} takes for its
 * IP hash.
 */
@Service
public class SignedUrlService {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final char SEPARATOR = '.';
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SignedUrlService(@Value("${docengine.signed-url.secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    SignedUrlService(String secret, Clock clock) {
        this.key =
                secret == null || secret.isBlank()
                        ? null
                        : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** @return a token authorizing {@code ref} for {@code ttl} from now. */
    public String sign(SignedObjectRef ref, Duration ttl) {
        requireKey();
        long expiry = clock.instant().plus(ttl).getEpochSecond();
        String payload = payload(ref, expiry);
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + SEPARATOR
                + ENCODER.encodeToString(hmac(payload));
    }

    /**
     * @return the object the token authorizes
     * @throws SignedUrlException if the token is malformed, forged/tampered, or expired — the caller
     *     maps every case to an identical 404
     */
    public SignedObjectRef verify(String token) {
        requireKey();
        if (token == null) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }
        int dot = token.indexOf(SEPARATOR);
        if (dot <= 0 || dot >= token.length() - 1) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }
        String payload;
        byte[] providedSig;
        try {
            payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            providedSig = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }

        // Signature FIRST: never parse or trust an unverified payload.
        if (!MessageDigest.isEqual(hmac(payload), providedSig)) {
            throw new SignedUrlException(SignedUrlException.Reason.BAD_SIGNATURE);
        }

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }
        long expiry;
        UUID id;
        UUID org;
        try {
            id = UUID.fromString(parts[2]);
            org = UUID.fromString(parts[3]);
            expiry = Long.parseLong(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }
        if (expiry < clock.instant().getEpochSecond()) {
            throw new SignedUrlException(SignedUrlException.Reason.EXPIRED);
        }
        try {
            return new SignedObjectRef(parts[1], id, org);
        } catch (IllegalArgumentException e) {
            throw new SignedUrlException(SignedUrlException.Reason.MALFORMED);
        }
    }

    private static String payload(SignedObjectRef ref, long expiry) {
        return VERSION + '|' + ref.type() + '|' + ref.id() + '|' + ref.orgId() + '|' + expiry;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HMAC-SHA256 is always present and the key is non-empty here; treat as unrecoverable.
            throw new IllegalStateException("signed-url MAC unavailable", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "docengine.signed-url.secret is not configured — refusing to sign or verify");
        }
    }
}
