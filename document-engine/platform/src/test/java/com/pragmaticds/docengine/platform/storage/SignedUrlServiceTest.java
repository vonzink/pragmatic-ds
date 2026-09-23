package com.pragmaticds.docengine.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The app-issued, app-verified capability token that stands in for a native presign on local
 * storage. The token carries the object it authorizes AND the org that owns it, HMAC-signed with a
 * server secret so neither can be altered without the key. These are pure unit tests — no Spring,
 * no HTTP — so the crypto contract is pinned in isolation from the endpoints that use it.
 */
class SignedUrlServiceTest {

    private static final String SECRET = "unit-test-signing-secret-0123456789";
    private static final Instant T0 = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static SignedUrlService at(Instant instant) {
        return new SignedUrlService(SECRET, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void a_valid_token_round_trips_to_the_same_object_and_org() {
        SignedUrlService service = at(T0);
        SignedObjectRef ref = new SignedObjectRef(SignedObjectRef.TYPE_FILE_CONTENT, OBJECT, ORG_A);

        String token = service.sign(ref, Duration.ofMinutes(5));
        SignedObjectRef verified = service.verify(token);

        assertThat(verified.type()).isEqualTo(SignedObjectRef.TYPE_FILE_CONTENT);
        assertThat(verified.id()).isEqualTo(OBJECT);
        assertThat(verified.orgId()).isEqualTo(ORG_A);
    }

    @Test
    void an_expired_token_is_rejected() {
        String token =
                at(T0).sign(
                                new SignedObjectRef(SignedObjectRef.TYPE_PAGE_RENDER, OBJECT, ORG_A),
                                Duration.ofSeconds(60));

        // A verifier whose clock is past the expiry rejects the very same token.
        SignedUrlService later = at(T0.plusSeconds(120));
        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOf(SignedUrlException.class)
                .extracting(e -> ((SignedUrlException) e).reason())
                .isEqualTo(SignedUrlException.Reason.EXPIRED);
    }

    @Test
    void a_token_with_a_tampered_signature_is_rejected() {
        String token =
                at(T0).sign(
                                new SignedObjectRef(SignedObjectRef.TYPE_FILE_CONTENT, OBJECT, ORG_A),
                                Duration.ofMinutes(5));
        // Flip the last character of the signature segment.
        int dot = token.indexOf('.');
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(dot).isGreaterThan(0);
        assertThatThrownBy(() -> at(T0).verify(tampered))
                .isInstanceOf(SignedUrlException.class)
                .extracting(e -> ((SignedUrlException) e).reason())
                .isEqualTo(SignedUrlException.Reason.BAD_SIGNATURE);
    }

    @Test
    void swapping_the_org_in_the_payload_breaks_the_signature() {
        // A token minted for org A, then re-pointed at org B by rewriting the payload but keeping
        // A's signature, must not verify: the org is inside the signed material.
        SignedUrlService service = at(T0);
        String token =
                service.sign(
                        new SignedObjectRef(SignedObjectRef.TYPE_FILE_CONTENT, OBJECT, ORG_A),
                        Duration.ofMinutes(5));

        int dot = token.indexOf('.');
        String payload =
                new String(
                        Base64.getUrlDecoder().decode(token.substring(0, dot)),
                        StandardCharsets.UTF_8);
        String forgedPayload = payload.replace(ORG_A.toString(), ORG_B.toString());
        String forgedToken =
                Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                        + token.substring(dot);

        assertThatThrownBy(() -> service.verify(forgedToken))
                .isInstanceOf(SignedUrlException.class)
                .extracting(e -> ((SignedUrlException) e).reason())
                .isEqualTo(SignedUrlException.Reason.BAD_SIGNATURE);
    }

    @Test
    void a_malformed_token_is_rejected_not_500() {
        SignedUrlService service = at(T0);
        assertThatThrownBy(() -> service.verify("not-a-token")).isInstanceOf(SignedUrlException.class);
        assertThatThrownBy(() -> service.verify("")).isInstanceOf(SignedUrlException.class);
        assertThatThrownBy(() -> service.verify(null)).isInstanceOf(SignedUrlException.class);
    }

    @Test
    void a_service_with_no_secret_fails_closed() {
        SignedUrlService noSecret = new SignedUrlService("", Clock.fixed(T0, ZoneOffset.UTC));
        assertThatThrownBy(
                        () ->
                                noSecret.sign(
                                        new SignedObjectRef(
                                                SignedObjectRef.TYPE_FILE_CONTENT, OBJECT, ORG_A),
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
    }
}
