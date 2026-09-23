package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainToolAdapterRun;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single recorded tool-adapter execution, for the admin runs view. The recorder
 * already sanitizes these rows (no secrets, no response bodies), so the DTO can
 * surface them directly.
 */
public record ToolAdapterRunDto(
        UUID id,
        String toolName,
        String mode,
        String targetHost,
        String httpMethod,
        String status,
        Integer httpStatusCode,
        long durationMs,
        String sessionId,
        String userId,
        String tenantId,
        String errorType,
        OffsetDateTime createdAt
) {
    public static ToolAdapterRunDto from(BrainToolAdapterRun run) {
        return new ToolAdapterRunDto(
                run.getId(),
                run.getToolName(),
                run.getMode(),
                run.getTargetHost(),
                run.getHttpMethod(),
                run.getStatus(),
                run.getHttpStatusCode(),
                run.getDurationMs(),
                run.getSessionId(),
                run.getUserId(),
                run.getTenantId(),
                run.getErrorType(),
                run.getCreatedAt());
    }
}
