package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The brain one Document Engine package belongs to, forever.
 *
 * <p>V34 achieved cross-brain isolation by making package identity globally unique across every
 * registration, which also made reuse impossible. Selecting one immutable parse from several
 * instances is the point of the generalized runtime, so isolation moved here: the package id is
 * this table's primary key, so a second brain cannot claim a package another brain already owns,
 * while any number of instances inside the owning brain may register against it.
 *
 * <p>Identity and one lifecycle timestamp only. Nothing here describes what the package contains.
 */
@Entity
@Table(name = "lab_engine_package_binding")
public class LabEnginePackageBinding {

    @Id
    @Column(name = "engine_package_id", nullable = false, updatable = false)
    private UUID enginePackageId;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "bound_at", nullable = false, updatable = false)
    private OffsetDateTime boundAt;

    protected LabEnginePackageBinding() {}

    public LabEnginePackageBinding(UUID enginePackageId, UUID brainId) {
        this.enginePackageId = enginePackageId;
        this.brainId = brainId;
    }

    @PrePersist
    void onCreate() {
        if (boundAt == null) {
            boundAt = OffsetDateTime.now();
        }
    }

    public UUID getEnginePackageId() { return enginePackageId; }
    public UUID getBrainId() { return brainId; }
    public OffsetDateTime getBoundAt() { return boundAt; }
}
