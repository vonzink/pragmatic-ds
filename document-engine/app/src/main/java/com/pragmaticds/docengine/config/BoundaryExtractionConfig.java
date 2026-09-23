package com.pragmaticds.docengine.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.pragmaticds.docengine.platform.ai.BoundaryExtractionPort;
import com.pragmaticds.docengine.platform.ai.StubBoundaryExtractionAdapter;
import com.pragmaticds.docengine.platform.ai.VertexCredentialsProvider;
import com.pragmaticds.docengine.platform.ai.VertexGeminiBoundaryExtractionAdapter;
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
 * Selects the boundary-extraction provider (Phase E2) with every incomplete state fail-closed to
 * the stub — the same shape as {@link AiExtractionConfig}. Vertex Gemini is the first (and so far
 * only) live adapter, per the compliance-authorized provider decision; it reuses the SAME Vertex
 * project/region/credentials the AI extraction path already mounts, so enabling this adds no new
 * credential surface — only new content leaving through an existing one, which is exactly what
 * the compliance register entry (roadmap R10) must record before the first live call.
 *
 * <p>Two switches, deliberately: this bean decides WHICH adapter exists; {@code
 * docengine.boundary-extraction.enabled} (read by the stage service) decides whether the stage
 * runs at all. Off at either level means the deterministic split, byte-identical.
 */
@Configuration
public class BoundaryExtractionConfig {

    private static final Logger log = LoggerFactory.getLogger(BoundaryExtractionConfig.class);

    @Bean
    @Profile({"local", "test"})
    public BoundaryExtractionPort localOrTestBoundaryExtractionPort() {
        return new StubBoundaryExtractionAdapter();
    }

    @Bean
    @Profile("!local & !test")
    public BoundaryExtractionPort boundaryExtractionPort(
            @Value("${docengine.boundary-extraction.provider:stub}") String provider,
            @Value("${docengine.boundary-extraction.model:gemini-2.5-flash-lite}") String model,
            @Value("${docengine.boundary-extraction.timeout-seconds:30}") long timeoutSeconds,
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
                log.warn("boundary extraction status=DISABLED reason=missing_credentials");
                return new StubBoundaryExtractionAdapter();
            }
            VertexCredentialsProvider credentialsProvider =
                    vertexCredentialsProviders.getIfAvailable(
                            VertexCredentialsProvider::applicationDefault);
            GoogleCredentials credentials;
            try {
                credentials = credentialsProvider.credentials();
            } catch (Exception invalidCredentials) {
                log.warn("boundary extraction status=DISABLED reason=missing_credentials");
                return new StubBoundaryExtractionAdapter();
            }
            try {
                return new VertexGeminiBoundaryExtractionAdapter(
                        model,
                        vertexProject,
                        vertexRegion,
                        credentials,
                        Duration.ofSeconds(timeoutSeconds));
            } catch (RuntimeException invalidConfiguration) {
                log.warn("boundary extraction status=DISABLED reason=invalid_configuration");
                return new StubBoundaryExtractionAdapter();
            }
        }
        if (!"stub".equals(normalizedProvider)) {
            log.warn("boundary extraction status=DISABLED reason=unsupported_provider");
        }
        return new StubBoundaryExtractionAdapter();
    }
}
