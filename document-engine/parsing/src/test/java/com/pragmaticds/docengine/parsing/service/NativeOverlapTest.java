package com.pragmaticds.docengine.parsing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.client.OcrSpan;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeOverlapTest {

    private static final UUID PAGE = UUID.randomUUID();

    private static TextSpan nativeWord(String text, double x, double y, double w, double h) {
        return new TextSpan(PAGE, 0, text, bd(x), bd(y), bd(w), bd(h), SpanSource.NATIVE, null,
                BigDecimal.ONE, null, null);
    }

    private static OcrSpan ocrWord(int ordinal, String text, double x, double y, double w, double h) {
        return new OcrSpan(ordinal, text, bd(x), bd(y), bd(w), bd(h), "rapidocr", new BigDecimal("0.95"));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void ocrReadingsOfNativeWordsAreDroppedAndCaptionsKeptInOrder() {
        // A whole form image was OCR'd: it reads the captions AND re-reads the native values.
        List<TextSpan> natives = List.of(nativeWord("228316.42", 300, 100, 40, 8));
        List<OcrSpan> ocr = List.of(
                ocrWord(0, "Wages,", 84, 90, 25, 5),
                ocrWord(1, "228316.42", 301, 100.5, 39, 7.5),
                ocrWord(2, "Copy", 84, 120, 20, 5),
                ocrWord(3, "B", 106, 120, 5, 5));

        List<OcrSpan> kept = NativeOverlap.withoutNativeDuplicates(ocr, natives);

        assertThat(kept).extracting(OcrSpan::text).containsExactly("Wages,", "Copy", "B");
        // Ordinals are renumbered contiguous, reading order preserved.
        assertThat(kept).extracting(OcrSpan::ordinal).containsExactly(0, 1, 2);
    }

    @Test
    void aWordOnlyGrazingANativeBoxIsKept() {
        List<TextSpan> natives = List.of(nativeWord("100.00", 300, 100, 40, 8));
        List<OcrSpan> ocr = List.of(ocrWord(0, "Box", 290, 106, 14, 6)); // ~11% overlap

        assertThat(NativeOverlap.withoutNativeDuplicates(ocr, natives)).hasSize(1);
    }

    @Test
    void noNativeWordsLeavesOcrUntouched() {
        List<OcrSpan> ocr = List.of(ocrWord(0, "Scan", 10, 10, 20, 8));
        assertThat(NativeOverlap.withoutNativeDuplicates(ocr, List.of())).isEqualTo(ocr);
    }
}
