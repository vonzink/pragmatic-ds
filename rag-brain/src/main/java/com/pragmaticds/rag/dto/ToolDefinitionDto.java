package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainToolDefinition;
import com.pragmaticds.rag.service.dashboard.DashboardToolMode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ToolDefinitionDto(
        UUID id,
        UUID brainId,
        String name,
        String description,
        DashboardToolMode mode,
        boolean confirmationRequired,
        List<String> requiredPermissions,
        Map<String, Object> inputSchema,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ToolDefinitionDto from(BrainToolDefinition tool) {
        return new ToolDefinitionDto(
                tool.getId(),
                tool.getBrainId(),
                tool.getName(),
                tool.getDescription(),
                tool.getMode(),
                tool.isConfirmationRequired(),
                tool.getRequiredPermissions(),
                tool.getInputSchema(),
                tool.isActive(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }
}
