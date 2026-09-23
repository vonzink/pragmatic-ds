package com.pragmaticds.rag.service.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentToolSecretResolverTest {

    @Test
    void resolvesNormalizedEnvironmentName() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RAG_TOOL_SECRET_DASHBOARD_API", "secret-token");
        EnvironmentToolSecretResolver resolver = new EnvironmentToolSecretResolver(env);

        assertEquals("secret-token", resolver.resolve("dashboard-api").orElseThrow());
    }

    @Test
    void rejectsBlankSecretRef() {
        EnvironmentToolSecretResolver resolver = new EnvironmentToolSecretResolver(new MockEnvironment());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(" "));

        assertEquals("secretRef is required", ex.getMessage());
    }
}
