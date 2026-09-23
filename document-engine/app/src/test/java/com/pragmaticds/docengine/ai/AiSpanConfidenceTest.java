package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code spanConfidence} is the WEAKEST anchored span, not their average.
 *
 * <p>The contract states it plainly — "one shaky word taints the whole value" — and the average is
 * exactly how a shaky word hides: a description anchored to eight spans, seven of them clean native
 * text at 1.0 and one a garbage OCR glyph at 0.2, averages to 0.90 and reads as trustworthy. The
 * minimum reports 0.20, which is what a reviewer needs to see. On a native text layer every span is
 * 1.0 and the two definitions agree, so only OCR pages move — and only downward.
 */
class AiSpanConfidenceTest {

    private static final UUID PAGE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    void one_weak_span_drags_the_whole_value_down() {
        AiEvidenceAnchor.Match anchor =
                matched(span(1.0), span(1.0), span(1.0), span(1.0),
                        span(1.0), span(1.0), span(1.0), span(0.2));

        // The mean of these is 0.9. Averaging is what let the 0.2 glyph pass as trustworthy.
        assertThat(AiExtractionStageService.spanConfidence(anchor))
                .isEqualByComparingTo(new BigDecimal("0.2000"));
    }

    @Test
    void an_all_native_run_is_unaffected() {
        assertThat(AiExtractionStageService.spanConfidence(matched(span(1.0), span(1.0), span(1.0))))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    /** A span carrying no recorded confidence is not evidence of a good read. */
    @Test
    void a_span_with_no_confidence_floors_the_value() {
        assertThat(AiExtractionStageService.spanConfidence(matched(span(1.0), span(null))))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void an_unmatched_anchor_has_no_span_confidence_to_report() {
        assertThat(AiExtractionStageService.spanConfidence(AiEvidenceAnchor.Match.unanchored()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(AiExtractionStageService.spanConfidence(AiEvidenceAnchor.Match.ambiguous()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static AiEvidenceAnchor.Match matched(TextSpan... spans) {
        return new AiEvidenceAnchor.Match(AiEvidenceAnchor.Status.MATCHED, List.of(spans));
    }

    private static TextSpan span(Double confidence) {
        return new TextSpan(
                PAGE,
                0,
                "token",
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(8),
                confidence == null ? SpanSource.OCR : SpanSource.NATIVE,
                confidence == null ? "RAPIDOCR" : null,
                confidence == null ? null : BigDecimal.valueOf(confidence),
                BigDecimal.TEN,
                "Synthetic");
    }
}
