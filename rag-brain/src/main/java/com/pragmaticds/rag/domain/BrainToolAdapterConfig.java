package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How one dashboard tool call becomes an outbound HTTP request — editable configuration, so this
 * row is legitimately mutable and must NOT be declared {@code @Immutable}.
 *
 * <p><b>JSON value types are load-bearing</b> (commit {@code 6517c35}): Hibernate dirty-checks
 * {@code staticHeaders}, {@code requestBodyTemplate}, and {@code allowedHosts} by round-tripping
 * them through the Jackson format mapper, and a value that does not survive that trip
 * ({@code Long}, {@code BigDecimal}) makes every managed row permanently "dirty" — rewriting
 * itself (and bumping {@code updated_at}) on unrelated flushes, potentially clobbering a
 * concurrent edit. Today every value arrives Jackson-parsed from a request body, and what
 * Jackson parses it round-trips stably, so no false-dirty fires; keep it that way if any code
 * ever builds these maps in Java. A structural fix is deferred until a real symptom shows.
 */
@Entity
@Table(name = "brain_tool_adapters")
public class BrainToolAdapterConfig {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "http_method", nullable = false, length = 12)
    private String httpMethod;

    @Column(name = "url_template", nullable = false, length = 2000)
    private String urlTemplate;

    @Column(name = "auth_mode", nullable = false, length = 40)
    private String authMode;

    @Column(name = "secret_ref", length = 160)
    private String secretRef;

    @Column(name = "api_key_header", length = 160)
    private String apiKeyHeader;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "static_headers", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> staticHeaders = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body_template", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestBodyTemplate = new LinkedHashMap<>();

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 5000;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_hosts", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedHosts = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    protected BrainToolAdapterConfig() {}

    public BrainToolAdapterConfig(UUID brainId,
                                  String toolName,
                                  boolean enabled,
                                  String httpMethod,
                                  String urlTemplate,
                                  String authMode,
                                  String secretRef,
                                  String apiKeyHeader,
                                  Map<String, Object> staticHeaders,
                                  Map<String, Object> requestBodyTemplate,
                                  int timeoutMs,
                                  List<String> allowedHosts,
                                  String actor) {
        this.brainId = brainId;
        this.toolName = toolName;
        this.enabled = enabled;
        this.httpMethod = httpMethod;
        this.urlTemplate = urlTemplate;
        this.authMode = authMode;
        this.secretRef = secretRef;
        this.apiKeyHeader = apiKeyHeader;
        setStaticHeaders(staticHeaders);
        setRequestBodyTemplate(requestBodyTemplate);
        this.timeoutMs = timeoutMs;
        setAllowedHosts(allowedHosts);
        this.createdBy = actor;
        this.updatedBy = actor;
    }

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getUrlTemplate() { return urlTemplate; }
    public void setUrlTemplate(String urlTemplate) { this.urlTemplate = urlTemplate; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public String getApiKeyHeader() { return apiKeyHeader; }
    public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
    public Map<String, Object> getStaticHeaders() { return staticHeaders; }
    public void setStaticHeaders(Map<String, Object> staticHeaders) {
        this.staticHeaders = staticHeaders == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(staticHeaders);
    }
    public Map<String, Object> getRequestBodyTemplate() { return requestBodyTemplate; }
    public void setRequestBodyTemplate(Map<String, Object> requestBodyTemplate) {
        this.requestBodyTemplate = requestBodyTemplate == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(requestBodyTemplate);
    }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public List<String> getAllowedHosts() { return List.copyOf(allowedHosts); }
    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
