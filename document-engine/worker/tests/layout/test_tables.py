"""tables.py — column detection via x-start histogram over candidate line groups.

A grid needs >=3 bands sharing >=3 x-aligned fragment starts (±4pt). Rows are the
line bands, cells are the words within a column slot. Rulings from the optional
PDF part CONFIRM a grid (ruled=true, confidence 0.95); their absence or
misalignment leaves it unruled at 0.75. Non-grid lines are returned untouched."""

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker.layout.lines import build_lines
from pragmaticds_docengine_worker.layout.model import Rulings
from pragmaticds_docengine_worker.layout.tables import detect_tables

COLUMNS = (72, 200, 290)


def _grid_spans(rows=3, jitter=0.0):
    """rows x 3 grid of 40x11 words at COLUMNS, 22pt leading, plus (row, col) truth."""
    spans = []
    ordinal = 0
    for row in range(rows):
        for col, x in enumerate(COLUMNS):
            spans.append(
                make_span(ordinal, f"r{row}c{col}", x + (jitter if row % 2 else 0.0),
                          100 + row * 22, 40, 11, size=11.0)
            )
            ordinal += 1
    return spans


def _cells_of(table):
    cells = {}
    for row in table.children:
        for cell in row.children:
            cells[(cell.attributes["row"], cell.attributes["col"])] = cell
    return cells


class TestGridDetection:
    def test_three_aligned_bands_form_a_table_with_row_col_cells(self):
        lines = build_lines(_grid_spans(rows=3))

        tables, remaining = detect_tables(lines)

        assert len(tables) == 1
        assert remaining == []
        table = tables[0]
        assert table.element_type == "TABLE"
        assert table.attributes == {"rows": 3, "cols": 3, "ruled": False}
        assert table.confidence == 0.75
        cells = _cells_of(table)
        assert set(cells) == {(r, c) for r in range(3) for c in range(3)}
        assert cells[(1, 2)].spans[0].text == "r1c2"

    def test_two_aligned_bands_are_not_enough(self):
        lines = build_lines(_grid_spans(rows=2))

        tables, remaining = detect_tables(lines)

        assert tables == []
        assert len(remaining) == len(lines)

    def test_two_shared_columns_are_not_enough(self):
        """Two text columns aligning at 2 x-starts is a layout, not a table."""
        spans = []
        for i, y in enumerate((100, 122, 144)):
            spans.append(make_span(2 * i, "left words here", 60, y, 150, 11, size=11.0))
            spans.append(make_span(2 * i + 1, "right words here", 330, y, 150, 11, size=11.0))
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert tables == []
        assert len(remaining) == len(lines)

    def test_jitter_within_4pt_still_aligns(self):
        lines = build_lines(_grid_spans(rows=3, jitter=3.5))

        tables, _ = detect_tables(lines)

        assert len(tables) == 1

    def test_flowing_text_sharing_only_the_left_margin_stays_out(self):
        spans = [
            make_span(0, "one", 72, 100, 30, 11, size=11.0),
            make_span(1, "flowing", 108, 100, 60, 11, size=11.0),
            make_span(2, "another", 72, 122, 55, 11, size=11.0),
            make_span(3, "line", 133, 122, 30, 11, size=11.0),
            make_span(4, "third", 72, 144, 38, 11, size=11.0),
            make_span(5, "row", 116, 144, 28, 11, size=11.0),
        ]
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert tables == []
        assert len(remaining) == 3

    def test_non_grid_lines_around_a_grid_survive(self):
        spans = _grid_spans(rows=3)
        spans.append(make_span(90, "Heading above", 72, 40, 90, 11, size=11.0))
        spans.append(make_span(91, "Footer below", 72, 200, 85, 11, size=11.0))
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert len(tables) == 1
        assert sorted(line.spans[0].text for line in remaining) == [
            "Footer below", "Heading above",
        ]


class TestRuledConfirmation:
    def _rulings_in_the_gaps(self):
        return Rulings(
            verticals=((190.0, 92.0, 160.0), (280.0, 92.0, 160.0)),
            horizontals=((116.0, 66.0, 340.0), (138.0, 66.0, 340.0)),
        )

    def test_aligned_rulings_confirm_ruled_at_095(self):
        lines = build_lines(_grid_spans(rows=3))

        tables, _ = detect_tables(lines, self._rulings_in_the_gaps())

        table = tables[0]
        assert table.attributes["ruled"] is True
        assert table.confidence == 0.95
        for row in table.children:
            assert row.confidence == 0.95
            for cell in row.children:
                assert cell.confidence == 0.95

    def test_table_box_extends_to_the_confirming_rulings(self):
        lines = build_lines(_grid_spans(rows=3))

        tables, _ = detect_tables(lines, self._rulings_in_the_gaps())

        box = tables[0].box
        assert box.x <= 66.0
        assert box.x + box.width >= 340.0

    def test_misplaced_rulings_do_not_confirm(self):
        """Rulings elsewhere on the page (a letterhead rule) are not a grid."""
        lines = build_lines(_grid_spans(rows=3))
        rulings = Rulings(verticals=(), horizontals=((700.0, 60.0, 550.0),))

        tables, _ = detect_tables(lines, rulings)

        assert tables[0].attributes["ruled"] is False
        assert tables[0].confidence == 0.75

    def test_no_rulings_means_unruled(self):
        lines = build_lines(_grid_spans(rows=3))

        tables, _ = detect_tables(lines, Rulings(verticals=(), horizontals=()))

        assert tables[0].attributes["ruled"] is False


class TestTableStructure:
    def test_rows_nest_under_table_and_cells_under_rows(self):
        lines = build_lines(_grid_spans(rows=3))

        tables, _ = detect_tables(lines)

        table = tables[0]
        assert [row.element_type for row in table.children] == ["TABLE_ROW"] * 3
        for index, row in enumerate(table.children):
            assert row.attributes == {"row": index}
            assert all(cell.element_type == "TABLE_CELL" for cell in row.children)

    def test_ocr_shaped_grid_spans_still_form_a_table(self):
        spans = [
            make_span(i * 3 + c, f"r{i}c{c}", x, 100 + i * 22, 40, 11)
            for i in range(3)
            for c, x in enumerate(COLUMNS)
        ]
        lines = build_lines(spans)

        tables, _ = detect_tables(lines)

        assert len(tables) == 1


class TestColumnLocality:
    """Phase 3 review finding (confirmed, high): table detection operated on
    page-WIDE y-bands, so a grid in one column absorbed the neighbouring
    column's prose into its cells — the prose was never a PARAGRAPH, never
    ordered, and persisted as table-cell text."""

    def test_a_grid_next_to_flowing_prose_does_not_consume_the_prose(self):
        spans = []
        ordinal = 0
        # Left column: a 3x3 grid at x-starts 60/130/200.
        for row in range(3):
            for col, x in enumerate((60.0, 130.0, 200.0)):
                spans.append(make_span(ordinal, f"g{row}{col}", x, 100.0 + row * 20, 40, 12))
                ordinal += 1
        # Right column: flowing text on the SAME y-bands at x=330.
        prose_ordinals = []
        for row in range(3):
            spans.append(make_span(ordinal, f"prose{row}", 330.0, 100.0 + row * 20, 150, 12))
            prose_ordinals.append(ordinal)
            ordinal += 1

        lines = build_lines(spans)
        tables, remaining = detect_tables(lines, Rulings((), ()))

        assert len(tables) == 1
        assert tables[0].attributes["cols"] == 3, "prose column must not widen the grid"
        cell_ordinals = {
            s.ordinal for t in tables for row in t.children for cell in row.children
            for s in cell.spans
        }
        for prose_ordinal in prose_ordinals:
            assert prose_ordinal not in cell_ordinals, "prose consumed into a cell"
        # The prose must survive as non-table lines, not vanish.
        remaining_ordinals = {s.ordinal for line in remaining for s in line.spans}
        assert set(prose_ordinals) <= remaining_ordinals, "prose vanished from layout"


# ── Issue #63: columns from the table's own gap statistics; header row above ──

#: A paystub-shaped earnings grid, page in points, 9pt type. FIVE printed columns:
#: Description · Rate · Hours · Current · YTD. Rate/Hours are blank on the Bonus
#: row, so only three x-starts are shared by EVERY band — the shape that made the
#: detector on the real page declare 3 columns and pack an hours figure into the
#: cell to its left across a hole far wider than any word gap.
PAYSTUB_COLUMNS = {"desc": 72.0, "rate": 145.0, "hours": 211.0, "current": 300.0, "ytd": 400.0}
PAYSTUB_SIZE = 9.0
PAYSTUB_ROW_STEP = 14.0
PAYSTUB_TOP = 115.0


def _paystub_grid_spans(top=PAYSTUB_TOP):
    """Five earnings rows; returns (spans, truth) where truth maps ordinal -> column key."""
    rows = [
        # (text, x, width, column key) — "Regular Pay" is a two-word cell with a 3pt word gap.
        [("Regular", 72.0, 34.0, "desc"), ("Pay", 109.0, 16.0, "desc"),
         ("25.00", 145.0, 22.0, "rate"), ("80.00", 211.0, 22.0, "hours"),
         ("2,000.00", 300.0, 38.0, "current"), ("10,000.00", 400.0, 44.0, "ytd")],
        [("Overtime", 72.0, 40.0, "desc"), ("37.50", 145.0, 22.0, "rate"),
         ("4.00", 211.0, 18.0, "hours"), ("150.00", 300.0, 30.0, "current"),
         ("600.00", 400.0, 30.0, "ytd")],
        [("Bonus", 72.0, 26.0, "desc"),
         ("500.00", 300.0, 30.0, "current"), ("500.00", 400.0, 30.0, "ytd")],
        [("Holiday", 72.0, 32.0, "desc"), ("25.00", 145.0, 22.0, "rate"),
         ("8.00", 211.0, 18.0, "hours"), ("200.00", 300.0, 30.0, "current"),
         ("800.00", 400.0, 30.0, "ytd")],
        [("Sick", 72.0, 18.0, "desc"), ("25.00", 145.0, 22.0, "rate"),
         ("8.00", 211.0, 18.0, "hours"), ("200.00", 300.0, 30.0, "current"),
         ("200.00", 400.0, 30.0, "ytd")],
    ]
    spans, truth = [], {}
    ordinal = 0
    for row_index, row in enumerate(rows):
        y = top + row_index * PAYSTUB_ROW_STEP
        for text, x, width, key in row:
            spans.append(make_span(ordinal, text, x, y, width, PAYSTUB_SIZE, size=PAYSTUB_SIZE))
            truth[ordinal] = key
            ordinal += 1
    return spans, truth


def _column_of_each_span(table):
    return {
        span.ordinal: cell.attributes["col"]
        for row in table.children
        for cell in row.children
        for span in cell.spans
    }


class TestColumnsFromGapStatistics:
    """Issue #63 defect 1: a column present in only SOME rows must still be a
    column when the hole to its left is an outlier against the page's own word
    gaps — and a genuine two-word cell must never be split."""

    def test_five_printed_columns_are_detected_as_five(self):
        spans, truth = _paystub_grid_spans()
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert len(tables) == 1
        assert remaining == []
        assert tables[0].attributes["cols"] == 5
        column_index = {"desc": 0, "rate": 1, "hours": 2, "current": 3, "ytd": 4}
        assert _column_of_each_span(tables[0]) == {
            ordinal: column_index[key] for ordinal, key in truth.items()
        }

    def test_a_two_word_cell_stays_one_cell(self):
        spans, _ = _paystub_grid_spans()
        lines = build_lines(spans)

        tables, _ = detect_tables(lines)

        cells = _cells_of(tables[0])
        assert [s.text for s in cells[(0, 0)].spans] == ["Regular", "Pay"]
        assert cells[(0, 1)].spans[0].text == "25.00"
        assert cells[(0, 2)].spans[0].text == "80.00"

    def test_a_row_missing_a_column_leaves_a_hole_not_a_shifted_cell(self):
        spans, _ = _paystub_grid_spans()
        lines = build_lines(spans)

        tables, _ = detect_tables(lines)

        cells = _cells_of(tables[0])
        assert (2, 1) not in cells and (2, 2) not in cells
        assert cells[(2, 3)].spans[0].text == "500.00"
        assert cells[(2, 4)].spans[0].text == "500.00"

    def test_a_multi_word_cell_reaching_past_the_next_column_start_is_not_split(self):
        """'Federal Income Tax' runs from the description column to under the rate
        column's x-start with only word gaps between the words: one cell."""
        spans, _ = _paystub_grid_spans()
        y = PAYSTUB_TOP + 5 * PAYSTUB_ROW_STEP
        base = len(spans)
        spans += [
            make_span(base, "Federal", 72.0, y, 32.0, PAYSTUB_SIZE, size=PAYSTUB_SIZE),
            make_span(base + 1, "Income", 107.0, y, 30.0, PAYSTUB_SIZE, size=PAYSTUB_SIZE),
            make_span(base + 2, "Tax", 140.0, y, 16.0, PAYSTUB_SIZE, size=PAYSTUB_SIZE),
            make_span(base + 3, "312.00", 300.0, y, 30.0, PAYSTUB_SIZE, size=PAYSTUB_SIZE),
            make_span(base + 4, "1,248.00", 400.0, y, 38.0, PAYSTUB_SIZE, size=PAYSTUB_SIZE),
        ]
        lines = build_lines(spans)

        tables, _ = detect_tables(lines)

        assert tables[0].attributes["cols"] == 5
        cells = _cells_of(tables[0])
        assert [s.text for s in cells[(5, 0)].spans] == ["Federal", "Income", "Tax"]
        assert (5, 1) not in cells

    def test_the_plain_grid_is_unchanged(self):
        """Every cell present in every row: the classic detector's answer stands."""
        tables, _ = detect_tables(build_lines(_grid_spans(rows=3)))

        assert tables[0].attributes == {"rows": 3, "cols": 3, "ruled": False}


class TestHeaderRowAbove:
    """Issue #63 defect 2a: a row of short runs directly above the grid whose
    x-extents each land in exactly one column is that grid's header row. A label
    that could belong to two columns keeps the whole row out — ungrouped beats
    attached to the wrong column."""

    def _with_labels(self, labels):
        spans, _ = _paystub_grid_spans()
        y = PAYSTUB_TOP - PAYSTUB_ROW_STEP
        base = len(spans)
        header_ordinals = []
        for offset, (text, x, width) in enumerate(labels):
            spans.append(
                make_span(base + offset, text, x, y, width, PAYSTUB_SIZE, size=PAYSTUB_SIZE)
            )
            header_ordinals.append(base + offset)
        return spans, header_ordinals

    def test_labels_over_their_columns_join_the_table_as_row_zero(self):
        # Only three labels, sharing ONE x-start with the data rows — on its own
        # this band never qualifies as a grid row, which is how the real page's
        # Earnings/Rate/Hours ended up as HEADER elements outside the table.
        spans, header_ordinals = self._with_labels(
            [("Earnings", 72.0, 36.0), ("Rate", 145.0, 18.0), ("Hours", 211.0, 24.0)]
        )
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert len(tables) == 1
        assert remaining == []
        table = tables[0]
        assert table.attributes["rows"] == 6
        assert table.attributes["cols"] == 5
        header_row = table.children[0]
        assert header_row.attributes == {"row": 0, "header": True}
        cells = _cells_of(table)
        assert cells[(0, 0)].spans[0].text == "Earnings"
        assert cells[(0, 1)].spans[0].text == "Rate"
        assert cells[(0, 2)].spans[0].text == "Hours"
        assert cells[(1, 0)].spans[0].text == "Regular"
        assert set(_column_of_each_span(table)) >= set(header_ordinals)
        for row in table.children[1:]:
            assert "header" not in row.attributes

    def test_a_label_straddling_two_columns_keeps_the_row_ungrouped(self):
        spans, header_ordinals = self._with_labels(
            [("Earnings", 72.0, 36.0), ("Rate/Hours", 150.0, 80.0)]  # 150–230: cols 1 AND 2
        )
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert tables[0].attributes["rows"] == 5
        assert all("header" not in row.attributes for row in tables[0].children)
        remaining_ordinals = {s.ordinal for line in remaining for s in line.spans}
        assert remaining_ordinals == set(header_ordinals)

    def test_a_label_row_aligned_with_only_the_first_rows_does_not_split_the_grid(self):
        """Earnings/Rate/Hours share three x-starts with the Regular and Overtime
        rows but not with Bonus. Greedy run growth used to start a 3-row table at
        the label row and leave the last three rows as a second table; the run
        that is longer WITHOUT the label row is the grid. Placed too far up to be
        absorbed, so this pins the run choice alone."""
        spans, header_ordinals = self._with_labels(
            [("Earnings", 72.0, 36.0), ("Rate", 145.0, 18.0), ("Hours", 211.0, 24.0)]
        )
        far = [
            make_span(s.ordinal, s.text, s.box.x, s.box.y - 3 * PAYSTUB_ROW_STEP, s.box.width,
                      s.box.height, size=s.size) if s.ordinal in header_ordinals else s
            for s in spans
        ]

        tables, remaining = detect_tables(build_lines(far))

        assert [t.attributes["rows"] for t in tables] == [5]
        assert {s.ordinal for line in remaining for s in line.spans} == set(header_ordinals)

    def test_a_grid_that_already_has_its_label_row_does_not_take_a_title_above(self):
        """The other common paystub shape: the label row is INSIDE the run (labels
        at the data x-starts). A section title one pitch above must not become
        row 0 and demote the real labels to data — the composer's label-row rule
        and TABLE_CLUSTER both read the TOPMOST row as the header."""
        labels = [("Description", 72.0, 50.0), ("Rate", 145.0, 18.0), ("Hours", 211.0, 24.0),
                  ("Current", 300.0, 32.0), ("YTD", 400.0, 18.0)]
        spans = [
            make_span(i, text, x, PAYSTUB_TOP, width, PAYSTUB_SIZE, size=PAYSTUB_SIZE)
            for i, (text, x, width) in enumerate(labels)
        ]
        data, _ = _paystub_grid_spans(top=PAYSTUB_TOP + PAYSTUB_ROW_STEP)
        spans += [
            make_span(len(labels) + s.ordinal, s.text, s.box.x, s.box.y, s.box.width,
                      s.box.height, size=s.size)
            for s in data
        ]
        spans.append(make_span(99, "EARNINGS", 72.0, PAYSTUB_TOP - PAYSTUB_ROW_STEP, 42.0,
                               PAYSTUB_SIZE, size=PAYSTUB_SIZE, font="Helvetica-Bold"))

        tables, remaining = detect_tables(build_lines(spans))

        table = tables[0]
        assert table.attributes["rows"] == 6
        assert all("header" not in row.attributes for row in table.children)
        assert _cells_of(table)[(0, 0)].spans[0].text == "Description"
        assert [s.ordinal for line in remaining for s in line.spans] == [99]

    def test_a_single_fragment_above_the_grid_is_a_title_not_a_label_row(self):
        spans, header_ordinals = self._with_labels([("EARNINGS", 72.0, 42.0)])

        tables, remaining = detect_tables(build_lines(spans))

        assert tables[0].attributes["rows"] == 5
        assert all("header" not in row.attributes for row in tables[0].children)
        assert {s.ordinal for line in remaining for s in line.spans} == set(header_ordinals)

    def test_a_row_too_far_above_is_not_a_header(self):
        spans, header_ordinals = self._with_labels([("Earnings", 72.0, 36.0), ("Rate", 145.0, 18.0)])
        far = [
            make_span(s.ordinal, s.text, s.box.x, s.box.y - 3 * PAYSTUB_ROW_STEP, s.box.width,
                      s.box.height, size=s.size) if s.ordinal in header_ordinals else s
            for s in spans
        ]
        lines = build_lines(far)

        tables, remaining = detect_tables(lines)

        assert tables[0].attributes["rows"] == 5
        assert {s.ordinal for line in remaining for s in line.spans} == set(header_ordinals)

    def test_a_prose_line_above_the_grid_is_not_a_header(self):
        spans, header_ordinals = self._with_labels(
            [("Employee", 72.0, 40.0), ("earnings", 115.0, 38.0), ("detail", 156.0, 26.0),
             ("follows", 185.0, 32.0)]  # one flowing run crossing three column slots
        )
        lines = build_lines(spans)

        tables, remaining = detect_tables(lines)

        assert tables[0].attributes["rows"] == 5
        assert {s.ordinal for line in remaining for s in line.spans} == set(header_ordinals)
