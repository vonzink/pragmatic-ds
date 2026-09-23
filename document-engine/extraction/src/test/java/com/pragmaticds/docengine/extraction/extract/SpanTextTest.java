package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The reading-order join and its offset table: the joining space belongs to neither neighbour,
 * matched ranges map back to exactly the overlapped spans, and case-insensitive literal matching
 * runs on the ORIGINAL text — never a case-folded copy, whose length can drift (Turkish İ) and
 * silently shift every later offset.
 */
class SpanTextTest {

    private static SpanRef span(long id, String text) {
        return new SpanRef(
                id,
                text,
                new Box(
                        BigDecimal.valueOf(id * 50),
                        new BigDecimal("100.0"),
                        new BigDecimal("30.0"),
                        new BigDecimal("10.0")),
                BigDecimal.ONE);
    }

    @Test
    void joins_span_texts_with_single_spaces() {
        SpanText text = SpanText.of(List.of(span(1, "Pay"), span(2, "Period:")));

        assertThat(text.text()).isEqualTo("Pay Period:");
    }

    @Test
    void a_match_ending_at_a_span_boundary_does_not_include_the_neighbour() {
        SpanText text = SpanText.of(List.of(span(1, "612.44"), span(2, "extra")));

        SpanText.Range range =
                text.findOccurrence(Pattern.compile("\\d+\\.\\d{2}"), 0).orElseThrow();

        assertThat(range).isEqualTo(new SpanText.Range(0, 6));
        assertThat(text.overlapping(range)).extracting(SpanRef::id).containsExactly(1L);
    }

    @Test
    void the_joining_space_belongs_to_neither_neighbour() {
        SpanText text = SpanText.of(List.of(span(1, "612.44"), span(2, "extra")));

        assertThat(text.overlapping(new SpanText.Range(6, 7))).isEmpty();
    }

    @Test
    void occurrences_of_duplicate_values_map_to_distinct_spans() {
        SpanText text = SpanText.of(List.of(span(1, "612.44"), span(2, "612.44")));
        Pattern money = Pattern.compile("\\d+\\.\\d{2}");

        SpanText.Range first = text.findOccurrence(money, 0).orElseThrow();
        SpanText.Range second = text.findOccurrence(money, 1).orElseThrow();

        assertThat(text.overlapping(first)).extracting(SpanRef::id).containsExactly(1L);
        assertThat(text.overlapping(second)).extracting(SpanRef::id).containsExactly(2L);
    }

    @Test
    void find_all_returns_every_occurrence_in_reading_order() {
        SpanText text = SpanText.of(List.of(
                span(1, "Ending"),
                span(2, "Balance"),
                span(3, "Ending"),
                span(4, "Balance"),
                span(5, "$1.00")));

        List<SpanText.Range> all = text.findAll(SpanText.labelPattern(
                new LabelSpec(AnchorKind.LITERAL, "Ending Balance")));

        assertThat(all).hasSize(2);
        assertThat(all.get(0).start()).isLessThan(all.get(1).start());
        assertThat(text.findAll(SpanText.labelPattern(
                new LabelSpec(AnchorKind.LITERAL, "Opening Balance")))).isEmpty();
    }

    @Test
    void an_occurrence_past_the_last_match_is_empty() {
        SpanText text = SpanText.of(List.of(span(1, "612.44"), span(2, "612.44")));

        assertThat(text.findOccurrence(Pattern.compile("\\d+\\.\\d{2}"), 2)).isEmpty();
    }

    @Test
    void a_range_straddling_the_boundary_includes_both_spans() {
        SpanText text = SpanText.of(List.of(span(1, "Pay"), span(2, "Period:")));

        SpanText.Range range =
                text.findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.LITERAL, "Pay Period")))
                        .orElseThrow();

        assertThat(text.overlapping(range)).extracting(SpanRef::id).containsExactly(1L, 2L);
    }

    @Test
    void case_insensitive_literal_matching_survives_case_folding_length_changes() {
        // Turkish dotted capital İ lowers to TWO chars (i + combining dot). A lowered-copy
        // implementation shifts every later offset by one per İ and mis-attributes spans.
        SpanText text =
                SpanText.of(
                        List.of(span(1, "İİİ"), span(2, "PAY"), span(3, "PERIOD:"), span(4, "X")));

        SpanText.Range range =
                text.findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.LITERAL, "pay period")))
                        .orElseThrow();

        assertThat(range).isEqualTo(new SpanText.Range(4, 14));
        assertThat(text.overlapping(range)).extracting(SpanRef::id).containsExactly(2L, 3L);
    }

    @Test
    void regex_labels_run_exactly_as_authored_without_implicit_case_folding() {
        SpanText text = SpanText.of(List.of(span(1, "net"), span(2, "pay")));

        assertThat(text.findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.REGEX, "NET"))))
                .isEmpty();
        assertThat(text.findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.LITERAL, "NET"))))
                .isPresent();
    }

    @Test
    void an_empty_scope_matches_nothing() {
        SpanText text = SpanText.of(List.of());

        assertThat(text.text()).isEmpty();
        assertThat(text.findOccurrence(Pattern.compile("x?"), 0)).isEmpty();
    }

    // ── typographic punctuation (the U+2019 defect) ─────────────────────────

    @Test
    void an_ascii_apostrophe_label_matches_a_scope_printed_with_U2019() {
        SpanText text =
                SpanText.of(
                        List.of(span(1, "Employee’s"), span(2, "social"), span(3, "security"),
                                span(4, "number")));

        SpanText.Range range =
                text.findFirst(
                                SpanText.labelPattern(
                                        new LabelSpec(
                                                AnchorKind.LITERAL,
                                                "Employee's social security number")))
                        .orElseThrow();

        assertThat(text.overlapping(range))
                .extracting(SpanRef::id)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void text_reports_the_scope_exactly_as_the_document_printed_it() {
        // The fold decides what MATCHES. It never rewrites what is REPORTED: capture() takes the
        // displayed value out of THIS string, and it is the document's own characters — so a
        // persisted value and its evidence box always agree with the page.
        SpanText text = SpanText.of(List.of(span(1, "O’Hara"), span(2, "Millwork")));

        assertThat(text.text()).isEqualTo("O’Hara Millwork");
    }

    @Test
    void a_folded_character_does_not_shift_the_offsets_of_the_text_after_it() {
        // THE RISK of the whole change, pinned. The fold is 1 char in, 1 char out, so a match
        // found in the folded copy indexes the ORIGINAL string at the same offsets and names the
        // same spans. A non-length-preserving normalization (NFKC expands ﬁ to two chars) would
        // shift every later offset and mis-attribute the evidence box — a confident wrong box,
        // strictly worse than the missing field this change exists to fix.
        SpanText folded = SpanText.of(List.of(span(1, "O’Hara"), span(2, "612.44"), span(3, "x")));
        SpanText plain = SpanText.of(List.of(span(1, "O'Hara"), span(2, "612.44"), span(3, "x")));
        Pattern money = Pattern.compile("\\d+\\.\\d{2}");

        SpanText.Range foldedRange = folded.findOccurrence(money, 0).orElseThrow();

        assertThat(foldedRange).isEqualTo(plain.findOccurrence(money, 0).orElseThrow());
        assertThat(foldedRange).isEqualTo(new SpanText.Range(7, 13));
        assertThat(folded.text().substring(foldedRange.start(), foldedRange.end()))
                .isEqualTo("612.44");
        assertThat(folded.overlapping(foldedRange)).extracting(SpanRef::id).containsExactly(2L);
    }

    @Test
    void a_regex_label_authored_with_an_ascii_apostrophe_matches_a_U2019_scope() {
        SpanText text = SpanText.of(List.of(span(1, "Spouse’s"), span(2, "name:")));

        assertThat(text.findFirst(
                        SpanText.labelPattern(new LabelSpec(AnchorKind.REGEX, "Spouse's name:"))))
                .isPresent();
    }

    @Test
    void folding_a_regex_label_never_turns_a_dash_into_a_character_class_range() {
        // Inside [...] the ASCII hyphen is the RANGE operator, so rewriting an authored en dash
        // to a bare "-" would turn [a–z]'s three literals into the whole a-z range: a confident
        // WRONG match where there had been a safe miss. The folded character stays a LITERAL.
        assertThat(SpanText.of(List.of(span(1, "m")))
                        .findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.REGEX, "[a–z]"))))
                .isEmpty();
        assertThat(SpanText.of(List.of(span(1, "-")))
                        .findFirst(SpanText.labelPattern(new LabelSpec(AnchorKind.REGEX, "[a–z]"))))
                .isPresent();
    }

    /**
     * One printed token handed over as several spans — the highest-volume income document's
     * commonest shape. Every box below is what the real worker returned for a page whose amount
     * has its punctuation set in a second font (worker: {@code test_shredded_numbers.py}); the
     * pattern is the paystub schema's own MONEY pattern, verbatim from {@code V7__extraction.sql}.
     */
    @Nested
    class ShreddedTokens {

        /** {@code (?<![\d,.])\$?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2}(?!\d)} — V7, unchanged. */
        private final Pattern money =
                SpanText.valuePattern(
                        "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)");

        private SpanRef at(long id, String text, String x, String width) {
            // y/height as the worker emits them: the comma and period sit 0.1pt lower.
            String y = text.equals(",") || text.equals(".") ? "85.0" : "84.9";
            return new SpanRef(
                    id,
                    text,
                    new Box(
                            new BigDecimal(x),
                            new BigDecimal(y),
                            new BigDecimal(width),
                            new BigDecimal("9.0")),
                    BigDecimal.ONE);
        }

        /** {@code Gross Pay $1,321.18} as seven spans; the amount's five print TOUCHING. */
        private List<SpanRef> grossPayLine() {
            return List.of(
                    at(1, "Gross", "72.0", "24.0"),
                    at(2, "Pay", "98.5", "15.5"),
                    at(3, "$1", "200.0", "10.0"),
                    at(4, ",", "210.0", "2.3"),
                    at(5, "321", "212.5", "15.0"),
                    at(6, ".", "227.5", "2.3"),
                    at(7, "18", "230.0", "10.0"));
        }

        @Test
        void a_multi_span_amount_reads_exactly_as_the_page_prints_it() {
            assertThat(SpanText.of(grossPayLine()).text()).isEqualTo("Gross Pay $1,321.18");
        }

        @Test
        void the_money_pattern_captures_the_whole_amount() {
            SpanText text = SpanText.of(grossPayLine());

            SpanText.Range range = text.findFirst(money).orElseThrow();

            assertThat(text.text().substring(range.start(), range.end())).isEqualTo("$1,321.18");
        }

        @Test
        void evidence_covers_every_fragment_of_the_value() {
            // Spec 5a already established this for "( 18,470 )": a value assembled from several
            // spans must draw its box over ALL of them, or the reviewer is shown part of a number.
            SpanText text = SpanText.of(grossPayLine());

            SpanText.Range range = text.findFirst(money).orElseThrow();

            assertThat(text.overlapping(range))
                    .extracting(SpanRef::text)
                    .containsExactly("$1", ",", "321", ".", "18");
        }

        @Test
        void the_label_beside_it_still_reads_as_two_words() {
            // The other half of the same join: `Gross` and `Pay` are separated by a printed
            // space, so concatenating them would cost the label that finds this field at all.
            assertThat(SpanText.of(grossPayLine())
                            .findFirst(
                                    SpanText.labelPattern(
                                            new LabelSpec(AnchorKind.LITERAL, "Gross Pay"))))
                    .isPresent();
        }

        @Test
        void punctuation_the_producer_dropped_stays_an_honest_miss() {
            // The OTHER failure shape (worker: TestWhitespaceMappedPunctuationIsUnrecoverable):
            // the comma and decimal point resolved to whitespace and pdfplumber discarded them,
            // leaving the dropped glyph's own 0.278 em advance behind — the same seam a printed
            // space leaves. Joining there would report $132118 for a page that says $1,321.18.
            // A miss is the only honest answer, and the geometry is what refuses it.
            SpanText text =
                    SpanText.of(
                            List.of(
                                    at(1, "Gross", "72.0", "24.0"),
                                    at(2, "Pay", "98.5", "15.5"),
                                    at(3, "$1", "200.0", "10.0"),
                                    at(4, "321", "212.5", "15.0"),
                                    at(5, "18", "230.0", "10.0")));

            assertThat(text.text()).isEqualTo("Gross Pay $1 321 18");
            assertThat(text.findFirst(money)).isEmpty();
        }
    }
}
