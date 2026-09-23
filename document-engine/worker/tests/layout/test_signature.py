"""signature.py — connected-ink signature detection on in-memory rasters (Spec 3 T4).

Same convention as test_checkbox.py: images are CONSTRUCTED in memory
(PIL/numpy), nothing touches fixtures/, so the provenance gate is not
involved. Geometry convention of every helper: dpi=200, so px * 0.36 = pt
exactly; the 600x800 px page is 216.0 x 288.0 pt.

Span boxes are passed CANONICAL (rotation-0 pt) exactly as the engine will
pass them from PageSpans — ink under a span box is machine text, not
handwriting, and must never become a SIGNATURE element.
"""

import math

import numpy as np
from PIL import Image, ImageDraw

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.geometry import Box, unrotate_box
from pragmaticds_docengine_worker.layout.signature import (
    CONFIDENCE_FLOOR,
    MAX_HEIGHT_PT,
    OUTSIDE_SPAN_FRACTION_MIN,
    SIGNATURE_DETECTOR,
    STROKE_VARIANCE_MIN,
    detect_signatures,
)

DPI = 200
#: 600x800 px at 200 dpi = a 216.0 x 288.0 pt page.
PAGE_W_PT, PAGE_H_PT = 216.0, 288.0


def _page(width_px: int = 600, height_px: int = 800) -> Image.Image:
    return Image.new("RGB", (width_px, height_px), "white")


def _draw_squiggle(image, x, y, width, height, stroke=3):
    """A bezier-like signature stroke: a polyline tracing two full sine
    oscillations across the envelope px (x, y, width, height) — thin ink,
    wide aspect, continuously turning stroke direction (what the variance
    gate measures)."""
    amplitude = height / 2.0 - stroke
    points = [
        (
            x + step,
            y + height / 2.0 + amplitude * math.sin(4.0 * math.pi * step / width),
        )
        for step in range(width + 1)
    ]
    ImageDraw.Draw(image).line(points, fill="black", width=stroke, joint="curve")


def _degrade(image: Image.Image, seed: int) -> Image.Image:
    """The fixtures' _degrade recipe (downscale-upscale blur + deterministic
    noise), turned up (//6, +-90 vs the fixtures' //3, +-70) so the result
    sits decisively below the confidence floor — same helper as
    test_checkbox.py, repeated by convention (test files stay self-contained)."""
    small = image.resize((image.width // 6, image.height // 6), Image.BILINEAR)
    blurred = small.resize(image.size, Image.BILINEAR)
    rng = np.random.default_rng(seed)
    noise = rng.integers(-90, 90, size=(image.height, image.width, 3))
    pixels = np.asarray(blurred).astype(np.int16) + noise
    return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))


def _detect(image, rotation=0, span_boxes=()):
    return detect_signatures(
        image, DPI, rotation, PAGE_W_PT, PAGE_H_PT, list(span_boxes)
    )


class TestDetection:
    def test_squiggle_detected_with_identity_and_ink_fraction(self):
        image = _page()
        # Envelope px (200, 300, 200, 40) = pt (72.0, 108.0, 72.0, 14.4);
        # PIL's stroke caps widen the drawn component by a pixel or two, so
        # the box is asserted against the envelope, not an exact grid value.
        _draw_squiggle(image, 200, 300, 200, 40)

        elements = _detect(image)

        assert len(elements) == 1
        element = elements[0]
        assert element.element_type == "SIGNATURE"
        assert 70.0 <= element.box.x <= 73.0
        assert 106.0 <= element.box.y <= 110.0
        assert 70.0 <= element.box.width <= 76.0
        assert 10.0 <= element.box.height <= 16.0
        assert set(element.attributes) == {"inkFraction"}
        # Handwriting is THIN ink: strokes trace the envelope, never fill it.
        assert 0.02 <= element.attributes["inkFraction"] <= 0.40
        assert CONFIDENCE_FLOOR <= element.confidence <= 1.0
        assert element.detector == SIGNATURE_DETECTOR == "signature-cv"
        assert element.detector_version == __version__
        assert element.spans == ()

    def test_two_signatures_come_back_in_reading_order(self):
        image = _page()
        _draw_squiggle(image, 300, 150, 160, 36)  # upper — 57.6 pt wide
        _draw_squiggle(image, 100, 500, 200, 40)  # lower

        elements = _detect(image)

        assert len(elements) == 2
        assert elements[0].box.y < elements[1].box.y


class TestSizeAndShapeGates:
    def test_narrow_component_rejected(self):
        image = _page()
        # 120 px envelope = 43.2 pt — below the 54 pt width floor.
        _draw_squiggle(image, 200, 300, 120, 40)

        assert _detect(image) == []

    def test_low_aspect_component_rejected(self):
        image = _page()
        # 160x120 px = 57.6x43.2 pt: width clears 54 pt and height clears the
        # 72 pt ceiling, but aspect ~1.3 falls below 2.0 — a blob, not a stroke.
        _draw_squiggle(image, 200, 300, 160, 120)

        assert _detect(image) == []

    def test_empty_signature_line_rejected(self):
        """The unsigned-contract case: a drawn signature RULE is 300 px wide
        (108 pt), aspect ~100, fully outside spans — only the
        stroke-direction variance gate rejects it. Its gradients all point
        one way (across the rule), so the doubled-angle variance is ~0,
        far below STROKE_VARIANCE_MIN."""
        image = _page()
        ImageDraw.Draw(image).line([(150, 500), (450, 500)], fill="black", width=3)

        assert STROKE_VARIANCE_MIN > 0.0  # the gate exists and is pinned
        assert _detect(image) == []

    def test_grid_shaped_component_rejected(self):
        """The ruled_table.pdf case: a drawn grid binarizes into ONE
        8-connected component. Hand-computed here: bbox ~523x243 px =
        ~188x88 pt — width clears 54 pt, aspect ~2.1 clears 2.0, ink is
        rulings so ~100% lies outside spans, and the horizontal/vertical
        stroke mix yields doubled-angle variance ~0.3-0.4, above the gate.
        Only the 72 pt height ceiling rejects it. This is what keeps the
        layout_ruled_table.json golden element-clean in step 2."""
        image = _page()
        draw = ImageDraw.Draw(image)
        for y in (200, 280, 360, 440):
            draw.line([(40, y), (560, y)], fill="black", width=3)
        for x in (40, 213, 386, 559):
            draw.line([(x, 200), (x, 440)], fill="black", width=3)

        assert MAX_HEIGHT_PT == 72.0
        assert _detect(image) == []


class TestSpanOverlapGate:
    def test_ink_fully_under_a_span_box_rejected(self):
        """Printed text: every glyph the page shows is claimed by a text-span
        box (native or OCR). The SAME ink that is a signature when unclaimed
        must vanish when a span box covers it."""
        image = _page()
        _draw_squiggle(image, 200, 300, 200, 40)
        covering_span = Box(66.0, 100.0, 84.0, 30.0)  # px 183..417 x 277..362

        assert len(_detect(image)) == 1  # control: unclaimed -> detected
        assert _detect(image, span_boxes=[covering_span]) == []

    def test_component_mostly_outside_span_boxes_still_detected(self):
        """The >= 60%-outside semantics: a span clipping the stroke's left
        quarter (px 200..251 of a 198..403 component — ~25% of its columns)
        leaves well over 60% of the ink unclaimed, so the signature stands.
        Real case: a signature overhanging the printed name beside it."""
        image = _page()
        _draw_squiggle(image, 200, 300, 200, 40)
        clipping_span = Box(72.0, 100.0, 18.0, 30.0)

        assert OUTSIDE_SPAN_FRACTION_MIN == 0.6
        elements = _detect(image, span_boxes=[clipping_span])

        assert len(elements) == 1
        assert elements[0].element_type == "SIGNATURE"


class TestDegradedOmission:
    def test_blur_and_noise_below_floor_is_omitted_not_guessed(self):
        """Design D6: a candidate the detector cannot be >= 0.5 confident in
        is DROPPED — the bound field later falls through its ladder and
        persists as missing (T7's SIGNATURE_PRESENCE rung then reports the
        anchor as unfound or the state as unprovable), instead of trusting a
        phantom SIGNED."""
        image = _page()
        _draw_squiggle(image, 200, 300, 200, 40)

        degraded = _degrade(image, seed=20260808)

        assert _detect(degraded) == []


class TestRotatedRaster:
    """A /Rotate 90 page renders its content UPRIGHT — the display frame is
    what a reader sees, so a signature lies HORIZONTALLY in the raster (an
    800x600 px frame for this 216.0 x 288.0 pt page). The shape gates measure
    that frame, because "wide, flat handwriting" is a claim about ink as READ:
    rotating a page must not change whether its ink is a signature. Canonical
    rotation-0 space on such a page is sideways for EVERYTHING — the page's
    own text spans included — which is what keeps the downstream window
    intersection self-consistent."""

    def test_box_on_a_rotated_raster_lands_in_rotation0_canonical_space(self):
        """One raster, two readings: as an unrotated 288.0 x 216.0 pt page it
        yields the display-frame box directly; as the /Rotate 90 display frame
        of a 216.0 x 288.0 pt page it must yield exactly unrotate_box of that
        — the conversion is the only difference between the two answers."""
        image = _page(800, 600)
        _draw_squiggle(image, 200, 300, 200, 40)

        display_box = detect_signatures(image, DPI, 0, 288.0, 216.0, [])[0].box
        elements = detect_signatures(image, DPI, 90, PAGE_W_PT, PAGE_H_PT, [])

        assert len(elements) == 1
        assert elements[0].box == unrotate_box(display_box, 90, PAGE_W_PT, PAGE_H_PT)
        # Sideways in canonical space: wide as read, tall as stored.
        assert display_box.width > display_box.height
        assert elements[0].box.height > elements[0].box.width
