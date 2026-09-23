package com.pragmaticds.rag.dto;

import java.util.List;
import java.util.UUID;

public record ConnectorClientRequest(
        String name,
        String type,
        UUID brainId,
        List<String> scopes,
        List<String> allowedOrigins,
        List<String> allowedPeerHosts,
        List<String> allowedTenants,
        List<String> grantedPermissions,
        boolean enabled
) {
}
