package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;

/**
 * One page's metadata from the {@code /v1/render} multipart response. {@code widthPt}/{@code
 * heightPt} are the ROTATION-0 box (canonical space); pixels are rendered at the declared
 * {@code rotation} — what a viewer shows.
 */
public record RenderPageMeta(
        int pageIndex,
        BigDecimal widthPt,
        BigDecimal heightPt,
        int rotation,
        int dpi,
        int widthPx,
        int heightPx,
        String pngPart) {}
