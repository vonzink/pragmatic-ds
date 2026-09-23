package com.pragmaticds.docengine.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every tenant-owned entity: UUID key, {@code @TenantId org_id}, timestamps.
 *
 * <p>⚠️ {@code @TenantId} filters JPQL/criteria reads and stamps writes, but it does NOT filter
 * {@code find()}-by-primary-key. Load tenant-scoped entities with {@code findByIdAndOrgId}, never
 * {@code findById} — the host-app rule, and the mistake its history warns about.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
