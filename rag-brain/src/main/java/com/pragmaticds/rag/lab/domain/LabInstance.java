package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The mutable administrative identity for one Lab instance within one brain.
 *
 * <p>The identity fields are scalar columns rather than cascade relationships so a Lab operation
 * cannot accidentally write or delete a brain while changing an instance's administrative state.
 */
@Entity
@Table(name = "lab_instance")
public class LabInstance {

    public enum State {
        ACTIVE,
        DISABLED
    }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(nullable = false, length = 32, updatable = false)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 500)
    private String purpose = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected LabInstance() {}

    public LabInstance(UUID brainId, String slug, String displayName, String purpose) {
        this.brainId = brainId;
        this.slug = slug;
        this.displayName = displayName;
        this.purpose = purpose == null ? "" : purpose;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
