package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ToolAdapterConfigDto(
        UUID id,
        UUID brainId,
        String toolName,
        boolean enabled,
        String httpMethod,
        String urlTemplate,
        String authMode,
        String secretRef,
        String apiKeyHeader,
        Map<String, Object> staticHeaders,
        Map<String, Object> requestBodyTemplate,
        int timeoutMs,
        List<String> allowedHosts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ToolAdapterConfigDto from(BrainToolAdapterConfig config) {
        return new ToolAdapterConfigDto(
                config.getId(),
                config.getBrainId(),
                config.getToolName(),
                config.isEnabled(),
                config.getHttpMethod(),
                config.getUrlTemplate(),
                config.getAuthMode(),
                config.getSecretRef(),
                config.getApiKeyHeader(),
                config.getStaticHeaders(),
                config.getRequestBodyTemplate(),
                config.getTimeoutMs(),
                config.getAllowedHosts(),
                config.getCreatedAt(),
                config.getUpdatedAt());
    }
}
