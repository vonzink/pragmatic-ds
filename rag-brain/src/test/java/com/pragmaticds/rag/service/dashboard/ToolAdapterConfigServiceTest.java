package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.dto.ToolAdapterConfigRequest;
import com.pragmaticds.rag.repository.BrainToolAdapterConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolAdapterConfigServiceTest {

    private final BrainToolAdapterConfigRepository repo = mock(BrainToolAdapterConfigRepository.class);
    private final ToolAdapterConfigService service = new ToolAdapterConfigService(repo);

    @Test
    void upsertNormalizesMethodAndBoundsTimeout() {
        when(repo.findByBrainIdAndToolName(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.upsert(TestBrains.DEFAULT_ID, " searchLoans ", new ToolAdapterConfigRequest(
                true,
                " post ",
                " https://dashboard.example.com/api/search ",
                " bearer_token ",
                " dashboard_api ",
                null,
                Map.of("X-App", "rag-brain"),
                Map.of("query", "{query}"),
                null,
                List.of(" Dashboard.EXAMPLE.com ")), "admin");

        assertEquals("searchLoans", dto.toolName());
        assertEquals("POST", dto.httpMethod());
        assertEquals("BEARER_TOKEN", dto.authMode());
        assertEquals(5000, dto.timeoutMs());
        assertEquals("dashboard_api", dto.secretRef());
        assertNull(dto.apiKeyHeader());
        assertEquals(List.of("dashboard.example.com"), dto.allowedHosts());
    }

    @Test
    void upsertRejectsBlockedStaticHeader() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(TestBrains.DEFAULT_ID, "searchLoans", new ToolAdapterConfigRequest(
                        true,
                        "GET",
                        "https://dashboard.example.com/api/search",
                        "NONE",
                        null,
                        null,
                        Map.of("Host", "bad"),
                        Map.of(),
                        5000,
                        List.of("dashboard.example.com")), "admin"));

        assertEquals("static header is blocked: Host", ex.getMessage());
    }

    @Test
    void upsertRejectsApiKeyModeWithoutHeader() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(TestBrains.DEFAULT_ID, "searchLoans", new ToolAdapterConfigRequest(
                        true,
                        "GET",
                        "https://dashboard.example.com/api/search",
                        "API_KEY_HEADER",
                        "dashboard_api",
                        " ",
                        Map.of(),
                        Map.of(),
                        5000,
                        List.of("dashboard.example.com")), "admin"));

        assertEquals("apiKeyHeader is required for API_KEY_HEADER", ex.getMessage());
    }

    @Test
    void upsertRejectsEnabledAdapterWithoutAllowedHosts() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(TestBrains.DEFAULT_ID, "searchLoans", new ToolAdapterConfigRequest(
                        true,
                        "GET",
                        "https://dashboard.example.com/api/search",
                        "NONE",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        5000,
                        List.of()), "admin"));

        assertEquals("allowedHosts is required when adapter is enabled", ex.getMessage());
    }

    @Test
    void upsertDropsBlankHostsAndDedupesAfterNormalizing() {
        when(repo.findByBrainIdAndToolName(TestBrains.DEFAULT_ID, "searchLoans")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.upsert(TestBrains.DEFAULT_ID, "searchLoans", new ToolAdapterConfigRequest(
                true,
                "GET",
                "https://dashboard.example.com/api/search",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of(),
                5000,
                Arrays.asList(
                        " Dashboard.EXAMPLE.com ",
                        null,
                        " ",
                        "dashboard.example.com",
                        " API.EXAMPLE.com ",
                        "api.example.com")), "admin");

        assertEquals(List.of("dashboard.example.com", "api.example.com"), dto.allowedHosts());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://dashboard.example.com",
            "dashboard.example.com/path",
            "dashboard.example.com?query=1"
    })
    void upsertRejectsMalformedAllowedHosts(String badHost) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(TestBrains.DEFAULT_ID, "searchLoans", new ToolAdapterConfigRequest(
                        true,
                        "GET",
                        "https://dashboard.example.com/api/search",
                        "NONE",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        5000,
                        List.of(badHost)), "admin"));

        assertEquals("allowedHosts entries must be host names only: " + badHost, ex.getMessage());
    }

    @Test
    void getAllowedHostsDoesNotExposeMutableInternalList() {
        BrainToolAdapterConfig config = new BrainToolAdapterConfig(
                TestBrains.DEFAULT_ID,
                "searchLoans",
                true,
                "GET",
                "https://dashboard.example.com/api/search",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of(),
                5000,
                List.of("api.example.com"),
                "test");

        List<String> allowedHosts = config.getAllowedHosts();

        assertThrows(UnsupportedOperationException.class, () -> allowedHosts.add("evil.example.com"));
        assertEquals(List.of("api.example.com"), config.getAllowedHosts());
    }

    @Test
    void findEnabledReturnsEmptyWhenNoConfig() {
        when(repo.findByBrainIdAndToolNameAndEnabledTrue(TestBrains.DEFAULT_ID, "searchLoans"))
                .thenReturn(Optional.empty());

        assertTrue(service.findEnabled(TestBrains.DEFAULT_ID, "searchLoans").isEmpty());

        verify(repo).findByBrainIdAndToolNameAndEnabledTrue(TestBrains.DEFAULT_ID, "searchLoans");
    }
}
