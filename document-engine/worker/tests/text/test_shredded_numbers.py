"""Why a printed amount arrives as several spans, measured rather than assumed.

pdfplumber only keeps growing a word while EVERY requested ``extra_attrs`` value
matches (``WordExtractor.merge_chars`` copies them from the word's first char), and
``_extract_page`` asks for ``size`` and ``fontname`` on rotation-0 pages. So a producer
that sets the punctuation of ``$1,321.18`` in a different font from its digits — a
subset-font switch, ordinary in payroll and tax output — has that one printed token cut
into five spans that PRINT TOUCHING. Nothing is lost; the pieces are simply handed over
separately, and joining them with a space asserts a gap the page never printed.

Two constructions live here and they are NOT the same defect:

  RECOVERABLE — the punctuation is drawn in another font. Every printed character
  survives as a span and the seams are sub-em, so the assembly seam can put the token
  back together (Java: ``SpanJoin``/``SpanText``).

  UNRECOVERABLE — the punctuation's ToUnicode resolves to whitespace. pdfplumber DROPS
  blank chars and breaks the word on them (``iter_chars_to_words``), so the comma and
  the decimal point are gone from the text layer altogether. What is left behind is a
  full glyph advance, indistinguishable from a printed space — and that is the point:
  the seam rule must refuse it, because concatenating there invents a number the
  document does not say.

These tests pin pdfplumber's behaviour and the GEOMETRY the join threshold is derived
from. The PDFs are built in memory: fixtures/ is owned by the provenance manifest.
"""

import io

import pdfplumber
import pytest

SIZE = 9.0
#: Helvetica AFM advance widths (per 1000 em) for the glyphs drawn below.
_WIDTHS = {
    "$": 556, ",": 278, ".": 278, "1": 556, "2": 556, "3": 556, "8": 556,
    "G": 722, "r": 333, "o": 556, "s": 500, " ": 278, "P": 667, "a": 556, "y": 500,
}
AMOUNT = "$1,321.18"

_HELVETICA = "/Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding"
_TIMES = "/Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding"
#: Helvetica with the comma (44) and period (46) codes re-pointed at /space — what a
#: subset font with a broken ToUnicode CMap looks like to pdfminer: the glyph is printed,
#: the character extracts as whitespace.
_BLANK_PUNCTUATION = (
    "/Type /Font /Subtype /Type1 /BaseFont /Helvetica "
    "/Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding "
    "/Differences [44 /space 46 /space] >>"
)


def _advance(text: str) -> float:
    return sum(_WIDTHS[c] for c in text) * SIZE / 1000.0


def _pdf(content: str, fonts: dict[str, str]) -> bytes:
    """A one-page PDF with an exact content stream — reportlab cannot express /Differences
    encodings or per-glyph Tm, and both are the whole point here."""
    objects: list[bytes] = []

    def add(body: str) -> int:
        objects.append(body.encode("latin-1"))
        return len(objects)

    font_refs = {name: add(f"<< {body} >>") for name, body in fonts.items()}
    stream = content.encode("latin-1")
    objects.append(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream))
    contents = len(objects)
    pages, page = len(objects) + 1, len(objects) + 2
    add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    resources = " ".join(f"/{n} {font_refs[n]} 0 R" for n in fonts)
    add(
        f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 612 792] "
        f"/Resources << /Font << {resources} >> >> /Contents {contents} 0 R >>"
    )
    catalog = add(f"<< /Type /Catalog /Pages {pages} 0 R >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    start = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objects) + 1, catalog, start,
    )
    return bytes(out)


def _amount_in_two_fonts(punctuation_font: str) -> bytes:
    """`Gross Pay` in one run, then the amount glyph by glyph at CONTIGUOUS advances,
    its punctuation drawn from a second font."""
    parts = [f"BT /F1 {SIZE} Tf 1 0 0 1 72 700 Tm (Gross Pay) Tj ET"]
    cursor = 200.0
    for glyph in AMOUNT:
        font = "F2" if glyph in ",." else "F1"
        parts.append(f"BT /{font} {SIZE} Tf 1 0 0 1 {cursor:.2f} 700.00 Tm ({glyph}) Tj ET")
        cursor += _advance(glyph)
    return _pdf("\n".join(parts), {"F1": _HELVETICA, "F2": punctuation_font})


def _words(pdf_bytes: bytes) -> list[dict]:
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as document:
        # Exactly what text._extract_page asks for on a rotation-0 page.
        return document.pages[0].extract_words(extra_attrs=["size", "fontname"])


def _seam(left: dict, right: dict) -> float:
    """The printed gap between two spans, as a fraction of the smaller em."""
    return (right["x0"] - left["x1"]) / min(
        left["bottom"] - left["top"], right["bottom"] - right["top"]
    )


@pytest.fixture(scope="module")
def shredded() -> list[dict]:
    return _words(_amount_in_two_fonts(_TIMES))


@pytest.fixture(scope="module")
def dropped() -> list[dict]:
    return _words(_amount_in_two_fonts(_BLANK_PUNCTUATION))


class TestFontSwitchShredsAPrintedToken:
    def test_one_printed_amount_arrives_as_five_spans(self, shredded):
        assert [word["text"] for word in shredded] == [
            "Gross", "Pay", "$1", ",", "321", ".", "18",
        ]

    def test_every_printed_character_survives(self, shredded):
        """The value is RECOVERABLE: assembly has to put it back, not invent it."""
        assert "".join(word["text"] for word in shredded[2:]) == AMOUNT

    def test_the_font_metadata_request_is_what_cuts_it(self):
        """Drop extra_attrs and pdfplumber hands back one word — so the shredding is a
        cost of asking for per-span fontSize/fontName, not a property of the page."""
        with pdfplumber.open(io.BytesIO(_amount_in_two_fonts(_TIMES))) as document:
            assert [w["text"] for w in document.pages[0].extract_words()] == [
                "Gross", "Pay", AMOUNT,
            ]

    def test_fragments_of_one_token_print_touching(self, shredded):
        """Every seam INSIDE the amount is positioning residue — the producer advancing
        by Helvetica's punctuation width while Times draws the glyph."""
        seams = [_seam(a, b) for a, b in zip(shredded[2:], shredded[3:])]

        assert max(abs(seam) for seam in seams) < 0.03
        assert seams == pytest.approx([0.0007, 0.0278, -0.0004, 0.0278], abs=5e-4)

    def test_a_printed_space_is_two_orders_of_magnitude_wider(self, shredded):
        """`Gross`/`Pay` are separated by Helvetica's space advance, 0.278 em. That
        separation from the sub-0.03 em seams above is what the join threshold sits in."""
        assert _seam(shredded[0], shredded[1]) == pytest.approx(0.278, abs=1e-3)


class TestWhitespaceMappedPunctuationIsUnrecoverable:
    """The other shape, kept honest: nothing here may be repaired by joining."""

    def test_the_punctuation_is_gone_from_the_text_layer(self, dropped):
        assert [word["text"] for word in dropped] == ["Gross", "Pay", "$1", "321", "18"]
        assert "".join(word["text"] for word in dropped[2:]) == "$132118"

    def test_what_is_left_behind_is_a_full_glyph_advance(self, dropped):
        """0.278 em — the dropped comma's own width, the SAME seam a printed space
        leaves. No geometry can tell these apart, so joining here would fabricate."""
        seams = [_seam(a, b) for a, b in zip(dropped[2:], dropped[3:])]

        assert seams == pytest.approx([0.278, 0.277], abs=1e-3)
        assert min(seams) > _seam(dropped[0], dropped[1]) - 1e-3
