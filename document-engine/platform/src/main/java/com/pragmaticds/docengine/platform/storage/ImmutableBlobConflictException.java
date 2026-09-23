package com.pragmaticds.docengine.platform.storage;

/** An immutable key already contains different bytes. Carries the key only, never content. */
public class ImmutableBlobConflictException extends RuntimeException {

    public ImmutableBlobConflictException(String key) {
        super("immutable blob conflict: " + key);
    }
}
