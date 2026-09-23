package com.pragmaticds.docengine.platform.storage;

/**
 * Port for blob storage — local filesystem in the MVP, S3 later, behind one interface so the swap
 * is a config change (docs/ARCHITECTURE.md 5.3). Keys are opaque, forward-slash-separated paths;
 * callers never see the physical location.
 */
public interface BlobStoragePort {

    void put(String key, byte[] content);

    /**
     * Atomically creates an immutable blob or succeeds when the key already contains bytes with
     * the expected SHA-256 digest.
     *
     * @throws ImmutableBlobConflictException when the key already contains different bytes
     */
    void putImmutable(String key, byte[] content, String expectedSha256);

    /** @throws BlobNotFoundException when the key does not exist — never returns null. */
    byte[] get(String key);

    boolean exists(String key);

    void delete(String key);
}
