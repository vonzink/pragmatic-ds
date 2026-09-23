"""pairs.py — a label immediately followed on its own baseline by ONE numeric run
(money, count, date) becomes one FORM_FIELD element (issue #63 defect 2b).

The label and the value are separate visual fragments — lines.py already split
them at the wide gap — so nothing here re-reads the page; the question is only
whether the two fragments belong together. Ambiguous shapes stay ungrouped."""

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker.layout.engine import ClusteringLayoutEngine, PageSpans
from pragmaticds_docengine_worker.layout.lines import build_lines
from pragmaticds_docengine_worker.layout.pairs import PAIR_CONFIDENCE, detect_pairs

SIZE = 9.0


def _line(y, *words, size=SIZE, font=None, start=0):
    """words = (text, x, width) on one baseline; returns spans numbered from `start`."""
    return [
        make_span(start + i, text, x, y, width, size, size=size, font=font)
        for i, (text, x, width) in enumerate(words)
    ]


def _ordinals(element):
    return [s.ordinal for s in element.spans]


class TestLabelValuePair:
    def test_gross_pay_and_its_figure_are_one_pair(self):
        spans = _line(100, ("Gross", 72, 26), ("Pay", 101, 16), ("1,234.56", 180, 40),
                      font="Helvetica-Bold")
        lines = build_lines(spans)

        pairs, remaining = detect_pairs(lines)

        assert remaining == []
        assert len(pairs) == 1
        pair = pairs[0]
        assert pair.element_type == "FORM_FIELD"
        assert _ordinals(pair) == [0, 1, 2]
        assert pair.attributes == {"labelSpanOrdinals": [0, 1], "valueSpanOrdinals": [2]}
        assert pair.confidence == PAIR_CONFIDENCE
        assert pair.box.x == 72.0 and pair.box.x + pair.box.width == 220.0

    def test_a_currency_symbol_span_belongs_to_the_value(self):
        spans = _line(100, ("Net", 72, 18), ("Pay", 93, 16), ("$", 150, 6), ("3,105.87", 158, 40))
        lines = build_lines(spans)

        pairs, remaining = detect_pairs(lines)

        assert len(pairs) == 1 and remaining == []
        assert pairs[0].attributes == {"labelSpanOrdinals": [0, 1], "valueSpanOrdinals": [2, 3]}

    def test_a_date_is_a_value(self):
        spans = _line(100, ("Pay", 72, 16), ("Date:", 91, 26), ("01/15/2026", 160, 46))

        pairs, remaining = detect_pairs(build_lines(spans))

        assert len(pairs) == 1 and remaining == []

    def test_two_pairs_on_one_baseline_each_keep_their_own_value(self):
        spans = _line(100, ("Pay", 72, 16), ("Date", 91, 22), ("01/15/2026", 150, 46),
                      ("Period", 320, 28), ("End", 351, 16), ("01/10/2026", 400, 46))

        pairs, remaining = detect_pairs(build_lines(spans))

        assert remaining == []
        assert [p.attributes for p in pairs] == [
            {"labelSpanOrdinals": [0, 1], "valueSpanOrdinals": [2]},
            {"labelSpanOrdinals": [3, 4], "valueSpanOrdinals": [5]},
        ]

    def test_pairs_come_back_top_to_bottom(self):
        spans = _line(100, ("Gross", 72, 26), ("Pay", 101, 16), ("1,234.56", 180, 40))
        spans += _line(114, ("Net", 72, 18), ("Pay", 93, 16), ("1,000.00", 170, 40), start=3)

        pairs, _ = detect_pairs(build_lines(spans))

        assert [_ordinals(p) for p in pairs] == [[0, 1, 2], [3, 4, 5]]


class TestStaysUngrouped:
    def _only_lines_remain(self, spans):
        lines = build_lines(spans)
        pairs, remaining = detect_pairs(lines)
        assert pairs == []
        assert remaining == lines

    def test_a_label_followed_by_two_figures_is_ambiguous(self):
        """Current AND year-to-date after one label: which is 'the' value is a
        guess, and a wrong pairing carries a real-looking citation."""
        self._only_lines_remain(
            _line(100, ("Gross", 72, 26), ("Pay", 101, 16), ("1,234.56", 180, 40),
                  ("12,345.67", 260, 44))
        )

    def test_words_after_a_label_are_not_a_value(self):
        self._only_lines_remain(_line(100, ("Pay", 72, 16), ("Frequency", 91, 44), ("Bi-Weekly", 170, 40)))

    def test_a_figure_a_page_width_from_its_label_is_not_paired(self):
        """A footer word at the left margin and a page number at the right share a
        baseline; ~40 ems apart they are two regions of the page, not a pair."""
        self._only_lines_remain(_line(100, ("Confidential", 72, 54), ("12", 500, 10)))

    def test_a_figure_in_a_column_of_its_own_is_not_paired(self):
        """Gross Pay at the margin and a figure right-aligned under a table column
        ~20 ems away: a column relationship the table detector and the reading
        order own, not a leader-and-tab-stop pair."""
        self._only_lines_remain(_line(100, ("Gross", 72, 26), ("Pay", 101, 16), ("2,850.00", 300, 38)))

    def test_a_value_across_a_gutter_from_a_label_in_the_other_column_is_not_paired(self):
        """The stacked CD/LE shape: the left column prints `Interest Rate` with its
        value on the NEXT line; the right column prints `$356.13` on the SAME band.
        Pairing them is a wrong association wearing a citation (#61)."""
        self._only_lines_remain(_cd_two_column_page())

    def test_prose_starting_inside_the_gap_on_another_band_blocks_the_pair(self):
        """Text living in the gap between a label and a figure means the gap is a
        column of the page, not the leader space of a form. A neighbouring pair's
        VALUE starting in the gap is the form's own ragged value column and does
        not count — the stacked `Loan Amount` pair below still pairs."""
        spans = _line(100, ("Interest", 72, 36), ("Rate", 111, 20), ("$356.13", 190, 34))
        spans += _line(114, ("See", 150, 16), ("page", 169, 22), ("4", 194, 6), start=3)
        spans += _line(128, ("Loan", 72, 20), ("Amount", 95, 32), ("$200,000", 150, 40), start=6)

        pairs, _ = detect_pairs(build_lines(spans))

        assert [p.attributes["labelSpanOrdinals"] for p in pairs] == [[6, 7]]

    def test_a_figure_with_no_label_is_not_paired(self):
        self._only_lines_remain(_line(100, ("1,234.56", 72, 40), ("12,345.67", 180, 44)))

    def test_a_label_and_value_in_one_fragment_are_left_to_the_paragraph_stage(self):
        """A word gap between 'Date:' and the date: lines.py kept them one
        fragment, so there is no wide gap to bridge and nothing to group."""
        self._only_lines_remain(_line(100, ("Pay", 72, 16), ("Date:", 91, 26), ("01/15/2026", 120, 46)))

    def test_a_long_run_before_the_figure_is_prose_not_a_label(self):
        self._only_lines_remain(
            _line(100, ("Total", 72, 22), ("amount", 97, 30), ("due", 130, 16), ("on", 149, 10),
                  ("or", 162, 10), ("before", 175, 28), ("the", 206, 14), ("2,500.00", 300, 40))
        )


def _cd_two_column_page():
    """Two columns, 9pt, gutter 208–330: left stacks label over value (CD/LE
    'Interest Rate' / '3.5%'), right prints prose lines and a figure."""
    spans = _line(100, ("Interest", 72, 36), ("Rate", 111, 20), ("$356.13", 330, 34))
    spans += _line(114, ("3.5%", 72, 20), ("Estimated", 330, 40), ("Escrow", 373, 28), start=3)
    spans += _line(128, ("Monthly", 72, 34), ("Principal", 109, 38), ("&", 150, 6),
                   ("Interest", 159, 36), ("Amount", 330, 32), ("can", 365, 16),
                   ("increase", 384, 36), start=6)
    spans += _line(142, ("$1,050.00", 72, 44), ("See", 330, 16), ("page", 349, 22), ("4", 374, 6),
                   start=13)
    return spans


class TestValueShapes:
    """A figure needs a decimal point, a thousands separator, a currency or percent
    mark, or a date shape; a bare integer counts only up to three digits. Form
    numbers, years, ids and account numbers are labels' neighbours, not values."""

    def _pairs(self, spans):
        pairs, _ = detect_pairs(build_lines(spans))
        return pairs

    def test_form_number_is_not_a_value(self):
        assert self._pairs(_line(100, ("Form", 72, 22), ("1040", 130, 22))) == []

    def test_employee_id_is_not_a_value(self):
        assert self._pairs(_line(100, ("Employee", 72, 40), ("ID", 115, 10), ("4471", 160, 22))) == []

    def test_tax_year_is_not_a_value(self):
        assert self._pairs(_line(100, ("Tax", 72, 16), ("Year", 91, 20), ("2026", 150, 22))) == []

    def test_account_number_is_not_a_value(self):
        assert self._pairs(_line(100, ("Account", 72, 34), ("123456789012", 140, 60))) == []

    def test_page_number_printed_beside_its_word_is_one_fragment_not_a_pair(self):
        """`Page 3` prints at a word gap: lines.py keeps it one fragment and no
        pairing question arises. (A bare 1–3 digit integer AT A WIDE GAP still
        counts as a value — `Hours   40` needs it — see the PR body.)"""
        assert self._pairs(_line(100, ("Page", 520, 22), ("3", 545, 6))) == []

    def test_a_decimal_figure_still_pairs(self):
        assert len(self._pairs(_line(100, ("Hours", 72, 24), ("80.00", 130, 22)))) == 1

    def test_a_short_count_still_pairs(self):
        assert len(self._pairs(_line(100, ("Dependents", 72, 48), ("2", 150, 6)))) == 1

    def test_a_percent_and_a_thousands_figure_still_pair(self):
        assert len(self._pairs(_line(100, ("Rate", 72, 20), ("3.5%", 130, 20)))) == 1
        assert len(self._pairs(_line(100, ("Balance", 72, 34), ("1,000", 130, 24)))) == 1


class TestEngineWiring:
    def test_a_two_column_page_keeps_its_column_major_reading_order(self):
        """No pair bridges the gutter, so `reading_order._valleys` still sees it
        and the page reads left column first — as on main."""
        page = PageSpans(page_index=0, width_pt=612.0, height_pt=792.0,
                         spans=_cd_two_column_page())

        elements = ClusteringLayoutEngine().analyze_page(page)["elements"]

        assert "FORM_FIELD" not in {e["elementType"] for e in elements}
        read = [o for e in elements for o in e["spanOrdinals"]]
        left = {0, 1, 3, 6, 7, 8, 9, 13}
        right = {2, 4, 5, 10, 11, 12, 14, 15, 16}
        assert set(read) == left | right
        assert max(read.index(o) for o in left) < min(read.index(o) for o in right)

    def test_gross_pay_pair_is_one_form_field_element_not_a_header_and_a_paragraph(self):
        spans = _line(100, ("Gross", 72, 26), ("Pay", 101, 16), ("1,234.56", 180, 40),
                      font="Helvetica-Bold")
        spans += _line(140, ("Some", 72, 24), ("body", 99, 24), ("text", 126, 20), start=3)
        page = PageSpans(page_index=0, width_pt=612.0, height_pt=792.0, spans=spans)

        elements = ClusteringLayoutEngine().analyze_page(page)["elements"]

        by_type = {}
        for element in elements:
            by_type.setdefault(element["elementType"], []).append(element)
        assert [e["spanOrdinals"] for e in by_type["FORM_FIELD"]] == [[0, 1, 2]]
        assert by_type["FORM_FIELD"][0]["attributes"] == {
            "labelSpanOrdinals": [0, 1], "valueSpanOrdinals": [2],
        }
        assert by_type["FORM_FIELD"][0]["detector"] == "clustering"
        assert "HEADER" not in by_type
        assert [e["spanOrdinals"] for e in by_type["PARAGRAPH"]] == [[3, 4, 5]]
