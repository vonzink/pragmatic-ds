package com.pragmaticds.rag.domain;

import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One dashboard tool's contract: name, mode, permissions, and its JSON-schema input shape —
 * editable configuration, so this row is legitimately mutable and must NOT be declared
 * {@code @Immutable}.
 *
 * <p><b>JSON value types are load-bearing</b> (commit {@code 6517c35}): Hibernate dirty-checks
 * {@code inputSchema} and {@code requiredPermissions} by round-tripping them through the Jackson
 * format mapper, and a value that does not survive that trip ({@code Long}, {@code BigDecimal})
 * makes every managed row permanently "dirty" — rewriting itself (and bumping {@code updated_at})
 * on unrelated flushes. The numeric schema constraints ({@code maxLength} and friends) arrive
 * Jackson-parsed from request bodies, and what Jackson parses it round-trips stably, so no
 * false-dirty fires today; keep it that way if any code ever builds these maps in Java. A
 * structural fix is deferred until a real symptom shows.
 */
@Entity
@Table(name = "brain_tool_definitions")
public class BrainToolDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DashboardToolMode mode;

    @Column(name = "confirmation_required", nullable = false)
    private boolean confirmationRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_permissions", nullable = false, columnDefinition = "jsonb")
    private List<String> requiredPermissions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSchema = new LinkedHashMap<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    protected BrainToolDefinition() {}

    public BrainToolDefinition(UUID brainId,
                               String name,
                               String description,
                               DashboardToolMode mode,
                               boolean confirmationRequired,
                               List<String> requiredPermissions,
                               Map<String, Object> inputSchema,
                               String createdBy) {
        this.brainId = brainId;
        this.name = name;
        this.description = description;
        this.mode = mode;
        this.confirmationRequired = confirmationRequired;
        this.requiredPermissions = requiredPermissions == null
                ? new ArrayList<>()
                : new ArrayList<>(requiredPermissions);
        this.inputSchema = inputSchema == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(inputSchema);
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public DashboardToolMode getMode() { return mode; }
    public void setMode(DashboardToolMode mode) { this.mode = mode; }
    public boolean isConfirmationRequired() { return confirmationRequired; }
    public void setConfirmationRequired(boolean confirmationRequired) { this.confirmationRequired = confirmationRequired; }
    public List<String> getRequiredPermissions() { return requiredPermissions; }
    public void setRequiredPermissions(List<String> requiredPermissions) {
        this.requiredPermissions = requiredPermissions == null
                ? new ArrayList<>()
                : new ArrayList<>(requiredPermissions);
    }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(inputSchema);
    }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
