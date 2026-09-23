package com.pragmaticds.docengine.classification.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seam rule, pinned from both sides. Two mutations have to die here: a rule that ALWAYS joins
 * runs words together and can weld two table columns into one confident wrong value, and a rule
 * that NEVER joins is the defect this class exists for. Every case below fails under one of them.
 */
class SpanJoinTest {

    private static final String NOTHING = "";
    private static final String SPACE = " ";

    /** A 9 pt-tall span, the size the reproduction and most payroll body text print at. */
    private static Box box(String x, String width) {
        return box(x, "84.9", width, "9.0");
    }

    private static Box box(String x, String y, String width, String height) {
        return new Box(
                new BigDecimal(x), new BigDecimal(y), new BigDecimal(width), new BigDecimal(height));
    }

    @Nested
    class OneTokenTheProducerCutApart {

        @Test
        void touching_spans_carry_no_separator() {
            // `$1` ends at 210.0 and the comma starts at 210.0 — the amount `$1,321.18` with its
            // punctuation set in a second font. Real worker output.
            assertThat(SpanJoin.separator(box("200.0", "10.0"), box("210.0", "2.3")))
                    .isEqualTo(NOTHING);
        }

        @Test
        void positioning_residue_inside_a_token_still_reads_as_touching() {
            // 0.2 pt = 0.022 em: the producer stepping by Helvetica's comma advance while
            // Times draws the glyph. A seam, not a gap.
            assertThat(SpanJoin.separator(box("210.0", "2.3"), box("212.5", "15.0")))
                    .isEqualTo(NOTHING);
        }

        @Test
        void a_seam_that_overlaps_slightly_is_still_one_token() {
            // Tightened tracking puts the next glyph a hair inside the previous advance box.
            assertThat(SpanJoin.separator(box("227.5", "2.3"), box("229.7", "10.0")))
                    .isEqualTo(NOTHING);
        }

        @Test
        void raised_cents_join_across_their_baseline_shift() {
            // pdfplumber cuts the word on the baseline change; the printed gap is zero and the
            // two boxes still share a row, so the amount closes back up.
            assertThat(SpanJoin.separator(box("200.0", "84.9", "30.0", "9.0"), box("230.0", "82.9", "10.0", "9.0")))
                    .isEqualTo(NOTHING);
        }
    }

    @Nested
    class GapsThePageActuallyPrinted {

        @Test
        void a_printed_space_stays_a_space() {
            // `Gross` ends at 96.0, `Pay` starts at 98.5 — Helvetica's 0.278 em space advance.
            assertThat(SpanJoin.separator(box("72.0", "24.0"), box("98.5", "15.5")))
                    .isEqualTo(SPACE);
        }

        @Test
        void a_dropped_glyphs_advance_is_not_a_seam() {
            // The unrecoverable shape: the comma resolved to whitespace and pdfplumber discarded
            // it, leaving its own 0.25 em advance. Indistinguishable from a space, so it must
            // read as one — joining here would report $132118 for a page that says $1,321.18.
            assertThat(SpanJoin.separator(box("200.0", "10.0"), box("212.5", "15.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void the_narrowest_gap_any_committed_fixture_prints_stays_a_space() {
            // 0.2167 em — a 24 pt `1040` beside 12 pt masthead text on a real Form 1040, the
            // tightest printed separation across all 3641 same-row adjacent pairs in fixtures/.
            assertThat(
                            SpanJoin.separator(
                                    box("60.0", "70.0", "40.0", "24.0"),
                                    box("102.6", "76.0", "30.0", "12.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void a_whole_table_column_away_stays_a_space() {
            assertThat(SpanJoin.separator(box("200.0", "10.0"), box("420.0", "15.0")))
                    .isEqualTo(SPACE);
        }
    }

    @Nested
    class TheThresholdIsAFractionOfTheEm {

        @Test
        void the_boundary_itself_is_a_gap() {
            // Exactly 0.10 em (0.9 pt at 9 pt): the comparison is strict, so the threshold is
            // the first width that reads as printed.
            assertThat(SpanJoin.separator(box("200.0", "10.0"), box("210.9", "5.0")))
                    .isEqualTo(SPACE);
            assertThat(SpanJoin.separator(box("200.0", "10.0"), box("210.8", "5.0")))
                    .isEqualTo(NOTHING);
        }

        @Test
        void the_same_printed_gap_reads_differently_at_a_different_type_size() {
            // 0.8 pt is a seam in 9 pt text and a gap in 6 pt text. A point-valued threshold
            // could not tell those apart; a fraction of the em is why this scales.
            assertThat(SpanJoin.separator(box("200.0", "84.9", "10.0", "9.0"), box("210.8", "84.9", "5.0", "9.0")))
                    .isEqualTo(NOTHING);
            assertThat(SpanJoin.separator(box("200.0", "84.9", "10.0", "6.0"), box("210.8", "84.9", "5.0", "6.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void the_em_comes_from_the_smaller_span() {
            // VisualLines' discipline: a tall span must not extend its reach over small text
            // beside it. 1.0 pt is under 0.10 em of the 24 pt span and over 0.10 em of the 6 pt
            // one, and the small span's reading is the one that governs.
            assertThat(
                            SpanJoin.separator(
                                    box("60.0", "70.0", "40.0", "24.0"),
                                    box("101.0", "79.0", "30.0", "6.0")))
                    .isEqualTo(SPACE);
        }
    }

    @Nested
    class RowsAndReadingOrder {

        @Test
        void spans_on_different_printed_rows_never_join() {
            // The Schedule E column header: `line 2c` and the `from` printed beneath it are one
            // row band apart but 0.06 em apart horizontally. Rows reach each other transitively
            // through a tall span, so the pairwise centre test is what keeps them separate.
            assertThat(
                            SpanJoin.separator(
                                    box("200.0", "84.9", "10.0", "9.0"),
                                    box("210.2", "94.9", "20.0", "9.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void a_row_apart_is_a_space_even_when_the_boxes_touch_exactly() {
            assertThat(
                            SpanJoin.separator(
                                    box("200.0", "84.9", "10.0", "9.0"),
                                    box("210.0", "94.9", "20.0", "9.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void a_backward_step_never_joins() {
            // A page stored with /Rotate 180 hands its spans over in READING order, so canonical
            // x runs backwards along the row. The seam is measured absolutely, and a whole
            // token's width of backward step is far outside the threshold.
            assertThat(SpanJoin.separator(box("230.0", "10.0"), box("200.0", "10.0")))
                    .isEqualTo(SPACE);
        }
    }

    @Nested
    class DegenerateGeometry {

        @Test
        void a_zero_height_box_falls_back_to_a_space() {
            // No scale to measure a gap against. A space is the reading the engine has always
            // had: a missed join is a miss, and a miss is honest.
            assertThat(SpanJoin.separator(box("200.0", "84.9", "10.0", "0.0"), box("210.0", "84.9", "5.0", "9.0")))
                    .isEqualTo(SPACE);
        }

        @Test
        void a_negative_height_box_falls_back_to_a_space() {
            assertThat(SpanJoin.separator(box("200.0", "84.9", "10.0", "-9.0"), box("210.0", "84.9", "5.0", "9.0")))
                    .isEqualTo(SPACE);
        }
    }
}
