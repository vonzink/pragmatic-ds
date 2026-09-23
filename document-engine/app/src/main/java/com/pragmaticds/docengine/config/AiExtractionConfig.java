package com.pragmaticds.docengine.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.pragmaticds.docengine.platform.ai.AiExtractionPort;
import com.pragmaticds.docengine.platform.ai.AnthropicAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.StubAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.VertexClaudeAiExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.VertexCredentialsProvider;
import com.pragmaticds.docengine.platform.ai.VertexGeminiAiExtractionAdapter;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Selects the configured AI extraction adapter while keeping every incomplete state fail-closed. */
@Configuration
public class AiExtractionConfig {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionConfig.class);

    @Bean
    @Profile({"local", "test"})
    public AiExtractionPort localOrTestAiExtractionPort() {
        return new StubAiExtractionAdapter();
    }

    @Bean
    @Profile({"local", "test"})
    public com.pragmaticds.docengine.platform.ai.AiSecondPass localOrTestAiSecondPass() {
        return new com.pragmaticds.docengine.platform.ai.AiSecondPass(new StubAiExtractionAdapter());
    }

    /**
     * Phase G: the second-pass provider — Vertex Gemini on a STRONGER tier, fail-closed to the
     * stub on any incomplete configuration exactly like the first pass. Vertex-only because it is
     * the compliance-authorized provider; the model default is the Pro tier, since escalating to
     * the same cheap reader buys nothing. Whether the second pass RUNS is the stage service's
     * {@code docengine.ai.second-pass.enabled} gate — this bean only decides what would answer.
     */
    @Bean
    @Profile("!local & !test")
    public com.pragmaticds.docengine.platform.ai.AiSecondPass aiSecondPass(
            @Value("${docengine.ai.second-pass.model:gemini-2.5-pro}") String model,
            @Value("${docengine.ai.timeout-seconds:90}") long timeoutSeconds,
            @Value("${docengine.ai.max-output-tokens:32768}") int maxOutputTokens,
            @Value("${docengine.ai.vertex.project:}") String vertexProject,
            @Value("${docengine.ai.vertex.region:us-central1}") String vertexRegion,
            ObjectProvider<VertexCredentialsProvider> vertexCredentialsProviders) {
        if (vertexProject == null
                || vertexProject.isBlank()
                || vertexRegion == null
                || vertexRegion.isBlank()) {
            log.warn("AI second pass status=DISABLED reason=missing_credentials");
            return new com.pragmaticds.docengine.platform.ai.AiSecondPass(new StubAiExtractionAdapter());
        }
        VertexCredentialsProvider credentialsProvider =
                vertexCredentialsProviders.getIfAvailable(
                        VertexCredentialsProvider::applicationDefault);
        GoogleCredentials credentials;
        try {
            credentials = credentialsProvider.credentials();
        } catch (Exception invalidCredentials) {
            log.warn("AI second pass status=DISABLED reason=missing_credentials");
            return new com.pragmaticds.docengine.platform.ai.AiSecondPass(new StubAiExtractionAdapter());
        }
        try {
            return new com.pragmaticds.docengine.platform.ai.AiSecondPass(
                    new VertexGeminiAiExtractionAdapter(
                            model,
                            vertexProject,
                            vertexRegion,
                            credentials,
                            maxOutputTokens,
                            Duration.ofSeconds(timeoutSeconds)));
        } catch (RuntimeException invalidConfiguration) {
            log.warn("AI second pass status=DISABLED reason=invalid_configuration");
            return new com.pragmaticds.docengine.platform.ai.AiSecondPass(new StubAiExtractionAdapter());
        }
    }

    @Bean
    @Profile("!local & !test")
    public AiExtractionPort aiExtractionPort(
            @Value("${docengine.ai.enabled:false}") boolean enabled,
            @Value("${docengine.ai.provider:vertex-gemini}") String provider,
            @Value("${docengine.ai.model:gemini-2.5-flash-lite}") String model,
            @Value("${docengine.ai.api-key:}") String apiKey,
            @Value("${docengine.ai.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${docengine.ai.timeout-seconds:90}") long timeoutSeconds,
            @Value("${docengine.ai.max-output-tokens:32768}") int maxOutputTokens,
            @Value("${docengine.ai.vertex.project:}") String vertexProject,
            @Value("${docengine.ai.vertex.region:us-central1}") String vertexRegion,
            ObjectProvider<VertexCredentialsProvider> vertexCredentialsProviders) {
        if (!enabled) {
            return new StubAiExtractionAdapter();
        }
        String normalizedProvider =
                provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(normalizedProvider)) {
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("AI extraction status=DISABLED reason=missing_credentials");
                return new StubAiExtractionAdapter();
            }
            try {
                return new AnthropicAiExtractionAdapter(
                        model, apiKey, baseUrl, Duration.ofSeconds(timeoutSeconds));
            } catch (RuntimeException invalidConfiguration) {
                log.warn("AI extraction status=DISABLED reason=invalid_configuration");
                return new StubAiExtractionAdapter();
            }
        }
        if ("vertex-gemini".equals(normalizedProvider)
                || "vertex-claude".equals(normalizedProvider)) {
            if (vertexProject == null
                    || vertexProject.isBlank()
                    || vertexRegion == null
                    || vertexRegion.isBlank()) {
                log.warn("AI extraction status=DISABLED reason=missing_credentials");
                return new StubAiExtractionAdapter();
            }
            VertexCredentialsProvider credentialsProvider =
                    vertexCredentialsProviders.getIfAvailable(
                            VertexCredentialsProvider::applicationDefault);
            GoogleCredentials credentials;
            try {
                credentials = credentialsProvider.credentials();
            } catch (Exception invalidCredentials) {
                log.warn("AI extraction status=DISABLED reason=missing_credentials");
                return new StubAiExtractionAdapter();
            }
            try {
                if ("vertex-gemini".equals(normalizedProvider)) {
                    return new VertexGeminiAiExtractionAdapter(
                            model,
                            vertexProject,
                            vertexRegion,
                            credentials,
                            maxOutputTokens,
                            Duration.ofSeconds(timeoutSeconds));
                }
                return new VertexClaudeAiExtractionAdapter(
                        model,
                        vertexProject,
                        vertexRegion,
                        credentials,
                        Duration.ofSeconds(timeoutSeconds));
            } catch (RuntimeException invalidConfiguration) {
                log.warn("AI extraction status=DISABLED reason=invalid_configuration");
                return new StubAiExtractionAdapter();
            }
        }
        if (!"stub".equals(normalizedProvider)) {
            log.warn("AI extraction status=DISABLED reason=unsupported_provider");
        }
        return new StubAiExtractionAdapter();
    }
}
