"""End-to-end layout over the generated fixtures — the production pipeline shape:
/v1/text extracts real spans, those spans (plus the PDF for ruling confirmation)
feed /v1/layout, and truth-by-construction grades the result.

Measured Phase 3 numbers (asserted here, reported in the phase notes):

 * M4 cell alignment, ruled_table:   25/25 = 1.00  (constructed grid is exact)
 * M4 cell alignment, unruled_table: 25/25 = 1.00  (whitespace-only detection)
 * M5 reading-order Kendall tau, two_column: 1.00

The unruled floor is asserted at the plan's 0.9 despite measuring 1.0 — the
fixture grid is clean by construction; real unruled tables are the MVP's
declared weak point and the benchmark publishes whatever they measure.

NOTE (deviation from the phase prompt): native_paystub.pdf contains NO
whitespace grid — its earnings block is FLOWING text (cursor + 6pt gaps;
second-column x-starts land at 116.5/122.6/135.4/115.3 across lines, spread
>4pt), so >=3 lines never share >=3 aligned starts and a detector loose enough
to call that a table would fabricate tables out of ordinary paragraphs. The
whitespace-grid-as-TABLE requirement is exercised by unruled_table.pdf, whose
grid IS drawn at shared column positions; native_paystub asserts header +
paragraphs + no fabricated table."""

import json

from worker.tests.layout.conftest import FIXTURES, post_layout


def _layout(client, build_request, fixture, attach_pdf):
    request = build_request(client, fixture)
    pdf = (FIXTURES / fixture).read_bytes() if attach_pdf else None
    response = post_layout(client, request, pdf_bytes=pdf)
    assert response.status_code == 200, response.text
    return request, response.json()


def _iou(a: dict, b: dict) -> float:
    ax1, ay1 = a["x"] + a["width"], a["y"] + a["height"]
    bx1, by1 = b["x"] + b["width"], b["y"] + b["height"]
    ix = max(0.0, min(ax1, bx1) - max(a["x"], b["x"]))
    iy = max(0.0, min(ay1, by1) - max(a["y"], b["y"]))
    intersection = ix * iy
    union = a["width"] * a["height"] + b["width"] * b["height"] - intersection
    return intersection / union if union > 0 else 0.0


def _best_span(word: dict, spans: list[dict]) -> dict | None:
    best, best_iou = None, 0.0
    for span in spans:
        overlap = _iou(span, word)
        if overlap > best_iou:
            best, best_iou = span, overlap
    return best


def measure_m4(truth_page: dict, spans: list[dict], elements: list[dict]) -> float:
    """Fraction of truth words with a `cell` annotation whose best-IoU span sits
    in a TABLE_CELL with the matching (row, col)."""
    cell_of_span = {}
    for element in elements:
        if element["elementType"] == "TABLE_CELL":
            key = (element["attributes"]["row"], element["attributes"]["col"])
            for ordinal in element["spanOrdinals"]:
                cell_of_span[ordinal] = key
    graded = [word for word in truth_page["words"] if "cell" in word]
    assert graded, "fixture truth carries no cell annotations"
    correct = 0
    for word in graded:
        span = _best_span(word, spans)
        if span is not None and cell_of_span.get(span["ordinal"]) == (
            word["cell"]["row"], word["cell"]["col"],
        ):
            correct += 1
    return correct / len(graded)


def measure_kendall_tau(truth_page: dict, spans: list[dict], elements: list[dict]) -> float:
    """Tau between truth word order (draw order = reading order by construction)
    and the span order reconstructed from ordered elements' spanOrdinals."""
    reconstructed = [o for element in elements for o in element["spanOrdinals"]]
    position = {ordinal: index for index, ordinal in enumerate(reconstructed)}
    ranks = []
    for word in truth_page["words"]:
        span = _best_span(word, spans)
        if span is not None and span["ordinal"] in position:
            ranks.append(position[span["ordinal"]])
    assert len(ranks) >= 2
    concordant = discordant = 0
    for i in range(len(ranks)):
        for j in range(i + 1, len(ranks)):
            if ranks[i] == ranks[j]:
                continue
            if ranks[i] < ranks[j]:
                concordant += 1
            else:
                discordant += 1
    pairs = concordant + discordant
    assert pairs > 0
    return (concordant - discordant) / pairs


class TestRuledTable:
    def test_grid_confirmed_ruled_with_full_cell_alignment(
        self, client, text_to_layout_request, fixture_truth
    ):
        request, body = _layout(client, text_to_layout_request, "ruled_table.pdf", attach_pdf=True)
        elements = body["pages"][0]["elements"]

        tables = [e for e in elements if e["elementType"] == "TABLE"]
        assert len(tables) == 1
        assert tables[0]["attributes"] == {"rows": 5, "cols": 5, "ruled": True}
        assert tables[0]["confidence"] == 0.95

        m4 = measure_m4(
            fixture_truth("ruled_table.json")["pages"][0],
            request["pages"][0]["spans"],
            elements,
        )
        assert m4 == 1.0, f"M4 on the constructed ruled grid must be exact, got {m4}"

    def test_without_the_pdf_the_same_grid_is_unruled(
        self, client, text_to_layout_request
    ):
        """The file part only CONFIRMS rulings — no file, no 0.95."""
        _, body = _layout(client, text_to_layout_request, "ruled_table.pdf", attach_pdf=False)

        table = next(
            e for e in body["pages"][0]["elements"] if e["elementType"] == "TABLE"
        )
        assert table["attributes"]["ruled"] is False
        assert table["confidence"] == 0.75


class TestUnruledTable:
    def test_whitespace_grid_detected_unruled_with_measured_m4(
        self, client, text_to_layout_request, fixture_truth
    ):
        request, body = _layout(
            client, text_to_layout_request, "unruled_table.pdf", attach_pdf=True
        )
        elements = body["pages"][0]["elements"]

        tables = [e for e in elements if e["elementType"] == "TABLE"]
        assert len(tables) == 1
        assert tables[0]["attributes"] == {"rows": 5, "cols": 5, "ruled": False}
        assert tables[0]["confidence"] == 0.75

        m4 = measure_m4(
            fixture_truth("unruled_table.json")["pages"][0],
            request["pages"][0]["spans"],
            elements,
        )
        assert m4 >= 0.9, f"unruled M4 fell below the published floor: {m4}"


class TestTwoColumnReadingOrder:
    def test_element_order_is_column_major_by_kendall_tau(
        self, client, text_to_layout_request, fixture_truth
    ):
        request, body = _layout(client, text_to_layout_request, "two_column.pdf", attach_pdf=False)

        tau = measure_kendall_tau(
            fixture_truth("two_column.json")["pages"][0],
            request["pages"][0]["spans"],
            body["pages"][0]["elements"],
        )
        assert tau >= 0.99, f"column-major reading order broke: tau={tau}"

    def test_no_table_is_fabricated_from_two_text_columns(
        self, client, text_to_layout_request
    ):
        _, body = _layout(client, text_to_layout_request, "two_column.pdf", attach_pdf=False)

        assert not any(
            e["elementType"] == "TABLE" for e in body["pages"][0]["elements"]
        )


class TestNativePaystub:
    def _texts_of(self, element, spans):
        by_ordinal = {span["ordinal"]: span["text"] for span in spans}
        return [by_ordinal[o] for o in element["spanOrdinals"]]

    def test_acme_line_is_a_header(self, client, text_to_layout_request):
        request, body = _layout(
            client, text_to_layout_request, "native_paystub.pdf", attach_pdf=True
        )
        spans = request["pages"][0]["spans"]

        headers = [
            e for e in body["pages"][0]["elements"] if e["elementType"] == "HEADER"
        ]
        assert ["ACME", "WIDGETS", "LLC"] in [self._texts_of(h, spans) for h in headers]

    def test_body_lines_are_paragraphs(self, client, text_to_layout_request):
        request, body = _layout(
            client, text_to_layout_request, "native_paystub.pdf", attach_pdf=True
        )
        spans = request["pages"][0]["spans"]

        paragraphs = [
            e for e in body["pages"][0]["elements"] if e["elementType"] == "PARAGRAPH"
        ]
        paragraph_texts = [" ".join(self._texts_of(p, spans)) for p in paragraphs]
        assert any(text.startswith("Employee: Jordan Q. Fixture") for text in paragraph_texts)
        assert any("Pay Period: 01/01/2026" in text for text in paragraph_texts)

    def test_flowing_earnings_block_is_not_mistaken_for_a_table(
        self, client, text_to_layout_request
    ):
        """The deviation documented in the module docstring: this page has no
        x-aligned grid, and pretending otherwise would need a detector that
        fabricates tables from any left-aligned prose."""
        _, body = _layout(
            client, text_to_layout_request, "native_paystub.pdf", attach_pdf=True
        )

        assert not any(
            e["elementType"] == "TABLE" for e in body["pages"][0]["elements"]
        )


class TestGoldens:
    def test_ruled_table_layout_matches_golden(
        self, client, text_to_layout_request, golden
    ):
        _, body = _layout(client, text_to_layout_request, "ruled_table.pdf", attach_pdf=True)

        golden("layout_ruled_table.json", body)

    def test_two_column_layout_matches_golden(
        self, client, text_to_layout_request, golden
    ):
        _, body = _layout(client, text_to_layout_request, "two_column.pdf", attach_pdf=False)

        golden("layout_two_column.json", body)
