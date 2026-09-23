package com.pragmaticds.docengine.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One audit event: everything that happened, including reads of sensitive data (docs/DATA_MODEL.md
 * 7, V8). APPEND-ONLY — a row is written once and never updated or deleted.
 *
 * <p>Deliberately NOT a {@code TenantScopedEntity}: like {@code text_span} it uses a bigint
 * identity key (audit is high-volume, nothing addresses an event by id alone) and is append-only
 * (no {@code updated_at} column). It still carries {@code @TenantId org_id}, so Hibernate tenant
 * filtering and Postgres RLS both apply.
 *
 * <p>Two PII invariants are structural, not aspirational: {@link #metadata} is PII-FREE (ids,
 * counts, codes — never a field value or document content) and {@link #ipHash} is HASHED, never a
 * raw address. {@link AuditService} is the only writer and it enforces both.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    // actor_type values (V8 check constraint) — mirror platform.security.ActorType.
    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_API_KEY = "API_KEY";

    // action values (docs/DATA_MODEL.md 7) — the stable, PII-free verbs.
    public static final String ACTION_PACKAGE_UPLOADED = "PACKAGE_UPLOADED";
    public static final String ACTION_DOCUMENT_VIEWED = "DOCUMENT_VIEWED";
    public static final String ACTION_FIELD_CORRECTED = "FIELD_CORRECTED";
    public static final String ACTION_DOCUMENT_REVIEWED = "DOCUMENT_REVIEWED";
    public static final String ACTION_DOCUMENT_RECLASSIFIED = "DOCUMENT_RECLASSIFIED";
    public static final String ACTION_EXPORT_GENERATED = "EXPORT_GENERATED";
    // Phase 7c lifecycle verbs. Metadata stays PII-free: retention-window and row/blob COUNTS only.
    public static final String ACTION_PACKAGE_DELETED = "PACKAGE_DELETED";
    public static final String ACTION_PACKAGE_PURGED = "PACKAGE_PURGED";
    public static final String ACTION_SIGNED_URL_ISSUED = "SIGNED_URL_ISSUED";
    // Spec 2 human-regroup verbs. Metadata stays PII-free: intent + moved/created/deleted COUNTS.
    public static final String ACTION_DOCUMENT_REGROUPED = "DOCUMENT_REGROUPED";
    public static final String ACTION_PAGE_VERDICT_OVERRIDDEN = "PAGE_VERDICT_OVERRIDDEN";
    /** ADMIN-only verified access to an immutable raw machine-result envelope. */
    public static final String ACTION_ENGINE_RESULT_ACCESSED = "ENGINE_RESULT_ACCESSED";
    /**
     * ADMIN-only access to one page's RAW text spans (L1). Its own verb, not
     * {@link #ACTION_DOCUMENT_VIEWED}: a document view is a masked, named projection, while this is
     * every captured word unmasked, and the two must stay separable in an access review. Metadata is
     * ids and counts only — a span's text never appears, which is the whole point of the boundary
     * this event records.
     */
    public static final String ACTION_PAGE_SPANS_ACCESSED = "PAGE_SPANS_ACCESSED";
    /**
     * ADMIN-only access to one page's L2 structure. Separate from
     * {@link #ACTION_PAGE_SPANS_ACCESSED} for the same reason that is separate from
     * {@link #ACTION_DOCUMENT_VIEWED}: an access review must be able to say WHICH representation of
     * the raw layer was read. The structures carry the page's text re-grouped — cell contents,
     * block text — so the boundary and the value-free metadata posture are identical to L1's.
     */
    public static final String ACTION_PAGE_STRUCTURE_ACCESSED = "PAGE_STRUCTURE_ACCESSED";
    /**
     * An upload of already-parsed bytes was answered with the PRIOR package instead of a new
     * parse (parse-once reuse). No stage row is written — a stage row never claims work that did
     * not run — so this event IS the durable record of the decision. Metadata is ids and digests
     * only: {@code reusedPackageId}, {@code engineResultRevision}, {@code behaviorFingerprint}.
     */
    public static final String ACTION_PACKAGE_REUSE_SERVED = "PACKAGE_REUSE_SERVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "actor_type", nullable = false, updatable = false)
    private String actorType;

    /** Null for SYSTEM and API_KEY actors — only a human carries an {@code app_user} id. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "subject_type", updatable = false)
    private String subjectType;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    /** Hashed client IP — never the raw address. */
    @Column(name = "ip_hash", updatable = false)
    private String ipHash;

    /** PII-free JSON: ids, counts, codes only. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private String metadata;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(
            String actorType,
            UUID actorId,
            String action,
            String subjectType,
            UUID subjectId,
            String requestId,
            String ipHash,
            String metadata) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.requestId = requestId;
        this.ipHash = ipHash;
        this.metadata = metadata;
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIpHash() {
        return ipHash;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
