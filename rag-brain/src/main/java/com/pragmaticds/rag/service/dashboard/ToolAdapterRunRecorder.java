package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.domain.BrainToolAdapterRun;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.repository.BrainToolAdapterRunRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.regex.Pattern;

@Service
public class ToolAdapterRunRecorder {

    private static final Pattern SAFE_ERROR_CLASS_NAME = Pattern.compile(
            "(?=.{1,160}$)(?:[a-z_][A-Za-z0-9_]*\\.)*[A-Z][A-Za-z0-9]*(?:Exception|Error)");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("HTTP_[0-9]{3}|DNS_FAIL");
    private static final String SANITIZED_ERROR_TYPE = "SanitizedError";

    private final BrainToolAdapterRunRepository repo;

    public ToolAdapterRunRecorder(BrainToolAdapterRunRepository repo) {
        this.repo = repo;
    }

    public void record(BrainToolAdapterConfig adapter,
                       DashboardToolDefinition tool,
                       DashboardToolCallRequest request,
                       URI targetUri,
                       DashboardToolStatus status,
                       Integer httpStatusCode,
                       long durationMs,
                       String errorType) {
        String sessionId = request == null ? null : request.sessionId();
        String userId = request == null || request.user() == null ? null : request.user().userId();
        String tenantId = request == null || request.user() == null ? null : request.user().tenantId();
        String targetHost = targetUri == null ? null : targetUri.getHost();
        repo.save(new BrainToolAdapterRun(adapter.getBrainId(), tool.name(), tool.mode().name(),
                targetHost, adapter.getHttpMethod(), status.name(), httpStatusCode,
                Math.max(0, durationMs), sessionId, userId, tenantId, sanitizeErrorType(errorType)));
    }

    private String sanitizeErrorType(String errorType) {
        if (errorType == null || errorType.isBlank()) {
            return null;
        }
        if (SAFE_ERROR_CLASS_NAME.matcher(errorType).matches() || SAFE_ERROR_CODE.matcher(errorType).matches()) {
            return errorType;
        }
        return SANITIZED_ERROR_TYPE;
    }
}
