package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.domain.BrainToolAdapterRun;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.repository.BrainToolAdapterRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolAdapterRunRecorderTest {

    private final BrainToolAdapterRunRepository repo = mock(BrainToolAdapterRunRepository.class);
    private final ToolAdapterRunRecorder recorder = new ToolAdapterRunRecorder(repo);

    @Test
    void recordsOnlySanitizedMetadata() {
        BrainToolAdapterConfig adapter = adapter();
        DashboardToolDefinition tool = tool();
        DashboardToolCallRequest request = new DashboardToolCallRequest("s1",
                new UserContext("user-1", "tenant-1", List.of("admin"), List.of()),
                Map.of("query", "Smith"), false, null);

        recorder.record(adapter, tool, request, URI.create("https://api.example.com/search?query=Smith"),
                DashboardToolStatus.SUCCEEDED, 200, 25, null);

        BrainToolAdapterRun saved = savedRun();
        assertEquals("api.example.com", saved.getTargetHost());
        assertEquals("s1", saved.getSessionId());
        assertEquals("user-1", saved.getUserId());
        assertEquals("tenant-1", saved.getTenantId());
        assertEquals(25, saved.getDurationMs());
    }

    @Test
    void sanitizesRawUriOrMessageLikeErrorType() {
        recorder.record(adapter(), tool(), request("s1"), URI.create("https://api.example.com/search?query=Smith"),
                DashboardToolStatus.FAILED, null, 25,
                "https://api.example.com/search?token=secret-token failed");

        BrainToolAdapterRun saved = savedRun();
        assertEquals("SanitizedError", saved.getErrorType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "secret_ref",
            "smoke_api",
            "api-key-prod",
            "sk-proj-abc",
            "api.example.com",
            "sk-proj-abcException",
            "api-key-prodError",
            "secret_refError",
            "api.example.comException"
    })
    void sanitizesSecretRefTokenAndHostShapedErrorTypes(String errorType) {
        recorder.record(adapter(), tool(), request("s1"), URI.create("https://api.example.com/search?query=Smith"),
                DashboardToolStatus.FAILED, null, 25, errorType);

        BrainToolAdapterRun saved = savedRun();
        assertEquals("SanitizedError", saved.getErrorType());
    }

    @Test
    void preservesSafeErrorClassName() {
        recorder.record(adapter(), tool(), request("s1"), URI.create("https://api.example.com/search?query=Smith"),
                DashboardToolStatus.FAILED, null, 25, "IllegalArgumentException");

        BrainToolAdapterRun saved = savedRun();
        assertEquals("IllegalArgumentException", saved.getErrorType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "IllegalArgumentException",
            "java.net.SocketTimeoutException",
            "RuntimeError",
            "HTTP_503",
            "DNS_FAIL"
    })
    void preservesKnownSafeErrorTypes(String errorType) {
        recorder.record(adapter(), tool(), request("s1"), URI.create("https://api.example.com/search?query=Smith"),
                DashboardToolStatus.FAILED, null, 25, errorType);

        BrainToolAdapterRun saved = savedRun();
        assertEquals(errorType, saved.getErrorType());
    }

    @Test
    void handlesMissingRequestMetadataAndClampsNegativeDuration() {
        DashboardToolCallRequest requestWithoutUser = new DashboardToolCallRequest("s2", null, Map.of(), false, null);

        recorder.record(adapter(), tool(), requestWithoutUser, null,
                DashboardToolStatus.FAILED, null, -50, "TimeoutException");

        BrainToolAdapterRun saved = savedRun();
        assertEquals("s2", saved.getSessionId());
        assertNull(saved.getUserId());
        assertNull(saved.getTenantId());
        assertNull(saved.getTargetHost());
        assertEquals(0, saved.getDurationMs());
    }

    private BrainToolAdapterConfig adapter() {
        return new BrainToolAdapterConfig(TestBrains.DEFAULT_ID,
                "searchLoans", true, "POST", "https://api.example.com/search?token=bad",
                "BEARER_TOKEN", "secret_ref", null, Map.of("Authorization", "bad"),
                Map.of("query", "{query}"), 5000, List.of("api.example.com"), "test");
    }

    private DashboardToolDefinition tool() {
        return new DashboardToolDefinition("searchLoans",
                "description", DashboardToolMode.READ, false, List.of(), Map.of());
    }

    private DashboardToolCallRequest request(String sessionId) {
        return new DashboardToolCallRequest(sessionId,
                new UserContext("user-1", "tenant-1", List.of("admin"), List.of()),
                Map.of("query", "Smith"), false, null);
    }

    private BrainToolAdapterRun savedRun() {
        ArgumentCaptor<BrainToolAdapterRun> captor = ArgumentCaptor.forClass(BrainToolAdapterRun.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }
}
