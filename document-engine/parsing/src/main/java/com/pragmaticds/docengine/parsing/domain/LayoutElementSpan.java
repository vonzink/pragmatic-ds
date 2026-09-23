package com.pragmaticds.docengine.parsing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Element → span link (docs/DATA_MODEL.md 4): a join table rather than a span range so regrouping
 * stays possible. Composite PK {@code (layout_element_id, text_span_id)}; {@code ordinal} is the
 * span's position WITHIN the element (the wire's spanOrdinals order).
 *
 * <p>Not a {@link com.pragmaticds.docengine.platform.domain.TenantScopedEntity}: the table has a
 * composite natural key and no surrogate id or timestamps. It still carries {@code @TenantId
 * org_id} — a join table is still tenant data (V4's own comment), so application-layer tenant
 * filtering and RLS both apply.
 */
@Entity
@Table(name = "layout_element_span")
@IdClass(LayoutElementSpanId.class)
public class LayoutElementSpan {

    @Id
    @Column(name = "layout_element_id", nullable = false, updatable = false)
    private UUID layoutElementId;

    @Id
    @Column(name = "text_span_id", nullable = false, updatable = false)
    private Long textSpanId;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** Position of the span within the element, per the wire's spanOrdinals order. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    protected LayoutElementSpan() {
        // JPA
    }

    public LayoutElementSpan(UUID layoutElementId, Long textSpanId, int ordinal) {
        this.layoutElementId = layoutElementId;
        this.textSpanId = textSpanId;
        this.ordinal = ordinal;
    }

    public UUID getLayoutElementId() {
        return layoutElementId;
    }

    public Long getTextSpanId() {
        return textSpanId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public int getOrdinal() {
        return ordinal;
    }
}
