package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed {@code POST /v1/ocr} response.
 *
 * <p>{@code engine} is the page-level winner; {@code "NONE"} means every gate tripped on both
 * engines — still a 200, and the JAVA side decides that means {@code OCR_LOW_CONFIDENCE}.
 *
 * <p>{@code rawRapidocrJson}/{@code rawTesseractJson} are the {@code raw.rapidocr} /
 * {@code raw.tesseract} subtrees re-serialized: BOTH persist to {@code parser_output} when both
 * engines ran ({@code rawTesseractJson} null when no fallback). {@code rawJson} is the whole
 * response body verbatim.
 */
public record OcrResult(
        WorkerBlock worker,
        int pageIndex,
        Integer detectedRotation,
        BigDecimal osdConfidence,
        String engine,
        String fallbackReason,
        BigDecimal confidenceMedian,
        List<OcrSpan> spans,
        String rawRapidocrJson,
        String rawTesseractJson,
        String rawJson) {}
