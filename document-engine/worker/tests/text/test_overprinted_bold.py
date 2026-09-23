"""Faux-bold overprinting must not shred a word into repeated glyphs.

Measured, not assumed (a real ADP earnings statement for loan 1000000253, engine package
``1b228f6e``, read out of ``text_span`` on 2026-09-09). Every BOLD caption on that stub
arrives with each character repeated four times::

    GGGGrrrroooossssssss PPPPaaaayyyy      is "Gross Pay"
    NNNNeeeetttt PPPPaaaayyyy              is "Net Pay"
    EEEEaaaarrrrnnnniiiinnnnggggssss      is "Earnings"

The producer has no bold font in the resource dictionary, so it fakes weight the way
PostScript always has: it draws the same glyphs several times, each a fraction of a point
from the last, and lets the ink thicken. Nothing is corrupt — the page really does contain
four "G"s — but every copy is a char, pdfplumber groups chars into words by proximity, and
the overprint is far closer than any inter-word gap, so the copies land in ONE word.

Why it matters beyond one employer. The classifier matches literal anchors over the page's
reading-order text, so a quadrupled caption matches nothing: on the real stub the PAYSTUB
pack scored 0.20 (only the UNBOLDED "Pay Date:" header hit) while BANK_STATEMENT scored
0.30 on the plain word "Checking" in the direct-deposit block — the wrong pack outranked
the right one purely because the payroll words were bold and the bank word was not. The
page landed UNKNOWN, drew no extraction schema, and produced zero fields.

The repair belongs HERE and not in the rule packs: re-weighting anchors would paper over
one vendor while every other faux-bold producer stays unreadable, and the same overprint
destroys the amounts extraction depends on ("$4,030.77" arrives quadrupled too).

The rule is geometric, never a spelling heuristic: a char is a duplicate only when the
SAME character is drawn at the same point, within a fraction of its own width. So the
doubled letters of an ordinarily-set word are untouched — they sit a full advance apart.
"""

import io

import pdfplumber
import pytest

from pragmaticds_docengine_worker.text import extract_text

PAGE_W, PAGE_H = 612.0, 792.0
SIZE = 10.0


def _literal(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def _pdf(runs) -> bytes:
    """One page. ``runs`` = [(text, x, baseline_top)], drawn in emission order."""
    objects = []

    def add(body):
        objects.append(body.encode("latin-1") if isinstance(body, str) else body)
        return len(objects)

    helv = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
               "/Encoding /WinAnsiEncoding >>")
    ops = [
        f"BT /F1 {SIZE:g} Tf 1 0 0 1 {x:.3f} {PAGE_H - top:.3f} Tm ({_literal(text)}) Tj ET"
        for text, x, top in runs
    ]
    stream = "\n".join(ops).encode("latin-1")
    contents = add(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream))
    pages, page = len(objects) + 1, len(objects) + 2
    add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    add(f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 {PAGE_W:g} {PAGE_H:g}] "
        f"/Resources << /Font << /F1 {helv} 0 R >> >> /Contents {contents} 0 R >>")
    catalog = add(f"<< /Type /Catalog /Pages {pages} 0 R >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % index + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += (b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n"
            % (len(objects) + 1, catalog, xref))
    return bytes(out)


def _overprinted(text: str, x: float, top: float, copies: int, offset: float):
    """The producer's own trick: the same run drawn `copies` times, `offset` pt apart."""
    return [(text, x + i * offset, top) for i in range(copies)]


def _texts(pdf_bytes: bytes) -> list[str]:
    return [span.text for span in extract_text(pdf_bytes, [0])[0].spans]


class TestOverprintedBoldIsCollapsed:
    def test_a_quadrupled_caption_reads_as_the_word_it_prints(self):
        # ADP's own geometry: four copies, ~0.2pt apart, at 10pt Helvetica.
        pdf = _pdf(_overprinted("Gross Pay", 72.0, 100.0, copies=4, offset=0.2))

        assert _texts(pdf) == ["Gross", "Pay"]

    @pytest.mark.parametrize("copies", [2, 3, 4])
    def test_the_repair_does_not_depend_on_how_many_copies_the_producer_drew(self, copies):
        pdf = _pdf(_overprinted("Earnings", 72.0, 100.0, copies=copies, offset=0.2))

        assert _texts(pdf) == ["Earnings"]

    def test_an_ordinary_word_with_doubled_letters_is_untouched(self):
        # The guard against a spelling heuristic: these letters repeat because the WORD
        # repeats them, a full advance apart, not because the glyph was overprinted.
        pdf = _pdf([("Mississippi", 72.0, 100.0), ("committee", 200.0, 100.0)])

        assert _texts(pdf) == ["Mississippi", "committee"]

    def test_a_masked_account_number_keeps_every_X_it_prints(self):
        # From the same stub: "XXXXXX9423" is the masked deposit account. Its six X's are
        # printed, not overprinted, and losing them would rewrite the document.
        pdf = _pdf([("XXXXXX9423", 72.0, 100.0)])

        assert _texts(pdf) == ["XXXXXX9423"]

    def test_a_quadrupled_amount_survives_as_the_amount(self):
        # Extraction reads this one, not just classification: the stub's gross pay.
        pdf = _pdf(_overprinted("$4,030.77", 72.0, 100.0, copies=4, offset=0.2))

        assert _texts(pdf) == ["$4,030.77"]
