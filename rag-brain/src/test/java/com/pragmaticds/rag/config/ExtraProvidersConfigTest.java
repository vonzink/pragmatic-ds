package com.pragmaticds.rag.config;

import com.pragmaticds.rag.provider.OpenAiCompatibleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtraProvidersConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiHttpClientFactory.class, () -> new AiHttpClientFactory(10_000, 60_000))
            .withUserConfiguration(ExtraProvidersConfig.class)
            .withPropertyValues(
                    "brain.providers.deepseek.base-url=https://api.deepseek.com",
                    "brain.providers.deepseek.model=deepseek-v4-flash",
                    "brain.providers.gemini.base-url=https://example.test",
                    "brain.providers.gemini.model=gemini-x",
                    "brain.providers.grok.base-url=https://example.test",
                    "brain.providers.grok.model=grok-x");

    @Test
    void noKeysMeansNoExtraProviders() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=")
                .run(context -> assertEquals(0,
                        context.getBeansOfType(OpenAiCompatibleProvider.class).size()));
    }

    @Test
    void aKeyActivatesExactlyThatProvider() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=sk-test",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=")
                .run(context -> {
                    var beans = context.getBeansOfType(OpenAiCompatibleProvider.class);
                    assertEquals(1, beans.size());
                    assertEquals("deepseek",
                            beans.values().iterator().next().getProviderName());
                });
    }

    @Test
    void localProviderRegistersWhenBaseUrlIsSet() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=",
                        "brain.providers.local.base-url=http://localhost:11434/v1")
                .run(context -> {
                    var beans = context.getBeansOfType(OpenAiCompatibleProvider.class);
                    assertEquals(1, beans.size());
                    assertEquals("local",
                            beans.values().iterator().next().getProviderName());
                });
    }

    @Test
    void localProviderAbsentWhenBaseUrlNotSet() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=")
                .run(context -> assertEquals(0,
                        context.getBeansOfType(OpenAiCompatibleProvider.class).size()));
    }

    @Test
    void modelOverrideFallsBackToInstanceDefault() {
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider("deepseek", "https://api.deepseek.com", "sk-x", "deepseek-v4-flash");
        assertEquals("deepseek", provider.getProviderName());
        assertEquals("deepseek-v4-flash", provider.getModelName());
    }
}
