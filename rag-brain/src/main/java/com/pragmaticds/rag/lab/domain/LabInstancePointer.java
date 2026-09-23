package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The one production release a brain's Lab instance runs against.
 *
 * <p>Runs resolve this pointer and then use the exact stored release, so mutating the live
 * analyzer pack after a release exists cannot change what an existing instance executes. Drift
 * appears as a separate {@link LabInstanceRelease.ProvenanceMode#CANDIDATE} release and never
 * silently advances this pointer.
 */
@Entity
@Table(name = "lab_instance_pointer")
@IdClass(LabInstancePointerId.class)
public class LabInstancePointer {

    @Id
    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Id
    @Column(name = "instance_slug", nullable = false, length = 32)
    private String instanceSlug;

    @Column(name = "production_release_id", nullable = false)
    private UUID productionReleaseId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Compare-and-set guard for live promotion. A promotion updates the pointer only where the
     * caller's expected release AND this version still match, so two administrators promoting
     * different candidates cannot silently overwrite each other — the loser is told the pointer
     * moved instead of winning by arriving second.
     */
    @Column(name = "pointer_version", nullable = false)
    private long pointerVersion;

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getInstanceSlug() { return instanceSlug; }
    public void setInstanceSlug(String instanceSlug) { this.instanceSlug = instanceSlug; }
    public UUID getProductionReleaseId() { return productionReleaseId; }
    public void setProductionReleaseId(UUID productionReleaseId) { this.productionReleaseId = productionReleaseId; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public long getPointerVersion() { return pointerVersion; }
    public void setPointerVersion(long pointerVersion) { this.pointerVersion = pointerVersion; }
}
