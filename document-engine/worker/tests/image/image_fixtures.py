"""Synthetic image documents, drawn in memory with ground truth by construction.

Nothing lands in `fixtures/` — that directory is owned by the provenance manifest
and holds PDFs only. Drawing here also means the expected coordinates exist by
construction: `draw_lines` returns the exact pixel box Pillow reports for every
string it painted (`ImageDraw.textbbox`), so the coordinate assertions never
capture an expectation from the code under test.

The font is `ImageFont.load_default(size=...)` — the TrueType face Pillow bundles.
The bitmap default is ~11px and unreadable to any OCR engine; the bundled vector
face renders at whatever size the test asks for and both engines read it cleanly.

Imported flat (`from image_fixtures import ...`), the same way the OCR suite
imports `ocr_fixture_helpers`.

## The one format that cannot be drawn: HEIC

HEIC is HEVC-coded, and nothing in this environment can encode HEVC. `pi-heif`,
the runtime decoder, ships `libde265` and no encoder at all; `pillow-heif`, which
can encode, is GPLv2 because it bundles x265, and CI runs `pip-licenses` over the
whole installed environment — so an encoder cannot be added even as a test-only
dependency (docs/LICENSING.md 5.4, 6).

So the two HEIC rasters below are base64 constants rather than drawings. They were
produced once by `pillow-heif` outside this repo, and the recipe is recorded here so
they can be reproduced rather than trusted:

    pillow_heif.register_heif_opener()
    img = Image.new("RGB", PAGE_PX, "white")
    ImageDraw.Draw(img).rectangle(HEIC_RECT_PX, fill="black")
    img.save(buf, format="HEIF", quality=50)

Quality 50 keeps them under 2 KB and still decodes a solid rectangle to exactly 0
inside and 255 outside, so `HEIC_RECT_PX` is real ground truth and not a tolerance
dressed up as one. Nothing lands in `fixtures/`, which stays PDF-only.
"""

import base64
import io

from PIL import Image, ImageDraw, ImageFont

#: US Letter at exactly 144 DPI. Chosen so the nominal-DPI rule is hand-checkable:
#: 1584 / 792 = 2 px per point, and the canonical page comes out 612.0 x 792.0.
PAGE_PX = (1224, 1584)
TEXT_PX = 34

#: A paystub whose words hit the PAYSTUB rule pack's anchors (Pay Period, Gross
#: Pay, Net Pay, Pay Date, Earnings) and the PAYSTUB extraction schema's labels.
#:
#: Laid out in COLUMNS — label at x=60, value at x=430 — because that is how payroll
#: stubs are actually set, and because it is what the recognisers need. RapidOCR
#: splits a recognised line on the whitespace it DETECTS, and on a run-together line
#: of this font it detects none, returning `FederalWithholding$312.45` as one span.
#: A column gap is unambiguous to it and to the label/value extractors alike.
PAYSTUB_LINES = [
    (60, 60, "PAYROLL STATEMENT"),
    (60, 130, "Employer:"),
    (430, 130, "ACME WIDGETS LLC"),
    (60, 190, "Employee:"),
    (430, 190, "Jordan Rivera"),
    (60, 250, "Pay Period"),
    (430, 250, "01/01/2026"),
    (700, 250, "01/15/2026"),
    (60, 310, "Pay Date"),
    (430, 310, "01/20/2026"),
    (60, 370, "Pay Frequency"),
    (430, 370, "Bi-Weekly"),
    (60, 450, "Earnings"),
    (60, 510, "Gross Pay"),
    (430, 510, "$2,450.00"),
    (60, 570, "Federal Withholding"),
    (430, 570, "$312.45"),
    (60, 630, "Net Pay"),
    (430, 630, "$1,884.10"),
    (60, 690, "YTD Gross"),
    (430, 690, "$9,800.00"),
]


def default_font(size: int = TEXT_PX):
    return ImageFont.load_default(size=size)


def draw_lines(
    size: tuple[int, int],
    lines: list[tuple[int, int, str]],
    fmt: str = "JPEG",
    background: str = "white",
) -> tuple[bytes, dict[str, tuple[float, float, float, float]]]:
    """Draw `(x, y, text)` lines; return `(encoded bytes, truth pixel boxes by text)`.

    Truth boxes are `(x, y, width, height)` in pixels, straight from
    `ImageDraw.textbbox` — the box Pillow says it painted, never one measured back
    off the raster.
    """
    image = Image.new("RGB", size, background)
    draw = ImageDraw.Draw(image)
    font = default_font()
    truth: dict[str, tuple[float, float, float, float]] = {}
    for x, y, text in lines:
        draw.text((x, y), text, fill="black", font=font)
        left, top, right, bottom = draw.textbbox((x, y), text, font=font)
        # Truth is keyed by text, so drawing the same string twice would silently
        # leave only the last box and every assertion against it would be measuring
        # a different place on the page than it says it is.
        assert text not in truth, f"duplicate truth key {text!r} — give the strings distinct text"
        truth[text] = (float(left), float(top), float(right - left), float(bottom - top))
    buffer = io.BytesIO()
    image.save(buffer, format=fmt, **({"quality": 92} if fmt == "JPEG" else {}))
    return buffer.getvalue(), truth


def paystub_bytes(fmt: str = "JPEG", size: tuple[int, int] = PAGE_PX):
    return draw_lines(size, PAYSTUB_LINES, fmt=fmt)


def blank_bytes(fmt: str = "PNG", size: tuple[int, int] = PAGE_PX) -> bytes:
    return draw_lines(size, [], fmt=fmt)[0]


def two_frame_tiff(size: tuple[int, int] = (600, 800)) -> bytes:
    """A two-frame TIFF — a scanner's or fax gateway's idea of a two-page document.

    Each frame carries a paragraph rather than one word: a page with a single label
    on it is genuinely below INK_FLOOR and would (correctly) come back NONE, which
    is not what a test about multi-frame paging is trying to say.
    """
    frames = []
    for label in ("PAGE ONE", "PAGE TWO"):
        image = Image.new("RGB", size, "white")
        draw = ImageDraw.Draw(image)
        font = default_font()
        for row in range(10):
            draw.text((40, 40 + row * 60), f"{label} LINE {row}", fill="black", font=font)
        frames.append(image)
    buffer = io.BytesIO()
    frames[0].save(buffer, format="TIFF", save_all=True, append_images=frames[1:])
    return buffer.getvalue()


def heif_ftyp_bytes(brand: bytes = b"heic", trailing: bytes = b"") -> bytes:
    """An ISO-BMFF `ftyp` box with `brand` as the MAJOR brand, and nothing behind it.

    Built by construction, so the header tests owe the base64 constants nothing:
    bytes 0..4 are the box size, 4..8 are `ftyp`, 8..12 are the major brand, 12..16
    the minor version, and the compatible-brand list follows.

    `mif1` sits in the compatible list on purpose — a sniffer that scanned that list
    instead of the major brand would accept every brand this helper can produce.
    """
    body = b"ftyp" + brand + b"\x00\x00\x00\x00" + b"mif1" + brand
    return (len(body) + 4).to_bytes(4, "big") + body + trailing


#: Pixel box of the black rectangle inside `heic_bytes()` — left, top, right, bottom.
HEIC_RECT_PX = (100, 200, 500, 400)

#: 1224x1584 (PAGE_PX), white, with HEIC_RECT_PX filled black. See the module
#: docstring for why this is a constant and how to regenerate it.
_HEIC_SINGLE_B64 = (
    "AAAAHGZ0eXBoZWljAAAAAG1pZjFoZWljbWlhZgAAAVhtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAA"
    "AAAAAAAAAAAAAAAAACJpbG9jAAAAAERAAAEAAQAAAAABfAABAAAAAAAABiYAAAAjaWluZgAAAAAAAQAAABVp"
    "bmZlAgAAAAABAABodmMxAAAAAA5waXRtAAAAAAABAAAA2GlwcnAAAAC5aXBjbwAAAHpodmNDAQNwAAAAAAAA"
    "AAAAePAA/P34+AAADwNgAAEAGEABDAH//wNwAAADAJAAAAMAAAMAeLoCQGEAAQAtQgEBA3AAAAMAkAAAAwAA"
    "AwB4oAJkgBjFlupJKa5uAhoMCAAAAwDIAAADAAhAYgABAAdEAcFysGJAAAAAE2NvbHJuY2x4AAEADQAGgAAA"
    "ABRpc3BlAAAAAAAABMgAAAYwAAAAEHBpeGkAAAAAAwgICAAAABdpcG1hAAAAAAAAAAEAAQSBAgMEAAAGLm1k"
    "YXQAAAYiKAGvEwyIRTk4ZTw5izY2NTU1NTU1NTU1NTU1NDQ0gObIaFPqH7pl35RhoHKRTP0/2agxdAAAawAA"
    "1QALcAAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwCBVMAACCijGesW6AAA9gACzAAbwAAAAwAA"
    "AwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAe1TAAAgokwABEQALQAAAAwAAAwAAAwAAAwAAAwAA"
    "AwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAItUwAAIKAhwAAxQyJbq7PoxmaBXVzOtd9H7cEhU+rcwIQYSGo"
    "jZiEqIgigAAAAwAAAwAAAwAFpIQ92c5WhsmlP0h1lqsMyHdMLLQAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMA"
    "AAMAAAMAAH7AAAADAetCigCdNfjLn8AAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMA"
    "AAMAAAMBSQAACzGv8Zw8RcPoAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AcUAAxBBvM7yWLbwrBkv+WeE+CHnwC3LcCWGlsCKrgAGj2Bl2s60AMZ4G8MAB1hBqQcs4AlygwpAAY1wiJLd"
    "0ARvglrgAUgwkEsb4Aa1wnUgAY5AkEsb4AdEAwyAHwXbmI/u2SFjXPMd7QiLMwAAAwAAAwAAAwAAAwAAAwAA"
    "AwAAAwAAAwAAAwAAAwAp4AAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAA"
    "AwAAAwAAPqAAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAPqA"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAMSAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAXMAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAqYAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAA/YAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAB9QAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADACrgAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAD/A"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAFzAAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAHfAAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAJCAAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAMGAAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADANGAAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAAD"
    "AAADAAADAVcAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMAAAMBcwAA"
    "AwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwHRAAADAAADAAADAAAD"
    "AAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAADe"
    "gA=="
)

#: 200x260, TWO coded images — a burst or Live Photo, which is one picture and not
#: two pages. Same recipe as above with `save_all=True, append_images=[second]`.
_HEIC_MULTI_B64 = (
    "AAAAHGZ0eXBoZWljAAAAAG1pZjFoZWljbWlhZgAAAYRtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAA"
    "AAAAAAAAAAAAAAAAADRpbG9jAAAAAERAAAIAAQAAAAABqAABAAAAAAAAAMYAAgAAAAACbgABAAAAAAAAAMgA"
    "AAA4aWluZgAAAAAAAgAAABVpbmZlAgAAAAABAABodmMxAAAAABVpbmZlAgAAAAACAABodmMxAAAAAA5waXRt"
    "AAAAAAABAAAA3WlwcnAAAAC3aXBjbwAAAHhodmNDAQNwAAAAAAAAAAAAPPAA/P34+AAADwNgAAEAGEABDAH/"
    "/wNwAAADAJAAAAMAAAMAPLoCQGEAAQArQgEBA3AAAAMAkAAAAwAAAwA8oBkgEJ95bqSSmubgIaDAgAAADIAA"
    "AAMAhGIAAQAHRAHBcrBiQAAAABNjb2xybmNseAABAA0ABoAAAAAUaXNwZQAAAAAAAADIAAABBAAAABBwaXhp"
    "AAAAAAMICAgAAAAeaXBtYQAAAAAAAAACAAEEgQIDBAACBIECAwQAAAGWbWRhdAAAAMIoAa8TKecyIEJVW3kQ"
    "nLfWDZWwDPQlQnZ/22Swmq2AE+S8dYdbKDrN3Ej0ci8hcoi1ogkPYyAHGiyxGA5qf8nlodLNTeROrGr0h791"
    "fVsjP5U6g/J58FQOURvDjMY0hSfcgHVFV/Vg2M4XXWjn8caNrLoLhwENBGr77mEpFerePXrU2TMr6zebduYg"
    "08lKtLiSoE/AMgAAAwAAE9CNgALMAAADAAADAAADAAAuYAAAAwAAAwAAAwAAAwAAAwAAAwABjwAAAMQoAa8T"
    "Kc4QfuLmyGhT6h+6Zd+UYaBykUz9P9moMhDmjAAAAwAUkKMZ6xboAAD2AAPQIkAAABPQLQFi7NAo0XNUkD9o"
    "ppLFUqZa1n8ioF57Ky/k82sUrH2ABqeOQt/9sbIDW2/CNT3Rei1B0Ize2VihwAAAAwA5YLpC/A4/RHAKYKn/"
    "McWoFHf7uzSBAdwK+R6GwIg/h5fgBKA49M8k5q3D8jV+A+W6z9Sl4AAAAwAuIIcAAAMAAAMAAAMAAAMAAAMA"
    "ADkg"
)


def heic_bytes() -> bytes:
    """A one-page HEIC: PAGE_PX white with HEIC_RECT_PX filled black."""
    return base64.b64decode(_HEIC_SINGLE_B64)


def heic_multi_frame_bytes() -> bytes:
    """A HEIC holding two coded images, the shape a burst or Live Photo takes."""
    return base64.b64decode(_HEIC_MULTI_B64)


def exif_rotated_jpeg(
    lines: list[tuple[int, int, str]], size: tuple[int, int] = PAGE_PX
) -> tuple[bytes, dict[str, tuple[float, float, float, float]]]:
    """A photo whose pixels are stored a quarter turn out with EXIF orientation 6
    ("rotate 90 CW for display") — what a phone held sideways actually writes.

    Truth boxes are the UPRIGHT ones: that is what the borrower saw, and it is the
    frame every downstream box has to land in.
    """
    upright = Image.new("RGB", size, "white")
    draw = ImageDraw.Draw(upright)
    font = default_font()
    truth: dict[str, tuple[float, float, float, float]] = {}
    for x, y, text in lines:
        draw.text((x, y), text, fill="black", font=font)
        left, top, right, bottom = draw.textbbox((x, y), text, font=font)
        truth[text] = (float(left), float(top), float(right - left), float(bottom - top))

    stored = upright.transpose(Image.Transpose.ROTATE_90)
    exif = stored.getexif()
    exif[274] = 6  # Orientation
    buffer = io.BytesIO()
    stored.save(buffer, format="JPEG", exif=exif, quality=92)
    return buffer.getvalue(), truth
