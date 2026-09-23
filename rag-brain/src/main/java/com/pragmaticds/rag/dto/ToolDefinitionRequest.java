package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.Map;

public record ToolDefinitionRequest(
        String name,
        String description,
        String mode,
        boolean confirmationRequired,
        List<String> requiredPermissions,
        Map<String, Object> inputSchema
) {}
