package com.pragmaticds.rag.lab.security;

import java.util.Objects;

/**
 * An encrypted-payload boundary failure.
 *
 * <p>Payload-free by construction, exactly like {@code LabContractException} and
 * {@code DocumentEngineFailure}: a stable {@link Code} and nothing else. There is no free-text
 * message and no cause, so no plaintext, key material, or JCE provider detail can travel inside it
 * into a response, a log line, or an audit row. A {@link javax.crypto.AEADBadTagException} in
 * particular is never wrapped — its message and stack would name the provider that rejected the
 * record.
 */
public final class LabCryptoException extends RuntimeException {

    /** Stable, value-free failure taxonomy for the Lab payload cipher. */
    public enum Code {
        /**
         * No usable 32-byte Base64 payload key is configured. A configuration fault, not a caller
         * fault: Lab requests answer a safe 503 while the rest of the application is unaffected.
         */
        KEY_UNAVAILABLE,
        /** The stored record cannot be an AES-256-GCM record: wrong nonce width, or no room for a tag. */
        PAYLOAD_MALFORMED,
        /** Authentication failed: wrong key, tampered bytes, or associated data that does not bind. */
        PAYLOAD_UNAUTHENTIC
    }

    private final Code code;

    public LabCryptoException(Code code) {
        // No cause, ever, and no suppression: only the code name and a class/method stack trace.
        super(Objects.requireNonNull(code, "code").name(), null, false, true);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /**
     * True when the failure is a deployment configuration fault rather than a bad request, which is
     * the signal the Lab exception handler maps to a safe 503. Exposed as a predicate so the web
     * layer never has to match on an error string.
     */
    public boolean isConfigurationUnavailable() {
        return code == Code.KEY_UNAVAILABLE;
    }

    /** Code only — never a nonce, ciphertext, plaintext, or key. */
    @Override
    public String toString() {
        return "LabCryptoException[code=" + code + "]";
    }
}
