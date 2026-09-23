package com.pragmaticds.docengine.results.canonical;

/** Raised when an input cannot be represented by the DOCENGINE-C14N-1 contract. */
public final class CanonicalizationException extends RuntimeException {

    public CanonicalizationException(String message) {
        super(message);
    }

    public CanonicalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
