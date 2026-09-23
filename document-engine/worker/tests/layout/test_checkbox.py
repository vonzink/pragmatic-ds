"""checkbox.py — contour checkbox detection on in-memory rasters (Spec 3 T3).

FIRST worker tests that CONSTRUCT their images in memory (PIL/numpy) instead
of reading committed fixtures. Nothing here touches fixtures/ — no fixture
files means no MANIFEST.json entry; the provenance gate governs only files
under fixtures/.

Geometry convention of every helper: dpi=200, so px * 0.36 = pt exactly. A
28 px square drawn at px (200, 300) is the canonical 10.1 x 10.1 pt box at
(72.0, 108.0) — hand-computed, per geometry.py's change discipline.
"""

import numpy as np
from PIL import Image, ImageDraw

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.layout.checkbox import (
    CHECKBOX_DETECTOR,
    CHECKED_FILL_MIN,
    CONFIDENCE_FLOOR,
    detect_checkboxes,
)

DPI = 200
#: 600x800 px at 200 dpi = a 216.0 x 288.0 pt page.
PAGE_W_PT, PAGE_H_PT = 216.0, 288.0


def _page(width_px: int = 600, height_px: int = 800) -> Image.Image:
    return Image.new("RGB", (width_px, height_px), "white")


def _draw_square(image, x, y, side, stroke=3):
    ImageDraw.Draw(image).rectangle(
        [x, y, x + side - 1, y + side - 1], outline="black", width=stroke
    )


def _draw_bowl_glyph(image, x, y, width, height, stroke=6):
    """A bold 'D': a rounded-right silhouette hollowed by a smaller one — the
    glyph shape that DOES frame all four sides of its bounding rect."""
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(
        [x, y, x + width - 1, y + height - 1],
        radius=height // 2,
        fill="black",
        corners=(False, True, True, False),
    )
    draw.rounded_rectangle(
        [x + stroke, y + stroke, x + width - 1 - stroke, y + height - 1 - stroke],
        radius=(height - 2 * stroke) // 2,
        fill="white",
        corners=(False, True, True, False),
    )


def _draw_check(image, x, y, side, stroke=3):
    """An X through the square's interior — the checked state."""
    draw = ImageDraw.Draw(image)
    draw.line([x + 4, y + 4, x + side - 5, y + side - 5], fill="black", width=stroke)
    draw.line([x + side - 5, y + 4, x + 4, y + side - 5], fill="black", width=stroke)


def _degrade(image: Image.Image, seed: int) -> Image.Image:
    """The fixtures' _degrade recipe (downscale-upscale blur + deterministic
    noise), turned up (//6, +-90 vs the fixtures' //3, +-70) so the result
    sits decisively below the confidence floor."""
    small = image.resize((image.width // 6, image.height // 6), Image.BILINEAR)
    blurred = small.resize(image.size, Image.BILINEAR)
    rng = np.random.default_rng(seed)
    noise = rng.integers(-90, 90, size=(image.height, image.width, 3))
    pixels = np.asarray(blurred).astype(np.int16) + noise
    return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))


def _detect(image, rotation=0):
    return detect_checkboxes(image, DPI, rotation, PAGE_W_PT, PAGE_H_PT)


class TestDetection:
    def test_unchecked_square_detected_with_canonical_box(self):
        image = _page()
        _draw_square(image, 200, 300, 28)

        elements = _detect(image)

        assert len(elements) == 1
        element = elements[0]
        assert element.element_type == "CHECKBOX"
        # 28 px at 200 dpi = 10.08 pt, rounded to the 0.1 pt grid.
        assert element.box == Box(72.0, 108.0, 10.1, 10.1)
        assert element.attributes["checked"] is False
        assert element.attributes["fillRatio"] < CHECKED_FILL_MIN
        assert CONFIDENCE_FLOOR <= element.confidence <= 1.0
        assert element.detector == CHECKBOX_DETECTOR == "checkbox-cv"
        assert element.detector_version == __version__
        assert element.spans == ()

    def test_checked_square_reports_checked_with_fill_ratio(self):
        image = _page()
        _draw_square(image, 200, 300, 28)
        _draw_check(image, 200, 300, 28)

        elements = _detect(image)

        assert len(elements) == 1
        assert elements[0].attributes["checked"] is True
        assert elements[0].attributes["fillRatio"] >= CHECKED_FILL_MIN
        assert CONFIDENCE_FLOOR <= elements[0].confidence <= 1.0

    def test_two_boxes_come_back_in_reading_order(self):
        image = _page()
        _draw_square(image, 300, 200, 28)  # upper, unchecked
        _draw_square(image, 100, 500, 28)  # lower, checked
        _draw_check(image, 100, 500, 28)

        elements = _detect(image)

        assert [e.attributes["checked"] for e in elements] == [False, True]
        assert elements[0].box.y < elements[1].box.y


class TestSizeAndShapeGates:
    def test_too_small_square_rejected(self):
        image = _page()
        _draw_square(image, 200, 300, 16)  # 5.8 pt — below the 8 pt band floor

        assert _detect(image) == []

    def test_too_large_square_rejected(self):
        image = _page()
        _draw_square(image, 200, 300, 80)  # 28.8 pt — above the 24 pt band ceiling

        assert _detect(image) == []

    def test_non_square_rectangle_rejected(self):
        image = _page()
        # 28x60 px = 10.1x21.6 pt: both sides inside the band, but aspect
        # 0.47 falls outside [0.75, 1.33].
        ImageDraw.Draw(image).rectangle([200, 300, 227, 359], outline="black", width=3)

        assert _detect(image) == []

    def test_glyph_shaped_ink_without_a_full_border_rejected(self):
        """An 'M'-shaped stroke passes the size band, the aspect gate, AND
        rectangularity x contrast >= 0.5 — only the border-coverage gate
        rejects it. This is the real-world case: 14 pt bold header glyphs
        (ACME WIDGETS on every tabular fixture) land in the 8-24 pt band."""
        image = _page()
        draw = ImageDraw.Draw(image)
        draw.line([200, 300, 200, 327], fill="black", width=4)   # left stem
        draw.line([227, 300, 227, 327], fill="black", width=4)   # right stem
        draw.line([202, 300, 213, 320], fill="black", width=2)   # left diagonal
        draw.line([225, 300, 214, 320], fill="black", width=2)   # right diagonal

        assert _detect(image) == []

    def test_bowl_glyph_that_does_frame_its_bounding_box_rejected(self):
        """A bold 'D' — the fixtures' ACME WIDGETS header renders one at
        24x28 px (8.6 x 10.1 pt) — clears EVERY other gate: size band, aspect
        0.86, border coverage (a D really does frame all four sides of its
        bounding rect), rectangularity x contrast ~0.84, and an inner fill of
        ~0.21 that would report it CHECKED. Only the quadrilateral gate
        rejects it: a drawn square approximates to four vertices, a bowl to
        six."""
        image = _page()
        _draw_bowl_glyph(image, 200, 300, 24, 28)

        assert _detect(image) == []


class TestDegradedOmission:
    def test_blur_and_noise_below_floor_is_omitted_not_guessed(self):
        """Design D6: a candidate the detector cannot be >= 0.5 confident in
        is DROPPED — the bound field later falls through its ladder and
        persists as missing, instead of trusting a guessed checked-state."""
        image = _page()
        _draw_square(image, 200, 300, 28)
        _draw_check(image, 200, 300, 28)

        degraded = _degrade(image, seed=20260808)

        assert _detect(degraded) == []


class TestRotatedRaster:
    def test_box_on_a_rotated_raster_lands_in_rotation0_canonical_space(self):
        """A page rendered AT /Rotate 90 shows its content a quarter-turn
        clockwise (raster 800x600 for this 600x800 page). The detector must
        hand back the SAME canonical box the rotation-0 render produces.
        Hand-computed: px (200, 300, 28, 28) rotates to raster px
        (472, 200, 28, 28); px_box_to_pt gives (169.9, 72.0, 10.1, 10.1);
        unrotate_box(90) maps it to (72.0, 288 - 169.9 - 10.1, ...) =
        (72.0, 108.0, 10.1, 10.1)."""
        image = _page()
        _draw_square(image, 200, 300, 28)
        rotated = image.rotate(-90, expand=True)  # clockwise = /Rotate 90 render

        elements = detect_checkboxes(rotated, DPI, 90, PAGE_W_PT, PAGE_H_PT)

        assert len(elements) == 1
        assert elements[0].box == Box(72.0, 108.0, 10.1, 10.1)
