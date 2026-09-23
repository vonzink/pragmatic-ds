"""One continuous pass over an image source: /v1/render -> /v1/text -> /v1/ocr.

This is the coordinate proof. The three-frame model (ocr/service.py) says engine
pixels become canonical points via `px_box_to_pt(box, dpi)` and then the two
rotation hops. For an image page both hops are the identity — there is no /Rotate
and EXIF orientation is baked in at decode — so the whole mapping collapses to a
single scale by `72 / dpi`, where `dpi` is the value /v1/render derived from the
pixel count and the page row stores.

Getting that wrong is silent: boxes stay plausible and land on the wrong words.
So the expectations are the pixel boxes Pillow reported for the strings it drew,
converted by hand. The fixture is 1224x1584 px = 144 DPI, so the conversion is an
exact halving, and a box that skipped the conversion would read 60.0 where 30.0 is
correct — a failure no tolerance can hide.
"""

import json

import pytest
from image_fixtures import exif_rotated_jpeg

#: 144 DPI => 2 px per point. OCR ink boxes hug glyphs while Pillow's textbbox
#: includes the font's full ascent/descent band, so a few points of slack is the
#: honest agreement bar. A missing 72/dpi scale is off by 2x, a quarter turn by
#: hundreds of points; neither survives this.
BOX_TOLERANCE_PT = 8.0


def render_page(client, render_of, mixed_parts, file_bytes: bytes, page_index: int = 0):
    """`(metadata, png_bytes)` for one page of an image source."""
    parts = mixed_parts(render_of(client, file_bytes))
    metadata = json.loads(parts[0]["content"])
    page = metadata["pages"][page_index]
    png = next(part["content"] for part in parts if part["name"] == page["pngPart"])
    return page, png


def ocr_page(client, ocr_of, page: dict, png: bytes) -> dict:
    """/v1/ocr driven ONLY by what /v1/render said — exactly what the Java
    OCR_PROCESSING stage does with the persisted page row."""
    response = ocr_of(
        client,
        png,
        {
            "pageIndex": page["pageIndex"],
            "widthPt": page["widthPt"],
            "heightPt": page["heightPt"],
            "dpi": page["dpi"],
            "rotation": page["rotation"],
            "regions": [],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def union_of(spans: list[dict]) -> tuple[float, float, float, float]:
    left = min(span["x"] for span in spans)
    top = min(span["y"] for span in spans)
    right = max(span["x"] + span["width"] for span in spans)
    bottom = max(span["y"] + span["height"] for span in spans)
    return left, top, right - left, bottom - top


#: The string the coordinate assertions measure. Its fragments occur exactly ONCE
#: in PAYSTUB_LINES — "Gross" and "Pay" each appear on four lines, so a union keyed
#: on those would silently span half the page and measure nothing.
MEASURED_LINE = "Federal Withholding"


def _compact(text: str) -> str:
    return "".join(text.split())


def line_union(spans: list[dict], line: str) -> tuple[float, float, float, float]:
    """The union box of the spans that make up `line`, in points.

    Tokenisation differs by engine and must not be baked into the expectation:
    Tesseract emits words, while RapidOCR splits a recognised line only on the
    whitespace it actually DETECTED — which for this font is sometimes none, so the
    whole line arrives as `FederalWithholding$312.45`. Fragments are therefore
    matched by containment and then required to RECONSTRUCT the line exactly, so a
    selector that quietly grabbed the wrong spans fails here rather than producing a
    union box that happens to look plausible.
    """
    compact = _compact(line)
    fragments = sorted(
        (span for span in spans if _compact(span["text"]) and _compact(span["text"]) in compact),
        key=lambda span: span["x"],
    )
    assert "".join(_compact(span["text"]) for span in fragments) == compact, (
        f"OCR did not read {line!r}; matched fragments: {[s['text'] for s in fragments]}"
    )
    return union_of(fragments)


class TestImageToOcrSpans:
    def test_ocr_reads_the_paystub_words_off_the_photographed_page(
        self, client, render_of, ocr_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        body = ocr_page(client, ocr_of, page, png)

        text = " ".join(span["text"] for span in body["spans"])
        for phrase in ("Gross", "Pay", "Net", "Earnings", "Period"):
            assert phrase in text, f"OCR did not read {phrase!r}; got: {text}"
        assert body["engine"] in ("RAPIDOCR", "TESSERACT")

    def test_the_money_amounts_survive_the_jpeg_round_trip(
        self, client, render_of, ocr_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        body = ocr_page(client, ocr_of, page, png)

        text = " ".join(span["text"] for span in body["spans"])
        # The numbers are the whole point of an income document.
        assert "2,450.00" in text
        assert "1,884.10" in text


class TestCoordinateFrame:
    def test_spans_land_on_the_pixels_they_were_drawn_at_scaled_by_the_page_dpi(
        self, client, render_of, ocr_of, paystub_image, mixed_parts
    ):
        jpeg, truth = paystub_image("JPEG")

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        body = ocr_page(client, ocr_of, page, png)

        x, y, width, height = line_union(body["spans"], MEASURED_LINE)

        scale = 72.0 / page["dpi"]
        tx, ty, tw, th = truth[MEASURED_LINE]
        assert x == pytest.approx(tx * scale, abs=BOX_TOLERANCE_PT)
        assert y == pytest.approx(ty * scale, abs=BOX_TOLERANCE_PT)
        assert width == pytest.approx(tw * scale, abs=BOX_TOLERANCE_PT)
        assert height == pytest.approx(th * scale, abs=BOX_TOLERANCE_PT)

    def test_an_unconverted_pixel_box_would_have_been_caught(
        self, client, render_of, ocr_of, paystub_image, mixed_parts
    ):
        # The negative control for the assertion above: at 144 DPI the raw pixel
        # value is exactly twice the correct point value, and the line's 570 px top
        # is 285 pt. If the conversion were ever dropped this test fails loudly
        # instead of the boxes quietly drifting off the bottom of the page.
        jpeg, truth = paystub_image("JPEG")

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        body = ocr_page(client, ocr_of, page, png)

        _x, y, _w, _h = line_union(body["spans"], MEASURED_LINE)
        raw_pixel_y = truth[MEASURED_LINE][1]

        assert abs(y - raw_pixel_y) > BOX_TOLERANCE_PT
        assert y < page["heightPt"], "a span may never sit outside its own page box"

    def test_every_span_lies_inside_the_page_box(
        self, client, render_of, ocr_of, paystub_image, mixed_parts
    ):
        jpeg, _truth = paystub_image("JPEG")

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        body = ocr_page(client, ocr_of, page, png)

        assert body["spans"], "the page produced no spans at all"
        for span in body["spans"]:
            assert -1 <= span["x"] <= page["widthPt"]
            assert -1 <= span["y"] <= page["heightPt"]
            assert span["x"] + span["width"] <= page["widthPt"] + 1
            assert span["y"] + span["height"] <= page["heightPt"] + 1


class TestExifRotatedPhoto:
    def test_a_sideways_phone_photo_reads_upright_and_boxes_stay_in_frame(
        self, client, render_of, ocr_of, mixed_parts
    ):
        # Orientation 6: the sensor stored the pixels turned, every viewer shows
        # them upright. Decode must agree with the viewer, or the borrower's photo
        # OCRs as noise and every evidence box is a quarter turn out of place.
        line = "Gross Pay $2,450.00"
        jpeg, truth = exif_rotated_jpeg([(60, 510, line)])

        page, png = render_page(client, render_of, mixed_parts, jpeg)
        assert (page["widthPt"], page["heightPt"]) == (612.0, 792.0)

        body = ocr_page(client, ocr_of, page, png)
        assert body["spans"], "a sideways-stored photo OCR'd to nothing"

        x, y, _w, _h = line_union(body["spans"], line)
        scale = 72.0 / page["dpi"]
        tx, ty, _tw, _th = truth[line]
        assert x == pytest.approx(tx * scale, abs=BOX_TOLERANCE_PT)
        assert y == pytest.approx(ty * scale, abs=BOX_TOLERANCE_PT)


class TestTextVerdictDrivesOcr:
    def test_the_verdict_the_engine_would_act_on_is_the_one_that_sends_it_to_ocr(
        self, client, text_of, paystub_image
    ):
        # WorkerParserAdapter.OCR_ELIGIBLE = {SCANNED, MIXED}. This is the link in
        # the chain that decides whether an image page is ever looked at.
        jpeg, _truth = paystub_image("JPEG")

        verdict = text_of(client, jpeg).json()["pages"][0]["verdict"]

        assert verdict in ("SCANNED", "MIXED")
