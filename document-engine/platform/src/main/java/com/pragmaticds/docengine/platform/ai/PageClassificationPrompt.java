package com.pragmaticds.docengine.platform.ai;

/**
 * Loads the static, cacheable page-classification instructions. Deliberately GENERIC, for the same
 * reason {@link BoundaryExtractionPrompt} is: everything mortgage-specific — what a paystub prints,
 * what distinguishes a W-2 from a 1099 — travels as taxonomy DATA in the request, so this module
 * never carries domain knowledge and adding a lender-specific type never edits a prompt.
 */
final class PageClassificationPrompt {

    private static final String SYSTEM_RESOURCE = "/ai/prompt/page-classification.system.md";

    private final String systemInstructions = AiExtractionResourceLoader.read(SYSTEM_RESOURCE);

    String cachedPrefix() {
        return systemInstructions;
    }
}
