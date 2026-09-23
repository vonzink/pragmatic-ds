package com.pragmaticds.rag.service.analyze;

/** A document that was NOT sent to the model, with the reason. Always surfaced to the caller. */
public record SkippedDoc(String id, String fileName, SkipCategory category, String reason) {}
