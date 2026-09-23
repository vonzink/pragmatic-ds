package com.pragmaticds.rag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fails startup when the app boots under the {@code prod} profile while still
 * carrying development defaults. These checks intentionally block the process:
 * a production instance with a default admin key, dev DB password, or localhost
 * CORS is not a safe deployment.
 *
 * Only active under the {@code prod} profile so local/test boots are unaffected.
 */
@Component
@Profile("prod")
public class ProductionConfigGuard {

    static final String DEFAULT_ADMIN_KEY = "change-me-local-admin-key";
    static final String DEFAULT_DB_PASSWORD = "pds_local_dev_only";

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    private final String adminKey;
    private final String dbPassword;
    private final String corsOrigins;

    public ProductionConfigGuard(
            @Value("${ragbrain.rag.admin.api-key:}") String adminKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${ragbrain.rag.cors.allowed-origins:}") String corsOrigins) {
        this.adminKey = adminKey;
        this.dbPassword = dbPassword;
        this.corsOrigins = corsOrigins;
    }

    @PostConstruct
    void verify() {
        warnings().forEach(w -> log.warn("[prod-config] {}", w));
        List<String> errors = errors();
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under the 'prod' profile with insecure configuration: "
                            + String.join("; ", errors));
        }
        log.info("[prod-config] production configuration checks passed");
    }

    List<String> errors() {
        List<String> errors = new ArrayList<>();
        if (isBlank(adminKey) || DEFAULT_ADMIN_KEY.equals(adminKey.strip())) {
            errors.add("ADMIN_API_KEY is blank or still the development default — set a strong, unique key");
        }
        if (isBlank(dbPassword) || DEFAULT_DB_PASSWORD.equals(dbPassword.strip())) {
            errors.add("DB_PASSWORD is blank or the development default — use a managed secret in production");
        }
        if (isLocalhostOnly(corsOrigins)) {
            errors.add("CORS allowed-origins is empty or localhost-only — set CORS_ALLOWED_ORIGINS to your real site origins");
        }
        return errors;
    }

    List<String> warnings() {
        return List.of();
    }

    private static boolean isLocalhostOnly(String origins) {
        if (isBlank(origins)) {
            return true;
        }
        return Arrays.stream(origins.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .allMatch(s -> s.contains("localhost") || s.contains("127.0.0.1"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
