package com.pragmaticds.docengine.classification.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.rules.Anchor;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The matcher joins a page's spans in reading order, each seam decided by {@link SpanJoin},
 * matches literal anchors by case-insensitive containment and regex anchors with java.util.regex,
 * and maps a hit back to ALL overlapping spans — ids, boxes, and the matched OFFSET range (never
 * the matched text itself: evidence must carry no document content).
 */
class AnchorMatcherTest {

    /**
     * A 12 pt span whose box is the WIDTH OF ITS OWN TEXT, at roughly half an em per character —
     * which is what a page prints and what {@link SpanJoin} now reads. This helper used to hand
     * every span a flat 40 pt box regardless of the word inside it, so a caller stepping x by 40
     * described spans printed exactly touching; the seam rule read that literally and welded them
     * into one token. The geometry was always fiction, it simply used not to be looked at.
     */
    private static AnchorSpan span(long id, String text, double x) {
        return new AnchorSpan(
                id,
                text,
                new BigDecimal(String.valueOf(x)),
                new BigDecimal("700.0"),
                new BigDecimal(String.valueOf(text.length() * 6.0)),
                new BigDecimal("12.0"));
    }

    private static Anchor literal(String pattern) {
        return new Anchor("a-lit", AnchorKind.LITERAL, pattern, 2.0, false);
    }

    private static Anchor regex(String pattern) {
        return new Anchor("a-re", AnchorKind.REGEX, pattern, 1.0, false);
    }

    @Test
    void a_literal_spanning_four_spans_maps_back_to_exactly_those_spans() {
        // Joined: "Form Wage and Tax Statement 2025"
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(
                                span(10, "Form", 72),
                                span(11, "Wage", 110),
                                span(12, "and", 150),
                                span(13, "Tax", 180),
                                span(14, "Statement", 210),
                                span(15, "2025", 290)));

        AnchorMatch match = matcher.match(literal("Wage and Tax Statement"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(11L, 12L, 13L, 14L);
        assertThat(match.boxes()).hasSize(4);
        assertThat(match.boxes().get(0).x()).isEqualByComparingTo("110");
        // "Form " is five chars; the match covers [5, 27) of the joined text.
        assertThat(match.rangeStart()).isEqualTo(5);
        assertThat(match.rangeEnd()).isEqualTo(27);
    }

    @Test
    void literal_matching_is_case_insensitive() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(span(1, "WAGE", 0), span(2, "And", 10), span(3, "tax", 20),
                                span(4, "STATEMENT", 30)));

        AnchorMatch match = matcher.match(literal("wage and TAX Statement"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void a_literal_contained_inside_a_single_longer_span_maps_to_that_span_only() {
        // "Homeowner," contains "Homeowner" — containment, not equality.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(7, "Dear", 0), span(8, "Homeowner,", 40)));

        AnchorMatch match = matcher.match(literal("Dear Homeowner"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(7L, 8L);
        assertThat(match.rangeStart()).isEqualTo(0);
        assertThat(match.rangeEnd()).isEqualTo(14);
    }

    @Test
    void a_match_ending_at_a_span_boundary_does_not_include_the_next_span() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Net", 0), span(2, "Pay", 10), span(3, "$1.00", 20)));

        AnchorMatch match = matcher.match(literal("Net Pay"));

        assertThat(match.spanIds()).containsExactly(1L, 2L);
    }

    @Test
    void regex_with_word_boundaries_matches_the_token_and_only_the_token() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Current", 0), span(2, "YTD", 10)));

        AnchorMatch match = matcher.match(regex("\\bYTD\\b"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(2L);
    }

    @Test
    void regex_word_boundary_does_not_match_inside_a_longer_token() {
        AnchorMatcher matcher = AnchorMatcher.forSpans(List.of(span(1, "YTDX", 0)));

        AnchorMatch match = matcher.match(regex("\\bYTD\\b"));

        assertThat(match.matched()).isFalse();
    }

    @Test
    void a_miss_returns_no_spans_no_boxes_and_no_range() {
        AnchorMatcher matcher = AnchorMatcher.forSpans(List.of(span(1, "Gross", 0)));

        AnchorMatch match = matcher.match(literal("Net Pay"));

        assertThat(match.matched()).isFalse();
        assertThat(match.spanIds()).isEmpty();
        assertThat(match.boxes()).isEmpty();
        assertThat(match.rangeStart()).isNull();
        assertThat(match.rangeEnd()).isNull();
    }

    @Test
    void an_empty_page_matches_nothing() {
        AnchorMatcher matcher = AnchorMatcher.forSpans(List.of());

        assertThat(matcher.match(literal("Net Pay")).matched()).isFalse();
        assertThat(matcher.match(regex("\\bYTD\\b")).matched()).isFalse();
    }

    @Test
    void the_first_occurrence_wins_when_a_literal_appears_twice() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(span(1, "Earnings", 0), span(2, "then", 10), span(3, "Earnings", 20)));

        AnchorMatch match = matcher.match(literal("Earnings"));

        assertThat(match.spanIds()).containsExactly(1L);
        assertThat(match.rangeStart()).isEqualTo(0);
    }

    @org.junit.jupiter.api.Test
    void literal_offsets_survive_case_folding_that_changes_string_length() {
        // Phase 4 review: offsets were found in the LOWERCASED text but applied to
        // original-text span offsets. Turkish dotted capital I lowercases to TWO
        // chars, shifting every later offset — evidence then names the wrong spans.
        var spans = java.util.List.of(
                span(1L, "\u0130\u0130\u0130\u0130", 0.0),
                span(2L, "Pay", 20.0),
                span(3L, "Period", 40.0));
        AnchorMatcher matcher = AnchorMatcher.forSpans(spans);

        AnchorMatch match = matcher.match(literal("Pay Period"));

        org.assertj.core.api.Assertions.assertThat(match.matched()).isTrue();
        org.assertj.core.api.Assertions.assertThat(match.spanIds())
                .containsExactly(2L, 3L);
    }

    // ── typographic punctuation (the U+2019 defect) ─────────────────────────

    @Test
    void an_ascii_apostrophe_anchor_matches_a_page_printed_with_U2019() {
        // The PURCHASE_CONTRACT pack (V11) anchors on "Buyer's Signature" with the ASCII
        // apostrophe; real contracts set it with U+2019 RIGHT SINGLE QUOTATION MARK, so the
        // anchor never fired and the weight it carries was simply lost from the score.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Buyer’s", 0), span(2, "Signature", 40)));

        AnchorMatch match = matcher.match(literal("Buyer's Signature"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(1L, 2L);
    }

    @Test
    void a_U2019_anchor_matches_a_page_printed_with_an_ascii_apostrophe() {
        // Both sides fold, so a pack author who pastes the typographic caption out of the real
        // PDF is not punished for it either.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Buyer's", 0), span(2, "Signature", 40)));

        AnchorMatch match = matcher.match(literal("Buyer’s Signature"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(1L, 2L);
    }

    @Test
    void folding_does_not_move_the_matched_offsets() {
        // The whole risk of the change. The fold is 1 char in, 1 char out, so the offsets the
        // match reports still index the ORIGINAL joined text and the spans they name are the
        // spans the reader sees. "Buyer’s " is eight chars either way.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(span(1, "Buyer’s", 0), span(2, "Signature", 40),
                                span(3, "Date", 90)));

        AnchorMatch match = matcher.match(literal("Signature"));

        assertThat(match.rangeStart()).isEqualTo(8);
        assertThat(match.rangeEnd()).isEqualTo(17);
        assertThat(match.spanIds()).containsExactly(2L);
    }

    @Test
    void a_regex_anchor_authored_with_an_ascii_apostrophe_matches_a_U2019_page() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Seller’s", 0), span(2, "Signature", 40)));

        AnchorMatch match = matcher.match(regex("\\bSeller's Signature\\b"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(1L, 2L);
    }

    @Test
    void folding_a_regex_anchor_never_turns_a_dash_into_a_character_class_range() {
        // THE TRAP. Inside [...] the ASCII hyphen is the RANGE operator. A fold that rewrote an
        // authored en dash to a bare "-" would silently turn the three literals [a–z] matches
        // today into the whole a-z range — a confident WRONG match where there had been a safe
        // miss, which is strictly worse than the defect being fixed. The folded character must
        // stay a LITERAL in every regex context.
        AnchorMatcher matcher = AnchorMatcher.forSpans(List.of(span(1, "m", 0)));

        assertThat(matcher.match(regex("[a–z]")).matched())
                .as("[a<en dash>z] matches a, - and z — never the a-z range")
                .isFalse();
        assertThat(AnchorMatcher.forSpans(List.of(span(1, "-", 0))).match(regex("[a–z]"))
                        .matched())
                .as("the folded en dash still matches a printed hyphen")
                .isTrue();
    }

    @Test
    void an_anchor_reads_a_phrase_the_producer_cut_apart_mid_token() {
        // Classification joins on the SAME seam extraction does. A producer that sets the hyphen
        // of a form number in a second font hands "W-2" over as three touching spans; joining
        // those with spaces offered the pack "W - 2", which its anchor cannot match.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(
                                at(1, "Form", "72.00", "700.00", "24.00", "12.00"),
                                at(2, "W", "100.00", "700.00", "8.60", "12.00"),
                                at(3, "-", "108.60", "700.00", "4.00", "12.00"),
                                at(4, "2", "112.60", "700.00", "6.70", "12.00")));

        AnchorMatch match = matcher.match(literal("W-2"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(2L, 3L, 4L);
        // "Form " is five chars and the amount reads as one token from there on.
        assertThat(match.rangeStart()).isEqualTo(5);
        assertThat(match.rangeEnd()).isEqualTo(8);
    }

    @Test
    void a_non_breaking_space_in_the_page_matches_an_authored_space() {
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Wage and Tax Statement", 0)));

        assertThat(matcher.match(literal("Wage and Tax Statement")).matched()).isTrue();
    }

    @Test
    void a_non_breaking_hyphen_in_the_page_matches_an_authored_hyphen() {
        // The W2 pack's "W-2" anchor against the form number as the IRS actually sets it.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(List.of(span(1, "Form", 0), span(2, "W‑2", 40)));

        assertThat(matcher.match(literal("W-2")).matched()).isTrue();
    }

    // ── the masthead defect: a dense title block read across its columns ─────

    private static AnchorSpan at(
            long id, String text, String x, String y, String width, String height) {
        return new AnchorSpan(
                id,
                text,
                new BigDecimal(x),
                new BigDecimal(y),
                new BigDecimal(width),
                new BigDecimal(height));
    }

    /**
     * The masthead of a real Form 1040 page 1, EXACTLY as the worker reports it — every box and
     * every ordinal taken from the blank official {@code f1040.pdf} through the same
     * {@code extract_words} + reading-order path the pipeline runs. Nothing here is authored from
     * a belief about how the IRS sets the page.
     *
     * <p>Two printed rows share the left margin at x 97.60: the 6pt Treasury line on top
     * (y 26.60) and the 12pt title under it (y 35.20). Beside them the form number "1040" is set
     * 24pt tall (y 25.50), so its vertical extent covers BOTH. The worker's band merge grows a
     * running envelope, that one tall word inflates it to cover both rows, and the band is then
     * emitted left-to-right — which interleaves the two rows word by word.
     */
    private static List<AnchorSpan> realFormMasthead() {
        return List.of(
                at(1, "m", "36.10", "27.70", "7.00", "6.00"),
                at(2, "r", "36.10", "33.60", "7.00", "2.30"),
                at(3, "oF", "36.10", "36.00", "7.00", "8.00"),
                at(4, "1040", "43.20", "25.50", "47.60", "24.00"),
                at(5, "Department", "97.60", "26.60", "31.70", "6.00"),
                at(6, "U.S.", "97.60", "35.20", "22.30", "12.00"),
                at(7, "Individual", "123.50", "35.20", "51.10", "12.00"),
                at(8, "of", "130.90", "26.60", "5.20", "6.00"),
                at(9, "the", "137.80", "26.60", "8.40", "6.00"),
                at(10, "Treasury—Internal", "148.00", "26.60", "49.10", "6.00"),
                at(11, "Income", "178.20", "35.20", "39.40", "12.00"),
                at(12, "Revenue", "198.70", "26.60", "23.40", "6.00"),
                at(13, "Tax", "221.20", "35.20", "19.40", "12.00"),
                at(14, "Service", "223.80", "26.60", "19.90", "6.00"),
                at(15, "Return", "244.20", "35.20", "36.00", "12.00"),
                at(16, "20", "298.00", "28.70", "25.30", "20.00"),
                at(17, "25", "323.30", "27.90", "26.70", "20.00"));
    }

    @Test
    void the_title_of_a_real_1040_matches_though_the_treasury_line_is_read_through_it() {
        // Joined in the worker's ordinal order this reads
        //   "... Department U.S. Individual of the Treasury-Internal Income Revenue Tax
        //    Service Return ..."
        // — the two heaviest TAX_RETURN anchors both spliced apart, which is why a real filled
        // 1040 page 1 scored 0.30 and classified UNKNOWN.
        AnchorMatcher matcher = AnchorMatcher.forSpans(realFormMasthead());

        AnchorMatch match = matcher.match(literal("U.S. Individual Income Tax Return"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(6L, 7L, 11L, 13L, 15L);
    }

    @Test
    void the_treasury_line_of_a_real_1040_matches_though_the_title_is_read_through_it() {
        AnchorMatcher matcher = AnchorMatcher.forSpans(realFormMasthead());

        AnchorMatch match =
                matcher.match(literal("Department of the Treasury—Internal Revenue Service"));

        assertThat(match.matched()).isTrue();
        assertThat(match.spanIds()).containsExactly(5L, 8L, 9L, 10L, 12L, 14L);
    }

    @Test
    void the_masthead_still_reads_correctly_without_the_sideways_form_label() {
        // NOT a duplicate of the two above, and the reason the row rule takes its threshold from
        // the SMALLER of two spans rather than the larger. The three tiny glyphs at x 36 are the
        // sideways word "Form" the IRS prints down the left edge; one of them is 2.3pt tall.
        // Under a larger-of-the-two threshold the fix WORKS only because that 2.3pt glyph
        // happens to interrupt the chain between the two rows — delete it, or let an OCR pass
        // merge it, and both anchors silently break again. The rows must separate on the
        // geometry that carries the meaning: two 6pt-and-12pt rows 8.6pt apart.
        List<AnchorSpan> withoutTheSidewaysLabel =
                realFormMasthead().stream().filter(span -> span.id() > 3).toList();

        AnchorMatcher matcher = AnchorMatcher.forSpans(withoutTheSidewaysLabel);

        assertThat(matcher.match(literal("U.S. Individual Income Tax Return")).matched()).isTrue();
        assertThat(
                        matcher.match(
                                        literal(
                                                "Department of the Treasury—Internal Revenue"
                                                        + " Service"))
                                .matched())
                .isTrue();
    }

    @Test
    void regrouping_rows_adds_no_tolerance_whatsoever() {
        // THE INVARIANT the whole pack system rests on, pinned at the seam that could break it.
        // Rows are RE-ORDERED, never re-written: no word is dropped, none is skipped over, and
        // no gap is closed. A page carrying an anchor's words with anything printed between
        // them still does not print the phrase, and must still miss — on any geometry, whether
        // the intruder shares the row or not. Widening this into "the words appear somewhere
        // near each other" is what would let a page that merely REFERENCES a form qualify as
        // that form, and that is the one failure this repository has paid for three times.
        AnchorSpan intruderOnTheSameRow = at(3, "Retirement", "140", "100", "60", "10");
        AnchorSpan intruderOnItsOwnRow = at(3, "Retirement", "72", "140", "60", "10");

        for (AnchorSpan intruder : List.of(intruderOnTheSameRow, intruderOnItsOwnRow)) {
            AnchorMatcher matcher =
                    AnchorMatcher.forSpans(
                            List.of(
                                    at(1, "Individual", "72", "100", "50", "10"),
                                    at(2, "Income", "100", "100", "40", "10"),
                                    intruder,
                                    at(4, "Tax", "72", "180", "20", "10"),
                                    at(5, "Return", "100", "180", "36", "10")));

            assertThat(matcher.match(literal("Individual Income Tax Return")).matched())
                    .as("intruder at y=%s", intruder.y())
                    .isFalse();
        }
    }

    @Test
    void regrouping_keeps_every_span_exactly_once_and_invents_no_text() {
        // The permutation guarantee, stated as a property rather than an example: the joined
        // text the matcher sees is the same spans with the same single-space separators, only
        // in a different order. Anything else would move an evidence offset off its word.
        List<AnchorSpan> masthead = realFormMasthead();
        AnchorMatcher matcher = AnchorMatcher.forSpans(masthead);

        // An anchor matching the whole page would have to cover every span exactly once.
        AnchorMatch everything = matcher.match(regex("(?s).*"));

        assertThat(everything.spanIds())
                .containsExactlyInAnyOrderElementsOf(masthead.stream().map(AnchorSpan::id).toList());
    }

    @Test
    void a_row_is_never_re_read_only_re_grouped() {
        // The rotated-page guard. Canonical boxes on a /Rotate 180 page run opposite to the
        // order the worker read them in, so a row ordered by x would reverse it — turning a
        // page that classifies today into one that does not. Rows are ordered by the ordinal
        // the parser assigned, so this seam can only ever SPLIT a row, never re-sequence one.
        AnchorMatcher matcher =
                AnchorMatcher.forSpans(
                        List.of(
                                at(1, "Net", "200", "100", "19", "10"),
                                at(2, "Pay", "100", "100", "19", "10")));

        assertThat(matcher.match(literal("Net Pay")).matched()).isTrue();
        assertThat(matcher.match(literal("Pay Net")).matched()).isFalse();
    }
}