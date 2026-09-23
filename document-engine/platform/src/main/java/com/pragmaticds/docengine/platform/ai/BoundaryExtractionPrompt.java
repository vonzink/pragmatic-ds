package com.pragmaticds.docengine.platform.ai;

/**
 * Loads the static, cacheable boundary-extraction instructions. Deliberately GENERIC: everything
 * mortgage-specific — what a bank statement looks like, what distinguishes a W-2 — travels as
 * taxonomy DATA in the request (design §7), so this module never carries domain knowledge and
 * adding a lender-specific type never edits a prompt.
 */
final class BoundaryExtractionPrompt {

    private static final String SYSTEM_RESOURCE = "/ai/prompt/boundary-extraction.system.md";

    private final String systemInstructions = AiExtractionResourceLoader.read(SYSTEM_RESOURCE);

    String cachedPrefix() {
        return systemInstructions;
    }
}
