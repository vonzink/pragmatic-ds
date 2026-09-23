package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;

/**
 * One OCR word from {@code /v1/ocr}. {@code engine} is PER SPAN — reconciliation may mix engines
 * by region, and evidence must name the engine that produced it.
 */
public record OcrSpan(
        int ordinal,
        String text,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        String engine,
        BigDecimal confidence) {}
