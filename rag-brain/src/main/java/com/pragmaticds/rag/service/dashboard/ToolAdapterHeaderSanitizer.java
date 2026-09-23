package com.pragmaticds.rag.service.dashboard;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ToolAdapterHeaderSanitizer {

    // Hop-by-hop headers plus credential headers. Credentials must go through
    // auth_mode + secretRef (resolved from env, redacted from responses), never
    // pasted into static_headers where they would sit in the DB/backups in plaintext.
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host", "connection", "transfer-encoding", "content-length",
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key");

    public Map<String, String> sanitize(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = requiredHeader(entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("static header value must be scalar: " + name);
            }
            sanitized.put(name, String.valueOf(value));
        }
        return sanitized;
    }

    private static String requiredHeader(String header) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("header is required");
        }
        String cleanHeader = header.strip();
        if (BLOCKED_HEADERS.contains(cleanHeader.toLowerCase(Locale.US))) {
            throw new IllegalArgumentException("static header is blocked: " + cleanHeader);
        }
        return cleanHeader;
    }
}
