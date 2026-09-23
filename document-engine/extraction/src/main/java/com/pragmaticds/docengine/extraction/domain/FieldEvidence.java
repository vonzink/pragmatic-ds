package com.pragmaticds.docengine.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * The traceability spine (docs/DATA_MODEL.md 6, V7): one evidence box behind one extracted field.
 * APPEND-ONLY — evidence is replaced wholesale with its field on EXTRACTING retry, never edited.
 *
 * <p>Boxes are DENORMALIZED (D10): a reparse regenerates spans and layout elements, and the box a
 * human already reviewed must not silently move underneath them. {@code textSpanId} and
 * {@code layoutElementId} may go NULL on reparse; the coordinates never do.
 *
 * <p>Not a {@code TenantScopedEntity}: append-only means no {@code updated_at} column exists to
 * map (same reasoning as {@code ClassificationResult}). Still {@code @TenantId org_id}, so
 * application-layer tenant filtering and RLS both apply.
 */
@Entity
@Table(name = "field_evidence")
public class FieldEvidence {

    // role CHECK values (V7). The label is evidence: "$3,565.87" alone proves nothing —
    // "Net Pay" to its left is the entire reason it was read as net pay.
    public static final String ROLE_VALUE = "VALUE";
    public static final String ROLE_LABEL = "LABEL";
    public static final String ROLE_CONTEXT = "CONTEXT";

    @Id @GeneratedValue private UUID id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "extracted_field_id", nullable = false, updatable = false)
    private UUID extractedFieldId;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    /** The supporting layout element (TABLE_CLUSTER cells); null otherwise. */
    @Column(name = "layout_element_id", updatable = false)
    private UUID layoutElementId;

    @Column(name = "text_span_id", updatable = false)
    private Long textSpanId;

    @Column(name = "x", nullable = false, updatable = false)
    private BigDecimal x;

    @Column(name = "y", nullable = false, updatable = false)
    private BigDecimal y;

    @Column(name = "width", nullable = false, updatable = false)
    private BigDecimal width;

    @Column(name = "height", nullable = false, updatable = false)
    private BigDecimal height;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    /** Position within the field's evidence of this role, 0-based. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FieldEvidence() {
        // JPA
    }

    public FieldEvidence(
            UUID extractedFieldId,
            UUID pageId,
            UUID layoutElementId,
            Long textSpanId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            String role,
            int ordinal) {
        this.extractedFieldId = extractedFieldId;
        this.pageId = pageId;
        this.layoutElementId = layoutElementId;
        this.textSpanId = textSpanId;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.role = role;
        this.ordinal = ordinal;
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

    public UUID getExtractedFieldId() {
        return extractedFieldId;
    }

    public UUID getPageId() {
        return pageId;
    }

    public UUID getLayoutElementId() {
        return layoutElementId;
    }

    public Long getTextSpanId() {
        return textSpanId;
    }

    public BigDecimal getX() {
        return x;
    }

    public BigDecimal getY() {
        return y;
    }

    public BigDecimal getWidth() {
        return width;
    }

    public BigDecimal getHeight() {
        return height;
    }

    public String getRole() {
        return role;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
