package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.ToolAdapterConfigDto;
import com.pragmaticds.rag.dto.ToolAdapterConfigRequest;
import com.pragmaticds.rag.dto.ToolAdapterRunDto;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.dashboard.ToolAdapterConfigService;
import com.pragmaticds.rag.service.dashboard.ToolAdapterRunService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminToolAdapterConfigControllerTest {

    private final ToolAdapterConfigService service = mock(ToolAdapterConfigService.class);
    private final ToolAdapterRunService runService = mock(ToolAdapterRunService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final AdminToolAdapterConfigController controller =
            new AdminToolAdapterConfigController(service, runService, brainResolver);

    @Test
    void runsDelegatesWithResolvedBrainAndToolName() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        ToolAdapterRunDto run = new ToolAdapterRunDto(UUID.randomUUID(), "searchLoans", "READ",
                "dashboard.example.com", "POST", "SUCCEEDED", 200, 42L, "s1", "u1", "t1", null, null);
        when(brainResolver.resolve("generic")).thenReturn(brain);
        when(runService.recentRuns(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(List.of(run));

        assertEquals(List.of(run), controller.runs("searchLoans", "generic"));
        verify(runService).recentRuns(TestBrains.DEFAULT_ID, "searchLoans");
    }

    @Test
    void getDelegatesWithResolvedBrainAndToolName() {
        ToolAdapterConfigDto dto = dto("searchLoans");
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain());
        when(service.get(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(dto);

        assertEquals(dto, controller.get("searchLoans", "dashboard-brain"));

        verify(service).get(TestBrains.DEFAULT_ID, "searchLoans");
    }

    @Test
    void putRejectsNullBody() {
        when(brainResolver.resolve(null)).thenReturn(brain());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.put("searchLoans", null, null));

        assertEquals("request body is required", ex.getMessage());
    }

    @Test
    void putDelegatesWithAdminActor() {
        ToolAdapterConfigRequest request = request();
        ToolAdapterConfigDto dto = dto("searchLoans");
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain());
        when(service.upsert(TestBrains.DEFAULT_ID, "searchLoans", request, "admin-api")).thenReturn(dto);

        assertEquals(dto, controller.put("searchLoans", request, "dashboard-brain"));

        verify(service).upsert(TestBrains.DEFAULT_ID, "searchLoans", request, "admin-api");
    }

    @Test
    void deleteDelegatesWithResolvedBrainAndToolName() {
        when(brainResolver.resolve("dashboard-brain")).thenReturn(brain());

        assertEquals("searchLoans", controller.delete("searchLoans", "dashboard-brain").getBody().get("toolName"));

        verify(service).delete(TestBrains.DEFAULT_ID, "searchLoans");
    }

    private static ToolAdapterConfigRequest request() {
        return new ToolAdapterConfigRequest(
                true,
                "POST",
                "https://dashboard.example.com/api/search",
                "BEARER_TOKEN",
                "dashboard_api",
                null,
                Map.of("X-App", "rag-brain"),
                Map.of("query", "{query}"),
                5000,
                List.of("dashboard.example.com"));
    }

    private static ToolAdapterConfigDto dto(String toolName) {
        return new ToolAdapterConfigDto(
                UUID.randomUUID(),
                TestBrains.DEFAULT_ID,
                toolName,
                true,
                "POST",
                "https://dashboard.example.com/api/search",
                "BEARER_TOKEN",
                "dashboard_api",
                null,
                Map.of("X-App", "rag-brain"),
                Map.of("query", "{query}"),
                5000,
                List.of("dashboard.example.com"),
                null,
                null);
    }

    private static Brain brain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        brain.setActive(true);
        return brain;
    }
}
