package com.pragmaticds.docengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.pragmaticds.docengine.platform.ai.AiExtractionPort;
import com.pragmaticds.docengine.platform.ai.AnthropicAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.StubAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.VertexClaudeAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.VertexCredentialsProvider;
import com.pragmaticds.docengine.platform.ai.VertexGeminiAiExtractionAdapter;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiExtractionConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(AiExtractionConfig.class);

    @Test
    void defaults_to_stub() {
        runner.run(
                context ->
                        assertThat(context.getBean(AiExtractionPort.class))
                                .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void disabled_configuration_uses_stub_even_with_a_key() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=false",
                        "docengine.ai.provider=anthropic",
                        "docengine.ai.api-key=synthetic-test-key")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void empty_key_fails_closed_to_stub() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=anthropic",
                        "docengine.ai.api-key=")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void stub_provider_wins_even_when_enabled_and_keyed() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=stub",
                        "docengine.ai.api-key=synthetic-test-key")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void unsupported_provider_fails_closed_to_stub() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=future-provider",
                        "docengine.ai.api-key=synthetic-test-key")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void invalid_base_url_fails_closed_to_stub() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=anthropic",
                        "docengine.ai.api-key=synthetic-test-key",
                        "docengine.ai.base-url=not a uri")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AiExtractionPort.class))
                                    .isInstanceOf(StubAiExtractionAdapter.class);
                        });
    }

    @Test
    void fully_configured_non_test_profile_selects_anthropic() {
        runner.withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=anthropic",
                        "docengine.ai.model=synthetic-model-v1",
                        "docengine.ai.api-key=synthetic-test-key",
                        "docengine.ai.base-url=http://127.0.0.1:1")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(AnthropicAiExtractionAdapter.class));
    }

    @Test
    void fully_configured_vertex_gemini_selects_vertex_without_an_api_key() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=vertex-gemini",
                        "docengine.ai.model=synthetic-gemini-model",
                        "docengine.ai.api-key=",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AiExtractionPort.class))
                                    .isInstanceOf(VertexGeminiAiExtractionAdapter.class);
                        });
    }

    @Test
    void fully_configured_vertex_claude_selects_vertex_without_an_api_key() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=vertex-claude",
                        "docengine.ai.model=synthetic-claude-model",
                        "docengine.ai.api-key=",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=global")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AiExtractionPort.class))
                                    .isInstanceOf(VertexClaudeAiExtractionAdapter.class);
                        });
    }

    @Test
    void missing_vertex_project_fails_closed_to_stub() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=vertex-gemini",
                        "docengine.ai.vertex.project=",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void missing_vertex_region_fails_closed_to_stub() {
        vertexRunner()
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=vertex-claude",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=")
                .run(
                        context ->
                                assertThat(context.getBean(AiExtractionPort.class))
                                        .isInstanceOf(StubAiExtractionAdapter.class));
    }

    @Test
    void unresolvable_vertex_credentials_fail_closed_to_stub() {
        runner.withBean(
                        VertexCredentialsProvider.class,
                        () -> {
                            return () -> {
                                throw new java.io.IOException("synthetic ADC failure");
                            };
                        })
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=vertex-gemini",
                        "docengine.ai.vertex.project=synthetic-project",
                        "docengine.ai.vertex.region=us-central1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AiExtractionPort.class))
                                    .isInstanceOf(StubAiExtractionAdapter.class);
                        });
    }

    @Test
    void test_profile_always_selects_stub() {
        runner.withInitializer(
                        context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        "docengine.ai.enabled=true",
                        "docengine.ai.provider=anthropic",
                        "docengine.ai.api-key=synthetic-test-key")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(AiExtractionPort.class)).hasSize(1);
                            assertThat(context.getBean(AiExtractionPort.class))
                                    .isInstanceOf(StubAiExtractionAdapter.class);
                        });
    }

    private ApplicationContextRunner vertexRunner() {
        GoogleCredentials credentials =
                GoogleCredentials.create(
                        new AccessToken(
                                "synthetic-access-token",
                                Date.from(Instant.now().plusSeconds(3600))));
        return runner.withBean(
                VertexCredentialsProvider.class, () -> () -> credentials);
    }
}
