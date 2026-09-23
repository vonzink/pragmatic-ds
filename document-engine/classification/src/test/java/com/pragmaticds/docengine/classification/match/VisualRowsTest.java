package com.pragmaticds.docengine.classification.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The row seam: geometry decides where a printed row ENDS, the spans' own ordinal order decides
 * what comes FIRST. Both halves are load-bearing and this test pins them separately, because
 * getting either one wrong is a defect this repository has already shipped once.
 */
class VisualRowsTest {

    private static AnchorSpan span(long id, String text, String x, String y, String height) {
        return new AnchorSpan(
                id,
                text,
                new BigDecimal(x),
                new BigDecimal(y),
                new BigDecimal("10.00"),
                new BigDecimal(height));
    }

    private static List<String> textsOf(List<AnchorSpan> spans) {
        return spans.stream().map(AnchorSpan::text).toList();
    }

    @Test
    void spans_on_one_printed_row_stay_on_one_row_in_their_own_order() {
        List<AnchorSpan> ordered =
                VisualRows.inRowOrder(
                        List.of(
                                span(1, "Wage", "72", "100", "10"),
                                span(2, "and", "110", "100", "10"),
                                span(3, "Tax", "140", "100", "10")));

        assertThat(textsOf(ordered)).containsExactly("Wage", "and", "Tax");
    }

    @Test
    void a_tall_span_cannot_pull_two_separate_printed_rows_into_one_row() {
        // THE DEFECT, reduced. A 24pt form number sits beside two 6pt boilerplate rows and
        // vertically spans both. A threshold taken from the LARGER of the two heights lets the
        // tall span bridge them; the printed rows then interleave when ordered across the page
        // and no multi-word caption on either row can match.
        List<AnchorSpan> ordered =
                VisualRows.inRowOrder(
                        List.of(
                                span(1, "1040", "43", "25.5", "24"),
                                span(2, "Department", "97", "26.6", "6"),
                                span(3, "U.S.", "97", "35.2", "12"),
                                span(4, "Treasury", "148", "26.6", "6"),
                                span(5, "Individual", "123", "35.2", "12")));

        // "Department Treasury" and "U.S. Individual" each stay whole and consecutive.
        assertThat(textsOf(ordered))
                .containsSubsequence("Department", "Treasury")
                .containsSubsequence("U.S.", "Individual");
        assertThat(textsOf(ordered).indexOf("Treasury"))
                .as("nothing from the other row may fall between the row's own words")
                .isEqualTo(textsOf(ordered).indexOf("Department") + 1);
    }

    @Test
    void a_row_keeps_its_spans_in_ordinal_order_not_in_x_order() {
        // Rows are regrouped, never re-READ. A page stored with /Rotate 180 has canonical boxes
        // whose x runs opposite to the reading order the worker recorded; ordering a row by x
        // would silently reverse it. Ordinal order means this seam can only ever SPLIT a row
        // that geometry says is two — never re-sequence one the parser already read.
        List<AnchorSpan> ordered =
                VisualRows.inRowOrder(
                        List.of(
                                span(1, "Net", "200", "100", "10"),
                                span(2, "Pay", "100", "100", "10")));

        assertThat(textsOf(ordered)).containsExactly("Net", "Pay");
    }

    @Test
    void rows_come_back_in_the_order_their_first_span_was_read() {
        List<AnchorSpan> ordered =
                VisualRows.inRowOrder(
                        List.of(
                                span(1, "second-row", "72", "200", "10"),
                                span(2, "first-row", "72", "100", "10")));

        assertThat(textsOf(ordered)).containsExactly("second-row", "first-row");
    }

    @Test
    void an_empty_page_has_no_rows() {
        assertThat(VisualRows.inRowOrder(List.of())).isEmpty();
    }

    @Test
    void every_span_survives_regrouping_exactly_once() {
        List<AnchorSpan> spans =
                List.of(
                        span(1, "a", "72", "100", "10"),
                        span(2, "b", "110", "100", "10"),
                        span(3, "c", "72", "140", "10"),
                        span(4, "d", "72", "25", "40"));

        assertThat(VisualRows.inRowOrder(spans))
                .containsExactlyInAnyOrderElementsOf(spans);
    }
}
