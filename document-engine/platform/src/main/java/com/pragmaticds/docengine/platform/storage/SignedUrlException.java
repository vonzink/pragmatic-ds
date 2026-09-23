package com.pragmaticds.docengine.platform.storage;

/**
 * A signed token that must not be honoured. The {@link Reason} is for callers and tests to reason
 * about; the HTTP boundary deliberately collapses every reason to the SAME opaque 404 so a probe
 * cannot tell an expired token from a forged one from a token for an object that does not exist.
 */
public class SignedUrlException extends RuntimeException {

    public enum Reason {
        /** Not a well-formed token (wrong shape, bad base64, unparseable fields). */
        MALFORMED,
        /** The signature does not match the payload — tampered or minted without the secret. */
        BAD_SIGNATURE,
        /** Well-formed and correctly signed, but past its expiry. */
        EXPIRED
    }

    private final transient Reason reason;

    public SignedUrlException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
