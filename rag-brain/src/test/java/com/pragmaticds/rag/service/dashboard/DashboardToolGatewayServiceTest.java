package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.service.connect.ConnectorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DashboardToolGatewayServiceTest {

    private final DashboardToolRegistry registry = mock(DashboardToolRegistry.class);
    private final ToolAdapterConfigService adapterConfigs = mock(ToolAdapterConfigService.class);
    private final ToolAdapterExecutor adapterExecutor = mock(ToolAdapterExecutor.class);
    private final DashboardToolGatewayService gateway =
            new DashboardToolGatewayService(registry, adapterConfigs, adapterExecutor);

    @Test
    void readToolExecutesEnabledAdapter() {
        when(registry.require(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));
        BrainToolAdapterConfig adapter = adapter("searchLoans", DashboardToolMode.READ);
        DashboardToolCallRequest request = request(
                List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null);
        DashboardToolCallResponse executed = new DashboardToolCallResponse(
                DashboardToolStatus.SUCCEEDED,
                "searchLoans",
                DashboardToolMode.READ,
                "Tool adapter executed.",
                false,
                null,
                Map.of("results", List.of("loan-1")),
                List.of());
        when(adapterConfigs.findEnabled(TestBrains.DEFAULT_ID, "searchLoans"))
                .thenReturn(Optional.of(adapter));
        when(adapterExecutor.execute(same(adapter), org.mockito.ArgumentMatchers.any(), same(request)))
                .thenReturn(executed);

        var response = gateway.call(TestBrains.DEFAULT_ID, "searchLoans", request,
                principal(List.of("dashboard.loans.read")));

        assertEquals(executed, response);
        verify(adapterExecutor).execute(same(adapter), org.mockito.ArgumentMatchers.any(), same(request));
    }

    @Test
    void missingAdapterKeepsStubbedBehavior() {
        when(registry.require(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));
        when(adapterConfigs.findEnabled(TestBrains.DEFAULT_ID, "searchLoans"))
                .thenReturn(Optional.empty());

        var response = gateway.call(TestBrains.DEFAULT_ID, "searchLoans", request(
                List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null),
                principal(List.of("dashboard.loans.read")));

        assertEquals(DashboardToolStatus.STUBBED, response.status());
        assertEquals("searchLoans", response.toolName());
        assertFalse(response.confirmationRequired());
        assertEquals("Live dashboard API adapter is not configured yet.", response.message());
        assertEquals("Smith", response.data().get("query"));
        verifyNoInteractions(adapterExecutor);
    }

    @Test
    void toolCallRejectsPermissionNotGrantedToConnector() {
        when(registry.require(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));

        // The request body self-asserts the permission, but the connector was not granted it.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gateway.call(TestBrains.DEFAULT_ID, "searchLoans",
                        request(List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null),
                        principal(List.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(adapterExecutor);
    }

    @Test
    void toolCallRejectsTenantOutsideConnectorAllowlist() {
        when(registry.require(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));

        // Connector is granted the permission and bound to tenant-2, but the request tries to
        // act as tenant-1 (the impersonation vector). It must be rejected before execution.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gateway.call(TestBrains.DEFAULT_ID, "searchLoans",
                        request(List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null),
                        new ConnectorPrincipal(List.of("tenant-2"), List.of("dashboard.loans.read"))));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(adapterExecutor);
    }

    @Test
    void toolCallRejectsAssertedTenantWhenConnectorHasNoAllowedTenants() {
        when(registry.require(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));

        // A connector with no tenant binding cannot assert any tenant identity at all.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gateway.call(TestBrains.DEFAULT_ID, "searchLoans",
                        request(List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null),
                        new ConnectorPrincipal(List.of(), List.of("dashboard.loans.read"))));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(adapterExecutor);
    }

    @Test
    void writeToolRequiresConfirmationBeforeExecution() {
        when(registry.require(TestBrains.DEFAULT_ID, "createTask")).thenReturn(
                tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")));

        var response = gateway.call(TestBrains.DEFAULT_ID, "createTask", request(
                List.of("dashboard.tasks.write"), Map.of("title", "Call borrower"), false, null),
                principal(List.of("dashboard.tasks.write")));

        assertEquals(DashboardToolStatus.CONFIRMATION_REQUIRED, response.status());
        assertTrue(response.confirmationRequired());
        assertNotNull(response.confirmationId());
        assertTrue(response.message().contains("requires confirmation"));
        verifyNoInteractions(adapterConfigs, adapterExecutor);
    }

    @Test
    void confirmedWriteExecutesEnabledAdapter() {
        when(registry.require(TestBrains.DEFAULT_ID, "createTask")).thenReturn(
                tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")));
        Map<String, Object> args = Map.of("title", "Call borrower");
        // Round-trip: obtain the confirmationId the gateway issues for these exact args.
        String confirmationId = gateway.call(TestBrains.DEFAULT_ID, "createTask",
                request(List.of("dashboard.tasks.write"), args, false, null),
                principal(List.of("dashboard.tasks.write"))).confirmationId();

        BrainToolAdapterConfig adapter = adapter("createTask", DashboardToolMode.WRITE);
        DashboardToolCallRequest request = request(
                List.of("dashboard.tasks.write"), args, true, confirmationId);
        DashboardToolCallResponse executed = new DashboardToolCallResponse(
                DashboardToolStatus.SUCCEEDED,
                "createTask",
                DashboardToolMode.WRITE,
                "Tool adapter executed.",
                false,
                null,
                Map.of("taskId", "task-1"),
                List.of());
        when(adapterConfigs.findEnabled(TestBrains.DEFAULT_ID, "createTask"))
                .thenReturn(Optional.of(adapter));
        when(adapterExecutor.execute(same(adapter), org.mockito.ArgumentMatchers.any(), same(request)))
                .thenReturn(executed);

        var response = gateway.call(TestBrains.DEFAULT_ID, "createTask", request,
                principal(List.of("dashboard.tasks.write")));

        assertEquals(executed, response);
        verify(adapterExecutor).execute(same(adapter), org.mockito.ArgumentMatchers.any(), same(request));
    }

    @Test
    void confirmedWriteRejectsMismatchedConfirmationId() {
        when(registry.require(TestBrains.DEFAULT_ID, "createTask")).thenReturn(
                tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")));

        // confirmed=true with a fabricated id that was never issued for these args.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gateway.call(TestBrains.DEFAULT_ID, "createTask", request(
                        List.of("dashboard.tasks.write"), Map.of("title", "Call borrower"), true, "confirm-123"),
                        principal(List.of("dashboard.tasks.write"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(adapterConfigs, adapterExecutor);
    }

    @Test
    void readToolWithConfirmationRequiredFlagStillRequiresConfirmation() {
        // A READ tool an admin flagged confirmationRequired must confirm before running.
        when(registry.require(TestBrains.DEFAULT_ID, "riskyRead")).thenReturn(
                tool("riskyRead", DashboardToolMode.READ, true, List.of("dashboard.loans.read")));

        var response = gateway.call(TestBrains.DEFAULT_ID, "riskyRead", request(
                List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null),
                principal(List.of("dashboard.loans.read")));

        assertEquals(DashboardToolStatus.CONFIRMATION_REQUIRED, response.status());
        verifyNoInteractions(adapterConfigs, adapterExecutor);
    }

    @Test
    void confirmedWriteKeepsStubbedBehaviorWhenAdapterIsMissing() {
        when(registry.require(TestBrains.DEFAULT_ID, "createTask")).thenReturn(
                tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")));
        Map<String, Object> args = Map.of("title", "Call borrower");
        String confirmationId = gateway.call(TestBrains.DEFAULT_ID, "createTask",
                request(List.of("dashboard.tasks.write"), args, false, null),
                principal(List.of("dashboard.tasks.write"))).confirmationId();
        when(adapterConfigs.findEnabled(TestBrains.DEFAULT_ID, "createTask"))
                .thenReturn(Optional.empty());

        var response = gateway.call(TestBrains.DEFAULT_ID, "createTask", request(
                List.of("dashboard.tasks.write"), args, true, confirmationId),
                principal(List.of("dashboard.tasks.write")));

        assertEquals(DashboardToolStatus.STUBBED, response.status());
        assertFalse(response.confirmationRequired());
        assertEquals("Confirmed write accepted, but live dashboard API adapter is not configured yet.",
                response.message());
        verifyNoInteractions(adapterExecutor);
    }

    @Test
    void navigationToolReturnsSiteContainedRoute() {
        when(registry.require(TestBrains.DEFAULT_ID, "navigateToRoute")).thenReturn(
                tool("navigateToRoute", DashboardToolMode.NAVIGATE, false,
                        List.of("dashboard.navigation.use")));

        var response = gateway.call(TestBrains.DEFAULT_ID, "navigateToRoute", request(
                List.of("dashboard.navigation.use"), Map.of("route", "/loans"), false, null),
                principal(List.of("dashboard.navigation.use")));

        assertEquals(DashboardToolStatus.SUCCEEDED, response.status());
        assertEquals(List.of("/loans"), response.navigationHints());
        assertEquals("/loans", response.data().get("route"));
        verifyNoMoreInteractions(adapterConfigs, adapterExecutor);
    }

    private static DashboardToolCallRequest request(List<String> permissions,
                                                    Map<String, Object> arguments,
                                                    boolean confirmed,
                                                    String confirmationId) {
        return new DashboardToolCallRequest("s1",
                new UserContext("user-1", "tenant-1", List.of("loan-officer"), permissions),
                arguments, confirmed, confirmationId);
    }

    private static ConnectorPrincipal principal(List<String> grantedPermissions) {
        return new ConnectorPrincipal(List.of("tenant-1"), grantedPermissions);
    }

    private static DashboardToolDefinition tool(String name, DashboardToolMode mode,
                                                boolean confirmationRequired,
                                                List<String> permissions) {
        return new DashboardToolDefinition(name, "description", mode, confirmationRequired,
                permissions, Map.of("type", "object"));
    }

    private static BrainToolAdapterConfig adapter(String name, DashboardToolMode mode) {
        return new BrainToolAdapterConfig(
                TestBrains.DEFAULT_ID,
                name,
                true,
                mode == DashboardToolMode.READ ? "GET" : "POST",
                "https://dashboard.example.com/api/" + name,
                "NONE",
                null,
                null,
                Map.of(),
                Map.of(),
                5000,
                List.of("api.example.com"),
                "test");
    }
}
