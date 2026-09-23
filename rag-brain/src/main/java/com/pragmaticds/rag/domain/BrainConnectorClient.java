package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "brain_connector_clients")
public class BrainConnectorClient {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "brain_id")
    private UUID brainId;

    @Column(name = "token_hash", length = 128, unique = true)
    private String tokenHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> scopes = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_origins", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedOrigins = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_peer_hosts", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedPeerHosts = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_tenants", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedTenants = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_permissions", nullable = false, columnDefinition = "jsonb")
    private List<String> grantedPermissions = new ArrayList<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BrainConnectorClient() {}

    public BrainConnectorClient(UUID id, String name, String type, String tokenHash) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.tokenHash = tokenHash;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes); }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
    public List<String> getAllowedPeerHosts() { return allowedPeerHosts; }
    public void setAllowedPeerHosts(List<String> allowedPeerHosts) {
        this.allowedPeerHosts = allowedPeerHosts == null ? new ArrayList<>() : new ArrayList<>(allowedPeerHosts);
    }
    public List<String> getAllowedTenants() { return allowedTenants; }
    public void setAllowedTenants(List<String> allowedTenants) {
        this.allowedTenants = allowedTenants == null ? new ArrayList<>() : new ArrayList<>(allowedTenants);
    }
    public List<String> getGrantedPermissions() { return grantedPermissions; }
    public void setGrantedPermissions(List<String> grantedPermissions) {
        this.grantedPermissions = grantedPermissions == null ? new ArrayList<>() : new ArrayList<>(grantedPermissions);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
