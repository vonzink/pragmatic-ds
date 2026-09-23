package com.pragmaticds.docengine.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * Layer 4 of the four value layers: one append-only human review decision (docs/DATA_MODEL.md 7,
 * V8). The audit trail's SUBSTANCE — a correction is a row here, never an in-place overwrite of
 * the machine's Layer-2 {@code extracted_field} value, which stays permanently readable.
 *
 * <p>Not a {@code TenantScopedEntity}: append-only means no {@code updated_at} to map (same
 * reasoning as {@code ClassificationResult}). There is deliberately NO {@code is_current} column —
 * the effective value is derived at READ time (latest {@code CORRECT} wins), so there is nothing
 * to supersede. Still {@code @TenantId org_id}, so tenant filtering and RLS both apply.
 *
 * <p>{@code decidedBy} is NOT NULL: a human is always accountable for a decision. A SYSTEM
 * principal (no {@code userId}) can never construct a valid row — the correction service refuses
 * it before this entity is ever built.
 */
@Entity
@Table(name = "review_decision")
public class ReviewDecision {

    // subject_type values (V8 check constraint).
    public static final String SUBJECT_EXTRACTED_FIELD = "EXTRACTED_FIELD";
    public static final String SUBJECT_LOGICAL_DOCUMENT = "LOGICAL_DOCUMENT";
    public static final String SUBJECT_PAGE_ASSIGNMENT = "PAGE_ASSIGNMENT";
    public static final String SUBJECT_CLASSIFICATION = "CLASSIFICATION";
    public static final String SUBJECT_PAGE = "PAGE";

    // action values (V8 check constraint).
    public static final String ACTION_CONFIRM = "CONFIRM";
    public static final String ACTION_CORRECT = "CORRECT";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_RECLASSIFY = "RECLASSIFY";
    public static final String ACTION_REGROUP = "REGROUP";
    public static final String ACTION_MARK_REVIEWED = "MARK_REVIEWED";
    public static final String ACTION_OVERRIDE_VERDICT = "OVERRIDE_VERDICT";

    @Id @GeneratedValue private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    /** The effective value BEFORE this decision. May carry a field value — masked at read. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_value", updatable = false)
    private String previousValue;

    /** The value the human supplied (CORRECT/RECLASSIFY). Null for CONFIRM/REJECT/MARK_REVIEWED. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private UUID decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected ReviewDecision() {
        // JPA
    }

    public ReviewDecision(
            String subjectType,
            UUID subjectId,
            String action,
            String previousValue,
            String newValue,
            String reason,
            UUID decidedBy) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.action = action;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.reason = reason;
        this.decidedBy = decidedBy;
    }

    @PrePersist
    void onCreate() {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getAction() {
        return action;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
