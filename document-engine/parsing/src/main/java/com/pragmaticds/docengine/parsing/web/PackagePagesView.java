package com.pragmaticds.docengine.parsing.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The page geometry the review UI needs to place evidence boxes. Coordinates are the canonical
 * space every stored box speaks: PDF points, top-left origin, at rotation-0.
 *
 * <p>{@code widthPt}/{@code heightPt}/{@code rotation} are what lets a client convert a stored box
 * into viewport pixels at any zoom. Without them a bounding box is an unplaceable pair of numbers,
 * which is why this endpoint exists at all — no other endpoint carries page geometry.
 */
public record PackagePagesView(UUID packageId, List<PageView> pages) {

    /**
     * @param sourceFileId which uploaded file this page came from, and {@code pageIndex} its
     *     0-based position inside THAT file — a package may hold several PDFs, so a client
     *     rendering the original bytes needs both
     * @param packagePageIndex 0-based position across the whole package: the stable review order
     * @param rotation the page's declared {@code /Rotate}; a viewer must apply it, and evidence
     *     boxes are stored pre-rotation (rotation-0), never in the rotated display frame
     * @param renderDpi the DPI of the stored raster, null when no render exists yet
     * @param hasRender whether {@code GET /v1/pages/{id}/render} will serve bytes for this page
     * @param sourceContentType the sniffed type of the file this page came from
     *     ({@code application/pdf}, {@code image/jpeg}, {@code image/png}, {@code image/tiff},
     *     {@code image/heic}). A
     *     client rendering the original bytes needs to know which renderer applies: an image source
     *     has no PDF to hand pdf.js, and it is the commonest upload there is
     */
    public record PageView(
            UUID pageId,
            UUID sourceFileId,
            int pageIndex,
            int packagePageIndex,
            BigDecimal widthPt,
            BigDecimal heightPt,
            int rotation,
            Integer detectedRotation,
            Integer renderDpi,
            boolean hasRender,
            String textLayer,
            boolean blank,
            UUID duplicateOfPageId,
            String sourceContentType) {}
}
