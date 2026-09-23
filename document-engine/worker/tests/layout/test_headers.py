"""headers.py — a HEADER is a line whose median font size exceeds 1.15x the page
median, or whose every span is bold, and which is positionally isolated (clear
gap above and below its neighbours). OCR pages carry no font data, so span
heights stand in for sizes and boldness is simply unavailable."""

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker.layout.headers import detect_headers
from pragmaticds_docengine_worker.layout.lines import build_lines


def _page(specs):
    """specs: (text, x, y, size, font) -> (lines, all spans)."""
    spans = [
        make_span(i, text, x, y, len(text) * 0.5 * size, size, size=size, font=font)
        for i, (text, x, y, size, font) in enumerate(specs)
    ]
    return build_lines(spans), spans


class TestHeaderDetection:
    def test_large_isolated_line_is_a_header(self):
        lines, spans = _page([
            ("TITLE", 72, 60, 14.0, "Helvetica-Bold"),
            ("body one", 72, 100, 11.0, "Helvetica"),
            ("body two", 72, 118, 11.0, "Helvetica"),
            ("body three", 72, 136, 11.0, "Helvetica"),
        ])

        headers = detect_headers(lines, spans)

        assert [line.spans[0].text for line in headers] == ["TITLE"]

    def test_bold_isolated_line_is_a_header_even_at_body_size(self):
        lines, spans = _page([
            ("Section", 72, 60, 11.0, "Helvetica-Bold"),
            ("body one", 72, 100, 11.0, "Helvetica"),
            ("body two", 72, 118, 11.0, "Helvetica"),
        ])

        headers = detect_headers(lines, spans)

        assert [line.spans[0].text for line in headers] == ["Section"]

    def test_large_line_without_isolation_is_not_a_header(self):
        """A big line jammed against its neighbour reads as emphasis, not a header."""
        lines, spans = _page([
            ("BIG", 72, 100, 14.0, "Helvetica"),
            ("body", 72, 115, 11.0, "Helvetica"),  # ~1pt gap to the line above
            ("more", 72, 133, 11.0, "Helvetica"),
        ])

        headers = detect_headers(lines, spans)

        assert headers == []

    def test_body_sized_regular_lines_are_never_headers(self):
        lines, spans = _page([
            ("alone", 72, 60, 11.0, "Helvetica"),
            ("body", 72, 200, 11.0, "Helvetica"),
        ])

        headers = detect_headers(lines, spans)

        assert headers == []

    def test_one_bold_word_inside_a_regular_line_is_not_a_header(self):
        spans = [
            make_span(0, "note", 72, 60, 22, 11, size=11.0, font="Helvetica-Bold"),
            make_span(1, "the rest is regular", 100, 60, 100, 11, size=11.0, font="Helvetica"),
            make_span(2, "body", 72, 100, 22, 11, size=11.0, font="Helvetica"),
        ]
        lines = build_lines(spans)

        headers = detect_headers(lines, spans)

        assert headers == []


class TestOcrFallback:
    def test_ocr_header_detected_by_height_ratio(self):
        """No fontSize/fontName anywhere: the 14pt-tall isolated line still wins
        via the height fallback."""
        spans = [
            make_span(0, "TITLE", 72, 60, 40, 14),
            make_span(1, "body one", 72, 100, 44, 11),
            make_span(2, "body two", 72, 118, 44, 11),
            make_span(3, "body three", 72, 136, 44, 11),
        ]
        lines = build_lines(spans)

        headers = detect_headers(lines, spans)

        assert [line.spans[0].text for line in headers] == ["TITLE"]

    def test_isolation_ignores_lines_in_the_other_column(self):
        """Neighbour gaps are measured against x-overlapping lines only — a
        right-column line at the same y must not spoil a left heading's isolation."""
        lines, spans = _page([
            ("Left Heading", 60, 60, 11.0, "Helvetica-Bold"),
            ("right body a", 330, 62, 11.0, "Helvetica"),
            ("left body", 60, 100, 11.0, "Helvetica"),
            ("right body b", 330, 80, 11.0, "Helvetica"),
            ("left body 2", 60, 118, 11.0, "Helvetica"),
        ])

        headers = detect_headers(lines, spans)

        assert [line.spans[0].text for line in headers] == ["Left Heading"]
