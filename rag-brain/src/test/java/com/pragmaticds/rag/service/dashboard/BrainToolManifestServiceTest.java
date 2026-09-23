package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolDefinition;
import com.pragmaticds.rag.dto.ToolDefinitionRequest;
import com.pragmaticds.rag.repository.BrainToolDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainToolManifestServiceTest {

    private final BrainToolDefinitionRepository repo = mock(BrainToolDefinitionRepository.class);
    private final BrainToolManifestService service = new BrainToolManifestService(repo);

    @Test
    void createCleansFieldsAndForcesWriteConfirmation() {
        when(repo.existsByBrainIdAndName(TestBrains.DEFAULT_ID, "createTask")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(TestBrains.DEFAULT_ID, new ToolDefinitionRequest(
                " createTask ",
                " Create a task ",
                "WRITE",
                false,
                Arrays.asList(" dashboard.tasks.write ", "", null),
                Map.of("type", "object")), "admin");

        assertEquals("createTask", dto.name());
        assertEquals(DashboardToolMode.WRITE, dto.mode());
        assertTrue(dto.confirmationRequired());
        assertEquals(List.of("dashboard.tasks.write"), dto.requiredPermissions());
    }

    @Test
    void createRejectsDuplicateNameForBrain() {
        when(repo.existsByBrainIdAndName(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(TestBrains.DEFAULT_ID, new ToolDefinitionRequest(
                        "searchLoans", "Search", "READ", false, List.of(), Map.of()), "admin"));

        assertEquals("tool definition already exists for brain: searchLoans", ex.getMessage());
    }

    @Test
    void requireActiveReturnsDashboardToolDefinition() {
        BrainToolDefinition entity = new BrainToolDefinition(
                TestBrains.DEFAULT_ID, "searchLoans", "Search loans",
                DashboardToolMode.READ, false, List.of("dashboard.loans.read"),
                Map.of("type", "object"), "admin");
        when(repo.findByBrainIdAndNameAndActiveTrue(TestBrains.DEFAULT_ID, "searchLoans"))
                .thenReturn(Optional.of(entity));

        var tool = service.requireActive(TestBrains.DEFAULT_ID, "searchLoans");

        assertEquals("searchLoans", tool.name());
        assertEquals(DashboardToolMode.READ, tool.mode());
        assertEquals(List.of("dashboard.loans.read"), tool.requiredPermissions());
    }

    @Test
    void updateRejectsDuplicateNameForBrain() {
        UUID id = UUID.randomUUID();
        BrainToolDefinition entity = new BrainToolDefinition(
                TestBrains.DEFAULT_ID, "searchLoans", "Search loans",
                DashboardToolMode.READ, false, List.of(), Map.of(), "admin");
        when(repo.findById(id)).thenReturn(Optional.of(entity));
        when(repo.existsByBrainIdAndNameAndIdNot(TestBrains.DEFAULT_ID, "listOpenTasks", id))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(TestBrains.DEFAULT_ID, id, new ToolDefinitionRequest(
                        "listOpenTasks", "List tasks", "READ", false, List.of(), Map.of()), "admin"));

        assertEquals("tool definition already exists for brain: listOpenTasks", ex.getMessage());
    }

    @Test
    void setActiveRejectsCrossBrainMutation() {
        UUID id = UUID.randomUUID();
        BrainToolDefinition entity = new BrainToolDefinition(
                UUID.randomUUID(), "searchLoans", "Search loans",
                DashboardToolMode.READ, false, List.of(), Map.of(), "admin");
        when(repo.findById(id)).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setActive(TestBrains.DEFAULT_ID, id, false, "admin"));

        assertEquals("tool definition not found for brain: " + id, ex.getMessage());
        verify(repo).findById(id);
    }
}
