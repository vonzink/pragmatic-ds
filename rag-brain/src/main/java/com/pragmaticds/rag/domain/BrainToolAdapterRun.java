package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "brain_tool_adapter_runs")
public class BrainToolAdapterRun {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(nullable = false, length = 40)
    private String mode;

    @Column(name = "target_host", length = 255)
    private String targetHost;

    @Column(name = "http_method", nullable = false, length = 12)
    private String httpMethod;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "error_type", length = 160)
    private String errorType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BrainToolAdapterRun() {}

    public BrainToolAdapterRun(UUID brainId,
                               String toolName,
                               String mode,
                               String targetHost,
                               String httpMethod,
                               String status,
                               Integer httpStatusCode,
                               long durationMs,
                               String sessionId,
                               String userId,
                               String tenantId,
                               String errorType) {
        this.brainId = brainId;
        this.toolName = toolName;
        this.mode = mode;
        this.targetHost = targetHost;
        this.httpMethod = httpMethod;
        this.status = status;
        this.httpStatusCode = httpStatusCode;
        this.durationMs = durationMs;
        this.sessionId = sessionId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.errorType = errorType;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getToolName() { return toolName; }
    public String getMode() { return mode; }
    public String getTargetHost() { return targetHost; }
    public String getHttpMethod() { return httpMethod; }
    public String getStatus() { return status; }
    public Integer getHttpStatusCode() { return httpStatusCode; }
    public long getDurationMs() { return durationMs; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getErrorType() { return errorType; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
