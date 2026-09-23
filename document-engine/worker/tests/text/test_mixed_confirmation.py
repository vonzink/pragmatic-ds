"""A MIXED candidate is confirmed on pixels before uncoveredRegions is published.

The image-coverage rule alone sends every browser-printed statement with a chart,
banner, or logo block to OCR (measured: a Chrome print with one 17%-of-page chart
came back MIXED with 18% uncovered). These pages are built in memory with reportlab
— a real text layer plus embedded images — and pushed through extract_text, so the
whole path is exercised: pdfplumber images -> uncovered regions -> floor -> coarse
render -> textlike -> verdict. The scan pasted below is the committed 200-DPI
scanned_paystub fixture raster, so "text" means real print, not a drawing of it.
"""

import io

import pytest
from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.pagesizes import letter
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas as rl_canvas

from pragmaticds_docengine_worker.text import VERDICT_MIXED, VERDICT_NATIVE, extract_text

PAGE_W, PAGE_H = letter  # 612 x 792
BODY = [
    "Checking Account Statement",
    "Account ending 4521  Statement period 03/01/2026 - 03/31/2026",
    "03/02/2026  POS PURCHASE MERCHANT 1     -13.37     4,986.63",
    "03/03/2026  POS PURCHASE MERCHANT 2     -26.74     4,959.89",
    "03/04/2026  DIRECT DEPOSIT EMPLOYER   2,450.00     7,409.89",
    "03/05/2026  ONLINE TRANSFER TO SAVINGS  -500.00     6,909.89",
    "Ending balance 6,909.89",
]


def _chart() -> Image.Image:
    """A bar chart with axis labels — a graphic, whatever OCR would make of it."""
    image = Image.new("RGB", (1200, 500), "white")
    draw = ImageDraw.Draw(image)
    for i in range(12):
        draw.rectangle((100 + i * 85, 420 - i * 28, 160 + i * 85, 420), fill=(40, 80, 160))
    draw.line((80, 420, 1150, 420), fill="black", width=3)
    draw.line((80, 30, 80, 420), fill="black", width=3)
    for i in range(5):
        draw.text((20, 410 - i * 95), f"{i * 1000}", fill="black")
    return image


def _scan_half(fixture_bytes) -> Image.Image:
    """The printed (top) half of the committed scanned paystub raster."""
    raster = Image.open(io.BytesIO(fixture_bytes("scanned_paystub_page0.png"))).convert("RGB")
    return raster.crop((0, 0, raster.width, raster.height // 2))


def _page(images: list[tuple[Image.Image, float, float, float, float]], *, text=True) -> bytes:
    """A letter page with `BODY` as a real text layer and `images` drawn at
    (x, y_from_bottom, width, height) in points."""
    buffer = io.BytesIO()
    canvas = rl_canvas.Canvas(buffer, pagesize=letter, invariant=1)
    if text:
        canvas.setFont("Helvetica", 10)
        for row, line in enumerate(BODY):
            canvas.drawString(36, PAGE_H - 50 - row * 14, line)
    for image, x, y, width, height in images:
        canvas.drawImage(ImageReader(image), x, y, width=width, height=height)
    canvas.showPage()
    canvas.save()
    return buffer.getvalue()


def _only_page(pdf_bytes: bytes):
    pages = extract_text(pdf_bytes, [])
    assert len(pages) == 1
    return pages[0]


class TestGraphicsStayNative:
    def test_a_chart_on_a_native_page_is_native(self):
        # 17% of the page, no words over it: MIXED by coverage, NATIVE on pixels.
        page = _only_page(_page([(_chart(), 36, 300, 540, 150)]))
        assert page.verdict == VERDICT_NATIVE
        assert page.uncovered_regions is None
        assert len(page.spans) > 20  # the text layer is intact

    def test_a_full_page_background_image_is_native(self):
        background = Image.new("RGB", (850, 1100), (245, 245, 250))
        page = _only_page(_page([(background, 0, 0, PAGE_W, PAGE_H)]))
        assert page.verdict == VERDICT_NATIVE

    def test_a_chart_and_a_logo_together_are_native(self):
        logo = Image.new("RGB", (300, 80), (0, 70, 140))
        ImageDraw.Draw(logo).ellipse((5, 5, 75, 75), fill="white")
        page = _only_page(_page([(_chart(), 36, 300, 540, 150), (logo, 36, 740, 135, 36)]))
        assert page.verdict == VERDICT_NATIVE


class TestPrintStaysMixed:
    def test_a_pasted_scan_is_mixed_with_its_region(self, fixture_bytes):
        page = _only_page(_page([(_scan_half(fixture_bytes), 0, 0, PAGE_W, PAGE_H / 2)]))
        assert page.verdict == VERDICT_MIXED
        assert page.uncovered_regions is not None
        area = sum(r.width * r.height for r in page.uncovered_regions)
        assert area / (PAGE_W * PAGE_H) > 0.45  # the whole pasted half, not a sliver

    def test_a_pasted_scan_next_to_a_chart_keeps_only_the_scan(self, fixture_bytes):
        """The confirmation is per REGION: the chart is dropped, the scan is kept,
        and OCR's coverage gate is never asked to account for chart ink."""
        scan = _scan_half(fixture_bytes)
        page = _only_page(
            _page([(scan, 0, 0, PAGE_W, PAGE_H / 2), (_chart(), 36, 560, 540, 150)])
        )
        assert page.verdict == VERDICT_MIXED
        assert page.uncovered_regions is not None
        # Every kept region lies in the scan's half of the page (canonical y >= 396).
        assert all(r.y >= PAGE_H / 2 - 1.0 for r in page.uncovered_regions), page.uncovered_regions

    @pytest.mark.parametrize("degrees", [90, 180, 270])
    def test_a_rotated_page_still_finds_its_pasted_scan(self, fixture_bytes, rotate_pdf, degrees):
        """The render is display-space; the regions are canonical. A /Rotate page
        whose scan half came back NATIVE would mean the crop looked at the wrong
        pixels."""
        pdf = rotate_pdf(_page([(_scan_half(fixture_bytes), 0, 0, PAGE_W, PAGE_H / 2)]), degrees)
        page = _only_page(pdf)
        assert page.rotation == degrees
        assert page.verdict == VERDICT_MIXED, page.uncovered_regions

    def test_form_captions_under_a_value_only_text_layer_are_kept(self):
        """A payroll-portal W-2 (Dayforce, measured 2026-09-16): the form — every box
        caption, "Wage and Tax Statement", "Copy B" — is ONE page image, and the text
        layer carries only the filled-in values. The values' top/bottom edges slice the
        image into strips a few points tall, each too short and too sparse to look like
        print on its own, so every caption was dropped and the page classified UNKNOWN.
        Judged as a whole, with the native words masked, the image is plainly print."""
        dpi = 200
        form = Image.new("RGB", (int(PAGE_W * dpi / 72), int(PAGE_H * dpi / 72)), "white")
        draw = ImageDraw.Draw(form)
        font = ImageFont.load_default(size=18)  # ~6.5pt, a W-2 caption
        captions = []  # (x_pt, y_pt from top) of each caption's centre
        values = []  # (text, x_pt, y_pt from top) — the native layer
        for row in range(14):
            for col in range(3):
                x_pt, y_pt = 40 + col * 180, 60 + row * 50
                draw.rectangle(
                    (x_pt * dpi / 72, y_pt * dpi / 72, (x_pt + 170) * dpi / 72,
                     (y_pt + 45) * dpi / 72),
                    outline="black", width=2,
                )
                label = f"{row * 3 + col + 1} Social security wages box"
                draw.text(((x_pt + 4) * dpi / 72, (y_pt + 3) * dpi / 72), label, fill="black",
                          font=font)
                captions.append((x_pt + 40, y_pt + 6))
                # Staggered per column, so the value edges cut every caption row apart.
                values.append((f"{(row + 1) * 1234.56:,.2f}", x_pt + 60, y_pt + 8 + col * 5))

        buffer = io.BytesIO()
        canvas = rl_canvas.Canvas(buffer, pagesize=letter, invariant=1)
        canvas.drawImage(ImageReader(form), 0, 0, width=PAGE_W, height=PAGE_H)
        canvas.setFont("Helvetica", 9)
        for text, x_pt, y_pt in values:
            canvas.drawString(x_pt, PAGE_H - y_pt - 7, text)
        canvas.showPage()
        canvas.save()

        page = _only_page(buffer.getvalue())
        assert page.verdict == VERDICT_MIXED
        regions = page.uncovered_regions or []
        unread = [
            (x, y) for x, y in captions
            if not any(r.x <= x <= r.x + r.width and r.y <= y <= r.y + r.height for r in regions)
        ]
        assert not unread, f"{len(unread)} of {len(captions)} captions never reach OCR"

    @pytest.mark.parametrize("degrees", [90, 180, 270])
    def test_a_rotated_page_with_a_chart_is_still_native(self, fixture_bytes, rotate_pdf, degrees):
        pdf = rotate_pdf(_page([(_chart(), 36, 300, 540, 150)]), degrees)
        assert _only_page(pdf).verdict == VERDICT_NATIVE
