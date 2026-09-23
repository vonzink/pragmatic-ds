package com.pragmaticds.rag.provider;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;

/**
 * Adapter for any provider that speaks the OpenAI chat-completions dialect
 * (DeepSeek, Grok/xAI, Gemini's compatibility endpoint). One instance per
 * provider, registered by ExtraProvidersConfig only when its API key is
 * configured — an unconfigured provider simply doesn't exist in the registry,
 * so it can't be selected from the dashboard.
 */
public class OpenAiCompatibleProvider implements AiModelProvider {

    private final String providerName;
    private final String defaultModel;
    private final OpenAiChatModel chatModel;

    /**
     * Backwards-compatible constructor (no explicit timeouts). Prefer the
     * {@link RestClient.Builder} overload in production so the client has
     * connect/read timeouts; this no-timeout form is retained for tests.
     */
    public OpenAiCompatibleProvider(String providerName, String baseUrl,
                                    String apiKey, String defaultModel) {
        this(providerName, baseUrl, apiKey, defaultModel, RestClient.builder());
    }

    public OpenAiCompatibleProvider(String providerName, String baseUrl,
                                    String apiKey, String defaultModel,
                                    RestClient.Builder restClientBuilder) {
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        // Spring AI 1.1.7 defaults completionsPath to /v1/chat/completions,
                        // which double-prefixes Grok (base ends in /v1) and breaks Gemini's
                        // compat endpoint. /chat/completions resolves correctly against all
                        // three committed base URLs, including DeepSeek's primary path.
                        .completionsPath("/chat/completions")
                        .restClientBuilder(restClientBuilder)
                        .build())
                .build();
    }

    @Override
    public AiResponse generate(AiRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        Prompt prompt = new Prompt(request.prompt(), OpenAiChatOptions.builder()
                .model(model)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build());

        ChatResponse response = chatModel.call(prompt);
        String content = requireContent(response);

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }

        return new AiResponse(content,
                providerName, model, promptTokens, completionTokens);
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getModelName() {
        return defaultModel;
    }
}
