package com.pragmaticds.rag.provider;

/**
 * Provider-agnostic response from a chat model.
 *
 * <p>{@code cachedPromptTokens} is the subset of {@code promptTokens} the provider served from its
 * own cache, and it is null whenever that is not known. Null rather than zero, deliberately: a
 * zero would say the provider cached nothing, while null says it did not tell us. Instance runs
 * carry that distinction all the way to the recorded cost, where a fabricated zero would be
 * indistinguishable from a measurement.
 */
public record AiResponse(
        String content,
        String providerName,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer cachedPromptTokens
) {

    /**
     * The pre-cache shape, kept so every existing caller compiles unchanged.
     *
     * <p>Reports the cached category as unknown, which is the correct answer for a caller that
     * never had it.
     */
    public AiResponse(String content, String providerName, String modelName,
                      Integer promptTokens, Integer completionTokens) {
        this(content, providerName, modelName, promptTokens, completionTokens, null);
    }
}
