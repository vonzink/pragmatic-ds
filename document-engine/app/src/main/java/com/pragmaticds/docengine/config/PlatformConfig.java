package com.pragmaticds.docengine.config;

import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.storage.LocalObjectStorageAdapter;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires platform ports to their configured adapters.
 *
 * <p>Phase 7a moved tenant binding and CORS here-onto the Spring Security chains: {@code
 * DevAuthFilter} (folding in the old {@code DevTenantFilter}) now binds the full principal for
 * {@code local}/{@code test}, and {@code SecurityConfig.devCorsConfigurationSource} owns the dev
 * CORS policy. Running two filters that both bound an org was the exact hazard called out in the
 * old {@code devTenantFilter} comment, so it is gone rather than duplicated.
 */
@Configuration
public class PlatformConfig {

    @Bean
    public BlobStoragePort blobStoragePort(
            @Value("${docengine.storage.driver:local}") String driver,
            @Value("${docengine.storage.local-root:./data/blobs}") Path localRoot) {
        if (!"local".equals(driver)) {
            // The S3 adapter arrives with AWS deployment; failing loud beats a silent
            // fallback that writes borrower documents to an unexpected place.
            throw new IllegalStateException("unsupported storage driver: " + driver);
        }
        return new LocalObjectStorageAdapter(localRoot);
    }
}
