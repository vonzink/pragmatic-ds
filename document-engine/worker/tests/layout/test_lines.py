"""lines.py — y-band grouping of spans into visual lines.

Tolerance is font-size-relative; OCR spans carry no fontSize, so the span height
stands in. A band splits into separate visual lines at large horizontal gaps —
that split is what keeps two-column pages two columns and turns a table band
into per-cell fragments."""

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker.layout.lines import build_lines


class TestYBandGrouping:
    def test_same_baseline_words_form_one_line(self):
        spans = [
            make_span(0, "Pay", 72, 100, 20, 11, size=11.0, font="Helvetica"),
            make_span(1, "Date:", 98, 100, 28, 11, size=11.0, font="Helvetica"),
        ]

        lines = build_lines(spans)

        assert len(lines) == 1
        assert [s.ordinal for s in lines[0].spans] == [0, 1]

    def test_distinct_baselines_form_distinct_lines(self):
        spans = [
            make_span(0, "first", 72, 100, 24, 11, size=11.0),
            make_span(1, "second", 72, 122, 34, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert len(lines) == 2

    def test_ocr_spans_without_font_size_group_by_height(self):
        """OCR boxes jitter vertically; the height fallback must still band them."""
        spans = [
            make_span(0, "48.0771", 200, 100.0, 40, 11),
            make_span(1, "80.00", 246, 102.5, 28, 11),  # 2.5pt of box jitter, 6pt gap
        ]

        lines = build_lines(spans)

        assert len(lines) == 1

    def test_line_spans_are_ordered_left_to_right(self):
        spans = [
            make_span(0, "right", 98, 100, 26, 11, size=11.0),
            make_span(1, "left", 72, 100, 20, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert [s.text for s in lines[0].spans] == ["left", "right"]

    def test_lines_come_out_top_to_bottom(self):
        spans = [
            make_span(0, "lower", 72, 200, 30, 11, size=11.0),
            make_span(1, "upper", 72, 100, 30, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert [line.spans[0].text for line in lines] == ["upper", "lower"]


class TestGapSplitting:
    def test_a_wide_gap_splits_a_band_into_two_lines(self):
        """Two columns on the same baseline are two visual lines, not one."""
        spans = [
            make_span(0, "left", 60, 100, 100, 11, size=11.0),
            make_span(1, "column", 330, 100, 100, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert len(lines) == 2
        assert lines[0].band == lines[1].band  # same y-band, split fragments
        assert lines[0].left == 60.0
        assert lines[1].left == 330.0

    def test_ordinary_word_gaps_do_not_split(self):
        spans = [
            make_span(0, "one", 72, 100, 18, 11, size=11.0),
            make_span(1, "two", 96, 100, 18, 11, size=11.0),  # 6pt gap
            make_span(2, "three", 120, 100, 26, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert len(lines) == 1

    def test_table_band_splits_into_per_cell_fragments(self):
        spans = [
            make_span(0, "Regular", 72, 100, 38, 11, size=11.0),
            make_span(1, "48.0771", 200, 100, 40, 11, size=11.0),
            make_span(2, "80.00", 290, 100, 28, 11, size=11.0),
        ]

        lines = build_lines(spans)

        assert [line.left for line in lines] == [72.0, 200.0, 290.0]

    def test_empty_input_yields_no_lines(self):
        assert build_lines([]) == []
