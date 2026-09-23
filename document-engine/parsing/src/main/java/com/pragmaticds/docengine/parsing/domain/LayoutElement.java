package com.pragmaticds.docengine.parsing.domain;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One layout element on a page (docs/DATA_MODEL.md 4, V4__parsed_content.sql). The box is
 * CANONICAL space stored verbatim from the worker; {@code parentElementId} expresses table → row →
 * cell nesting as a self-FK resolved at persistence time from the wire's request-scoped element
 * ids; {@code detector}/{@code detectorVersion} name what produced the element — acceptance
 * criterion 4 of Phase 3, never defaulted.
 *
 * <p>Rows are replaced wholesale on PARSING retry (delete-then-persist, like text spans), never
 * mutated in place.
 */
@Entity
@Table(name = "layout_element")
public class LayoutElement extends TenantScopedEntity {

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    /** Table → row → cell nesting; null for top-level elements. */
    @Column(name = "parent_element_id", updatable = false)
    private UUID parentElementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "element_type", nullable = false, updatable = false)
    private LayoutElementType elementType;

    /** Reading order across the page's elements (multi-column pages order column-major). */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "x", nullable = false, updatable = false)
    private BigDecimal x;

    @Column(name = "y", nullable = false, updatable = false)
    private BigDecimal y;

    @Column(name = "width", nullable = false, updatable = false)
    private BigDecimal width;

    @Column(name = "height", nullable = false, updatable = false)
    private BigDecimal height;

    /** Denormalized convenience: linked span texts in link order; null when the element has none. */
    @Column(name = "text", updatable = false)
    private String text;

    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    @Column(name = "detector", nullable = false, updatable = false)
    private String detector;

    @Column(name = "detector_version", nullable = false, updatable = false)
    private String detectorVersion;

    /** Worker attributes object, serialized JSON (e.g. {@code {"row":0,"col":0}}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", updatable = false)
    private String attributes;

    protected LayoutElement() {
        // JPA
    }

    public LayoutElement(
            UUID pageId,
            UUID parentElementId,
            LayoutElementType elementType,
            int ordinal,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            String text,
            BigDecimal confidence,
            String detector,
            String detectorVersion,
            String attributes) {
        this.pageId = pageId;
        this.parentElementId = parentElementId;
        this.elementType = elementType;
        this.ordinal = ordinal;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.text = text;
        this.confidence = confidence;
        this.detector = detector;
        this.detectorVersion = detectorVersion;
        this.attributes = attributes;
    }

    public UUID getPageId() {
        return pageId;
    }

    public UUID getParentElementId() {
        return parentElementId;
    }

    public LayoutElementType getElementType() {
        return elementType;
    }

    public int getOrdinal() {
        return ordinal;
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

    public String getText() {
        return text;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getDetector() {
        return detector;
    }

    public String getDetectorVersion() {
        return detectorVersion;
    }

    public String getAttributes() {
        return attributes;
    }
}
