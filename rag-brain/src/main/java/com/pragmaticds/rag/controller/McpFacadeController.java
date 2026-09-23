package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.FederationDtos;
import com.pragmaticds.rag.dto.DashboardAgentDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/mcp/tools")
public class McpFacadeController {

    private final FederationController federation;
    private final DashboardAgentController dashboard;

    public McpFacadeController(FederationController federation, DashboardAgentController dashboard) {
        this.federation = federation;
        this.dashboard = dashboard;
    }

    @GetMapping
    public List<ToolDto> tools() {
        return List.of(
                tool("rag_brain_list_brains", "List active rag-brain brains", List.of()),
                tool("rag_brain_readiness", "Read public attach readiness for a brain", List.of("slug")),
                tool("rag_brain_ask", "Ask a public-safe question against a rag-brain brain", List.of("slug", "message")),
                tool("rag_brain_retrieve", "Retrieve public evidence chunks from a rag-brain brain", List.of("slug", "question")),
                tool("rag_brain_dashboard_tools", "List internal dashboard-brain live data tools", List.of("slug")),
                tool("rag_brain_dashboard_tool_call", "Call an internal dashboard-brain live data tool", List.of("slug", "toolName")),
                tool("rag_brain_dashboard_ask", "Ask an internal dashboard-brain question", List.of("slug", "message")));
    }

    @PostMapping("/{toolName}")
    public Object call(@PathVariable String toolName,
                       @RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestHeader(value = "Host", required = false) String host,
                       @RequestHeader(value = "Origin", required = false) String origin,
                       @RequestBody Map<String, Object> input) {
        Map<String, Object> body = input == null ? Map.of() : input;
        return switch (toolName) {
            case "rag_brain_list_brains" -> federation.brains(authorization, host, origin);
            case "rag_brain_readiness" -> federation.readiness(require(body, "slug"), authorization, host, origin);
            case "rag_brain_ask" -> federation.ask(require(body, "slug"), authorization, host, origin,
                    new FederationDtos.FederationAskRequest(conversationId(body), sessionId(body),
                            require(body, "message"), string(body, "pageRoute"), facts(body)));
            case "rag_brain_retrieve" -> federation.retrieve(require(body, "slug"), authorization, host, origin,
                    new FederationDtos.FederationRetrieveRequest(require(body, "question")));
            case "rag_brain_dashboard_tools" -> dashboard.tools(require(body, "slug"), authorization, host, origin);
            case "rag_brain_dashboard_tool_call" -> dashboard.callTool(require(body, "slug"),
                    require(body, "toolName"), authorization, host, origin, dashboardToolCall(body));
            case "rag_brain_dashboard_ask" -> dashboard.ask(require(body, "slug"),
                    authorization, host, origin, dashboardAsk(body));
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown MCP tool: " + toolName);
        };
    }

    private static ToolDto tool(String name, String description, List<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("slug", Map.of("type", "string"));
        properties.put("message", Map.of("type", "string"));
        properties.put("question", Map.of("type", "string"));
        properties.put("sessionId", Map.of("type", "string"));
        properties.put("pageRoute", Map.of("type", "string"));
        properties.put("visibility", Map.of("type", "string"));
        properties.put("toolName", Map.of("type", "string"));
        properties.put("confirmed", Map.of("type", "boolean"));
        properties.put("confirmationId", Map.of("type", "string"));
        properties.put("user", Map.of("type", "object"));
        properties.put("arguments", Map.of("type", "object"));
        properties.put("facts", Map.of("type", "object"));
        return new ToolDto(name, description, Map.of(
                "type", "object",
                "required", required,
                "properties", properties));
    }

    private static String require(Map<String, Object> input, String key) {
        String value = string(input, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String sessionId(Map<String, Object> input) {
        String value = string(input, "sessionId");
        return value == null || value.isBlank() ? "mcp-session" : value;
    }

    private static UUID conversationId(Map<String, Object> input) {
        String value = string(input, "conversationId");
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> facts(Map<String, Object> input) {
        Object value = input.get("facts");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static DashboardAgentDtos.DashboardToolCallRequest dashboardToolCall(Map<String, Object> input) {
        return new DashboardAgentDtos.DashboardToolCallRequest(
                sessionId(input),
                user(input),
                objectMap(input, "arguments"),
                bool(input, "confirmed"),
                string(input, "confirmationId"));
    }

    private static DashboardAgentDtos.DashboardAskRequest dashboardAsk(Map<String, Object> input) {
        return new DashboardAgentDtos.DashboardAskRequest(
                conversationId(input),
                sessionId(input),
                require(input, "message"),
                string(input, "pageRoute"),
                string(input, "visibility"),
                user(input),
                facts(input));
    }

    private static DashboardAgentDtos.UserContext user(Map<String, Object> input) {
        Map<String, Object> user = objectMap(input, "user");
        return new DashboardAgentDtos.UserContext(
                string(user, "userId"),
                string(user, "tenantId"),
                stringList(user, "roles"),
                stringList(user, "permissions"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return ((List<Object>) values).stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .toList();
    }

    private static boolean bool(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    public record ToolDto(String name, String description, Map<String, Object> inputSchema) {}
}
