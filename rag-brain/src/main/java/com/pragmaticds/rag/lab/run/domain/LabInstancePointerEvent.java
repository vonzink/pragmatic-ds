package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One movement of a live pointer, appended and never rewritten.
 *
 * <p>{@link #pointerVersion} is the version the pointer reached, and it is unique per instance, so
 * the history is a total order rather than a set of timestamps that might tie. A promotion that
 * lost its compare-and-set writes no event at all.
 *
 * <p>{@code actorId} and {@code changeReason} are the only free text in this part of the schema.
 * Both are bounded and rejected by the database if they contain control characters — a newline in
 * an audit export is a way to make it show a record that never happened. Borrower-data-shaped
 * input is additionally refused at the DTO boundary; the constraint is the floor, not the policy.
 *
 * <p>Declared {@code @Immutable} with {@code updatable = false} columns so Hibernate can never
 * compose an {@code UPDATE} against this append-only row; see commit {@code 6517c35} and
 * {@link com.pragmaticds.rag.lab.domain.LabInstanceRelease} for the dirty-check round-trip that
 * made the declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_instance_pointer_event")
public class LabInstancePointerEvent {

    public enum Action { PROMOTE, ROLLBACK }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(name = "instance_slug", nullable = false, updatable = false, length = 32)
    private String instanceSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Action action;

    /** Null only for a first promotion, which has no predecessor. A rollback always has one. */
    @Column(name = "from_release_id", updatable = false)
    private UUID fromReleaseId;

    @Column(name = "to_release_id", nullable = false, updatable = false)
    private UUID toReleaseId;

    @Column(name = "pointer_version", nullable = false, updatable = false)
    private long pointerVersion;

    @Column(name = "actor_id", nullable = false, updatable = false, length = 120)
    private String actorId;

    @Column(name = "change_reason", nullable = false, updatable = false, length = 400)
    private String changeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LabInstancePointerEvent() {}

    public LabInstancePointerEvent(UUID brainId, String instanceSlug, Action action,
                                   UUID fromReleaseId, UUID toReleaseId, long pointerVersion,
                                   String actorId, String changeReason) {
        this.brainId = brainId;
        this.instanceSlug = instanceSlug;
        this.action = action;
        this.fromReleaseId = fromReleaseId;
        this.toReleaseId = toReleaseId;
        this.pointerVersion = pointerVersion;
        this.actorId = actorId;
        this.changeReason = changeReason;
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
    public String getInstanceSlug() { return instanceSlug; }
    public Action getAction() { return action; }
    public UUID getFromReleaseId() { return fromReleaseId; }
    public UUID getToReleaseId() { return toReleaseId; }
    public long getPointerVersion() { return pointerVersion; }
    public String getActorId() { return actorId; }
    public String getChangeReason() { return changeReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
