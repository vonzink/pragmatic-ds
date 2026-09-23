package com.pragmaticds.docengine.platform.ai;

/**
 * Provider-neutral extraction result. Failure reasons are short taxonomy values and must never
 * contain request or response content.
 */
public record AiExtractionResult(
        String structuredJson,
        AiStructuredExtraction extraction,
        String provider,
        String model,
        AiExtractionStatus status,
        AiTokenCounts tokenCounts,
        String reason) {

    public AiExtractionResult(
            String structuredJson,
            String provider,
            String model,
            AiExtractionStatus status,
            AiTokenCounts tokenCounts,
            String reason) {
        this(structuredJson, null, provider, model, status, tokenCounts, reason);
    }
}
