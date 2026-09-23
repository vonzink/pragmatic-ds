package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardToolRegistryTest {

    private final BrainToolManifestService manifests = mock(BrainToolManifestService.class);
    private final DashboardToolRegistry registry = new DashboardToolRegistry(manifests);

    @Test
    void listIncludesReadWriteAndNavigationTools() {
        when(manifests.active(TestBrains.DEFAULT_ID)).thenReturn(List.of(
                tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")),
                tool("listOpenTasks", DashboardToolMode.READ, false, List.of("dashboard.tasks.read")),
                tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")),
                tool("updateTaskStatus", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")),
                tool("navigateToRoute", DashboardToolMode.NAVIGATE, false, List.of("dashboard.navigation.use"))));

        var tools = registry.list(TestBrains.DEFAULT_ID);
        var names = tools.stream().map(tool -> tool.name()).toList();

        assertTrue(names.contains("searchLoans"));
        assertTrue(names.contains("listOpenTasks"));
        assertTrue(names.contains("createTask"));
        assertTrue(names.contains("updateTaskStatus"));
        assertTrue(names.contains("navigateToRoute"));
    }

    @Test
    void readToolsDoNotRequireConfirmationAndHaveReadPermissions() {
        when(manifests.requireActive(TestBrains.DEFAULT_ID, "searchLoans"))
                .thenReturn(tool("searchLoans", DashboardToolMode.READ, false, List.of("dashboard.loans.read")));

        var tool = registry.require(TestBrains.DEFAULT_ID, "searchLoans");

        assertEquals(DashboardToolMode.READ, tool.mode());
        assertFalse(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.loans.read"));
    }

    @Test
    void writeToolsRequireConfirmationAndWritePermissions() {
        when(manifests.requireActive(TestBrains.DEFAULT_ID, "createTask"))
                .thenReturn(tool("createTask", DashboardToolMode.WRITE, true, List.of("dashboard.tasks.write")));

        var tool = registry.require(TestBrains.DEFAULT_ID, "createTask");

        assertEquals(DashboardToolMode.WRITE, tool.mode());
        assertTrue(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.tasks.write"));
    }

    @Test
    void navigationToolIsSiteContained() {
        when(manifests.requireActive(TestBrains.DEFAULT_ID, "navigateToRoute"))
                .thenReturn(tool("navigateToRoute", DashboardToolMode.NAVIGATE, false,
                        List.of("dashboard.navigation.use")));

        var tool = registry.require(TestBrains.DEFAULT_ID, "navigateToRoute");

        assertEquals(DashboardToolMode.NAVIGATE, tool.mode());
        assertFalse(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.navigation.use"));
    }

    @Test
    void requireRejectsUnknownTool() {
        when(manifests.requireActive(TestBrains.DEFAULT_ID, "deleteEverything"))
                .thenThrow(new IllegalArgumentException("Unknown dashboard tool: deleteEverything"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.require(TestBrains.DEFAULT_ID, "deleteEverything"));

        assertEquals("Unknown dashboard tool: deleteEverything", ex.getMessage());
    }

    private static DashboardToolDefinition tool(String name, DashboardToolMode mode,
                                                boolean confirmationRequired,
                                                List<String> permissions) {
        return new DashboardToolDefinition(name, "description", mode, confirmationRequired,
                permissions, Map.of("type", "object"));
    }
}
