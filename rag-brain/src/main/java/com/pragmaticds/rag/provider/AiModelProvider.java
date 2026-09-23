package com.pragmaticds.rag.provider;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * Contract for all AI chat providers (per rag.md).
 * Implementations wrap Spring AI ChatModels, so adding a provider
 * (Gemini, DeepSeek, Groq, Bedrock) is one small adapter class plus config.
 */
public interface AiModelProvider {

    AiResponse generate(AiRequest request);

    /** Stable lowercase identifier used in routing config, e.g. "anthropic". */
    String getProviderName();

    String getModelName();

    /**
     * Whether this provider actually sends {@link AiRequest#media()} — the native PDF/image blocks —
     * to the model. Default {@code false}: a provider must opt in by overriding.
     *
     * <p>A provider that answers {@code false} receives the text prompt ONLY; the documents are
     * dropped on the floor. The model still answers, still sounds confident, and the trace still
     * records SUCCESS, so an analysis produced without ever seeing the pages is indistinguishable
     * from one produced with them. That is why the router refuses to route a media-carrying request
     * to a provider that answers {@code false} rather than letting it degrade quietly.
     */
    default boolean supportsMedia() {
        return false;
    }

    /**
     * Null-safe extraction of the model's text. A provider can return a response
     * with no choices / null output (filtered, truncated, or an upstream hiccup);
     * dereferencing it blindly would NPE and surface as an opaque 500. Throwing a
     * clear exception lets the router fall back and the ask pipeline escalate.
     */
    default String requireContent(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException(
                    "AI provider '" + getProviderName() + "' returned an empty response");
        }
        return response.getResult().getOutput().getText();
    }
}
