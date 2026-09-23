package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.ToolDefinitionDto;
import com.pragmaticds.rag.dto.ToolDefinitionRequest;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.dashboard.BrainToolManifestService;
import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminToolDefinitionControllerTest {

    private final BrainToolManifestService service = mock(BrainToolManifestService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final AdminToolDefinitionController controller =
            new AdminToolDefinitionController(service, brainResolver);

    @Test
    void listResolvesBrainAndDelegates() {
        Brain brain = brain();
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain);
        when(service.list(TestBrains.DEFAULT_ID)).thenReturn(List.of());

        assertEquals(List.of(), controller.list("dashboard-brain"));

        verify(service).list(TestBrains.DEFAULT_ID);
    }

    @Test
    void createRejectsMissingRequestBody() {
        when(brainResolver.resolve(null)).thenReturn(brain());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.create(null, null));

        assertEquals("request body is required", ex.getMessage());
    }

    @Test
    void createDelegatesWithAdminActor() {
        Brain brain = brain();
        ToolDefinitionRequest request = new ToolDefinitionRequest(
                "searchLoans", "Search loans", "READ", false,
                List.of("dashboard.loans.read"), Map.of("type", "object"));
        ToolDefinitionDto dto = new ToolDefinitionDto(UUID.randomUUID(), TestBrains.DEFAULT_ID,
                "searchLoans", "Search loans", DashboardToolMode.READ, false,
                List.of("dashboard.loans.read"), Map.of("type", "object"),
                true, null, null);
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain);
        when(service.create(TestBrains.DEFAULT_ID, request, "admin-api")).thenReturn(dto);

        assertEquals(dto, controller.create(request, "dashboard-brain"));
    }

    @Test
    void deactivateDelegatesToService() {
        Brain brain = brain();
        UUID id = UUID.randomUUID();
        ToolDefinitionDto dto = new ToolDefinitionDto(id, TestBrains.DEFAULT_ID,
                "searchLoans", "Search loans", DashboardToolMode.READ, false,
                List.of(), Map.of(), false, null, null);
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain);
        when(service.setActive(TestBrains.DEFAULT_ID, id, false, "admin-api")).thenReturn(dto);

        assertEquals(dto, controller.deactivate(id, "dashboard-brain"));
    }

    private static Brain brain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        brain.setActive(true);
        return brain;
    }
}
