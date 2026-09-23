package com.pragmaticds.docengine.platform.storage;

/** Thrown by {@link BlobStoragePort#get} for a missing key. Carries the key only — never content. */
public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String key) {
        super("blob not found: " + key);
    }
}
