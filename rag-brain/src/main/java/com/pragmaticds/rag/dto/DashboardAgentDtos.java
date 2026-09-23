package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import com.pragmaticds.rag.service.dashboard.DashboardToolStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DashboardAgentDtos {
    private DashboardAgentDtos() {}

    public record UserContext(
            String userId,
            String tenantId,
            List<String> roles,
            List<String> permissions
    ) {}

    public record DashboardToolDefinition(
            String name,
            String description,
            DashboardToolMode mode,
            boolean confirmationRequired,
            List<String> requiredPermissions,
            Map<String, Object> inputSchema
    ) {}

    public record DashboardToolCallRequest(
            String sessionId,
            UserContext user,
            Map<String, Object> arguments,
            boolean confirmed,
            String confirmationId
    ) {}

    public record DashboardToolCallResponse(
            DashboardToolStatus status,
            String toolName,
            DashboardToolMode mode,
            String message,
            boolean confirmationRequired,
            String confirmationId,
            Map<String, Object> data,
            List<String> navigationHints
    ) {}

    public record DashboardAskRequest(
            UUID conversationId,
            String sessionId,
            String message,
            String pageRoute,
            String visibility,
            UserContext user,
            Map<String, Object> facts
    ) {}

    public record DashboardAskResponse(
            AskResponse answer,
            List<DashboardToolDefinition> availableTools
    ) {}
}
