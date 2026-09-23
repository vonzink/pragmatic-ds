#!/usr/bin/env python3
"""Synthetic corpus fixtures reproducing the REAL-document failure geometry.

    .venv/bin/python tools/gen_realworld_fixtures.py [--out corpus]

Three fixtures, every value INVENTED, geometry copied from the failing shapes
two real borrower documents exhibited (2026-08-17 diagnosis of "correctly
classified, zero fields extracted"). The PDFs land in the gitignored corpus/
beside their ``.answers.json`` keys, so ``tools/corpus_score.py`` verifies them
against a running stack exactly like any other corpus document. CI never sees
the PDFs; it sees this script, which can rebuild them byte-for-byte.

FIXTURE A — bank-degenerate-metrics.pdf ("baseline-stripe mixed-font statement")
    A real bank statement sets its DATA in an embedded font whose metrics
    collapse: pdfplumber 0.11.10 reports fontname='unknown', size≈0.24, and
    every word's box is a ~0.2pt-tall stripe at the baseline, while TEMPLATE
    captions are honest ~7.8pt Helvetica. A caption row whose value is a
    baseline stripe SPLITS into two visual lines (|Δcenter| 4.8pt vs threshold
    0.10pt), LINE_RIGHT sees nothing right of the caption, and the field goes
    missing with the value printed right there. Reproduced here with a Type3
    font whose FontBBox is a 30/1000-em sliver while its glyphs draw full-height.
      - 'Account Number:' caption (Helvetica ~7.8pt) + 15-digit value in the
        degenerate font at the caption row's baseline: THE pinned defect.
      - CHECKING-SUMMARY-style control rows set ENTIRELY in the degenerate font
        (label and value share the collapsed metrics, so they stay one line):
        must extract before AND after any fix.
      - DECOY 1 (row annexation): a 12-digit reference in HONEST metrics on the
        NEXT printed row — a fix that repairs heights must not hand it to
        'Account Number:'.
      - DECOY 2 (prose trap): footnote prose '...statement period: <date>
        <date>...' with the dates RIGHT of the phrase, all on one row. A phrase
        inside prose is not a caption: statementPeriodStart/End must stay
        MISSING. (At HEAD the old naked 'Statement Period' label anchors here
        and captures the prose dates — the trap the answer key makes loud.)

FIXTURE B — paystub-image-punctuation.pdf ("punctuation-as-images stub")
    A real paystub draws its money punctuation as 3.8pt-wide 1-bit images
    BETWEEN text digit groups: '$7,514.92' arrives as spans '$7'/'514'/'92'
    with ~5.1pt inked holes. SpanJoin correctly refuses sub-em holes wider than
    0.10 em, the money pattern correctly declines '$7 514 92', and the field
    goes missing (missing-over-wrong, D5).
      - gross/YTD/net rows with comma/period images at the measured offsets.
      - 'Pay Date: 07/03/2026' control: date punctuation as REAL text.
      - masthead 'Colwynn Payroll Inc.' (employerName captured by the PAGE
        regex — key pins the FULL name).
      - DECOY 3 (join fabrication): two separate digit runs '84' '112' with the
        same hole geometry and NO ink between: nothing may bridge them.
      - DECOY 4 (punctuation discipline): 'Federal Withholding' row whose value
        is '61' [non-punctuation speck image] '400': the field must stay
        MISSING — no fix may buy a comma or period from a speck.
      - Colman-dialect captions ('Period Beginning:'/'Period Ending:') printed
        as INERT text: the schema does not know them, so payPeriodStart/End
        stay MISSING — pinning that unknown captions are not guessed at.

FIXTURE C — bank-dialect-chase.pdf ("real-statement vocabulary")
    Honest metrics, real-bank wording: a FIVE-ROW summary block (Beginning
    Balance, Deposits and Additions, Checks Paid, Electronic Withdrawals,
    Ending Balance — not 'Total Deposits'/'Total Withdrawals'), a period line
    printing month-name dates with the measured '2026throughJune' word fusion,
    and the same section names repeating as detail headers with transaction
    rows beneath.
      - totalDeposits must capture the SUMMARY amount, never a detail row
        (decoy: detail rows carry different amounts).
      - totalWithdrawals must stay MISSING and is OMITTED from the key. The
        five rows balance only WITH 'Checks Paid', so 'Electronic Withdrawals'
        is one category among several and no row here is the withdrawals
        total. This is the fixture's own correction: while the block carried a
        single withdrawal row, the key could call that row the total and agree
        with a rung that bound a category subtotal — both having come from the
        same wrong assumption. Now any totalWithdrawals capture is an
        UNMAPPED_CAPTURE and fails the gate.
      - statementPeriodStart/End must come from the month-name masthead line,
        never from the footnote prose trap (whose slash dates are DIFFERENT
        values, so a prose capture is a loud MISMATCH, not a silent pass).

Nothing here depends on reportlab: the PDFs are written raw (exact content
streams, a Type3 font, image XObjects — none of which reportlab can express).
Every fixture is verified after writing: the script re-opens it with pdfplumber
and asserts the geometry class it exists to pin (degenerate heights, hole
widths, image positions, word fusion), failing loudly on drift.
"""

from __future__ import annotations

import argparse
import io
import json
import zlib
from decimal import Decimal
from pathlib import Path

import pdfplumber

PAGE_W, PAGE_H = 612.0, 792.0

#: Helvetica AFM advances (per-1000-em) for every character the honest-font
#: fixtures print. Bold shares digit/symbol widths where it matters (556).
AFM = {
    " ": 278, "!": 278, '"': 355, "$": 556, "%": 889, "&": 667, "'": 191,
    "(": 333, ")": 333, "*": 389, "+": 584, ",": 278, "-": 333, ".": 278,
    "/": 278, "0": 556, "1": 556, "2": 556, "3": 556, "4": 556, "5": 556,
    "6": 556, "7": 556, "8": 556, "9": 556, ":": 278, ";": 278, "?": 556,
    "A": 667, "B": 667, "C": 722, "D": 722, "E": 667, "F": 611, "G": 778,
    "H": 722, "I": 278, "J": 500, "K": 667, "L": 556, "M": 833, "N": 722,
    "O": 778, "P": 667, "Q": 778, "R": 722, "S": 667, "T": 611, "U": 722,
    "V": 667, "W": 944, "X": 667, "Y": 667, "Z": 611,
    "a": 556, "b": 556, "c": 500, "d": 556, "e": 556, "f": 278, "g": 556,
    "h": 556, "i": 222, "j": 222, "k": 500, "l": 222, "m": 833, "n": 556,
    "o": 556, "p": 556, "q": 556, "r": 333, "s": 500, "t": 278, "u": 556,
    "v": 500, "w": 722, "x": 500, "y": 500, "z": 500,
}


def text_width(text: str, size: float) -> float:
    return sum(AFM[c] for c in text) * size / 1000.0


# ── raw PDF assembly ─────────────────────────────────────────────────────────


class RawPdf:
    """A one-page PDF assembled object by object — exact content streams,
    Type3 fonts and image XObjects, none of which reportlab can express."""

    def __init__(self):
        self.objects: list[bytes] = []

    def add(self, body: bytes | str) -> int:
        if isinstance(body, str):
            body = body.encode("latin-1")
        self.objects.append(body)
        return len(self.objects)

    def add_stream(self, dict_body: str, data: bytes) -> int:
        head = f"<< {dict_body} /Length {len(data)} >>\nstream\n".encode("latin-1")
        return self.add(head + data + b"\nendstream")

    def build(self, content: str, font_refs: dict[str, int],
              xobject_refs: dict[str, int]) -> bytes:
        contents = self.add_stream("", content.encode("latin-1"))
        pages = len(self.objects) + 1
        page = len(self.objects) + 2
        fonts = " ".join(f"/{n} {r} 0 R" for n, r in font_refs.items())
        xobjects = " ".join(f"/{n} {r} 0 R" for n, r in xobject_refs.items())
        resources = f"/Font << {fonts} >>"
        if xobject_refs:
            resources += f" /XObject << {xobjects} >>"
        self.add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
        self.add(
            f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 {PAGE_W:g} {PAGE_H:g}] "
            f"/Resources << {resources} >> /Contents {contents} 0 R >>"
        )
        catalog = self.add(f"<< /Type /Catalog /Pages {pages} 0 R >>")

        out = bytearray(b"%PDF-1.5\n")
        offsets = []
        for number, body in enumerate(self.objects, start=1):
            offsets.append(len(out))
            out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
        start = len(out)
        out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(self.objects) + 1)
        for offset in offsets:
            out += b"%010d 00000 n \n" % offset
        out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
            len(self.objects) + 1, catalog, start,
        )
        return bytes(out)


STANDARD_FONTS = {
    "F1": "/Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding",
    "FB": "/Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding",
    "FO": "/Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding",
}


def add_standard_fonts(pdf: RawPdf) -> dict[str, int]:
    return {name: pdf.add(f"<< {body} >>") for name, body in STANDARD_FONTS.items()}


# ── the degenerate Type3 data font (fixture A) ───────────────────────────────

#: The degenerate mechanism, measured against pdfminer's own arithmetic: an
#: LTChar's box height IS the effective font size (Tf × text matrix) no matter
#: what the glyphs draw. The real statement sets Tf 0.24 and blows the glyphs
#: up through the font's own matrix, so every char extracts as a 0.24pt stripe
#: at the baseline while rendering at reading size. Reproduced identically:
T3_SIZE = 0.24
#: FontMatrix scale: 1000-unit glyphs × 0.0229 × Tf 0.24 ≈ 5.5pt — the measured
#: per-char advance of the real data font.
T3_MATRIX = 0.0229
T3_ADVANCE = 1000
T3_ADVANCE_PT = T3_ADVANCE * T3_MATRIX * T3_SIZE

#: Characters the degenerate font can set (codes are their ASCII codepoints).
T3_CHARS = "0123456789$,.:/-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def _to_unicode_cmap(chars: str) -> bytes:
    pairs = "\n".join(f"<{ord(c):02x}> <{ord(c):04x}>" for c in chars)
    return (
        "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
        "/CMapName /A-T3 def\n/CMapType 2 def\n"
        "1 begincodespacerange\n<00> <ff>\nendcodespacerange\n"
        f"{len(chars)} beginbfchar\n{pairs}\nendbfchar\nendcmap\n"
        "CMapName currentdict /CMap defineresource pop\nend\nend\n"
    ).encode("latin-1")


def add_degenerate_font(pdf: RawPdf) -> int:
    """A Type3 font whose FontMatrix scales 1000-unit glyphs up ~23x, used at
    Tf 0.24: the glyphs RENDER at reading size, but pdfminer boxes every char
    at the EFFECTIVE FONT SIZE (0.24pt) sitting on the baseline, and a Type3
    dict carries no BaseFont so the fontname reports 'unknown' — the real
    statement's shape, reproduced number for number.

    Every glyph is the same ink bar (distinct shapes would add nothing:
    nothing downstream reads these pixels except the ink check); the TEXT
    comes from the ToUnicode CMap, exactly as a real embedded font's does.
    """
    proc = f"{T3_ADVANCE} 0 d0 80 0 {T3_ADVANCE - 160} 1150 re f".encode("latin-1")
    glyph_ref = pdf.add_stream("", proc)
    charprocs = " ".join(f"/g{ord(c)} {glyph_ref} 0 R" for c in T3_CHARS)
    charprocs_ref = pdf.add(f"<< {charprocs} >>")
    codes = sorted(ord(c) for c in T3_CHARS)
    first, last = codes[0], codes[-1]
    widths = " ".join(
        str(T3_ADVANCE) if chr(code) in T3_CHARS else "0"
        for code in range(first, last + 1)
    )
    differences = " ".join(f"{code} /g{code}" for code in codes)
    tounicode_ref = pdf.add_stream("", _to_unicode_cmap(T3_CHARS))
    return pdf.add(
        "<< /Type /Font /Subtype /Type3 "
        f"/FontBBox [0 0 {T3_ADVANCE} 1250] "
        f"/FontMatrix [{T3_MATRIX} 0 0 {T3_MATRIX} 0 0] "
        f"/CharProcs {charprocs_ref} 0 R "
        f"/Encoding << /Type /Encoding /Differences [{differences}] >> "
        f"/FirstChar {first} /LastChar {last} /Widths [{widths}] "
        f"/ToUnicode {tounicode_ref} 0 R >>"
    )


# ── 1-bit punctuation image XObjects (fixture B) ─────────────────────────────


def _bitmap_stream(rows: list[str]) -> tuple[int, int, bytes]:
    """Pack '#'-for-ink rows into 1-bit-per-sample scanlines (1 = ink; the
    XObject's /Decode [1 0] makes 1 paint)."""
    height = len(rows)
    width = len(rows[0])
    data = bytearray()
    for row in rows:
        assert len(row) == width
        byte, bits = 0, 0
        line = bytearray()
        for cell in row:
            byte = (byte << 1) | (1 if cell == "#" else 0)
            bits += 1
            if bits == 8:
                line.append(byte)
                byte, bits = 0, 0
        if bits:
            line.append(byte << (8 - bits))
        data += line
    return width, height, bytes(data)


def _glyph_rows(glyph: str) -> list[str]:
    """A real comma/period shape, rendered from Pillow's built-in scalable
    font and thresholded to 1-bit — what a payroll renderer's punctuation
    bitmaps actually look like (the real stub's images OCR-read as their
    characters; hand-drawn blobs do not)."""
    from PIL import Image, ImageDraw, ImageFont

    font = ImageFont.load_default(size=48)
    canvas = Image.new("L", (64, 80), 255)
    ImageDraw.Draw(canvas).text((8, 8), glyph, font=font, fill=0)
    # getbbox() boxes NON-ZERO pixels, so build an ink=255 mask to find the glyph.
    box = canvas.point(lambda p: 255 if p < 128 else 0).getbbox()
    glyph_img = canvas.crop(box)
    return [
        "".join("#" if glyph_img.getpixel((x, y)) < 128 else "."
                for x in range(glyph_img.width))
        for y in range(glyph_img.height)
    ]


COMMA_ROWS = _glyph_rows(",")
PERIOD_ROWS = _glyph_rows(".")

#: The speck: a hollow ring — deliberately NOT comma- or period-shaped. What it
#: pins is that ink between digit runs does not buy punctuation unless it READS
#: as punctuation.
SPECK_ROWS = [
    "..####..",
    ".##..##.",
    "##....##",
    "##....##",
    ".##..##.",
    "..####..",
]


def add_mask_image(pdf: RawPdf, rows: list[str]) -> int:
    width, height, data = _bitmap_stream(rows)
    compressed = zlib.compress(data)
    return pdf.add_stream(
        f"/Type /XObject /Subtype /Image /Width {width} /Height {height} "
        "/BitsPerComponent 1 /ImageMask true /Decode [1 0] /Filter /FlateDecode",
        compressed,
    )


# ── content-stream helpers ───────────────────────────────────────────────────


def esc(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


class Content:
    """Accumulates content-stream operations in TOP-coordinates (y measured
    from the page's top edge, like pdfplumber reports) so the fixture code and
    the measured geometry it copies read in one frame. ``y`` is the BASELINE."""

    def __init__(self):
        self.ops: list[str] = []

    def text(self, font: str, size: float, x: float, y_top_baseline: float, text: str):
        y = PAGE_H - y_top_baseline
        self.ops.append(
            f"BT /{font} {size:g} Tf 1 0 0 1 {x:.2f} {y:.2f} Tm ({esc(text)}) Tj ET"
        )

    def words(self, font: str, size: float, x: float, y: float, words: list[str],
              gap: float = 4.5) -> list[tuple[float, float]]:
        """Place words left to right from ``x`` with a fixed printed gap;
        returns each word's (x0, x1). Only meaningful for the AFM fonts."""
        spans = []
        cursor = x
        for word in words:
            self.text(font, size, cursor, y, word)
            width = text_width(word, size)
            spans.append((cursor, cursor + width))
            cursor += width + gap
        return spans

    def image(self, name: str, x: float, y_top: float, width: float, height: float):
        y = PAGE_H - y_top - height
        self.ops.append(
            f"q 0 g {width:.2f} 0 0 {height:.2f} {x:.2f} {y:.2f} cm /{name} Do Q"
        )

    def stream(self) -> str:
        return "\n".join(self.ops)


# ── fixture A ────────────────────────────────────────────────────────────────

A_ACCOUNT_NUMBER = "429815003117208"
A_DECOY_REFERENCE = "882031455907"
A_BEGINNING = "$412,867.03"
A_ENDING = "$408,110.77"
A_PROSE_DATES = ("04/10/2026", "05/09/2026")


def t3_words(content: Content, x: float, baseline_top: float,
             words: list[str], gap: float = 6.0) -> list[tuple[float, float]]:
    """Degenerate-font words: each its own run, gaps wide enough that
    pdfplumber keeps them separate words."""
    spans = []
    cursor = x
    for word in words:
        content.text("T3", T3_SIZE, cursor, baseline_top, word)
        width = len(word) * T3_ADVANCE_PT
        spans.append((cursor, cursor + width))
        cursor += width + gap
    return spans


def build_fixture_a() -> bytes:
    pdf = RawPdf()
    fonts = add_standard_fonts(pdf)
    fonts["T3"] = add_degenerate_font(pdf)
    c = Content()

    # Masthead: honest template type. No ' BANK' token on purpose — bankName's
    # regex must keep failing (as it does on the real statement's mixed case).
    c.text("FB", 10, 39.6, 40.0, "Metropolitan Nest Egg")
    c.words("F1", 7.8, 39.6, 50.0, ["Account", "Statement"])

    # THE DEFECT ROW — template caption, degenerate value, one printed row.
    # Caption baseline at top 68.4-ish (7.8pt Helvetica): top ≈ 60.6.
    c.words("F1", 7.8, 58.8, 68.4, ["P", "O", "Box", "88214"])
    c.text("F1", 7.8, 362.1, 68.4, "Account")
    c.text("F1", 7.8, 394.0, 68.4, "Number:")
    # The value's baseline sits ~1pt below the caption's (the measured Δ):
    # its box collapses to a stripe at that baseline.
    t3_words(c, 436.5, 69.4, [A_ACCOUNT_NUMBER])

    # DECOY 1: the NEXT printed row, honest metrics — must never be annexed.
    c.words("F1", 7.8, 362.1, 87.8, ["Reference:"])
    c.text("F1", 7.8, 436.5, 87.8, A_DECOY_REFERENCE)

    # CHECKING-SUMMARY control rows: label AND value in the degenerate font at
    # one shared baseline — the only reason these rows survive on the real page.
    c.text("FB", 8, 39.6, 310.0, "CHECKING SUMMARY")
    t3_words(c, 39.6, 321.9, ["Beginning", "Balance"])
    t3_words(c, 326.1, 321.9, [A_BEGINNING])
    t3_words(c, 39.6, 375.2, ["Ending", "Balance"])
    t3_words(c, 326.1, 375.2, [A_ENDING])

    # DECOY 2: the prose trap, one degenerate-font row, dates RIGHT of the
    # phrase. A phrase inside prose is not a caption.
    t3_words(
        c, 39.6, 671.3,
        ["fees", "for", "the", "monthly", "statement", "period:",
         A_PROSE_DATES[0], A_PROSE_DATES[1], "were", "waived"],
    )

    return pdf.build(c.stream(), fonts, {})


def verify_fixture_a(pdf_bytes: bytes) -> dict:
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as doc:
        page = doc.pages[0]
        words = page.extract_words(extra_attrs=["size", "fontname"])
    by_text = {}
    for word in words:
        by_text.setdefault(word["text"], []).append(word)

    value = by_text[A_ACCOUNT_NUMBER][0]
    caption = by_text["Number:"][0]
    v_h = value["bottom"] - value["top"]
    c_h = caption["bottom"] - caption["top"]
    assert v_h < 0.5, f"degenerate value height {v_h} not under 0.5pt"
    assert value["fontname"] == "unknown", f"fontname {value['fontname']}"
    assert value["size"] < 0.5, f"reported size {value['size']}"
    assert 6.0 < c_h < 9.0, f"caption height {c_h}"
    assert abs(value["bottom"] - caption["bottom"]) <= 2.0, (
        "baselines must nearly agree: the boxes are wrong, not the print")
    v_center = (value["top"] + value["bottom"]) / 2
    c_center = (caption["top"] + caption["bottom"]) / 2
    assert abs(v_center - c_center) > 0.5 * min(v_h, c_h), (
        "the visual-line split precondition must hold at HEAD")
    assert value["x0"] > caption["x1"], "value must sit right of the caption"

    advance = (value["x1"] - value["x0"]) / len(A_ACCOUNT_NUMBER)
    assert 4.0 < advance < 7.0, f"per-char advance {advance}"

    decoy = by_text[A_DECOY_REFERENCE][0]
    assert decoy["bottom"] - decoy["top"] > 6.0, "decoy 1 must be honest metrics"
    assert decoy["top"] > caption["bottom"], "decoy 1 sits on the NEXT row"

    for text in ("Beginning", "Balance", A_BEGINNING, "Ending", A_ENDING,
                 "statement", "period:", A_PROSE_DATES[0]):
        stripe = by_text[text][0]
        assert stripe["bottom"] - stripe["top"] < 0.5, f"{text!r} must be a stripe"
    b_label = by_text["Beginning"][0]
    b_value = by_text[A_BEGINNING][0]
    assert abs(b_label["top"] - b_value["top"]) < 0.05, (
        "control row label and value must share one collapsed baseline")

    ink = _ink_in_band(pdf_bytes, value["x0"], value["x1"],
                       value["bottom"] - 8.0, value["bottom"] + 1.0)
    assert ink > 50, f"degenerate glyphs must still RENDER (ink {ink})"
    return {"valueHeight": v_h, "captionHeight": c_h, "advance": advance}


def _ink_in_band(pdf_bytes: bytes, x0: float, x1: float, top: float, bottom: float) -> int:
    import pypdfium2 as pdfium

    scale = 150 / 72.0
    doc = pdfium.PdfDocument(pdf_bytes)
    try:
        raster = doc[0].render(scale=scale).to_pil().convert("L")
    finally:
        doc.close()
    import numpy as np

    pixels = np.asarray(raster)
    band = pixels[
        max(0, int(top * scale)):int(bottom * scale),
        max(0, int(x0 * scale)):int(x1 * scale),
    ]
    return int((band < 128).sum())


# ── fixture B ────────────────────────────────────────────────────────────────

B_EMPLOYER = "Colwynn Payroll Inc."
B_PAY_DATE = "07/03/2026"
B_GROSS_RUNS = ("$7", "514", "92")     # renders $7,514.92
B_YTD_RUNS = ("18", "203", "44")       # renders 18,203.44
B_NET_RUNS = ("$5", "891", "27")       # renders $5,891.27
B_GROSS = "$7,514.92"
B_YTD = "18,203.44"
B_NET = "$5,891.27"
B_PERIOD_BEGIN = "06/23/2026"
B_PERIOD_END = "06/29/2026"


def _digit_runs(content: Content, images: list[tuple[str, float, float, float, float]],
                font: str, size: float, x: float, baseline_top: float,
                runs: tuple[str, ...], punctuation: list[str | None]) -> list[float]:
    """Digit runs with ~5.1pt holes; each hole optionally carries an image
    (comma/period/speck name) at the measured sub-glyph size. Returns run x0s."""
    xs = []
    cursor = x
    for index, run in enumerate(runs):
        content.text(font, size, cursor, baseline_top, run)
        xs.append(cursor)
        width = text_width(run, size)
        end = cursor + width
        if index < len(runs) - 1:
            mark = punctuation[index]
            if mark is not None:
                if mark == "comma":
                    w, h = 3.8, 4.6
                elif mark == "period":
                    w, h = 3.8, 4.1
                else:
                    w, h = 2.6, 2.2
                # The image sits IN the hole, at the baseline like real
                # punctuation (top ≈ baseline − glyph height).
                images.append((mark, end + 0.1, baseline_top - h + 0.6, w, h))
            cursor = end + 5.1
        else:
            cursor = end
    return xs


def build_fixture_b() -> bytes:
    pdf = RawPdf()
    fonts = add_standard_fonts(pdf)
    xobjects = {
        "ImComma": add_mask_image(pdf, COMMA_ROWS),
        "ImPeriod": add_mask_image(pdf, PERIOD_ROWS),
        "ImSpeck": add_mask_image(pdf, SPECK_ROWS),
    }
    c = Content()
    images: list[tuple[str, float, float, float, float]] = []

    # Masthead — italic, like the measured page. employerName's PAGE regex must
    # capture the WHOLE name (the key pins it).
    c.words("FO", 11, 72.0, 58.0, B_EMPLOYER.split(" "), gap=3.2)
    c.words("F1", 9, 72.0, 82.0, ["Earnings", "Statement"])

    # Employee address block — NO 'Employee:' caption anywhere (borrowerName
    # stays missing, as on the real stub).
    c.words("F1", 9, 72.0, 106.0, ["Erik", "Q.", "Sample"])
    c.words("F1", 9, 72.0, 118.0, ["1184", "Synthetic", "Ave"])

    # Pay Date control: caption + REAL-text date (the '/' glyphs are text on
    # the same page whose money punctuation is images).
    c.text("F1", 9, 353.8, 82.1, "Pay")
    c.text("F1", 9, 374.6, 82.1, "Date:")
    c.text("F1", 9, 454.6, 82.1, B_PAY_DATE)

    # Colman-dialect period captions, INERT: the schema knows 'Pay Period',
    # not these — payPeriodStart/End must stay MISSING rather than guessed.
    c.text("F1", 9, 353.8, 100.1, "Period")
    c.text("F1", 9, 384.0, 100.1, "Beginning:")
    c.text("F1", 9, 454.6, 100.1, B_PERIOD_BEGIN)
    c.text("F1", 9, 353.8, 118.1, "Period")
    c.text("F1", 9, 384.0, 118.1, "Ending:")
    c.text("F1", 9, 454.6, 118.1, B_PERIOD_END)

    # Earnings block.
    c.words("F1", 9, 94.6, 232.0, ["this", "period"], gap=3.0)
    c.words("F1", 9, 291.1, 232.0, ["year", "to", "date"], gap=3.0)
    c.text("F1", 9, 94.6, 254.6, "Gross")
    c.text("F1", 9, 125.8, 254.6, "Pay")
    _digit_runs(c, images, "FB", 9, 210.5, 254.6, B_GROSS_RUNS, ["comma", "period"])
    _digit_runs(c, images, "F1", 9, 291.1, 254.6, B_YTD_RUNS, ["comma", "period"])
    c.words("F1", 9, 400.0, 254.6, ["Important", "Notes"])

    # Net pay row.
    c.text("F1", 9, 94.6, 404.6, "Net")
    c.text("F1", 9, 115.6, 404.6, "Pay")
    _digit_runs(c, images, "FB", 9, 210.5, 404.6, B_NET_RUNS, ["comma", "period"])

    # DECOY 4: the schema's own caption with a SPECK in the hole — the field
    # must stay MISSING; no fix may read punctuation into a non-punctuation mark.
    c.text("F1", 9, 94.6, 449.6, "Federal")
    c.text("F1", 9, 129.6, 449.6, "Withholding")
    _digit_runs(c, images, "F1", 9, 210.5, 449.6, ("61", "400"), ["speck"])

    # DECOY 3: two SEPARATE amounts, same hole geometry, NO ink between.
    c.text("F1", 9, 94.6, 494.6, "Misc")
    _digit_runs(c, images, "F1", 9, 210.5, 494.6, ("84", "112"), [None])

    image_names = {"comma": "ImComma", "period": "ImPeriod", "speck": "ImSpeck"}
    for mark, x, y_top, w, h in images:
        c.image(image_names[mark], x, y_top, w, h)

    return pdf.build(c.stream(), fonts, xobjects)


def verify_fixture_b(pdf_bytes: bytes) -> dict:
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as doc:
        page = doc.pages[0]
        words = page.extract_words(extra_attrs=["size", "fontname"])
        images = [
            {"x0": im["x0"], "x1": im["x1"], "top": im["top"], "bottom": im["bottom"]}
            for im in page.images
        ]
    texts = [w["text"] for w in words]
    for fragment in (*B_GROSS_RUNS, *B_YTD_RUNS, *B_NET_RUNS, "61", "400", "84", "112"):
        assert fragment in texts, f"digit run {fragment!r} missing from text layer"
    assert B_GROSS not in texts, "the amount must NOT be one text token"
    for joined in (B_GROSS.replace("$", ""), "61400", "61,400", "61.400", "84,112", "84.112"):
        assert joined not in texts, f"{joined!r} must not exist in the text layer"

    by_text = {}
    for word in words:
        by_text.setdefault(word["text"], []).append(word)

    d7 = by_text["$7"][0]
    d514 = by_text["514"][0]
    hole = d514["x0"] - d7["x1"]
    assert 4.5 < hole < 5.7, f"hole width {hole} drifted from the measured ~5.1pt"
    in_hole = [
        im for im in images
        if im["x0"] >= d7["x1"] - 0.5 and im["x1"] <= d514["x0"] + 0.5
        and im["bottom"] > d7["top"] and im["top"] < d7["bottom"] + 1.0
    ]
    assert len(in_hole) == 1, f"exactly one punctuation image in the hole, got {len(in_hole)}"
    width = in_hole[0]["x1"] - in_hole[0]["x0"]
    assert width < 4.0, f"punctuation image width {width} must sit under the 4pt sliver floor"

    d84 = by_text["84"][0]
    d112 = by_text["112"][0]
    empty_hole = [
        im for im in images
        if im["x0"] >= d84["x1"] - 0.5 and im["x1"] <= d112["x0"] + 0.5
        and im["bottom"] > d84["top"] and im["top"] < d84["bottom"] + 1.0
    ]
    assert not empty_hole, "decoy 3's hole must carry NO ink"

    d61 = by_text["61"][0]
    d400 = by_text["400"][0]
    speck = [
        im for im in images
        if im["x0"] >= d61["x1"] - 0.5 and im["x1"] <= d400["x0"] + 0.5
        and im["bottom"] > d61["top"] and im["top"] < d61["bottom"] + 1.0
    ]
    assert len(speck) == 1, "decoy 4's hole must carry exactly the speck"

    ink = _ink_in_band(pdf_bytes, in_hole[0]["x0"], in_hole[0]["x1"],
                       in_hole[0]["top"], in_hole[0]["bottom"])
    assert ink >= 4, f"the comma image must actually ink pixels (got {ink})"
    return {"holePt": hole, "images": len(images)}


# ── fixture C ────────────────────────────────────────────────────────────────

C_ACCOUNT_NUMBER = "731100482915530"
C_PERIOD_LINE = "May 16, 2026throughJune 15, 2026"
C_PERIOD_START = "May 16, 2026"
C_PERIOD_END = "June 15, 2026"
C_BEGINNING = "$9,214.55"
# The five summary rows BALANCE, and that is the whole point of the second
# withdrawal category: 9,214.55 + 2,412.19 - 745.00 - 1,834.02 = 9,047.72.
# Drop 'Checks Paid' and the block no longer balances — which is the proof that
# 'Electronic Withdrawals' is a CATEGORY and not the withdrawals total.
C_DEPOSITS = "2,412.19"
C_CHECKS_PAID = "-745.00"
C_ELECTRONIC = "-1,834.02"
C_ENDING = "$9,047.72"
C_PROSE_DATES = ("04/10/2026", "05/09/2026")


def build_fixture_c() -> bytes:
    pdf = RawPdf()
    fonts = add_standard_fonts(pdf)
    c = Content()

    # Masthead. Mixed-case 'Bank' on purpose: bankName's ALL-CAPS regex must
    # keep failing, as it does on the real statement.
    c.words("F1", 9, 39.6, 50.0, ["Metropolitan", "Nest", "Egg", "Bank,", "N.A."])
    c.words("F1", 9, 39.6, 62.0, ["P", "O", "Box", "88214"])
    # The period line: month-name dates with the measured 'through' fusion —
    # ONE text run whose only spaces sit inside the dates, so pdfplumber hands
    # over '2026throughJune' as one word.
    c.text("F1", 9, 380.0, 62.0, C_PERIOD_LINE)
    c.text("F1", 9, 380.0, 74.0, "Account Number:")
    c.text("F1", 9, 454.5, 74.0, C_ACCOUNT_NUMBER)

    # Account holder, bare — no 'Account Holder:' caption exists on a real
    # statement of this dialect; the field must stay MISSING, never guessed.
    c.words("F1", 9, 39.6, 110.0, ["Erik", "Q.", "Sample"])

    # CHECKING SUMMARY: the real-dialect summary block.
    c.text("FB", 9, 39.6, 300.0, "CHECKING SUMMARY")
    c.words("F1", 9, 39.6, 321.7, ["Beginning", "Balance"])
    c.text("F1", 9, 326.1, 321.7, C_BEGINNING)
    c.words("F1", 9, 39.6, 339.5, ["Deposits", "and", "Additions"])
    c.text("F1", 9, 326.1, 339.5, C_DEPOSITS)
    # The SECOND withdrawal category, as every measured real statement prints.
    # Without it the block has one withdrawal row, its answer key can call that
    # row the total, and the key agrees with the mis-binding because both came
    # from the same wrong assumption. With it, binding either category as
    # totalWithdrawals is an UNMAPPED_CAPTURE and the gate fails.
    c.words("F1", 9, 39.6, 357.3, ["Checks", "Paid"])
    c.text("F1", 9, 326.1, 357.3, C_CHECKS_PAID)
    c.words("F1", 9, 39.6, 375.0, ["Electronic", "Withdrawals"])
    c.text("F1", 9, 326.1, 375.0, C_ELECTRONIC)
    c.words("F1", 9, 39.6, 392.8, ["Ending", "Balance"])
    c.text("F1", 9, 326.1, 392.8, C_ENDING)

    # Detail sections — the SAME wordings as section headers, with transaction
    # rows whose amounts are the decoys nothing may capture.
    c.text("FB", 9, 39.6, 420.0, "DEPOSITS AND ADDITIONS")
    c.words("F1", 9, 39.6, 438.0, ["05/21", "Zelle", "Payment", "From", "Quarry"])
    c.text("F1", 9, 326.1, 438.0, "1,180.00")
    c.words("F1", 9, 39.6, 456.0, ["06/02", "Remote", "Online", "Deposit"])
    c.text("F1", 9, 326.1, 456.0, "1,232.19")
    c.text("FB", 9, 39.6, 480.0, "ELECTRONIC WITHDRAWALS")
    c.words("F1", 9, 39.6, 498.0, ["05/28", "Payment", "Sent"])
    c.text("F1", 9, 326.1, 498.0, "-934.02")
    c.words("F1", 9, 39.6, 516.0, ["06/10", "Card", "Purchase"])
    c.text("F1", 9, 326.1, 516.0, "-900.00")

    # The prose trap, honest metrics, DIFFERENT dates from the true period —
    # so a naked 'Statement Period' label capturing here is a loud MISMATCH.
    c.words(
        "F1", 8, 39.6, 671.1,
        ["fees", "for", "the", "monthly", "statement", "period:",
         C_PROSE_DATES[0], C_PROSE_DATES[1], "were", "waived", "this", "cycle"],
        gap=3.6,
    )

    return pdf.build(c.stream(), fonts, {})


def verify_fixture_c(pdf_bytes: bytes) -> dict:
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as doc:
        words = doc.pages[0].extract_words()
    texts = [w["text"] for w in words]
    assert "2026throughJune" in texts, "the measured word fusion must reproduce"
    assert "through" not in texts, "an unfused 'through' defeats the fusion pin"
    for needle in ("Deposits", "Additions", "Checks", "Paid", "Electronic",
                   "Withdrawals", "statement", "period:", C_PROSE_DATES[0],
                   C_DEPOSITS, C_CHECKS_PAID, C_ELECTRONIC, C_ACCOUNT_NUMBER):
        assert needle in texts, (
            f"{needle!r} missing (words under pdfplumber's 3pt merge tolerance "
            "fuse and defeat both the anchors and the traps)"
        )
    # The summary must BALANCE across all five rows and NOT balance without the
    # second withdrawal category. That inequality is the fixture's whole claim
    # about what 'Electronic Withdrawals' means; a fixture whose numbers stop
    # saying it has stopped being able to fail.
    money = [Decimal(v.replace("$", "").replace(",", ""))
             for v in (C_BEGINNING, C_DEPOSITS, C_CHECKS_PAID, C_ELECTRONIC, C_ENDING)]
    begin, deposits, checks, electronic, ending = money
    assert begin + deposits + checks + electronic == ending, "five rows must balance"
    assert begin + deposits + electronic != ending, (
        "without Checks Paid the block must NOT balance — otherwise 'Electronic "
        "Withdrawals' would be the total after all")
    assert checks != 0, "a zero second category proves nothing"
    return {"words": len(texts)}


# ── answer keys ──────────────────────────────────────────────────────────────
# Generic-layout keys (top-level scalars named exactly like engine fields):
# BANK_STATEMENT and PAYSTUB have no FIELD_LABEL_MAPS table, so corpus_score
# bridges by normalized name. Fields expected MISSING are OMITTED — a capture
# for any of them surfaces as UNMAPPED_CAPTURE and fails the gate, which is
# exactly the decoy discipline these fixtures exist to enforce.

ANSWERS_A = {
    "file": "bank-degenerate-metrics.pdf",
    "form": "Synthetic bank statement — degenerate glyph metrics (fixture A)",
    "extractionNotes": (
        "Data font reports ~0.2pt-tall baseline stripes (pdfplumber size 0.24, "
        "fontname unknown) while captions are honest 7.8pt Helvetica. "
        "accountNumber pins the row-split defect: MISSING at HEAD, MATCH after "
        "the worker box repair. statementPeriod*/accountHolderName/totalDeposits/"
        "totalWithdrawals are absent by design and must stay MISSING — the prose "
        "'statement period:' row and the honest-metrics 12-digit reference on "
        "the next row are decoys, so any capture for them is UNMAPPED_CAPTURE."
    ),
    "accountNumber": A_ACCOUNT_NUMBER,
    "beginningBalance": A_BEGINNING,
    "endingBalance": A_ENDING,
}

ANSWERS_B = {
    "file": "paystub-image-punctuation.pdf",
    "form": "Synthetic paystub — punctuation drawn as images (fixture B)",
    "extractionNotes": (
        "Money amounts are text digit runs with comma/period drawn as ~3.8pt "
        "1-bit images in ~5.1pt holes: gross/YTD/net are MISSING at HEAD and "
        "MATCH only after a pixel-truth repair. payDate is the always-green "
        "control (its '/' is real text). federalWithholding ('61' speck '400'), "
        "payPeriodStart/End (unknown captions), borrowerName and payFrequency "
        "must stay MISSING — any capture is UNMAPPED_CAPTURE."
    ),
    "employerName": B_EMPLOYER,
    "payDate": B_PAY_DATE,
    "currentGrossPay": B_GROSS,
    "ytdGrossPay": B_YTD,
    "netPay": B_NET,
}

ANSWERS_C = {
    "file": "bank-dialect-chase.pdf",
    "form": "Synthetic bank statement — real-bank vocabulary (fixture C)",
    "extractionNotes": (
        "Summary says 'Deposits and Additions' and splits withdrawals across "
        "TWO categories ('Checks Paid', 'Electronic Withdrawals') that only "
        "balance together — so this dialect prints NO withdrawals total and "
        "totalWithdrawals is OMITTED here: any capture for it is an "
        "UNMAPPED_CAPTURE and fails the gate, which is how this key catches a "
        "rung that binds a category subtotal. 'Deposits and Additions' IS the "
        "whole credit side (the same identity balances with it as the only "
        "deposit row), so totalDeposits is expected. The period line prints "
        "month-name dates with the '2026throughJune' word fusion; the footnote "
        "prose repeats 'statement period:' with DIFFERENT slash dates so a "
        "prose capture is a loud MISMATCH. Detail rows repeat the section "
        "wordings with different amounts — capturing one is a MISMATCH."
    ),
    "accountNumber": C_ACCOUNT_NUMBER,
    "beginningBalance": C_BEGINNING,
    "endingBalance": C_ENDING,
    "totalDeposits": C_DEPOSITS,
    "statementPeriodStart": C_PERIOD_START,
    "statementPeriodEnd": C_PERIOD_END,
}


FIXTURES = [
    ("bank-degenerate-metrics.pdf", build_fixture_a, verify_fixture_a, ANSWERS_A),
    ("paystub-image-punctuation.pdf", build_fixture_b, verify_fixture_b, ANSWERS_B),
    ("bank-dialect-chase.pdf", build_fixture_c, verify_fixture_c, ANSWERS_C),
]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=Path("corpus"),
                        help="output directory (default: corpus/)")
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    for name, build, verify, answers in FIXTURES:
        pdf_bytes = build()
        measured = verify(pdf_bytes)
        path = args.out / name
        path.write_bytes(pdf_bytes)
        key_path = path.with_suffix(".answers.json")
        key_path.write_text(json.dumps(answers, indent=2) + "\n")
        print(f"wrote {path} ({len(pdf_bytes)} bytes) + {key_path.name}  {measured}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
