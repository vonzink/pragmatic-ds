"""textlike.assess: is an image region print, or a graphic?

The classifier decides whether a MIXED page's uncovered image regions reach OCR at
all. Its failure modes are asymmetric — a graphic sent to OCR costs one wasted
call, a document called a graphic is silently unread — so the cases below pin the
"must be text" side hardest: clean print, small print, a scan fixture, and a
photographed page under uneven light (the case global Otsu alone gets wrong).
"""

import io

import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFont

from pragmaticds_docengine_worker.textlike import MIN_GLYPHS, TEXT_CHECK_DPI, assess

FIXTURE_DPI = 200  # fixtures/*_page0.png are 200-DPI renders


def _blank(width: int, height: int) -> Image.Image:
    return Image.new("L", (width, height), 255)


def _font(size_px: int):
    # Pillow >= 10.1 bundles a TrueType default; no system font dependency.
    return ImageFont.load_default(size=size_px)


def _print_block(size_px: int, lines: int = 8) -> np.ndarray:
    """Rows of body text at `size_px` glyph height, like a rendered paystub."""
    image = _blank(700, 40 + lines * (size_px + 8))
    draw = ImageDraw.Draw(image)
    for row in range(lines):
        draw.text(
            (12, 12 + row * (size_px + 8)),
            "Gross Pay 1,234.56  Federal Tax 212.00  Net Pay 987.65  YTD 45,000.00",
            fill=0,
            font=_font(size_px),
        )
    return np.asarray(image)


def _fixture_gray(fixture_bytes, name: str) -> np.ndarray:
    """A 200-DPI fixture raster downsampled to the check DPI."""
    image = Image.open(io.BytesIO(fixture_bytes(name))).convert("L")
    factor = TEXT_CHECK_DPI / FIXTURE_DPI
    resized = image.resize(
        (max(int(image.width * factor), 1), max(int(image.height * factor), 1)),
        Image.LANCZOS,
    )
    return np.asarray(resized)


class TestPrintIsText:
    @pytest.mark.parametrize("size_px", [8, 11, 14, 20])
    def test_rendered_body_text_is_text_like(self, size_px):
        reading = assess(_print_block(size_px))
        assert reading.text_like, reading
        assert reading.glyph_count >= MIN_GLYPHS
        assert reading.glyph_ink_share > 0.6

    def test_a_single_line_strip_is_text_like(self):
        """One line of a statement: the smallest region a MIXED split produces."""
        strip = _print_block(11, lines=8)[: 11 + 20]
        assert assess(strip).text_like

    def test_scanned_paystub_fixture_is_text_like(self, fixture_bytes):
        reading = assess(_fixture_gray(fixture_bytes, "scanned_paystub_page0.png"))
        assert reading.text_like, reading

    def test_degraded_scan_fixture_is_text_like(self, fixture_bytes):
        """Noise and blur must not turn a scan into a 'graphic'."""
        reading = assess(_fixture_gray(fixture_bytes, "degraded_paystub_page0.png"))
        assert reading.text_like, reading

    def test_photographed_page_under_uneven_light_is_text_like(self, fixture_bytes):
        """A phone shot: paper brightness falls off across the page. Global Otsu
        calls the dark half of the paper ink (measured glyph share ~0.01); the
        adaptive mask must rescue it."""
        gray = _fixture_gray(fixture_bytes, "scanned_paystub_page0.png").astype(np.float64)
        _, columns = np.mgrid[0 : gray.shape[0], 0 : gray.shape[1]]
        shaded = gray * (0.45 + 0.55 * columns / gray.shape[1])
        noise = np.random.default_rng(1).normal(0.0, 6.0, gray.shape)
        photo = np.clip(shaded + noise, 0, 255).astype(np.uint8)

        reading = assess(photo)

        assert reading.text_like, reading


class TestGraphicsAreNotText:
    def test_blank_region_is_not_text(self):
        assert not assess(np.asarray(_blank(600, 300))).text_like

    def test_white_background_image_with_speckle_is_not_text(self):
        gray = np.full((300, 600), 255, dtype=np.uint8)
        gray[::37, ::41] = 0  # isolated single dark pixels
        assert not assess(gray).text_like

    def test_solid_fill_is_not_text(self):
        image = _blank(600, 300)
        ImageDraw.Draw(image).rectangle((50, 50, 550, 250), fill=200)
        assert not assess(np.asarray(image)).text_like

    def test_bar_chart_with_tick_labels_is_not_text(self):
        """Labels are glyph-sized blobs, but the bars own the ink."""
        image = _blank(600, 300)
        draw = ImageDraw.Draw(image)
        for i in range(12):
            draw.rectangle((50 + i * 40, 250 - i * 15, 75 + i * 40, 250), fill=60)
        draw.line((40, 250, 560, 250), fill=0, width=2)
        draw.line((40, 20, 40, 250), fill=0, width=2)
        for i in range(6):
            draw.text((8, 240 - i * 40), f"{i * 500}", fill=0, font=_font(11))
        for i in range(12):
            draw.text((50 + i * 40, 256), "Jan", fill=0, font=_font(11))

        reading = assess(np.asarray(image))

        assert not reading.text_like, reading

    def test_line_chart_is_not_text(self):
        image = _blank(600, 300)
        draw = ImageDraw.Draw(image)
        draw.line([(40 + i * 45, 200 - (i * i * 3) % 120) for i in range(12)], fill=0, width=3)
        draw.line((40, 250, 560, 250), fill=0, width=2)
        assert not assess(np.asarray(image)).text_like

    def test_logo_with_short_wordmark_is_not_text(self):
        image = _blank(400, 120)
        draw = ImageDraw.Draw(image)
        draw.ellipse((10, 10, 110, 110), fill=30)
        draw.text((130, 40), "FIRST BANK", fill=0, font=_font(28))
        reading = assess(np.asarray(image))
        assert not reading.text_like, reading

    def test_gradient_background_is_not_text(self):
        _, columns = np.mgrid[0:300, 0:600]
        gray = (255 * (0.5 + 0.5 * columns / 600)).astype(np.uint8)
        assert not assess(gray).text_like


class TestDegenerateInput:
    def test_empty_and_one_pixel_rasters_are_not_text(self):
        assert not assess(np.zeros((0, 0), dtype=np.uint8)).text_like
        assert not assess(np.zeros((1, 1), dtype=np.uint8)).text_like
        assert not assess(np.zeros((1, 50), dtype=np.uint8)).text_like

    def test_reading_carries_its_numbers(self):
        reading = assess(_print_block(11))
        assert 0.0 < reading.ink_fraction < 1.0
        assert 0.0 < reading.glyph_ink_share <= 1.0
        assert 0.0 < reading.height_agreement <= 1.0
