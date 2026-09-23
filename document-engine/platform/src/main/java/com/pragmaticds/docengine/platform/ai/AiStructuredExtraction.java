package com.pragmaticds.docengine.platform.ai;

/** Marker for a validated, document-type-specific extraction returned through the AI seam. */
public interface AiStructuredExtraction {

    AiDocumentType documentType();
}
