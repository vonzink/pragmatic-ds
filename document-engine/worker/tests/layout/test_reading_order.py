"""reading_order.py — column-major element ordering.

Columns are found via the x-histogram of element boxes: an x-interval free of
elements over a contiguous >= 60% of the page height, WITH elements wholly on
both sides, splits the page into columns. Order = per-column top-to-bottom,
columns left-to-right; single-column pages are plain top-to-bottom."""

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.layout.model import Element
from pragmaticds_docengine_worker.layout.reading_order import order_elements

PAGE_W, PAGE_H = 612.0, 792.0


def _element(name, x, y, w, h):
    return Element(element_type="PARAGRAPH", box=Box(x, y, w, h), spans=(),
                   confidence=0.9, attributes={"name": name})


def _names(elements):
    return [e.attributes["name"] for e in elements]


class TestSingleColumn:
    def test_plain_top_to_bottom(self):
        elements = [
            _element("second", 72, 200, 200, 50),
            _element("first", 72, 60, 200, 50),
            _element("third", 72, 400, 200, 50),
        ]

        assert _names(order_elements(elements, PAGE_W, PAGE_H)) == [
            "first", "second", "third",
        ]

    def test_a_wide_element_prevents_a_false_column_split(self):
        """A table reaching far right must not shear the page into columns —
        there is nothing wholly on the other side of the 'gap'."""
        elements = [
            _element("para", 72, 60, 150, 40),
            _element("table", 66, 300, 494, 110),
            _element("later", 72, 500, 150, 40),
        ]

        assert _names(order_elements(elements, PAGE_W, PAGE_H)) == [
            "para", "table", "later",
        ]


class TestTwoColumns:
    def test_column_major_order(self):
        elements = [
            _element("R1", 330, 60, 140, 40),
            _element("L1", 60, 60, 140, 40),
            _element("R2", 330, 120, 140, 300),
            _element("L2", 60, 120, 140, 300),
        ]

        assert _names(order_elements(elements, PAGE_W, PAGE_H)) == [
            "L1", "L2", "R1", "R2",
        ]

    def test_full_width_title_glues_the_columns_top_to_bottom(self):
        """Documented Phase 3 limitation: an element physically spanning the
        gutter removes the zero-coverage valley, so the page reads plain
        top-to-bottom (which still puts the title first). See the
        reading_order.py docstring for why the softer 60%-free-run rule was
        rejected — it shears sparse pages apart instead."""
        elements = [
            _element("title", 60, 40, 490, 20),
            _element("R1", 330, 100, 140, 400),
            _element("L1", 60, 100, 140, 400),
        ]

        assert _names(order_elements(elements, PAGE_W, PAGE_H)) == [
            "title", "L1", "R1",
        ]

    def test_a_footer_pocket_becomes_the_last_column(self):
        elements = [
            _element("page-number", 520, 744, 30, 12),
            _element("body", 72, 60, 220, 600),
        ]

        assert _names(order_elements(elements, PAGE_W, PAGE_H)) == [
            "body", "page-number",
        ]

    def test_empty_input(self):
        assert order_elements([], PAGE_W, PAGE_H) == []


class TestMarginPockets:
    """Phase 3 review finding (confirmed): ANY zero-coverage gap promoted a
    column boundary, so a bottom-left page number became column 0 and read
    before the entire page body. A column must carry real mass; tiny pockets
    join the nearest real column and take their place by y-position."""

    def test_a_bottom_left_page_number_reads_after_the_body(self):
        body = [
            _element("body1", 150.0, 100.0, 400.0, 200.0),
            _element("body2", 150.0, 320.0, 400.0, 200.0),
        ]
        page_number = _element("pageno", 60.0, 744.0, 30.0, 12.0)

        ordered = order_elements([page_number, *body], page_width_pt=612.0, page_height_pt=792.0)

        assert _names(ordered) == ["body1", "body2", "pageno"], _names(ordered)

    def test_left_gutter_line_numbers_do_not_hijack_the_page(self):
        # A legal page: 5 tiny line-number elements down the left gutter, body right.
        gutter = [_element(f"ln{i}", 40.0, 80.0 + i * 130, 18.0, 12.0) for i in range(5)]
        body = [_element(f"b{i}", 120.0, 80.0 + i * 130, 440.0, 110.0) for i in range(5)]

        ordered = order_elements([*gutter, *body], page_width_pt=612.0, page_height_pt=792.0)

        names = _names(ordered)
        first_body = min(i for i, n in enumerate(names) if n.startswith("b"))
        last_gutter = max(i for i, n in enumerate(names) if n.startswith("ln"))
        # Interleaved by y is acceptable; ALL gutter before ALL body is the bug.
        assert not (last_gutter < first_body), names
