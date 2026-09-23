package com.pragmaticds.rag.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Anthropic Claude adapter — the default provider.
 */
@Component
@ConditionalOnBean(AnthropicChatModel.class)
public class AnthropicProvider implements AiModelProvider {

    private final AnthropicChatModel chatModel;
    private final String modelName;

    public AnthropicProvider(AnthropicChatModel chatModel,
                             @Value("${spring.ai.anthropic.chat.options.model}") String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        String model = request.model() != null ? request.model() : modelName;
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build();

        // With document/image blocks, send a UserMessage carrying the native media
        // (Claude vision). Without media, keep the plain-string prompt path unchanged.
        Prompt prompt = request.media().isEmpty()
                ? new Prompt(request.prompt(), options)
                : new Prompt(java.util.List.of(
                        org.springframework.ai.chat.messages.UserMessage.builder()
                                .text(request.prompt())
                                .media(request.media())
                                .build()),
                        options);

        ChatResponse response = chatModel.call(prompt);
        String content = requireContent(response);

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }

        // The cached-prompt category is left unknown rather than guessed. Spring AI's Usage
        // interface exposes prompt and completion totals only; the cache split lives inside
        // getNativeUsage(), whose shape is provider-specific and version-specific. Reporting null
        // means an instance run records the category as UNAVAILABLE, which is true, instead of
        // recording a zero that would read as "nothing was cached".
        return new AiResponse(
                content,
                getProviderName(),
                model,
                promptTokens,
                completionTokens,
                null
        );
    }

    /** Claude takes native document/image blocks — see the media branch in {@link #generate}. */
    @Override
    public boolean supportsMedia() {
        return true;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
