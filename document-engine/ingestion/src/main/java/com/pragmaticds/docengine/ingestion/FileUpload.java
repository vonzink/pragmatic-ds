package com.pragmaticds.docengine.ingestion;

/**
 * One file as received from the client: the name and content type are unauthenticated claims;
 * only the bytes are trusted (and only after validation).
 */
public record FileUpload(String filename, String declaredContentType, byte[] bytes) {}
