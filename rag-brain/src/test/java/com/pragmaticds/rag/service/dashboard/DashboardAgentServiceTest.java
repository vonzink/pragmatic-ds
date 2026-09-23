package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.connect.ConnectorPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardAgentServiceTest {

    private final AskService askService = mock(AskService.class);
    private final DashboardToolRegistry registry = mock(DashboardToolRegistry.class);
    private final DashboardAgentService service = new DashboardAgentService(askService, registry);

    @Test
    void askUsesInternalVisibilityAndThreadsUserContextIntoFacts() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        AskResponse answer = new AskResponse(UUID.randomUUID(), "Answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(org.mockito.Mockito.any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL)))
                .thenReturn(answer);
        when(registry.list(TestBrains.DEFAULT_ID)).thenReturn(List.of(new DashboardToolDefinition(
                "searchLoans", "Search loans", DashboardToolMode.READ, false,
                List.of("dashboard.loans.read"), Map.of("type", "object"))));

        var response = service.ask(brain, new DashboardAskRequest(null, "s1",
                "What needs attention?", "/loans", null,
                new UserContext("user-1", "tenant-1", List.of("loan-officer"),
                        List.of("dashboard.loans.read")),
                Map.of("current_module", "loans")),
                new ConnectorPrincipal(List.of("tenant-1"), List.of("dashboard.loans.read")));

        assertEquals(answer, response.answer());
        assertFalse(response.availableTools().isEmpty());
        verify(registry).list(TestBrains.DEFAULT_ID);
        ArgumentCaptor<AskRequest> request = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(request.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL));
        assertEquals("INTERNAL", request.getValue().surface());
        assertEquals("user-1", request.getValue().facts().get("user_id"));
        assertEquals("tenant-1", request.getValue().facts().get("tenant_id"));
        assertEquals("loan-officer", request.getValue().facts().get("roles"));
        assertEquals("loans", request.getValue().facts().get("current_module"));
    }

    @Test
    void askAllowsSecureRetrievalVisibilityButKeepsInternalSurface() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        AskResponse answer = new AskResponse(UUID.randomUUID(), "Answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(org.mockito.Mockito.any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.SECURE)))
                .thenReturn(answer);

        service.ask(brain, new DashboardAskRequest(null, "s1",
                "Show secure file status", "/documents", "SECURE",
                new UserContext("user-1", "tenant-1", List.of(), List.of()),
                Map.of()),
                new ConnectorPrincipal(List.of("tenant-1"), List.of()));

        ArgumentCaptor<AskRequest> request = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(request.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.SECURE));
        assertEquals("INTERNAL", request.getValue().surface());
    }

    @Test
    void askRejectsTenantNotBoundToTheConnector() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");

        // Connector is bound to tenant-1 but the request asserts tenant-2.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ask(brain, new DashboardAskRequest(null, "s1",
                        "What needs attention?", "/loans", null,
                        new UserContext("user-9", "tenant-2", List.of(), List.of()),
                        Map.of()),
                        new ConnectorPrincipal(List.of("tenant-1"), List.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        // Rejected before any answer is generated.
        verifyNoInteractions(askService);
    }
}
