package com.pragmaticds.rag.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates Spring AI model clients only when their credentials are configured.
 * This keeps the admin/dashboard surface bootable while still failing clearly
 * when an AI-backed operation is invoked without the required provider key.
 */
@Configuration
public class AiModelConfig {

    private final AiHttpClientFactory httpClientFactory;

    public AiModelConfig(AiHttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
    public OpenAiChatModel openAiChatModel(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:gpt-4.1-nano}") String model) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi(apiKey))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.2)
                        .build())
                .build();
    }

    @Bean("openAiEmbeddingModel")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
    public EmbeddingModel openAiEmbeddingModel(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:1536}") Integer dimensions) {
        return new OpenAiEmbeddingModel(
                openAiApi(apiKey),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .dimensions(dimensions)
                        .build());
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.anthropic.api-key:}')")
    public AnthropicChatModel anthropicChatModel(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            @Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5}") String model) {
        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder()
                        .apiKey(apiKey)
                        .restClientBuilder(httpClientFactory.restClientBuilder())
                        .build())
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .temperature(0.2)
                        .maxTokens(1500)
                        .build())
                .build();
    }

    private OpenAiApi openAiApi(String apiKey) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(httpClientFactory.restClientBuilder())
                .build();
    }
}
