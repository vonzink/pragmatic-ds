package com.pragmaticds.docengine.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationPort;
import com.pragmaticds.docengine.platform.ai.StubPageTypeClassificationAdapter;
import com.pragmaticds.docengine.platform.ai.VertexCredentialsProvider;
import com.pragmaticds.docengine.platform.ai.VertexGeminiPageTypeClassificationAdapter;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Selects the page-type-classification provider (plan 2026-09-09-llm-classification-fallback) with
 * every incomplete state fail-closed to the stub — file-for-file the shape of {@link
 * BoundaryExtractionConfig}, deliberately, because a second model seam that fails closed DIFFERENTLY
 * is a second thing to audit. Vertex Gemini is the only live adapter, and it mounts the SAME {@code
 * docengine.ai.vertex.*} project/region/credentials the extraction and boundary paths already use:
 * turning this on adds no new credential surface, only new content leaving through an existing one.
 *
 * <p>Two switches, deliberately: this bean decides WHICH adapter exists; {@code
 * docengine.ai.page-classification.enabled} (read by {@code AiPageClassificationService}, along with
 * {@code min-confidence} and {@code max-pages}) decides whether the stage asks it anything. Off at
 * either level means an UNKNOWN page stays UNKNOWN, exactly as the deterministic packs left it.
 */
@Configuration
public class PageTypeClassificationConfig {

    private static final Logger log = LoggerFactory.getLogger(PageTypeClassificationConfig.class);

    @Bean
    @Profile({"local", "test"})
    public PageTypeClassificationPort localOrTestPageTypeClassificationPort() {
        return new StubPageTypeClassificationAdapter();
    }

    @Bean
    @Profile("!local & !test")
    public PageTypeClassificationPort pageTypeClassificationPort(
            @Value("${docengine.ai.page-classification.provider:stub}") String provider,
            @Value("${docengine.ai.page-classification.model:gemini-2.5-flash-lite}") String model,
            @Value("${docengine.ai.page-classification.timeout-seconds:30}") long timeoutSeconds,
            @Value("${docengine.ai.vertex.project:}") String vertexProject,
            @Value("${docengine.ai.vertex.region:us-central1}") String vertexRegion,
            ObjectProvider<VertexCredentialsProvider> vertexCredentialsProviders) {
        String normalizedProvider =
                provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("vertex-gemini".equals(normalizedProvider)) {
            if (vertexProject == null
                    || vertexProject.isBlank()
                    || vertexRegion == null
                    || vertexRegion.isBlank()) {
                log.warn("page classification status=DISABLED reason=missing_credentials");
                return new StubPageTypeClassificationAdapter();
            }
            VertexCredentialsProvider credentialsProvider =
                    vertexCredentialsProviders.getIfAvailable(
                            VertexCredentialsProvider::applicationDefault);
            GoogleCredentials credentials;
            try {
                credentials = credentialsProvider.credentials();
            } catch (Exception invalidCredentials) {
                // Ambient credentials that cannot be resolved are indistinguishable, from here,
                // from none at all — and a half-configured model path must never be the reason a
                // deterministic classification stops running.
                log.warn("page classification status=DISABLED reason=missing_credentials");
                return new StubPageTypeClassificationAdapter();
            }
            try {
                return new VertexGeminiPageTypeClassificationAdapter(
                        model,
                        vertexProject,
                        vertexRegion,
                        credentials,
                        Duration.ofSeconds(timeoutSeconds));
            } catch (RuntimeException invalidConfiguration) {
                log.warn("page classification status=DISABLED reason=invalid_configuration");
                return new StubPageTypeClassificationAdapter();
            }
        }
        if (!"stub".equals(normalizedProvider)) {
            log.warn("page classification status=DISABLED reason=unsupported_provider");
        }
        return new StubPageTypeClassificationAdapter();
    }
}
