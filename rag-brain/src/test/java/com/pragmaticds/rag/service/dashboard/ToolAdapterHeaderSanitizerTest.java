package com.pragmaticds.rag.service.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolAdapterHeaderSanitizerTest {

    private final ToolAdapterHeaderSanitizer sanitizer = new ToolAdapterHeaderSanitizer();

    @Test
    void blocksHopByHopHeaders() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sanitizer.sanitize(Map.of("Transfer-Encoding", "chunked")));

        assertEquals("static header is blocked: Transfer-Encoding", ex.getMessage());
    }

    @Test
    void blocksCredentialHeadersSoSecretsStayInSecretRef() {
        assertThrows(IllegalArgumentException.class,
                () -> sanitizer.sanitize(Map.of("Authorization", "Bearer sk-live-123")));
        assertThrows(IllegalArgumentException.class,
                () -> sanitizer.sanitize(Map.of("X-Api-Key", "sk-live-123")));
        assertThrows(IllegalArgumentException.class,
                () -> sanitizer.sanitize(Map.of("Cookie", "session=abc")));
    }

    @Test
    void convertsScalarHeaderValuesToStrings() {
        assertEquals(Map.of(
                "X-App", "rag-brain",
                "X-Retry", "3",
                "X-Enabled", "true"),
                sanitizer.sanitize(Map.of(
                        "X-App", "rag-brain",
                        "X-Retry", 3,
                        "X-Enabled", true)));
    }
}
