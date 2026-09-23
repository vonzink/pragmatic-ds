package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * The {@code /v1/ocr} request JSON part. {@code regions} restricts OCR to the given canonical-space
 * boxes (MIXED pages); empty means whole page.
 *
 * <p>{@code rotation} is the page /Rotate the raster was rendered at (render emits display-space
 * pixels). Without it the worker cannot relate the raster frame to the rotation-0 page frame, and
 * OCR boxes on /Rotate pages land a quarter-turn out of frame — Phase 2 review, critical.
 */
public record OcrRequest(
        int pageIndex,
        BigDecimal widthPt,
        BigDecimal heightPt,
        int dpi,
        int rotation,
        List<WireBox> regions) {}
