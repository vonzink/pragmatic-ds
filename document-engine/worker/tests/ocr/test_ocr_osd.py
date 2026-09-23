"""OSD rotation detection + projection-profile arbitration, against real fixtures.

Convention throughout: rotation = how far the CONTENT appears rotated CLOCKWISE in
the raster (Tesseract OSD's convention, matched by geometry.unrotate_box).
"""

import pytest
from ocr_fixture_helpers import fixture_image
from PIL import Image, ImageDraw

from pragmaticds_docengine_worker.ocr.gates import OcrConfig
from pragmaticds_docengine_worker.ocr.osd import (
    HORIZONTAL,
    VERTICAL,
    detect_rotation,
    projection_profile_axis,
)


def _stripes(direction: str) -> Image.Image:
    """Synthetic text-like stripes: horizontal bands mimic upright text lines."""
    image = Image.new("RGB", (400, 400), "white")
    draw = ImageDraw.Draw(image)
    for offset in range(20, 400, 40):
        if direction == "h":
            draw.rectangle([20, offset, 380, offset + 12], fill="black")
        else:
            draw.rectangle([offset, 20, offset + 12, 380], fill="black")
    return image


class TestProjectionProfileAxis:
    def test_horizontal_text_lines_read_horizontal(self):
        assert projection_profile_axis(_stripes("h")) == HORIZONTAL

    def test_vertical_structure_reads_vertical(self):
        assert projection_profile_axis(_stripes("v")) == VERTICAL

    def test_blank_image_has_no_axis(self):
        assert projection_profile_axis(Image.new("RGB", (100, 100), "white")) is None


class TestDetectRotation:
    @pytest.mark.slow
    @pytest.mark.parametrize(
        "name,expected",
        [
            ("scanned_paystub_page0.png", 0),
            ("scanned_rot90_page0.png", 90),
            ("scanned_rot180_page0.png", 180),
            ("scanned_rot270_page0.png", 270),
        ],
    )
    def test_all_four_rotations_detected(self, name, expected):
        rotation, confidence = detect_rotation(fixture_image(name), OcrConfig())
        assert rotation == expected
        assert confidence >= OcrConfig().osd_confidence_threshold

    def test_blank_image_returns_zero_without_crashing(self):
        """Tesseract OSD throws on blank input; the ladder must not."""
        blank = Image.new("RGB", (1700, 2200), "white")
        assert detect_rotation(blank, OcrConfig()) == (0, 0.0)

    @pytest.mark.slow
    def test_low_osd_confidence_is_arbitrated_by_projection_profile(self):
        """The degraded fixture: OSD misreads it as 270 at confidence ~0.1, but its
        text lines are horizontal — arbitration must overrule to 0."""
        rotation, confidence = detect_rotation(
            fixture_image("degraded_paystub_page0.png"), OcrConfig()
        )
        assert rotation == 0
        assert confidence < OcrConfig().osd_confidence_threshold

    @pytest.mark.parametrize("orientation", [180, 270])
    @pytest.mark.parametrize("raw_score", [0.21, 0.87])
    def test_a_noise_level_osd_answer_is_ignored(self, monkeypatch, orientation, raw_score):
        """A payroll-portal W-2 (Dayforce, 2026-09-16): OSD answered 180 at raw scores
        0.21 and 0.87 on two upright pages. Same axis, so arbitration let it
        stand; the page was OCR'd upside down, RapidOCR's own line classifier turned
        each line back, and mapping the boxes through 180 reversed every line's word
        order — "Employer identification number" became "number identification
        Employer" and no caption anchor could match. A score that low is no answer at
        all: the axis prior wins, whichever axis OSD named."""
        monkeypatch.setattr(
            "pytesseract.image_to_osd",
            lambda *_a, **_k: {"orientation": orientation, "orientation_conf": raw_score},
        )
        rotation, _ = detect_rotation(_stripes("h"), OcrConfig())
        assert rotation == 0

    def test_a_weak_but_real_osd_answer_on_the_measured_axis_still_stands(self, monkeypatch):
        """Above the noise floor and on the measured axis, an unsure OSD 180 is kept:
        a raw 4.5 is weak, but it is an answer."""
        monkeypatch.setattr(
            "pytesseract.image_to_osd",
            lambda *_a, **_k: {"orientation": 180, "orientation_conf": 4.5},
        )
        rotation, _ = detect_rotation(_stripes("h"), OcrConfig())
        assert rotation == 180
