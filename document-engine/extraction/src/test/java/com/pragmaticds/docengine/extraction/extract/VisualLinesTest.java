package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic line grouping: spans share a line when their vertical centers are within half the
 * larger of the two heights; lines come back top-to-bottom, spans within a line by x.
 */
class VisualLinesTest {

    private static SpanRef span(long id, String text, String x, String y, String height) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal("30.0"), new BigDecimal(height)),
                BigDecimal.ONE);
    }

    @Test
    void spans_with_near_vertical_centers_share_a_line() {
        SpanRef a = span(1, "Net", "72.0", "343.4", "11.1");
        SpanRef b = span(2, "Pay", "97.3", "343.4", "11.1");

        assertThat(VisualLines.group(List.of(a, b))).containsExactly(List.of(a, b));
    }

    @Test
    void centers_exactly_half_the_larger_height_apart_still_share_a_line() {
        // centers 105 and 110, heights 10 → threshold max(10,10)/2 = 5, gap 5 → same line.
        SpanRef a = span(1, "a", "72.0", "100.0", "10.0");
        SpanRef b = span(2, "b", "120.0", "105.0", "10.0");

        assertThat(VisualLines.group(List.of(a, b))).containsExactly(List.of(a, b));
    }

    @Test
    void centers_farther_than_half_the_larger_height_split_lines() {
        // centers 105 and 110.2 → gap 5.2 > 5 → two lines.
        SpanRef a = span(1, "a", "72.0", "100.0", "10.0");
        SpanRef b = span(2, "b", "120.0", "105.2", "10.0");

        assertThat(VisualLines.group(List.of(a, b))).containsExactly(List.of(a), List.of(b));
    }

    @Test
    void a_tall_span_does_not_annex_the_small_rows_it_merely_sits_beside() {
        // The real Form 1040 masthead, measured off the blank official f1040.pdf: a 24pt form
        // number beside a 6pt Treasury line and the 12pt title beneath it. Taking the threshold
        // from the LARGER height gives the numeral a 12pt reach, which bridges all three onto
        // one line; ordered by x the two rows are then read through each other and taxYear's
        // label "U.S. Individual Income Tax Return" cannot match. The numeral may share a line
        // with ONE of them, but the two boilerplate rows must not end up on the same line.
        SpanRef numeral = span(1, "1040", "43.2", "25.5", "24.0");
        SpanRef treasury = span(2, "Department", "97.6", "26.6", "6.0");
        SpanRef title = span(3, "U.S.", "97.6", "35.2", "12.0");

        List<List<SpanRef>> lines = VisualLines.group(List.of(numeral, treasury, title));

        assertThat(lines)
                .as("the 6pt Treasury row and the 12pt title row are different printed rows")
                .noneSatisfy(line -> assertThat(line).contains(treasury, title));
    }

    @Test
    void a_caption_and_its_value_at_different_sizes_still_share_their_printed_row() {
        // The other direction, and the reason the threshold is half the smaller height rather
        // than something smaller still: a 6pt caption and its 10pt value drawn on ONE baseline
        // have centers about a quarter of the size gap apart, which must stay well inside the
        // threshold or every box-grid caption would split away from the value it captions.
        SpanRef caption = span(1, "Wages,", "72.0", "100.5", "5.6");
        SpanRef value = span(2, "104,982.00", "160.0", "98.7", "9.3");

        assertThat(VisualLines.group(List.of(caption, value)))
                .containsExactly(List.of(caption, value));
    }

    @Test
    void lines_order_top_to_bottom_and_spans_within_a_line_by_x() {
        SpanRef bottomRight = span(1, "612.44", "178.8", "314.1", "10.2");
        SpanRef bottomLeft = span(2, "Federal", "72.0", "314.1", "10.2");
        SpanRef top = span(3, "Employee:", "72.0", "94.1", "10.2");

        assertThat(VisualLines.group(List.of(bottomRight, bottomLeft, top)))
                .containsExactly(List.of(top), List.of(bottomLeft, bottomRight));
    }

    @Test
    void a_degenerate_baseline_stripe_splits_away_from_its_own_caption_row() {
        // Measured on a real bank statement (live text_span rows, 2026-08-17): the caption
        // 'Number:' is honest 7.8pt at y 60.6 (center 64.5) while its 15-digit value arrives
        // as a 0.2pt-tall stripe AT THE BASELINE, y 69.2 (center 69.3). Δcenter 4.8pt against
        // a threshold of 0.5 × min(7.8, 0.2) = 0.1pt: the printed row splits, LINE_RIGHT sees
        // nothing right of the caption, and the value on the page is never offered to its
        // rung. This pin is the record of WHY the worker repairs such boxes at the source
        // (text._repair_degenerate_boxes) rather than this class growing a threshold floor —
        // a floor wide enough to heal 4.8pt exceeds real 6-8pt row pitch and would annex
        // neighbouring rows.
        SpanRef caption = span(1, "Number:", "392.3", "60.6", "7.8");
        SpanRef stripe = span(2, "429815003117208", "436.5", "69.2", "0.2");

        assertThat(VisualLines.group(List.of(caption, stripe)))
                .containsExactly(List.of(caption), List.of(stripe));
    }

    @Test
    void the_worker_repaired_stripe_rejoins_its_caption_row_without_reaching_the_next() {
        // The same row AFTER the worker rebuilds the stripe from its own per-char advance
        // (height 5.5, bottom kept at 69.4): centers 64.5 vs 66.65, threshold 0.5 × 5.5 =
        // 2.75 — one line again. The honest 7.8pt reference printed on the NEXT row (top
        // 80.6, center 84.5) must stay a separate line: repairing the measurement may never
        // become annexation.
        SpanRef caption = span(1, "Number:", "392.3", "60.6", "7.8");
        SpanRef repaired = span(2, "429815003117208", "436.5", "63.9", "5.5");
        SpanRef nextRow = span(3, "882031455907", "436.5", "80.6", "7.8");

        assertThat(VisualLines.group(List.of(caption, repaired, nextRow)))
                .containsExactly(List.of(caption, repaired), List.of(nextRow));
    }
}
