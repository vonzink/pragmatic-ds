"""paragraphs.py — consecutive lines with a consistent left edge (±3pt) and
consistent line spacing (±40%) merge into paragraphs. Interleaved columns must
NOT break each other's paragraphs: lines are grouped per column track."""

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker.layout.lines import build_lines
from pragmaticds_docengine_worker.layout.paragraphs import group_paragraphs


def _line(text, x, y, size=11.0):
    return build_lines([make_span(0, text, x, y, len(text) * 5.5, size, size=size)])[0]


def _lines(specs):
    """specs: (text, x, y[, size]) tuples -> Line list ordered top-to-bottom."""
    spans = [
        make_span(i, text, x, y, len(text) * 5.5, spec[3] if len(spec) > 3 else 11.0,
                  size=spec[3] if len(spec) > 3 else 11.0)
        for i, spec in enumerate(specs)
        for text, x, y in [spec[:3]]
    ]
    return build_lines(spans)


class TestParagraphMerging:
    def test_evenly_spaced_left_aligned_lines_merge(self):
        lines = _lines([("one", 72, 100), ("two", 72, 118), ("three", 72, 136)])

        paragraphs = group_paragraphs(lines)

        assert len(paragraphs) == 1
        assert len(paragraphs[0]) == 3

    def test_left_edge_drift_beyond_3pt_breaks_the_paragraph(self):
        lines = _lines([("one", 72, 100), ("two", 76, 118)])

        paragraphs = group_paragraphs(lines)

        assert len(paragraphs) == 2

    def test_left_edge_drift_within_3pt_still_merges(self):
        lines = _lines([("one", 72, 100), ("two", 74.5, 118)])

        paragraphs = group_paragraphs(lines)

        assert len(paragraphs) == 1

    def test_spacing_jump_beyond_40_percent_breaks_the_paragraph(self):
        # 18pt, 18pt, then 44pt: the fourth line starts something new.
        lines = _lines([("a", 72, 100), ("b", 72, 118), ("c", 72, 136), ("d", 72, 180)])

        paragraphs = group_paragraphs(lines)

        assert [len(p) for p in paragraphs] == [3, 1]

    def test_a_fresh_pair_with_a_huge_gap_does_not_merge(self):
        """No established spacing yet — but 100pt between 11pt lines is no paragraph."""
        lines = _lines([("alone", 72, 100), ("far", 72, 200)])

        paragraphs = group_paragraphs(lines)

        assert len(paragraphs) == 2

    def test_single_line_is_its_own_paragraph(self):
        paragraphs = group_paragraphs(_lines([("only", 72, 100)]))

        assert [len(p) for p in paragraphs] == [1]


class TestColumnInterleaving:
    def test_two_columns_build_two_paragraphs_despite_band_interleaving(self):
        """Sorted by y, column lines alternate L,R,L,R — each column must still
        assemble its own paragraph."""
        lines = _lines([
            ("left-one", 60, 100), ("right-one", 330, 100),
            ("left-two", 60, 116), ("right-two", 330, 116),
            ("left-three", 60, 132), ("right-three", 330, 132),
        ])

        paragraphs = group_paragraphs(lines)

        assert len(paragraphs) == 2
        assert sorted(len(p) for p in paragraphs) == [3, 3]
        lefts = {round(p[0].left) for p in paragraphs}
        assert lefts == {60, 330}
