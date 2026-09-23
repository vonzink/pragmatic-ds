package com.pragmaticds.docengine.parsing.service;

import com.pragmaticds.docengine.parsing.client.OcrSpan;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.util.ArrayList;
import java.util.List;

/**
 * Drops OCR words that re-read a native word on a MIXED page.
 *
 * <p>The worker publishes a whole embedded image as one OCR region when the image is print — a
 * payroll-portal W-2 whose captions live in a page image under a value-only text layer. OCR of that
 * region reads the captions AND the native values drawn over the image; the native reading is exact
 * and already persisted, so the OCR copy would only double every value. A word is a duplicate when
 * at least {@link #DUPLICATE_COVERAGE} of its box lies under one native word.
 */
public final class NativeOverlap {

    static final double DUPLICATE_COVERAGE = 0.5;

    private NativeOverlap() {}

    public static List<OcrSpan> withoutNativeDuplicates(List<OcrSpan> ocr, List<TextSpan> natives) {
        if (natives.isEmpty()) {
            return ocr;
        }
        List<OcrSpan> kept = new ArrayList<>(ocr.size());
        for (OcrSpan span : ocr) {
            if (!duplicatesNative(span, natives)) {
                kept.add(
                        new OcrSpan(
                                kept.size(),
                                span.text(),
                                span.x(),
                                span.y(),
                                span.width(),
                                span.height(),
                                span.engine(),
                                span.confidence()));
            }
        }
        return kept;
    }

    private static boolean duplicatesNative(OcrSpan span, List<TextSpan> natives) {
        double x0 = span.x().doubleValue();
        double y0 = span.y().doubleValue();
        double x1 = x0 + span.width().doubleValue();
        double y1 = y0 + span.height().doubleValue();
        double area = (x1 - x0) * (y1 - y0);
        if (area <= 0) {
            return false;
        }
        for (TextSpan word : natives) {
            double nx0 = word.getX().doubleValue();
            double ny0 = word.getY().doubleValue();
            double nx1 = nx0 + word.getWidth().doubleValue();
            double ny1 = ny0 + word.getHeight().doubleValue();
            double w = Math.min(x1, nx1) - Math.max(x0, nx0);
            double h = Math.min(y1, ny1) - Math.max(y0, ny0);
            if (w > 0 && h > 0 && (w * h) / area >= DUPLICATE_COVERAGE) {
                return true;
            }
        }
        return false;
    }
}
