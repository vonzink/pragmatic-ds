package com.pragmaticds.rag.service.analyze;

/**
 * One document supplied to an analyze run. Bytes are held IN MEMORY ONLY for the
 * duration of the call — never persisted to corpus, disk, or the audit body.
 *
 * @param id          suite-side document id (echoed back in findings/skipped)
 * @param fileName    original file name (drives extension-based type inference)
 * @param contentType MIME type declared by the suite (may be null/generic)
 * @param bytes       raw document bytes
 * @param createdAt   epoch millis; OLDEST (smallest) docs are skipped first over cap
 */
public record DocInput(String id, String fileName, String contentType, byte[] bytes, long createdAt) {}
