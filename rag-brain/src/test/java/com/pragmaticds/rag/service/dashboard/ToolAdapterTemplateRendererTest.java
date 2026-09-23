package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolAdapterTemplateRendererTest {

    private final ToolAdapterTemplateRenderer renderer = new ToolAdapterTemplateRenderer();

    @Test
    void rendersUrlFromArgumentsAndUserContext() {
        URI uri = renderer.renderUrl(
                "https://dashboard.example.com/api/search?q={query}&tenant={user.tenantId}&user={user.userId}",
                request(Map.of("query", "Jane Smith")));

        assertEquals("https://dashboard.example.com/api/search?q=Jane+Smith&tenant=tenant-1&user=user-1",
                uri.toString());
    }

    @Test
    void failsOnMissingPlaceholder() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> renderer.renderUrl("https://dashboard.example.com/api/search?q={missing}",
                        request(Map.of())));

        assertEquals("Missing tool adapter placeholder: missing", ex.getMessage());
    }

    @Test
    void rendersNestedJsonBodyTemplate() {
        Object body = renderer.renderJson(Map.of(
                "query", "{query}",
                "tenant", "{user.tenantId}",
                "filters", Map.of("loanId", "{loanId}"),
                "tags", List.of("{query}", "static"),
                "limit", 10), request(Map.of("query", "PMI", "loanId", "loan-123")));

        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) body;
        assertEquals("PMI", rendered.get("query"));
        assertEquals("tenant-1", rendered.get("tenant"));
        assertEquals(10, rendered.get("limit"));
        assertEquals(Map.of("loanId", "loan-123"), rendered.get("filters"));
        assertEquals(List.of("PMI", "static"), rendered.get("tags"));
    }

    private static DashboardToolCallRequest request(Map<String, Object> arguments) {
        return new DashboardToolCallRequest("s1",
                new UserContext("user-1", "tenant-1", List.of("loan-officer"),
                        List.of("dashboard.loans.read")),
                arguments, false, null);
    }
}
