package com.pragmaticds.rag.lab.security;

import com.pragmaticds.rag.lab.config.LabProperties;
import com.pragmaticds.rag.lab.security.LabCryptoException.Code;
import com.pragmaticds.rag.lab.security.LabPayloadCipher.RecordType;
import com.pragmaticds.rag.lab.security.LabPayloadCipher.SealedPayload;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encrypted payload boundary: AES-256-GCM, a random nonce per record, associated data binding
 * brain/run/record/type, and errors that cannot leak what they failed to decrypt.
 *
 * <p>Every plaintext here is a synthetic canary string. Nothing is corpus-derived and nothing
 * resembles borrower data.
 */
class LabPayloadCipherTest {

    /** A distinctive synthetic marker: if it appears in stored bytes or an error, we leaked. */
    private static final String CANARY = "CANARY-LAB3-PLAINTEXT-MARKER-7f3a";

    private static final UUID BRAIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECORD = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static String key() {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    private static byte[] plaintext() {
        return (CANARY + " report body").getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- round trip

    @Test
    void sealsAndOpensTheExactPlaintext() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        assertEquals(LabPayloadCipher.ALGORITHM, sealed.algorithm());
        assertEquals(12, sealed.nonce().length);
        assertArrayEquals(
                plaintext(),
                cipher.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed));
    }

    @Test
    void ciphertextIsLongerThanPlaintextByExactlyTheGcmTag() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        assertEquals(plaintext().length + 16, sealed.ciphertext().length);
    }

    @Test
    void anEmptyPlaintextStillProducesAnAuthenticatedRecord() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.DISCUSSION_USER, new byte[0]);

        assertEquals(16, sealed.ciphertext().length, "a bare GCM tag is still storable");
        assertArrayEquals(
                new byte[0], cipher.open(BRAIN, RUN, RECORD, RecordType.DISCUSSION_USER, sealed));
    }

    // ---------------------------------------------------------------- nonces

    @Test
    void everyRecordGetsAFreshRandomNonceEvenForIdenticalPlaintext() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());
        Set<String> nonces = new HashSet<>();
        Set<String> ciphertexts = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            SealedPayload sealed =
                    cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());
            nonces.add(Base64.getEncoder().encodeToString(sealed.nonce()));
            ciphertexts.add(Base64.getEncoder().encodeToString(sealed.ciphertext()));
        }

        assertEquals(200, nonces.size(), "a repeated nonce under one key destroys GCM");
        assertEquals(200, ciphertexts.size(), "identical plaintext must not produce identical bytes");
    }

    // ---------------------------------------------------------------- associated data

    @Test
    void associatedDataBindsTheRecordToItsBrainRunRecordAndType() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());
        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        UUID other = UUID.fromString("44444444-4444-4444-4444-444444444444");
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(other, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed)));
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(BRAIN, other, RECORD, RecordType.ANALYSIS_OUTPUT, sealed)));
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(BRAIN, RUN, other, RecordType.ANALYSIS_OUTPUT, sealed)));
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(BRAIN, RUN, RECORD, RecordType.DISCUSSION_ASSISTANT, sealed)));
    }

    @Test
    void associatedDataIsUnambiguousAcrossItsFixedWidthFields() {
        // Fixed-width UUIDs plus a delimited version prefix: no reshuffling of the identity
        // fields can produce the same associated data, so ciphertext cannot be replayed
        // between records by rearranging identifiers.
        LabPayloadCipher cipher = new LabPayloadCipher(key());
        SealedPayload sealed = cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(RUN, BRAIN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed)));
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                cipher.open(BRAIN, RECORD, RUN, RecordType.ANALYSIS_OUTPUT, sealed)));
    }

    // ---------------------------------------------------------------- wrong key and tamper

    @Test
    void aWrongKeyCannotOpenTheRecord() {
        SealedPayload sealed = new LabPayloadCipher(key())
                .seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        LabPayloadCipher other = new LabPayloadCipher(key());

        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() ->
                other.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed)));
    }

    @Test
    void aTamperedCiphertextNonceOrTagFailsClosed() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());
        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        byte[] flippedBody = sealed.ciphertext();
        flippedBody[0] ^= 0x01;
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() -> cipher.open(BRAIN, RUN, RECORD,
                RecordType.ANALYSIS_OUTPUT,
                new SealedPayload(sealed.nonce(), flippedBody))));

        byte[] flippedTag = sealed.ciphertext();
        flippedTag[flippedTag.length - 1] ^= 0x01;
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() -> cipher.open(BRAIN, RUN, RECORD,
                RecordType.ANALYSIS_OUTPUT,
                new SealedPayload(sealed.nonce(), flippedTag))));

        byte[] flippedNonce = sealed.nonce();
        flippedNonce[0] ^= 0x01;
        assertEquals(Code.PAYLOAD_UNAUTHENTIC, failureOf(() -> cipher.open(BRAIN, RUN, RECORD,
                RecordType.ANALYSIS_OUTPUT,
                new SealedPayload(flippedNonce, sealed.ciphertext()))));
    }

    @Test
    void aTruncatedRecordFailsClosedWithoutAttemptingDecryption() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        assertEquals(Code.PAYLOAD_MALFORMED, failureOf(() -> cipher.open(BRAIN, RUN, RECORD,
                RecordType.ANALYSIS_OUTPUT, new SealedPayload(new byte[12], new byte[15]))));
        assertEquals(Code.PAYLOAD_MALFORMED, failureOf(() -> cipher.open(BRAIN, RUN, RECORD,
                RecordType.ANALYSIS_OUTPUT, new SealedPayload(new byte[11], new byte[32]))));
    }

    // ---------------------------------------------------------------- defensive copies

    @Test
    void sealedPayloadsAreDefensivelyCopiedInAndOut() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());
        byte[] source = plaintext();

        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, source);

        // Mutating the caller's plaintext buffer after sealing cannot change what was sealed.
        java.util.Arrays.fill(source, (byte) 0);
        assertArrayEquals(
                plaintext(), cipher.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed));

        // Each accessor hands back a copy, so a caller cannot corrupt the record in place.
        byte[] firstRead = sealed.ciphertext();
        firstRead[0] ^= 0x7f;
        assertFalse(java.util.Arrays.equals(firstRead, sealed.ciphertext()));
        byte[] nonceRead = sealed.nonce();
        nonceRead[0] ^= 0x7f;
        assertFalse(java.util.Arrays.equals(nonceRead, sealed.nonce()));

        // And the opened plaintext is the caller's own copy.
        byte[] opened = cipher.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed);
        java.util.Arrays.fill(opened, (byte) 0);
        assertArrayEquals(
                plaintext(), cipher.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed));
    }

    // ---------------------------------------------------------------- canary absence

    @Test
    void noPlaintextCanaryAppearsInStoredBytes() {
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());

        assertFalse(containsCanary(sealed.ciphertext()), "ciphertext must not contain plaintext");
        assertFalse(containsCanary(sealed.nonce()));
        assertFalse(sealed.toString().contains(CANARY), "toString must be payload-free");
        assertFalse(cipher.toString().contains(CANARY));
    }

    @Test
    void noPlaintextOrKeyCanaryAppearsInAnyError() {
        String keyMaterial = key();
        LabPayloadCipher cipher = new LabPayloadCipher(keyMaterial);
        SealedPayload sealed =
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());
        byte[] tampered = sealed.ciphertext();
        tampered[3] ^= 0x40;

        LabCryptoException failure = assertThrows(LabCryptoException.class, () ->
                cipher.open(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT,
                        new SealedPayload(sealed.nonce(), tampered)));

        String rendered = render(failure);
        assertFalse(rendered.contains(CANARY), "error text leaked plaintext: " + rendered);
        assertFalse(rendered.contains(keyMaterial), "error text leaked the key");
        assertNull(failure.getCause(), "a cause would carry provider detail into logs");
        assertEquals("PAYLOAD_UNAUTHENTIC", failure.getMessage());
    }

    // ---------------------------------------------------------------- key handling

    @Test
    void theKeyMustBeExactlyThirtyTwoBase64EncodedBytes() {
        assertNotNull(new LabPayloadCipher(key()));

        for (String unusable : new String[] {
                null,
                "",
                "   ",
                "not base64 at all ***",
                Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33]),
                Base64.getEncoder().encodeToString(new byte[16])}) {
            LabPayloadCipher unusableCipher = new LabPayloadCipher(unusable);
            assertFalse(unusableCipher.isAvailable(),
                    "a missing or invalid key must not look usable: " + unusable);
            assertEquals(Code.KEY_UNAVAILABLE, failureOf(() -> unusableCipher.seal(
                    BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext())));
            assertEquals(Code.KEY_UNAVAILABLE, failureOf(() -> unusableCipher.open(
                    BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT,
                    new SealedPayload(new byte[12], new byte[32]))));
        }
    }

    @Test
    void anUnusableKeyIsNeverEchoedIntoAnErrorOrToString() {
        String almostValid = Base64.getEncoder().encodeToString(new byte[31]);
        LabPayloadCipher cipher = new LabPayloadCipher(almostValid);

        LabCryptoException failure = assertThrows(LabCryptoException.class, () ->
                cipher.seal(BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext()));

        assertFalse(render(failure).contains(almostValid));
        assertFalse(cipher.toString().contains(almostValid));
    }

    @Test
    void theKeyMaterialIsCopiedOutOfTheCallersString() {
        // The configured value stays a String we never retain: the cipher holds only a SecretKey,
        // so a heap dump of the cipher does not hand over the configured property text.
        LabPayloadCipher cipher = new LabPayloadCipher(key());

        assertTrue(cipher.isAvailable());
        assertEquals("LabPayloadCipher[algorithm=AES-256-GCM, keyAvailable=true]",
                cipher.toString());
        assertEquals("LabPayloadCipher[algorithm=AES-256-GCM, keyAvailable=false]",
                new LabPayloadCipher(null).toString());
    }

    // ---------------------------------------------------------------- startup posture

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LabProperties.class)
    @Import(LabPayloadCipher.class)
    static class LabWiring {}

    private ApplicationContextRunner wiring() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(LabWiring.class);
    }

    @Test
    void anAbsentKeyDoesNotPreventOrdinaryStartupWhileTheFeatureIsOff() {
        // The prototype's whole default posture: no Lab key configured, Lab off, app boots.
        wiring()
                .withPropertyValues("ragbrain.lab.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "an absent Lab key must never break ordinary startup");
                    assertEquals(0, context.getBeanNamesForType(LabPayloadCipher.class).length,
                            "no cipher bean may exist with the Lab disabled");
                });
        wiring()
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeanNamesForType(LabPayloadCipher.class).length);
                });
    }

    @Test
    void anInvalidKeyStillStartsTheContextWithTheLabEnabledAndFailsOnlyLabRequests() {
        // Failing startup here would take the whole application down over one Lab property; the
        // plan requires a safe 503 on Lab requests instead.
        wiring()
                .withPropertyValues(
                        "ragbrain.lab.enabled=true",
                        "ragbrain.lab.max-upload-bytes=26214400",
                        "ragbrain.lab.engine.base-url=http://localhost:9090",
                        "ragbrain.lab.engine.dev-auth=true",
                        "ragbrain.lab.engine.org-id=" + UUID.randomUUID(),
                        "ragbrain.lab.engine.max-envelope-bytes=8388608",
                        "ragbrain.lab.engine.connect-timeout-ms=2000",
                        "ragbrain.lab.engine.read-timeout-ms=5000",
                        "ragbrain.lab.payload-key=" + Base64.getEncoder().encodeToString(new byte[16]))
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "an invalid Lab key must not fail application startup");
                    LabPayloadCipher cipher = context.getBean(LabPayloadCipher.class);
                    assertFalse(cipher.isAvailable());
                    assertEquals(Code.KEY_UNAVAILABLE, failureOf(() -> cipher.seal(
                            BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext())));
                });
    }

    @Test
    void aValidKeyIsBoundFromConfigurationWhenTheLabIsEnabled() {
        wiring()
                .withPropertyValues(
                        "ragbrain.lab.enabled=true",
                        "ragbrain.lab.max-upload-bytes=26214400",
                        "ragbrain.lab.engine.base-url=http://localhost:9090",
                        "ragbrain.lab.engine.dev-auth=true",
                        "ragbrain.lab.engine.org-id=" + UUID.randomUUID(),
                        "ragbrain.lab.engine.max-envelope-bytes=8388608",
                        "ragbrain.lab.engine.connect-timeout-ms=2000",
                        "ragbrain.lab.engine.read-timeout-ms=5000",
                        "ragbrain.lab.payload-key=" + key())
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    LabPayloadCipher cipher = context.getBean(LabPayloadCipher.class);
                    assertTrue(cipher.isAvailable());
                    SealedPayload sealed = cipher.seal(
                            BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, plaintext());
                    assertArrayEquals(plaintext(), cipher.open(
                            BRAIN, RUN, RECORD, RecordType.ANALYSIS_OUTPUT, sealed));
                });
    }

    // ---------------------------------------------------------------- helpers

    private static Code failureOf(Runnable call) {
        return assertThrows(LabCryptoException.class, call::run).code();
    }

    private static boolean containsCanary(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(CANARY);
    }

    private static String render(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.toString());
        for (StackTraceElement element : failure.getStackTrace()) {
            text.append('\n').append(element);
        }
        Throwable cause = failure.getCause();
        while (cause != null) {
            text.append('\n').append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }
}
