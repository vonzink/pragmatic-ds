package com.pragmaticds.docengine.platform.ai;

/** Deterministic disabled adapter used for tests, local development, and fail-closed configuration. */
public final class StubAiExtractionAdapter implements AiExtractionPort {

    private static final AiExtractionResult DISABLED =
            new AiExtractionResult(
                    null,
                    "stub",
                    "stub",
                    AiExtractionStatus.DISABLED,
                    AiTokenCounts.ZERO,
                    "disabled");

    @Override
    public AiExtractionResult extract(AiExtractionRequest request) {
        return DISABLED;
    }
}
