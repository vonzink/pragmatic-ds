package com.pragmaticds.docengine.classification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One classification attempt (docs/DATA_MODEL.md 5). APPEND-ONLY: reclassification writes a new
 * row and flips the prior row's {@code is_current} off — it never updates or deletes an attempt.
 * That supersession flip is the single sanctioned mutation, owned by {@code
 * ClassificationResultRepository#supersedeCurrent}.
 *
 * <p>Not a {@code TenantScopedEntity}: append-only means no {@code updated_at} column exists to
 * map (same reasoning as {@code TextSpan}). Still {@code @TenantId org_id}, so application-layer
 * tenant filtering and RLS both apply.
 *
 * <p>{@code evidence} carries matched anchors as span IDS + boxes + text OFFSETS — never matched
 * text, never page text (Phase 4 rule 3).
 */
@Entity
@Table(name = "classification_result")
public class ClassificationResult {

    /** {@code subject_type} values (V6 check constraint). */
    public static final String SUBJECT_PAGE = "PAGE";

    public static final String SUBJECT_LOGICAL_DOCUMENT = "LOGICAL_DOCUMENT";

    /** {@code method} value for everything Phase 4 produces. */
    public static final String METHOD_RULE_ANCHOR = "RULE_ANCHOR";

    /**
     * {@code method} value for the MODEL FALLBACK — a page no rule pack could type, retyped from a
     * quote the engine verified against that page's own spans. Already permitted by the V6 check
     * constraint ({@code RULE_ANCHOR|ML|LLM|HUMAN}), so naming it here needs no migration.
     *
     * <p>A distinct method rather than a flavour of {@link #METHOD_RULE_ANCHOR} because everything
     * downstream — the reuse fingerprint, review triage, the "widen the packs" mining loop — has to
     * tell a free deterministic verdict from a paid model one, and "{@code rule_pack_version} is
     * null" is an inference, not an answer.
     */
    public static final String METHOD_LLM = "LLM";

    @Id @GeneratedValue private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "document_type_code", nullable = false, updatable = false)
    private String documentTypeCode;

    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    @Column(name = "method", nullable = false, updatable = false)
    private String method;

    @Column(name = "rule_pack_version", updatable = false)
    private String rulePackVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, updatable = false)
    private String evidence;

    /** The one mutable column — flipped off by supersession, never back on. */
    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    /** Null = system (the rule classifier). Human calls arrive in Phase 7. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClassificationResult() {
        // JPA
    }

    public ClassificationResult(
            String subjectType,
            UUID subjectId,
            String documentTypeCode,
            BigDecimal confidence,
            String method,
            String rulePackVersion,
            String evidence) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.documentTypeCode = documentTypeCode;
        this.confidence = confidence;
        this.method = method;
        this.rulePackVersion = rulePackVersion;
        this.evidence = evidence;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
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

    public String getDocumentTypeCode() {
        return documentTypeCode;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getMethod() {
        return method;
    }

    public String getRulePackVersion() {
        return rulePackVersion;
    }

    public String getEvidence() {
        return evidence;
    }

    public boolean isCurrent() {
        return current;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
