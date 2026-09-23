package com.pragmaticds.rag.service.dashboard;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class EnvironmentToolSecretResolver implements ToolSecretResolver {

    private static final String PREFIX = "RAG_TOOL_SECRET_";

    private final Environment environment;

    public EnvironmentToolSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String secretRef) {
        String normalized = normalize(secretRef);
        return Optional.ofNullable(environment.getProperty(PREFIX + normalized));
    }

    private static String normalize(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            throw new IllegalArgumentException("secretRef is required");
        }
        return secretRef.strip()
                .toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9]", "_");
    }
}
