package com.pragmaticds.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "brain_connector_events")
public class BrainConnectorEvent {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "connector_client_id")
    private UUID connectorClientId;

    @Column(name = "brain_id")
    private UUID brainId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 80)
    private String scope;

    @Column(name = "request_host", length = 255)
    private String requestHost;

    @Column(name = "request_id", length = 120)
    private String requestId;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BrainConnectorEvent() {}

    public BrainConnectorEvent(UUID id, UUID connectorClientId, UUID brainId,
                               String eventType, String scope, String requestHost, String status) {
        this.id = id;
        this.connectorClientId = connectorClientId;
        this.brainId = brainId;
        this.eventType = eventType;
        this.scope = scope;
        this.requestHost = requestHost;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getConnectorClientId() { return connectorClientId; }
    public void setConnectorClientId(UUID connectorClientId) { this.connectorClientId = connectorClientId; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getRequestHost() { return requestHost; }
    public void setRequestHost(String requestHost) { this.requestHost = requestHost; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
