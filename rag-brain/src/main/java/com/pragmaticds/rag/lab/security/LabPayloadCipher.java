package com.pragmaticds.rag.lab.security;

import com.pragmaticds.rag.lab.security.LabCryptoException.Code;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * The Lab's encrypted payload boundary: AES-256-GCM with a fresh random nonce per record and
 * associated data that binds each record to its brain, run, record, and payload type.
 *
 * <p>The associated data is the point of this class, not decoration. Because the tag is computed
 * over {@code brain | run | record | type}, ciphertext lifted from one row and pasted into another
 * fails to open — a cross-brain read cannot be achieved by editing a foreign key, and an analysis
 * payload cannot be replayed as a discussion message. The identity fields are fixed-width UUIDs
 * behind a version prefix, so no rearrangement of them produces the same associated data.
 *
 * <p><strong>Startup posture.</strong> This bean exists only while {@code ragbrain.lab.enabled} is
 * true, and even then its constructor never throws: a missing or malformed key leaves the cipher
 * unavailable, so every Lab request fails with {@link Code#KEY_UNAVAILABLE} (a safe 503) while
 * ordinary RAG Brain startup and every existing route are untouched. Taking the whole application
 * down over one prototype property would be the wrong trade, so the key is deliberately <em>not</em>
 * part of {@code LabProperties}, whose constructor validates eagerly when the Lab is enabled.
 *
 * <p>Errors are value-free and byte arrays are copied on every crossing, so neither a plaintext
 * canary nor key material can escape through an exception, a {@code toString()}, or a retained
 * caller reference.
 */
/**
 * Shared by the Lab prototype and the instance control plane, so it exists when EITHER is on.
 *
 * <p>It was gated on the Lab alone when the Lab was its only caller. Instance runs now seal and
 * open the same payloads, and a deployment running instances with the prototype off would have
 * failed to start rather than merely lacking a feature — which is what
 * {@code InstanceAdminControllerMvcIT} and {@code LabIdempotencyServiceTxIT} pin by booting in
 * exactly that configuration.
 */
@Component
@ConditionalOnExpression(
        "${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public final class LabPayloadCipher {

    /** The one algorithm V34 will store, spelled exactly as the {@code cipher_algorithm} column. */
    public static final String ALGORITHM = "AES-256-GCM";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    /** GCM's recommended nonce width; V34 checks it too, so a 12-byte nonce is a schema fact. */
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    /** Version prefix so a future associated-data change is a visible, non-silent break. */
    private static final String AAD_VERSION = "LAB-AAD-1";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a sealed record is, so one payload type can never be opened as another. */
    public enum RecordType {
        ANALYSIS_OUTPUT,
        /** A generalized instance run's pinned execution provenance. */
        RUN_PROVENANCE,
        DISCUSSION_USER,
        DISCUSSION_ASSISTANT,
        /**
         * One registration's loan-level facts — program, purpose, qualifying monthly income,
         * Adjusted Value. Bound to the registration rather than to a run: both AAD slots that
         * normally carry a run and a record id carry the registration id, so a sealed record
         * cannot be opened against another registration or another brain.
         */
        REGISTRATION_LOAN_FACTS
    }

    /**
     * One sealed record: the nonce to store beside the ciphertext, and the ciphertext with its
     * appended GCM tag. Both are copied in and out, and {@code toString()} reports lengths only.
     */
    public record SealedPayload(byte[] nonce, byte[] ciphertext) {

        public SealedPayload {
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(ciphertext, "ciphertext");
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        /** Always {@link LabPayloadCipher#ALGORITHM}: this record holds nothing else. */
        public String algorithm() {
            return ALGORITHM;
        }

        /** Lengths only — never the bytes, which are ciphertext but still not for logs. */
        @Override
        public String toString() {
            return "SealedPayload[nonceBytes=" + nonce.length
                    + ", ciphertextBytes=" + ciphertext.length + "]";
        }
    }

    /** Null exactly when no usable key is configured. Never the configured String itself. */
    private final SecretKeySpec key;

    public LabPayloadCipher(@Value("${ragbrain.lab.payload-key:}") String base64Key) {
        this.key = readKey(base64Key);
    }

    /** Whether Lab payload encryption is usable; false means Lab requests fail with a safe 503. */
    public boolean isAvailable() {
        return key != null;
    }

    /**
     * Seals one record. The caller's plaintext is copied immediately and the copy is wiped before
     * returning, so a later mutation of the caller's buffer cannot change what was sealed and the
     * working copy does not linger.
     */
    public SealedPayload seal(
            UUID brainId, UUID runId, UUID recordId, RecordType type, byte[] plaintext) {
        SecretKeySpec usable = requireKey();
        byte[] working = Objects.requireNonNull(plaintext, "plaintext").clone();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, usable, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(brainId, runId, recordId, type));
            return new SealedPayload(nonce, cipher.doFinal(working));
        } catch (GeneralSecurityException exception) {
            // Encryption under a validated key does not fail for data reasons; treat anything
            // here as an unusable cryptographic configuration rather than leaking the cause.
            throw new LabCryptoException(Code.KEY_UNAVAILABLE);
        } finally {
            Arrays.fill(working, (byte) 0);
        }
    }

    /**
     * Opens one record, returning the caller's own plaintext copy.
     *
     * @throws LabCryptoException {@link Code#PAYLOAD_MALFORMED} when the stored bytes cannot be an
     *     AES-256-GCM record at all — checked before any decryption is attempted — or
     *     {@link Code#PAYLOAD_UNAUTHENTIC} for a wrong key, tampered bytes, or associated data that
     *     does not bind this record to the requested brain/run/record/type
     */
    public byte[] open(
            UUID brainId, UUID runId, UUID recordId, RecordType type, SealedPayload sealed) {
        SecretKeySpec usable = requireKey();
        Objects.requireNonNull(sealed, "sealed");
        byte[] nonce = sealed.nonce();
        byte[] ciphertext = sealed.ciphertext();
        if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BYTES) {
            throw new LabCryptoException(Code.PAYLOAD_MALFORMED);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, usable, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(brainId, runId, recordId, type));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            // AEADBadTagException and friends are swallowed deliberately: their message and stack
            // would name the record and the provider that rejected it.
            throw new LabCryptoException(Code.PAYLOAD_UNAUTHENTIC);
        }
    }

    /**
     * The authenticated binding. Fixed-width UUIDs behind a delimited version prefix, so the field
     * order cannot be permuted into an equal value.
     */
    private static byte[] associatedData(
            UUID brainId, UUID runId, UUID recordId, RecordType type) {
        String bound = AAD_VERSION
                + '|' + Objects.requireNonNull(brainId, "brainId")
                + '|' + Objects.requireNonNull(runId, "runId")
                + '|' + Objects.requireNonNull(recordId, "recordId")
                + '|' + Objects.requireNonNull(type, "type").name();
        return bound.getBytes(StandardCharsets.US_ASCII);
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new LabCryptoException(Code.KEY_UNAVAILABLE);
        }
        return key;
    }

    /**
     * Decodes the configured key, or returns null. Never throws and never echoes the configured
     * value: an operator's typo must produce an unavailable cipher, not a startup crash or a log
     * line containing most of a key.
     */
    private static SecretKeySpec readKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] material;
        try {
            material = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        try {
            return material.length == KEY_BYTES ? new SecretKeySpec(material, "AES") : null;
        } finally {
            // SecretKeySpec copies the array, so this wipe leaves only the key object holding it.
            Arrays.fill(material, (byte) 0);
        }
    }

    /** Algorithm and availability only — never the key, a nonce, or any payload. */
    @Override
    public String toString() {
        return "LabPayloadCipher[algorithm=" + ALGORITHM + ", keyAvailable=" + isAvailable() + "]";
    }
}
