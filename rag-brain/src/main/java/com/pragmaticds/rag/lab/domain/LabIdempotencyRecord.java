package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable receipt for a completed Lab mutation.
 *
 * <p>Only a canonical request hash and durable result identity are retained. Request and response
 * bodies deliberately have no column or object field in this model.
 */
@Entity
@Immutable
@Table(name = "lab_idempotency_record")
public class LabIdempotencyRecord {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(nullable = false, length = 80, updatable = false)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;

    @Column(name = "result_kind", nullable = false, length = 40, updatable = false)
    private String resultKind;

    @Column(name = "result_id", nullable = false, updatable = false)
    private UUID resultId;

    @Column(name = "result_version", updatable = false)
    private Long resultVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LabIdempotencyRecord() {}

    public LabIdempotencyRecord(UUID brainId, String operation, String idempotencyKey,
                                String requestSha256, String resultKind, UUID resultId,
                                Long resultVersion) {
        this.brainId = brainId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestSha256 = requestSha256;
        this.resultKind = resultKind;
        this.resultId = resultId;
        this.resultVersion = resultVersion;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestSha256() { return requestSha256; }
    public String getResultKind() { return resultKind; }
    public UUID getResultId() { return resultId; }
    public Long getResultVersion() { return resultVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
