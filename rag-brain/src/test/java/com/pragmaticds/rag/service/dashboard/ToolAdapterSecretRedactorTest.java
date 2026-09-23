package com.pragmaticds.rag.service.dashboard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolAdapterSecretRedactorTest {

    private final ToolAdapterSecretRedactor redactor = new ToolAdapterSecretRedactor();

    @Test
    void redactsSecretInsideNestedResponseData() {
        Object redacted = redactor.redact(Map.of(
                "token", "secret-token",
                "message", "Bearer secret-token",
                "items", List.of(Map.of("value", "secret-token"))),
                List.of("secret-token"));

        assertEquals(Map.of(
                "token", "[redacted]",
                "message", "Bearer [redacted]",
                "items", List.of(Map.of("value", "[redacted]"))),
                redacted);
    }

    @Test
    void leavesDataUntouchedWhenNoSecretsProvided() {
        Object data = Map.of("ok", true, "message", "hello");
        Object redacted = redactor.redact(data, List.of());

        assertSame(data, redacted);
        assertEquals(data, redacted);
    }

    @Test
    void redactsLongerOverlappingSecretsFirst() {
        Object redacted = redactor.redact("secret-token", List.of("secret", "secret-token"));

        assertEquals("[redacted]", redacted);
    }

    @Test
    void redactsSecretsInsideMapKeys() {
        Object redacted = redactor.redact(Map.of("secret-token", "visible"),
                List.of("secret-token"));

        assertEquals(Map.of("[redacted]", "visible"), redacted);
    }
}
