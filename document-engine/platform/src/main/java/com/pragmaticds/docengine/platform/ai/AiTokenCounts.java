package com.pragmaticds.docengine.platform.ai;

/** Token usage reported by a provider, including prompt-cache reads and writes. */
public record AiTokenCounts(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens) {

    public static final AiTokenCounts ZERO = new AiTokenCounts(0, 0, 0, 0);
}
