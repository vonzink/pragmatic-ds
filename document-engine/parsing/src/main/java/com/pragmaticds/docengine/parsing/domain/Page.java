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
 * One rendered/parsed page (docs/DATA_MODEL.md 4, V4__parsed_content.sql).
 *
 * <p>{@code widthPt}/{@code heightPt} are the ROTATION-0 dimensions in PDF points — canonical
 * space, stored verbatim from the worker. {@code packageId} is denormalized from {@code
 * source_file} so package-wide page ordering is a database constraint, not application hope.
 *
 * <p>Lifecycle: RENDERING creates the row (geometry + render blob), TEXT_EXTRACTION sets the
 * verdict (and uncovered regions for MIXED), OCR_PROCESSING fills the OCR columns. Until the
 * verdict lands, {@code textLayer} holds the {@link TextLayer#NONE} placeholder.
 */
@Entity
@Table(name = "page")
public class Page extends TenantScopedEntity {

    @Column(name = "source_file_id", nullable = false, updatable = false)
    private UUID sourceFileId;

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    /** 0-based within the source file. */
    @Column(name = "page_index", nullable = false, updatable = false)
    private int pageIndex;

    /** 0-based across the whole package — the stable review ordering. */
    @Column(name = "package_page_index", nullable = false, updatable = false)
    private int packagePageIndex;

    @Column(name = "width_pt", nullable = false, updatable = false)
    private BigDecimal widthPt;

    @Column(name = "height_pt", nullable = false, updatable = false)
    private BigDecimal heightPt;

    /** Declared /Rotate in the PDF: 0/90/180/270. */
    @Column(name = "rotation", nullable = false, updatable = false)
    private int rotation;

    /** From Tesseract OSD during OCR. */
    @Column(name = "detected_rotation")
    private Integer detectedRotation;

    @Column(name = "osd_confidence")
    private BigDecimal osdConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_layer", nullable = false)
    private TextLayer textLayer = TextLayer.NONE;

    @Column(name = "is_blank", nullable = false)
    private boolean blank;

    @Column(name = "blank_score")
    private BigDecimal blankScore;

    /**
     * Duplicate-page detection hash. RENDERING seeds it with the PNG sha256 (raster identity, the
     * stage-digest input); PARSING replaces it with the NORMALIZED span-content hash — sha256 over
     * the ordered (text, x, y, width, height) tuples as stored — and nulls it for pages with no
     * spans at all: blank pages are not "duplicates" of each other.
     */
    @Column(name = "content_hash", columnDefinition = "char(64)")
    private String contentHash;

    /**
     * Within the package, pages sharing a non-null content hash: every page after the FIRST
     * (package_page_index order) points at that first page. Null = not a duplicate.
     */
    @Column(name = "duplicate_of_page_id")
    private UUID duplicateOfPageId;

    @Column(name = "render_storage_key")
    private String renderStorageKey;

    @Column(name = "render_dpi")
    private Integer renderDpi;

    /** Winning engine at page level; "NONE" when every gate tripped on both engines. */
    @Column(name = "ocr_engine")
    private String ocrEngine;

    /** Tripped gate (G1_COVERAGE … G6_GEOMETRY), or OCR_LOW_CONFIDENCE when both engines failed. */
    @Column(name = "ocr_fallback_reason")
    private String ocrFallbackReason;

    @Column(name = "ocr_confidence_median")
    private BigDecimal ocrConfidenceMedian;

    @Column(name = "extraction_confidence")
    private BigDecimal extractionConfidence;

    /** /v1/text uncoveredRegions for MIXED pages, serialized JSON. Null otherwise. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "uncovered_regions")
    private String uncoveredRegions;

    /**
     * The worker's per-page layout-coverage declaration: the {@code /v1/layout} {@code
     * notImplemented} list as a JSON array, persisted VERBATIM at PARSING (V18). It names the
     * element types the worker did NOT look for on this page — "an empty result must stay
     * distinguishable from 'did not look'" (WORKER_CONTRACT). {@code []} means full coverage.
     * NULL means no declaration exists: the parse predates the column, PARSING has not run (or
     * failed), or the worker never sent the key. Coverage is unknowable in all three, and the L2
     * read serves {@code UNKNOWN} rather than guessing.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_not_implemented")
    private String layoutNotImplemented;

    protected Page() {
        // JPA
    }

    public Page(
            UUID sourceFileId,
            UUID packageId,
            int pageIndex,
            int packagePageIndex,
            BigDecimal widthPt,
            BigDecimal heightPt,
            int rotation) {
        this.sourceFileId = sourceFileId;
        this.packageId = packageId;
        this.pageIndex = pageIndex;
        this.packagePageIndex = packagePageIndex;
        this.widthPt = widthPt;
        this.heightPt = heightPt;
        this.rotation = rotation;
    }

    /** RENDERING: the PNG blob is written and the row learns where it went. */
    public void recordRender(String storageKey, int dpi, String contentHash) {
        this.renderStorageKey = storageKey;
        this.renderDpi = dpi;
        this.contentHash = contentHash;
    }

    /**
     * TEXT_EXTRACTION: the worker's verdict, uncovered regions when MIXED, and the blank signals —
     * {@code blank_score} is the /v1/text inkFraction verbatim (null when the worker could not
     * render the check), {@code is_blank} derives from the verdict: {@link TextLayer#NONE} means
     * no usable text AND no ink.
     */
    public void applyTextVerdict(
            TextLayer verdict, String uncoveredRegionsJson, BigDecimal inkFraction) {
        this.textLayer = verdict;
        this.uncoveredRegions = uncoveredRegionsJson;
        this.blankScore = inkFraction;
        this.blank = verdict == TextLayer.NONE;
    }

    /** PARSING: span-content hash + package-wide duplicate resolution (both null-able). */
    public void applyContentSignals(String contentHash, UUID duplicateOfPageId) {
        this.contentHash = contentHash;
        this.duplicateOfPageId = duplicateOfPageId;
    }

    /** Reviewer override: this page is not blank after all, so it may be assigned to a document. */
    public void overrideBlank() {
        this.blank = false;
    }

    /** Reviewer override: this page is not a duplicate after all; clears the duplicate pointer. */
    public void overrideDuplicate() {
        this.duplicateOfPageId = null;
    }

    /** OCR_PROCESSING: page-level OCR outcome. Spans carry their own per-span engines. */
    public void applyOcrOutcome(
            Integer detectedRotation,
            BigDecimal osdConfidence,
            String engine,
            String fallbackReason,
            BigDecimal confidenceMedian) {
        this.detectedRotation = detectedRotation;
        this.osdConfidence = osdConfidence;
        this.ocrEngine = engine;
        this.ocrFallbackReason = fallbackReason;
        this.ocrConfidenceMedian = confidenceMedian;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPackagePageIndex() {
        return packagePageIndex;
    }

    public BigDecimal getWidthPt() {
        return widthPt;
    }

    public BigDecimal getHeightPt() {
        return heightPt;
    }

    public int getRotation() {
        return rotation;
    }

    public Integer getDetectedRotation() {
        return detectedRotation;
    }

    public BigDecimal getOsdConfidence() {
        return osdConfidence;
    }

    public TextLayer getTextLayer() {
        return textLayer;
    }

    public boolean isBlank() {
        return blank;
    }

    public BigDecimal getBlankScore() {
        return blankScore;
    }

    public String getContentHash() {
        return contentHash;
    }

    public UUID getDuplicateOfPageId() {
        return duplicateOfPageId;
    }

    public String getRenderStorageKey() {
        return renderStorageKey;
    }

    public Integer getRenderDpi() {
        return renderDpi;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public String getOcrFallbackReason() {
        return ocrFallbackReason;
    }

    public BigDecimal getOcrConfidenceMedian() {
        return ocrConfidenceMedian;
    }

    public BigDecimal getExtractionConfidence() {
        return extractionConfidence;
    }

    public String getUncoveredRegions() {
        return uncoveredRegions;
    }

    public String getLayoutNotImplemented() {
        return layoutNotImplemented;
    }

    /**
     * PARSING owns this signal, like {@link #applyContentSignals}: the stage clears it for every
     * package page at the start of an attempt and sets it per layout page received, so a page
     * missing from an attempt's layout response cannot keep a previous attempt's claim.
     */
    public void applyLayoutCoverage(String notImplementedJson) {
        this.layoutNotImplemented = notImplementedJson;
    }
}
