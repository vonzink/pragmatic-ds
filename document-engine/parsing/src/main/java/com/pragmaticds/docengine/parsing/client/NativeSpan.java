package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;

/** One native word from {@code /v1/text}: pdfplumber word boxes, canonical space, verbatim. */
public record NativeSpan(
        int ordinal,
        String text,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        BigDecimal fontSize,
        String fontName) {}
