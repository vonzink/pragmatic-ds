package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * One page of the {@code /v1/text} response: geometry, the text-layer verdict, native word spans,
 * and — for MIXED pages only — the uncovered regions OCR must handle ({@code null} otherwise; the
 * contract omits the field for NATIVE/SCANNED/NONE).
 *
 * <p>{@code inkFraction} is the blank-page signal: the fraction of dark pixels at a coarse render,
 * persisted as {@code page.blank_score}. {@code null} when the page could not be rendered for the
 * check (and tolerated as absent for older worker responses).
 */
public record TextPage(
        int pageIndex,
        BigDecimal widthPt,
        BigDecimal heightPt,
        int rotation,
        String verdict,
        List<NativeSpan> spans,
        List<WireBox> uncoveredRegions,
        BigDecimal inkFraction) {}
