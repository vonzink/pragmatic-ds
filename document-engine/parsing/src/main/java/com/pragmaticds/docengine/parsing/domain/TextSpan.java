package com.pragmaticds.docengine.parsing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * The atomic unit of traceable text (docs/DATA_MODEL.md 4). THE volume table — ~30k–70k rows per
 * 75-page package.
 *
 * <p>Deliberately NOT a {@link com.pragmaticds.docengine.platform.domain.TenantScopedEntity}: that base
 * assumes a UUID key and maps {@code updated_at}, while text_span uses a bigint identity (half the
 * key width at volume, sequential inserts, and nothing external addresses a span by id alone) and
 * is append-only (no {@code updated_at} column exists). It still carries {@code @TenantId org_id},
 * so application-layer tenant filtering and Postgres RLS both apply exactly as for every other
 * tenant table.
 *
 * <p>Boxes are canonical space, stored VERBATIM from the worker (0.1pt values as sent).
 * {@code ocrEngine} is per SPAN, not per page: reconciliation may mix engines by region, and
 * evidence must name the engine that produced it.
 */
@Entity
@Table(name = "text_span")
public class TextSpan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    /** Reading order within the page. */
    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "text", nullable = false, updatable = false)
    private String text;

    @Column(name = "x", nullable = false, updatable = false)
    private BigDecimal x;

    @Column(name = "y", nullable = false, updatable = false)
    private BigDecimal y;

    @Column(name = "width", nullable = false, updatable = false)
    private BigDecimal width;

    @Column(name = "height", nullable = false, updatable = false)
    private BigDecimal height;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private SpanSource source;

    /** RAPIDOCR · TESSERACT — null for native spans. */
    @Column(name = "ocr_engine", updatable = false)
    private String ocrEngine;

    /** Per-word from OCR; 1.0 for native. */
    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    @Column(name = "font_size", updatable = false)
    private BigDecimal fontSize;

    @Column(name = "font_name", updatable = false)
    private String fontName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TextSpan() {
        // JPA
    }

    public TextSpan(
            UUID pageId,
            int ordinal,
            String text,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            SpanSource source,
            String ocrEngine,
            BigDecimal confidence,
            BigDecimal fontSize,
            String fontName) {
        this.pageId = pageId;
        this.ordinal = ordinal;
        this.text = text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.source = source;
        this.ocrEngine = ocrEngine;
        this.confidence = confidence;
        this.fontSize = fontSize;
        this.fontName = fontName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getPageId() {
        return pageId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getText() {
        return text;
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

    public SpanSource getSource() {
        return source;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public BigDecimal getFontSize() {
        return fontSize;
    }

    public String getFontName() {
        return fontName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
