package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.service.connect.ConnectorPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardToolGatewayService {

    private final DashboardToolRegistry registry;
    private final ToolAdapterConfigService adapterConfigs;
    private final ToolAdapterExecutor adapterExecutor;

    public DashboardToolGatewayService(DashboardToolRegistry registry,
                                       ToolAdapterConfigService adapterConfigs,
                                       ToolAdapterExecutor adapterExecutor) {
        this.registry = registry;
        this.adapterConfigs = adapterConfigs;
        this.adapterExecutor = adapterExecutor;
    }

    public DashboardToolCallResponse call(UUID brainId, String toolName, DashboardToolCallRequest request,
                                          ConnectorPrincipal principal) {
        DashboardToolDefinition tool = registry.require(brainId, toolName);
        requirePermissions(tool, principal);
        requireAllowedTenant(request, principal);
        Map<String, Object> args = request == null || request.arguments() == null
                ? Map.of()
                : request.arguments();
        boolean confirmed = request != null && request.confirmed();
        String sessionId = request == null ? null : request.sessionId();

        // Confirmation is required for every WRITE tool AND any tool an admin flagged
        // confirmationRequired (previously that flag was silently ignored). A confirmed
        // call must echo the confirmationId issued for these exact (tool, session, args),
        // so a client can't skip the flow by hard-coding confirmed=true.
        if (tool.mode() == DashboardToolMode.WRITE || tool.confirmationRequired()) {
            String expectedConfirmationId = confirmationId(toolName, sessionId, args);
            if (!confirmed) {
                return response(DashboardToolStatus.CONFIRMATION_REQUIRED, tool,
                        "Tool '" + toolName + "' requires confirmation before execution.",
                        true, expectedConfirmationId, args, List.of());
            }
            String provided = request == null ? null : request.confirmationId();
            if (provided == null || !provided.equals(expectedConfirmationId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "confirmationId is missing or does not match; re-request confirmation for tool '"
                                + toolName + "'");
            }
        }

        if (tool.mode() == DashboardToolMode.NAVIGATE) {
            String route = route(args);
            return response(DashboardToolStatus.SUCCEEDED, tool,
                    "Navigation route prepared.", false, null,
                    route == null ? Map.of() : Map.of("route", route),
                    route == null ? List.of() : List.of(route));
        }

        var adapter = adapterConfigs.findEnabled(brainId, toolName);
        if (adapter.isPresent()) {
            return adapterExecutor.execute(adapter.get(), tool, request);
        }

        String message = tool.mode() == DashboardToolMode.WRITE
                ? "Confirmed write accepted, but live dashboard API adapter is not configured yet."
                : "Live dashboard API adapter is not configured yet.";
        return response(DashboardToolStatus.STUBBED, tool, message,
                false, null, args, List.of());
    }

    private static void requirePermissions(DashboardToolDefinition tool, ConnectorPrincipal principal) {
        List<String> granted = principal == null ? List.of() : principal.grantedPermissions();
        for (String required : tool.requiredPermissions()) {
            if (!granted.contains(required)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Connector is not granted dashboard permission: " + required);
            }
        }
    }

    /**
     * A connector may only act for tenants bound to its token. When the request asserts a
     * tenantId (which flows into outbound data-access calls via {@code {user.tenantId}}
     * templating), it must be in the connector's allow-list, otherwise a leaked token could
     * read or write another tenant's data. A connector with no allowed tenants configured
     * cannot assert any tenant identity at all — it fails closed.
     */
    private static void requireAllowedTenant(DashboardToolCallRequest request, ConnectorPrincipal principal) {
        UserContext user = request == null ? null : request.user();
        String tenantId = user == null ? null : user.tenantId();
        ConnectorPrincipal effective = principal == null
                ? new ConnectorPrincipal(List.of(), List.of())
                : principal;
        effective.assertMayActForTenant(tenantId);
    }

    private static DashboardToolCallResponse response(DashboardToolStatus status,
                                                      DashboardToolDefinition tool,
                                                      String message,
                                                      boolean confirmationRequired,
                                                      String confirmationId,
                                                      Map<String, Object> data,
                                                      List<String> navigationHints) {
        return new DashboardToolCallResponse(status, tool.name(), tool.mode(), message,
                confirmationRequired, confirmationId, data, navigationHints);
    }

    private static String route(Map<String, Object> args) {
        Object value = args.get("route");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String route = String.valueOf(value).strip();
        return route.startsWith("/") ? route : "/" + route;
    }

    private static String confirmationId(String toolName, String sessionId, Map<String, Object> args) {
        try {
            String seed = toolName + "|" + (sessionId == null ? "" : sessionId) + "|" + args;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "confirm_" + HexFormat.of()
                    .formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
