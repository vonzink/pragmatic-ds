package com.pragmaticds.rag.service.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpToolAdapterExecutor implements ToolAdapterExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpToolAdapterExecutor.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ToolAdapterTemplateRenderer renderer;
    private final ToolAdapterHeaderSanitizer headerSanitizer;
    private final ToolSecretResolver secrets;
    private final ToolAdapterUrlValidator urlValidator;
    private final ToolAdapterRunRecorder runRecorder;
    private final ToolAdapterSecretRedactor secretRedactor;
    private final HttpClient httpClient;

    public HttpToolAdapterExecutor(ObjectMapper objectMapper,
                                   ToolAdapterTemplateRenderer renderer,
                                   ToolAdapterHeaderSanitizer headerSanitizer,
                                   ToolSecretResolver secrets,
                                   ToolAdapterUrlValidator urlValidator,
                                   ToolAdapterRunRecorder runRecorder,
                                   ToolAdapterSecretRedactor secretRedactor) {
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.headerSanitizer = headerSanitizer;
        this.secrets = secrets;
        this.urlValidator = urlValidator;
        this.runRecorder = runRecorder;
        this.secretRedactor = secretRedactor;
        // Never auto-follow redirects: a downstream 3xx to a metadata/internal IP would
        // otherwise bypass the allowlist that was enforced on the original URL.
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public DashboardToolCallResponse execute(BrainToolAdapterConfig adapter,
                                             DashboardToolDefinition tool,
                                             DashboardToolCallRequest request) {
        long startNanos = System.nanoTime();
        URI targetUri = null;
        List<String> secretsToRedact = new ArrayList<>();
        try {
            URI rendered = renderer.renderUrl(adapter.getUrlTemplate(), request);
            targetUri = rendered;
            // Fail closed: enforce the per-adapter host allowlist (and prod scheme/private-IP
            // rules) on the fully rendered URL before any bytes leave the process.
            targetUri = urlValidator.validate(rendered, adapter);
            String resolvedSecret = resolveSecretIfNeeded(adapter);
            if (resolvedSecret != null) {
                secretsToRedact.add(resolvedSecret);
            }

            HttpResponse<String> response = httpClient.send(
                    buildRequest(adapter, request, targetUri, resolvedSecret),
                    HttpResponse.BodyHandlers.ofString());

            Map<String, Object> data = redact(responseData(response), secretsToRedact);
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            DashboardToolStatus status = ok ? DashboardToolStatus.SUCCEEDED : DashboardToolStatus.FAILED;
            recordRun(adapter, tool, request, targetUri, status, response.statusCode(),
                    elapsedMs(startNanos), null);
            String message = ok
                    ? "Tool adapter executed."
                    : "Tool adapter request failed with status " + response.statusCode() + ".";
            return response(status, tool, message, data);
        } catch (IllegalArgumentException e) {
            recordRun(adapter, tool, request, targetUri, DashboardToolStatus.FAILED, null,
                    elapsedMs(startNanos), e.getClass().getSimpleName());
            return response(DashboardToolStatus.FAILED, tool,
                    e.getMessage(), Map.of("error", e.getClass().getSimpleName()));
        } catch (Exception e) {
            recordRun(adapter, tool, request, targetUri, DashboardToolStatus.FAILED, null,
                    elapsedMs(startNanos), e.getClass().getSimpleName());
            return response(DashboardToolStatus.FAILED, tool,
                    "Tool adapter request failed.", Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private HttpRequest buildRequest(BrainToolAdapterConfig adapter,
                                     DashboardToolCallRequest request,
                                     URI uri,
                                     String resolvedSecret) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(adapter.getTimeoutMs()));
        for (Map.Entry<String, String> header : headerSanitizer.sanitize(adapter.getStaticHeaders()).entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        addAuth(adapter, builder, resolvedSecret);

        String method = adapter.getHttpMethod();
        if (supportsBody(method) && !adapter.getRequestBodyTemplate().isEmpty()) {
            Object renderedBody = renderer.renderJson(adapter.getRequestBodyTemplate(), request);
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(renderedBody)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private void addAuth(BrainToolAdapterConfig adapter, HttpRequest.Builder builder, String resolvedSecret) {
        if ("BEARER_TOKEN".equals(adapter.getAuthMode())) {
            builder.header("Authorization", "Bearer " + resolvedSecret);
        } else if ("API_KEY_HEADER".equals(adapter.getAuthMode())) {
            builder.header(adapter.getApiKeyHeader(), resolvedSecret);
        }
    }

    private String resolveSecretIfNeeded(BrainToolAdapterConfig adapter) {
        if ("BEARER_TOKEN".equals(adapter.getAuthMode()) || "API_KEY_HEADER".equals(adapter.getAuthMode())) {
            return resolveSecret(adapter.getSecretRef());
        }
        return null;
    }

    private String resolveSecret(String secretRef) {
        return secrets.resolve(secretRef)
                .orElseThrow(() -> new IllegalArgumentException("Tool adapter secret is not configured."));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redact(Map<String, Object> data, List<String> secretsToRedact) {
        if (secretsToRedact.isEmpty()) {
            return data;
        }
        Object redacted = secretRedactor.redact(data, secretsToRedact);
        return redacted instanceof Map<?, ?> ? (Map<String, Object>) redacted : data;
    }

    private void recordRun(BrainToolAdapterConfig adapter,
                           DashboardToolDefinition tool,
                           DashboardToolCallRequest request,
                           URI targetUri,
                           DashboardToolStatus status,
                           Integer httpStatusCode,
                           long durationMs,
                           String errorType) {
        try {
            runRecorder.record(adapter, tool, request, targetUri, status, httpStatusCode, durationMs, errorType);
        } catch (Exception e) {
            // An audit-write failure must never turn a real tool result into an error.
            log.warn("Failed to record tool adapter run for tool={}: {}", tool.name(), e.getClass().getSimpleName());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private Map<String, Object> responseData(HttpResponse<String> response) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statusCode", response.statusCode());
        String body = response.body();
        if (body == null || body.isBlank()) {
            return data;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, MAP_TYPE);
            data.putAll(parsed);
        } catch (Exception e) {
            data.put("body", body);
        }
        return data;
    }

    private static boolean supportsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static DashboardToolCallResponse response(DashboardToolStatus status,
                                                      DashboardToolDefinition tool,
                                                      String message,
                                                      Map<String, Object> data) {
        return new DashboardToolCallResponse(status, tool.name(), tool.mode(), message,
                false, null, data, List.of());
    }
}
