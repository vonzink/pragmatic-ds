"""engine.py — ClusteringLayoutEngine orchestration: every element carries the
detector identity, ids nest table > row > cell, ordinals are reading order, and
CHECKBOX/SIGNATURE stay unconditionally declared in notImplemented — the Spec 3
raster reaches analyze_page as a pass-through nothing reads until the T3/T4
detectors land. OCR-shaped spans (no fontSize, no fontName) must flow through
without a crash."""

import math

from PIL import Image, ImageDraw

from worker.tests.layout.conftest import make_span

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.layout.engine import (
    IMPLEMENTED_WITH_PIXELS,
    NOT_IMPLEMENTED,
    ClusteringLayoutEngine,
    PageSpans,
)
from pragmaticds_docengine_worker.layout.model import Element
from pragmaticds_docengine_worker.layout.raster import PageRaster


def _native_page():
    spans = [
        make_span(0, "TITLE", 72, 40, 60, 14, size=14.0, font="Helvetica-Bold"),
        make_span(1, "body", 72, 100, 30, 11, size=11.0, font="Helvetica"),
        make_span(2, "text", 108, 100, 28, 11, size=11.0, font="Helvetica"),
        make_span(3, "more", 72, 118, 32, 11, size=11.0, font="Helvetica"),
        make_span(4, "words", 72, 136, 36, 11, size=11.0, font="Helvetica"),
    ]
    # a 3x3 grid further down
    ordinal = 5
    for row in range(3):
        for col, x in enumerate((72, 200, 290)):
            spans.append(make_span(ordinal, f"r{row}c{col}", x, 300 + row * 22, 40, 11,
                                   size=11.0, font="Helvetica"))
            ordinal += 1
    return PageSpans(page_index=0, width_pt=612.0, height_pt=792.0, spans=spans)


def _analyze(page=None):
    return ClusteringLayoutEngine().analyze_page(page or _native_page())


def _raster(image):
    return PageRaster(
        page_index=0,
        image=image,
        dpi=200,
        rotation=0,
        width_pt=612.0,
        height_pt=792.0,
    )


def _blank_letter_raster():
    """A 612x792 pt page at 200 dpi — white, so detections come only from
    what a test draws."""
    return Image.new("RGB", (1700, 2200), "white")


class TestPagePayload:
    def test_not_implemented_declared_without_pixels(self):
        payload = _analyze()

        assert payload["notImplemented"] == ["CHECKBOX", "SIGNATURE"]
        assert NOT_IMPLEMENTED == ("CHECKBOX", "SIGNATURE")
        assert IMPLEMENTED_WITH_PIXELS == ("CHECKBOX", "SIGNATURE")

    def test_every_element_carries_the_detector_identity(self):
        payload = _analyze()

        assert payload["elements"], "expected elements on a populated page"
        for element in payload["elements"]:
            assert element["detector"] == "clustering"
            assert element["detectorVersion"] == __version__

    def test_ordinals_are_sequential_and_ids_unique(self):
        elements = _analyze()["elements"]

        assert [e["ordinal"] for e in elements] == list(range(len(elements)))
        ids = [e["elementId"] for e in elements]
        assert len(set(ids)) == len(ids)

    def test_table_children_nest_by_parent_id(self):
        elements = _analyze()["elements"]
        by_id = {e["elementId"]: e for e in elements}

        tables = [e for e in elements if e["elementType"] == "TABLE"]
        rows = [e for e in elements if e["elementType"] == "TABLE_ROW"]
        cells = [e for e in elements if e["elementType"] == "TABLE_CELL"]
        assert len(tables) == 1 and len(rows) == 3 and len(cells) == 9
        assert tables[0]["parentElementId"] is None
        for row in rows:
            assert by_id[row["parentElementId"]]["elementType"] == "TABLE"
        for cell in cells:
            assert by_id[cell["parentElementId"]]["elementType"] == "TABLE_ROW"

    def test_children_follow_their_parent_in_ordinal_order(self):
        elements = _analyze()["elements"]
        position = {e["elementId"]: e["ordinal"] for e in elements}

        for element in elements:
            parent = element["parentElementId"]
            if parent is not None:
                assert position[parent] < element["ordinal"]

    def test_span_ordinals_partition_the_input_spans(self):
        """Every input span lands in exactly one leaf element — nothing lost,
        nothing duplicated (tables and rows hold no spans of their own)."""
        page = _native_page()
        elements = ClusteringLayoutEngine().analyze_page(page)["elements"]

        claimed = [o for e in elements for o in e["spanOrdinals"]]
        assert sorted(claimed) == [s.ordinal for s in page.spans]
        for element in elements:
            if element["elementType"] in ("TABLE", "TABLE_ROW"):
                assert element["spanOrdinals"] == []

    def test_title_detected_as_header_and_body_as_paragraph(self):
        elements = _analyze()["elements"]

        headers = [e for e in elements if e["elementType"] == "HEADER"]
        paragraphs = [e for e in elements if e["elementType"] == "PARAGRAPH"]
        assert [h["spanOrdinals"] for h in headers] == [[0]]
        assert [0] not in [p["spanOrdinals"] for p in paragraphs]
        assert any(set(p["spanOrdinals"]) == {1, 2, 3, 4} for p in paragraphs)

    def test_boxes_are_canonical_numbers(self):
        for element in _analyze()["elements"]:
            for key in ("x", "y", "width", "height"):
                value = element[key]
                assert isinstance(value, float)
                assert round(value, 1) == value  # 0.1pt grid

    def test_empty_page_still_declares_not_implemented(self):
        page = PageSpans(page_index=3, width_pt=612.0, height_pt=792.0, spans=[])

        payload = ClusteringLayoutEngine().analyze_page(page)

        assert payload["pageIndex"] == 3
        assert payload["elements"] == []
        assert payload["notImplemented"] == ["CHECKBOX", "SIGNATURE"]


class TestOcrShapedSpans:
    def test_no_font_data_anywhere_no_crash_lines_and_paragraphs_form(self):
        spans = [
            make_span(0, "OCRTITLE", 72, 40, 70, 14),
            make_span(1, "ocr", 72, 100, 24, 11),
            make_span(2, "body", 102, 101.5, 30, 10.5),  # jittered boxes
            make_span(3, "second", 72, 118, 40, 11),
            make_span(4, "line", 118, 117.2, 26, 11.3),
        ]
        page = PageSpans(page_index=0, width_pt=612.0, height_pt=792.0, spans=spans)

        payload = ClusteringLayoutEngine().analyze_page(page)

        paragraphs = [e for e in payload["elements"] if e["elementType"] == "PARAGRAPH"]
        assert any(set(p["spanOrdinals"]) == {1, 2, 3, 4} for p in paragraphs)


class TestPerElementDetectorIdentity:
    """Spec 3: pixel detectors stamp their own identity; clustered elements
    keep the engine's class-level identity. The serializer is the seam."""

    def test_detector_fields_override_the_engine_default(self):
        engine = ClusteringLayoutEngine()
        own_identity = Element(
            element_type="CHECKBOX",
            box=Box(72.0, 108.0, 10.1, 10.1),
            spans=(),
            confidence=0.9,
            attributes={"checked": True, "fillRatio": 0.31},
            detector="checkbox-cv",
            detector_version="9.9.9",
        )
        engine_identity = Element(
            element_type="PARAGRAPH",
            box=Box(72.0, 200.0, 100.0, 11.0),
            spans=(),
            confidence=0.9,
        )

        payload = engine._serialize([own_identity, engine_identity])

        assert payload[0]["detector"] == "checkbox-cv"
        assert payload[0]["detectorVersion"] == "9.9.9"
        assert payload[1]["detector"] == "clustering"
        assert payload[1]["detectorVersion"] == __version__


class TestPixelDetections:
    def _checkbox_raster(self):
        image = _blank_letter_raster()
        draw = ImageDraw.Draw(image)
        # 28 px checked square at px (400, 600) = canonical (144.0, 216.0),
        # clear of every _native_page span.
        draw.rectangle([400, 600, 427, 627], outline="black", width=3)
        draw.line([404, 604, 423, 623], fill="black", width=3)
        draw.line([423, 604, 404, 623], fill="black", width=3)
        return _raster(image)

    def test_checkbox_element_appended_with_its_own_identity(self):
        payload = ClusteringLayoutEngine().analyze_page(
            _native_page(), raster=self._checkbox_raster()
        )

        checkboxes = [e for e in payload["elements"] if e["elementType"] == "CHECKBOX"]
        assert len(checkboxes) == 1
        checkbox = checkboxes[0]
        assert checkbox["detector"] == "checkbox-cv"
        assert checkbox["detectorVersion"] == __version__
        assert checkbox["attributes"]["checked"] is True
        assert checkbox["spanOrdinals"] == []
        assert (checkbox["x"], checkbox["y"]) == (144.0, 216.0)
        clustered = [e for e in payload["elements"] if e["elementType"] != "CHECKBOX"]
        assert clustered and all(e["detector"] == "clustering" for e in clustered)

    def test_ordinals_stay_sequential_with_detections_appended(self):
        payload = ClusteringLayoutEngine().analyze_page(
            _native_page(), raster=self._checkbox_raster()
        )

        elements = payload["elements"]
        assert [e["ordinal"] for e in elements] == list(range(len(elements)))
        # Detections ride BEHIND the clustered reading order.
        assert elements[-1]["elementType"] == "CHECKBOX"

    def test_raster_present_retires_both_from_not_implemented(self):
        payload = ClusteringLayoutEngine().analyze_page(
            _native_page(), raster=_raster(_blank_letter_raster())
        )

        assert payload["notImplemented"] == []

    def _signature_raster(self):
        image = _blank_letter_raster()
        # A sine squiggle across envelope px (400, 1500, 300, 80) = canonical
        # (144.0, 540.0, 108.0, 28.8) — 108 pt wide, aspect ~3.5, clear of
        # every _native_page span (they all sit above 400 pt). Same drawing
        # as test_signature._draw_squiggle, repeated by convention.
        amplitude = 80 / 2.0 - 3
        points = [
            (
                400 + step,
                1500 + 80 / 2.0 + amplitude * math.sin(4.0 * math.pi * step / 300),
            )
            for step in range(301)
        ]
        ImageDraw.Draw(image).line(points, fill="black", width=3, joint="curve")
        return _raster(image)

    def test_signature_element_appended_with_its_own_identity(self):
        payload = ClusteringLayoutEngine().analyze_page(
            _native_page(), raster=self._signature_raster()
        )

        signatures = [e for e in payload["elements"] if e["elementType"] == "SIGNATURE"]
        assert len(signatures) == 1
        signature = signatures[0]
        assert signature["detector"] == "signature-cv"
        assert signature["detectorVersion"] == __version__
        assert set(signature["attributes"]) == {"inkFraction"}
        assert signature["spanOrdinals"] == []
        assert 142.0 <= signature["x"] <= 146.0
        assert 537.0 <= signature["y"] <= 543.0
        clustered = [
            e
            for e in payload["elements"]
            if e["elementType"] not in ("CHECKBOX", "SIGNATURE")
        ]
        assert clustered and all(e["detector"] == "clustering" for e in clustered)

    def test_printed_spans_never_become_signatures(self):
        """The engine hands the detector the page's OWN span boxes: ink drawn
        exactly where _native_page declares a span is machine text and must
        not surface, even at signature-friendly geometry. Drawn wide enough
        to clear every shape gate — only the span-overlap gate rejects it."""
        image = _blank_letter_raster()
        # _native_page span 0 is "TITLE" at pt (72, 40, 60, 14). Draw a
        # squiggle inside a span-shaped region widened to signature size at
        # pt (72, 40, 108, 22) = px (200, 111, 300, 61) and DECLARE the span
        # box over it via a widened page.
        amplitude = 61 / 2.0 - 3
        points = [
            (
                200 + step,
                111 + 61 / 2.0 + amplitude * math.sin(4.0 * math.pi * step / 300),
            )
            for step in range(301)
        ]
        ImageDraw.Draw(image).line(points, fill="black", width=3, joint="curve")
        page = PageSpans(
            page_index=0,
            width_pt=612.0,
            height_pt=792.0,
            spans=[make_span(0, "PRINTED", 72, 40, 108, 22, size=14.0,
                             font="Helvetica-Bold")],
        )

        payload = ClusteringLayoutEngine().analyze_page(page, raster=_raster(image))

        assert not any(e["elementType"] == "SIGNATURE" for e in payload["elements"])
        assert payload["notImplemented"] == []
