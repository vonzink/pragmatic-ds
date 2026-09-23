package com.pragmaticds.rag.lab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only Lab audit row: action and status, the brain and subject it concerns, a bounded
 * proxy actor and correlation id, and count/boolean metadata.
 *
 * <p>Never a parsed value, filename, prompt, body, ciphertext, policy-forbidden hash, or
 * credential. {@link #getMetadata()} is width-limited by the database, not by convention: V34's
 * {@code lab_metadata_is_counts_only} check refuses any JSON member that is not a number or a
 * boolean, so a string cannot be smuggled into the audit trail.
 *
 * <p>A purge appends a value-free tombstone here rather than deleting history, which is why the
 * table refuses {@code UPDATE} and {@code DELETE} alike.
 *
 * <p><b>The mapping says append-only too, and it has to.</b> {@link #getMetadata()} is a mutable
 * {@code Map} behind a JSON type: Hibernate builds its dirty-check snapshot by round-tripping the
 * map through the format mapper, which returns a {@code Long} count as an {@code Integer}. The
 * snapshot then never equals the value it was copied from, so the same flush that inserts the row
 * also schedules an {@code UPDATE} of it, V34's trigger refuses that with {@code LAB_ROW_IMMUTABLE}
 * and the whole independent audit transaction rolls back — losing the row entirely, and quietly,
 * because {@link com.pragmaticds.rag.lab.service.LabAuditService#record} swallows a write failure by
 * design. Every audit row carrying a {@code long} count (a pointer version, a collection version)
 * was exposed to that. {@code @Immutable} stops the UPDATE being composed at all.
 */
@Entity
@Immutable
@Table(name = "lab_audit_event")
public class LabAuditEvent {

    /** The outcome being recorded. */
    public enum Status {
        SUCCEEDED,
        FAILED,
        DENIED
    }

    /** What the action concerned. */
    public enum SubjectType {
        INSTANCE,
        RELEASE,
        REGISTRATION,
        ENVELOPE,
        RUN,
        EXCHANGE,
        COLLECTION,
        SNAPSHOT,
        GROUP
    }

    /**
     * The prototype's explicit actor when only the shared admin key authenticated the request.
     * Named honestly: there is no user identity to record until Cognito/OIDC lands.
     */
    public static final String ANONYMOUS_ADMIN_ACTOR = "anonymous-admin-prototype";

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "brain_id", nullable = false, updatable = false)
    private UUID brainId;

    @Column(nullable = false, updatable = false, length = 48)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 24)
    private SubjectType subjectType;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(nullable = false, updatable = false, length = 64)
    private String actor;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "failure_code", updatable = false, length = 64)
    private String failureCode;

    /** Counts and booleans only — enforced by the database, not by this comment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

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
    public void setId(UUID id) { this.id = id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public SubjectType getSubjectType() { return subjectType; }
    public void setSubjectType(SubjectType subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
