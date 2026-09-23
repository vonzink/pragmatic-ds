package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.Map;

public record ToolAdapterConfigRequest(
        boolean enabled,
        String httpMethod,
        String urlTemplate,
        String authMode,
        String secretRef,
        String apiKeyHeader,
        Map<String, Object> staticHeaders,
        Map<String, Object> requestBodyTemplate,
        Integer timeoutMs,
        List<String> allowedHosts
) {}
