package com.pragmaticds.docengine.platform.ai;

/**
 * Everything a provider adapter needs to serve ONE document type: the canonical output schema,
 * the cacheable prompt prefix, and the strict parse that turns raw structured JSON into the
 * type's {@link AiStructuredExtraction}. Adding a document type to the AI seam means adding a
 * dialect to {@link AiExtractionDialects} — the adapters themselves stay type-agnostic.
 */
public interface AiExtractionDialect {

    AiDocumentType type();

    /** The canonical JSON Schema the stage sends and the adapter pins its response format to. */
    String schemaJson();

    /** The static, cacheable system prefix: instructions + synthetic example + schema. */
    String cachedPrefix();

    /**
     * Shape-validates and normalizes a raw OK result into a typed extraction; anything
     * structurally invalid is refused whole ({@code validation_error}), never half-believed.
     */
    AiExtractionResult parse(AiExtractionResult rawResult);
}
