package com.pragmaticds.docengine.classification.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The split result (docs/DATA_MODEL.md 5): one run of consecutive same-type pages in a package,
 * cut also wherever a page begins a new FORM — a pack may declare an anchor
 * {@code "startsDocument": true}, and a borrower's second Schedule E is a second document rather
 * than a continuation of the first (Spec 5a). {@code classification_confidence} is the MINIMUM of
 * the member pages' confidences — the number review sorts by. Review columns stay at their
 * defaults until Phase 7.
 */
@Entity
@Table(name = "logical_document")
public class LogicalDocument extends TenantScopedEntity {

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** Position of this document within the package's split, 0-based. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "document_type_code", nullable = false)
    private String documentTypeCode;

    @Column(name = "classification_confidence")
    private BigDecimal classificationConfidence;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus = "NOT_REVIEWED";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * How this document's STARTING boundary was decided (V24). Null only for rows written before
     * provenance existed — never for a document this code creates.
     */
    @Column(name = "boundary_provenance")
    private String boundaryProvenance;

    /**
     * How many member pages had no type of their own and were absorbed as continuations by the
     * splitter's UNKNOWN-is-a-continuation rule (V46, issue #60). 0 for a document every page of
     * which classified as its type; null for rows split before the column existed, and for a
     * document a human reshaped — the machine's count no longer describes it, the same rule the
     * nulled confidence follows.
     */
    @Column(name = "absorbed_untyped_pages")
    private Integer absorbedUntypedPages;

    protected LogicalDocument() {
        // JPA
    }

    public LogicalDocument(
            UUID packageId,
            int ordinal,
            String documentTypeCode,
            BigDecimal classificationConfidence,
            String boundaryProvenance) {
        this(packageId, ordinal, documentTypeCode, classificationConfidence, boundaryProvenance, null);
    }

    public LogicalDocument(
            UUID packageId,
            int ordinal,
            String documentTypeCode,
            BigDecimal classificationConfidence,
            String boundaryProvenance,
            Integer absorbedUntypedPages) {
        this.packageId = packageId;
        this.ordinal = ordinal;
        this.documentTypeCode = documentTypeCode;
        this.classificationConfidence = classificationConfidence;
        this.boundaryProvenance = boundaryProvenance;
        this.absorbedUntypedPages = absorbedUntypedPages;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public String getBoundaryProvenance() {
        return boundaryProvenance;
    }

    public Integer getAbsorbedUntypedPages() {
        return absorbedUntypedPages;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getDocumentTypeCode() {
        return documentTypeCode;
    }

    public BigDecimal getClassificationConfidence() {
        return classificationConfidence;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    // review_status CHECK values (V6).
    /**
     * How a document's starting boundary was decided, strongest evidence first. Mirrors the V24
     * CHECK constraint; {@link #BOUNDARY_AI} has no producer until Phase D.
     */
    public static final String BOUNDARY_HUMAN = "HUMAN";

    /** A page matched a pack anchor declaring {@code "startsDocument": true} — proof of a header. */
    public static final String BOUNDARY_RULE = "RULE";

    /**
     * A second instance of the SAME type begins here: the document's own printed identity — the
     * key its extraction schema declares, e.g. a bank statement's period — changed on this page
     * (Phase C, V25). Deterministic like {@link #BOUNDARY_RULE}, and kept distinct from it because
     * a reviewer learns something different: not "a form header was found" but "this is the next
     * statement". It is the only cut that can separate two documents no anchor distinguishes.
     */
    public static final String BOUNDARY_INSTANCE_CHANGE = "INSTANCE_CHANGE";

    /** This document opens the package: structural, not inferred. */
    public static final String BOUNDARY_PACKAGE_START = "PACKAGE_START";

    /** The page type changed between two typed pages — real evidence, blind to same-type seams. */
    public static final String BOUNDARY_TYPE_CHANGE = "TYPE_CHANGE";

    /** Reserved for Phase D boundary extraction. */
    public static final String BOUNDARY_AI = "AI";

    public static final String REVIEW_NOT_REVIEWED = "NOT_REVIEWED";
    public static final String REVIEW_IN_REVIEW = "IN_REVIEW";
    public static final String REVIEW_REVIEWED = "REVIEWED";

    /**
     * MARK_REVIEWED (Phase 7b): a reviewer signs off on the document. Sets the sanctioned mutable
     * review columns in place; the human decision itself is recorded as an append-only
     * {@code review_decision} row by the caller, which is the accountable audit substance.
     */
    public void markReviewed(UUID reviewedBy, Instant reviewedAt) {
        this.reviewStatus = REVIEW_REVIEWED;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }

    /**
     * RECLASSIFY (Phase 7b): a reviewer overrides the machine's document type. Updates the type in
     * place; the previous -> new transition is captured in the append-only {@code review_decision}
     * row the caller writes. Re-running extraction is out of scope — this records the human call.
     */
    public void reclassify(String documentTypeCode) {
        this.documentTypeCode = documentTypeCode;
    }

    /**
     * The reviewer reshaped this document (created it, merged into it, moved its pages, or split it).
     * The machine's classification no longer describes it, so confidence is nulled — a fabricated
     * score would be a lie — and the declared type stands. The prior state is captured in the
     * append-only {@code review_decision} the caller writes (Spec 2 D4).
     */
    public void humanRegroup(String documentTypeCode) {
        this.documentTypeCode = documentTypeCode;
        this.classificationConfidence = null;
        this.reviewStatus = REVIEW_IN_REVIEW;
        // The boundary is now a human's, whatever decided it before. This is the same rule the
        // nulled confidence expresses: the machine's account of this document no longer applies.
        this.boundaryProvenance = BOUNDARY_HUMAN;
        // And neither does its absorbed-page count: the pages a reviewer moved in or out were
        // placed on purpose, so "absorbed" would describe a document that no longer exists.
        this.absorbedUntypedPages = null;
    }
}
