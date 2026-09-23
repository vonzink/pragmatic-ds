package com.pragmaticds.rag.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.domain.BrainToolAdapterRun;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.repository.BrainToolAdapterRunRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HttpToolAdapterExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executesReadAdapterAndPassesJsonResponseIntoData() {
        server.createContext("/search", exchange ->
                respond(exchange, 200, "{\"ok\":true,\"items\":[{\"id\":\"loan-1\"}]}"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.empty());
        BrainToolAdapterConfig adapter = adapter(
                "POST",
                baseUrl + "/search?q={query}&tenant={user.tenantId}",
                "NONE",
                null,
                null,
                Map.of("X-App", "rag-brain"),
                Map.of("query", "{query}"));

        var response = executor.execute(adapter, tool("searchLoans", DashboardToolMode.READ),
                request(Map.of("query", "Jane Smith")));

        assertEquals(DashboardToolStatus.SUCCEEDED, response.status());
        assertEquals("searchLoans", response.toolName());
        assertEquals(true, response.data().get("ok"));
        assertEquals("POST", recorded.get().method());
        assertEquals("/search", recorded.get().path());
        assertEquals("q=Jane+Smith&tenant=tenant-1", recorded.get().query());
        assertEquals("rag-brain", recorded.get().header("X-App"));
        assertEquals("{\"query\":\"Jane Smith\"}", recorded.get().body());
    }

    @Test
    void sendsBearerSecretWithoutLeakingItInResponse() {
        server.createContext("/summary", exchange ->
                respond(exchange, 200, "{\"ok\":true,\"summary\":\"done\"}"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.of("secret-token"));
        BrainToolAdapterConfig adapter = adapter(
                "GET",
                baseUrl + "/summary?loanId={loanId}",
                "BEARER_TOKEN",
                "dashboard_api",
                null,
                Map.of(),
                Map.of());

        var response = executor.execute(adapter, tool("getLoanSummary", DashboardToolMode.READ),
                request(Map.of("loanId", "loan-1")));

        assertEquals(DashboardToolStatus.SUCCEEDED, response.status());
        assertEquals("Bearer secret-token", recorded.get().header("Authorization"));
        assertFalse(response.message().contains("secret-token"));
        assertFalse(response.data().toString().contains("secret-token"));
    }

    @Test
    void non2xxResponseReturnsFailed() {
        server.createContext("/search", exchange ->
                respond(exchange, 503, "{\"error\":\"unavailable\"}"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.empty());
        BrainToolAdapterConfig adapter = adapter(
                "GET",
                baseUrl + "/search?q={query}",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of());

        var response = executor.execute(adapter, tool("searchLoans", DashboardToolMode.READ),
                request(Map.of("query", "PMI")));

        assertEquals(DashboardToolStatus.FAILED, response.status());
        assertEquals(503, response.data().get("statusCode"));
        assertTrue(response.message().contains("503"));
    }

    @Test
    void rejectsHostNotOnAllowlistWithoutSendingRequest() {
        // The live server is running, but its host is NOT in the adapter's allowlist.
        // If the validator were unwired, this request would reach the server and succeed —
        // this test is the seam that proves allowlist enforcement at request time.
        server.createContext("/search", exchange ->
                respond(exchange, 200, "{\"ok\":true}"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.empty());
        BrainToolAdapterConfig adapter = adapter(
                "GET",
                baseUrl + "/search?q={query}",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of(),
                List.of("dashboard.example.com"));

        var response = executor.execute(adapter, tool("searchLoans", DashboardToolMode.READ),
                request(Map.of("query", "PMI")));

        assertEquals(DashboardToolStatus.FAILED, response.status());
        assertTrue(response.message().contains("not allowlisted"));
        assertNull(recorded.get(), "blocked request must never reach the target server");

        // A blocked attempt is still audited (FAILED, no HTTP status, host recorded).
        ArgumentCaptor<BrainToolAdapterRun> run = ArgumentCaptor.forClass(BrainToolAdapterRun.class);
        verify(runRepo, times(1)).save(run.capture());
        assertEquals("FAILED", run.getValue().getStatus());
        assertNull(run.getValue().getHttpStatusCode());
        assertEquals("127.0.0.1", run.getValue().getTargetHost());
    }

    @Test
    void recordsSuccessfulRunToAuditTrail() {
        server.createContext("/search", exchange ->
                respond(exchange, 200, "{\"ok\":true}"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.empty());
        BrainToolAdapterConfig adapter = adapter(
                "GET",
                baseUrl + "/search?q={query}",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of());

        executor.execute(adapter, tool("searchLoans", DashboardToolMode.READ),
                request(Map.of("query", "PMI")));

        ArgumentCaptor<BrainToolAdapterRun> run = ArgumentCaptor.forClass(BrainToolAdapterRun.class);
        verify(runRepo, times(1)).save(run.capture());
        assertEquals("SUCCEEDED", run.getValue().getStatus());
        assertEquals(200, run.getValue().getHttpStatusCode());
        assertEquals("127.0.0.1", run.getValue().getTargetHost());
        assertEquals("searchLoans", run.getValue().getToolName());
    }

    @Test
    void auditWriteFailureDoesNotBreakToolCall() {
        server.createContext("/search", exchange ->
                respond(exchange, 200, "{\"ok\":true}"));
        Mockito.when(runRepo.save(Mockito.any())).thenThrow(new RuntimeException("db down"));

        HttpToolAdapterExecutor executor = executor(ref -> Optional.empty());
        BrainToolAdapterConfig adapter = adapter(
                "GET",
                baseUrl + "/search?q={query}",
                "NONE",
                null,
                null,
                Map.of(),
                Map.of());

        var response = executor.execute(adapter, tool("searchLoans", DashboardToolMode.READ),
                request(Map.of("query", "PMI")));

        // The audit save threw, but the successful tool result is preserved.
        assertEquals(DashboardToolStatus.SUCCEEDED, response.status());
        assertEquals(true, response.data().get("ok"));
    }

    private final BrainToolAdapterRunRepository runRepo = Mockito.mock(BrainToolAdapterRunRepository.class);

    private HttpToolAdapterExecutor executor(ToolSecretResolver secrets) {
        return new HttpToolAdapterExecutor(
                objectMapper,
                new ToolAdapterTemplateRenderer(),
                new ToolAdapterHeaderSanitizer(),
                secrets,
                new ToolAdapterUrlValidator(new MockEnvironment()),
                new ToolAdapterRunRecorder(runRepo),
                new ToolAdapterSecretRedactor());
    }

    private BrainToolAdapterConfig adapter(String method,
                                           String urlTemplate,
                                           String authMode,
                                           String secretRef,
                                           String apiKeyHeader,
                                           Map<String, Object> staticHeaders,
                                           Map<String, Object> requestBodyTemplate) {
        return adapter(method, urlTemplate, authMode, secretRef, apiKeyHeader,
                staticHeaders, requestBodyTemplate, List.of("127.0.0.1"));
    }

    private BrainToolAdapterConfig adapter(String method,
                                           String urlTemplate,
                                           String authMode,
                                           String secretRef,
                                           String apiKeyHeader,
                                           Map<String, Object> staticHeaders,
                                           Map<String, Object> requestBodyTemplate,
                                           List<String> allowedHosts) {
        return new BrainToolAdapterConfig(
                com.pragmaticds.rag.TestBrains.DEFAULT_ID,
                "searchLoans",
                true,
                method,
                urlTemplate,
                authMode,
                secretRef,
                apiKeyHeader,
                staticHeaders,
                requestBodyTemplate,
                5000,
                allowedHosts,
                "test");
    }

    private static DashboardToolDefinition tool(String name, DashboardToolMode mode) {
        return new DashboardToolDefinition(name, "description", mode, false, List.of(), Map.of());
    }

    private static DashboardToolCallRequest request(Map<String, Object> arguments) {
        return new DashboardToolCallRequest("s1",
                new UserContext("user-1", "tenant-1", List.of("loan-officer"), List.of()),
                arguments,
                false,
                null);
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        recorded.set(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> String.join(",", entry.getValue()))),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RecordedRequest(
            String method,
            String path,
            String query,
            Map<String, String> headers,
            String body
    ) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}
