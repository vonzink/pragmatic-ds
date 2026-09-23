package com.pragmaticds.rag.service.storage;

import java.io.InputStream;

/**
 * Abstraction over document file storage.
 * Local disk for MVP; swap in an S3 implementation for AWS deployment
 * without touching the ingestion pipeline.
 */
public interface StorageService {

    /**
     * Stores the file and returns the storage key (path or S3 key).
     */
    String store(String fileName, InputStream content);

    InputStream retrieve(String storageKey);

    void delete(String storageKey);
}
