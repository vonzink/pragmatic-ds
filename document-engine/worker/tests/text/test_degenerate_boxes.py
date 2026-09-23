"""Degenerate glyph metrics: a word box that collapses to a baseline stripe.

A real bank statement sets its DATA in an embedded font used at an effective
size of ~0.24pt whose glyphs are blown back up through the font matrix: the
print reads normally, but pdfplumber boxes every char at the EFFECTIVE size,
so each word arrives as a ~0.2pt-tall stripe sitting on the baseline
(fontname 'unknown', size 0.24 — measured live, 92% of the spans on page 0).
Every downstream proximity rule derives its reach from span height, so the
caption row 'Account Number:' (honest 7.8pt Helvetica) and its 15-digit value
(a stripe 1pt below the caption's baseline) SPLIT into two visual lines and a
printed value is never offered to its caption's extraction rung.

``text._repair_degenerate_boxes`` rebuilds such boxes from the one scale the
word still carries — its own per-char advance — keeping the baseline-anchored
BOTTOM and raising TOP. These tests pin the repair's arithmetic AND its
refusals: honest boxes, consistent micro-print, and neighbouring rows must
come through byte-identically. The PDF is built in memory (fixtures/ is owned
by the provenance manifest), with the same Type3 construction the corpus
generator uses (tools/gen_realworld_fixtures.py).
"""

import io

import pdfplumber
import pytest

from pragmaticds_docengine_worker.text import extract_text

PAGE_H = 792.0

#: Tf 0.24 with a 0.0229-scaled FontMatrix over 1000-unit glyphs: 5.496pt
#: per-char advance, 0.24pt reported boxes — the measured real-world numbers.
TF = 0.24
MATRIX = 0.0229
ADVANCE_PT = 1000 * MATRIX * TF

DIGITS = "429815003117208"     # 15 chars — the pinned account-number shape
DECOY = "882031455907"         # honest metrics, the NEXT printed row
TINY = "77"                    # consistent micro-print: small box, small advance


def _degenerate_pdf() -> bytes:
    """One page: honest 7.8pt captions, one degenerate value word, one honest
    decoy word on the row below, one consistent micro-print word."""
    objects: list[bytes] = []

    def add(body: str | bytes) -> int:
        objects.append(body.encode("latin-1") if isinstance(body, str) else body)
        return len(objects)

    def add_stream(data: bytes) -> int:
        return add(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(data), data))

    helv = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    glyph = add_stream(b"1000 0 d0 80 0 840 1150 re f")
    chars = sorted(set(DIGITS + TINY))
    charprocs = add("<< %s >>" % " ".join(f"/g{ord(c)} {glyph} 0 R" for c in chars))
    codes = [ord(c) for c in chars]
    widths = " ".join("1000" if chr(c) in chars else "0"
                      for c in range(min(codes), max(codes) + 1))
    cmap = (
        "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
        "/CMapName /T-T3 def\n/CMapType 2 def\n"
        "1 begincodespacerange\n<00> <ff>\nendcodespacerange\n"
        f"{len(chars)} beginbfchar\n"
        + "\n".join(f"<{ord(c):02x}> <{ord(c):04x}>" for c in chars)
        + "\nendbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"
    )
    tounicode = add_stream(cmap.encode("latin-1"))
    t3 = add(
        "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1250] "
        f"/FontMatrix [{MATRIX} 0 0 {MATRIX} 0 0] /CharProcs {charprocs} 0 R "
        "/Encoding << /Type /Encoding /Differences ["
        + " ".join(f"{ord(c)} /g{ord(c)}" for c in chars)
        + f"] >> /FirstChar {min(codes)} /LastChar {max(codes)} /Widths [{widths}] "
        f"/ToUnicode {tounicode} 0 R >>"
    )

    def text(font: str, size: float, x: float, baseline_top: float, s: str) -> str:
        return (f"BT /{font} {size:g} Tf 1 0 0 1 {x:.2f} {PAGE_H - baseline_top:.2f} "
                f"Tm ({s}) Tj ET")

    content = "\n".join([
        text("F1", 7.8, 362.1, 68.4, "Account"),
        text("F1", 7.8, 394.0, 68.4, "Number:"),
        text("T3", TF, 436.5, 69.4, DIGITS),          # the degenerate value
        text("F1", 7.8, 436.5, 87.8, DECOY),          # honest, NEXT row
        text("T3", TF, 100.0, 200.0, TINY),           # degenerate, must repair too
        text("F1", 0.9, 200.0, 300.0, "mm"),          # micro but CONSISTENT: untouched
    ]).encode("latin-1")
    contents = add_stream(content)
    pages, page = len(objects) + 1, len(objects) + 2
    add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    add(
        f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 612 {PAGE_H:g}] "
        f"/Resources << /Font << /F1 {helv} 0 R /T3 {t3} 0 R >> >> "
        f"/Contents {contents} 0 R >>"
    )
    catalog = add(f"<< /Type /Catalog /Pages {pages} 0 R >>")

    out = bytearray(b"%PDF-1.5\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    start = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objects) + 1, catalog, start)
    return bytes(out)


@pytest.fixture(scope="module")
def pdf_bytes() -> bytes:
    return _degenerate_pdf()


@pytest.fixture(scope="module")
def spans(pdf_bytes) -> dict[str, dict]:
    page = extract_text(pdf_bytes, [0])[0]
    return {span.text: span.payload() for span in page.spans}


class TestTheProducerDefectIsReal:
    def test_pdfplumber_reports_the_baseline_stripe(self, pdf_bytes):
        """The pin on the RAW producer behaviour the repair exists for."""
        with pdfplumber.open(io.BytesIO(pdf_bytes)) as doc:
            words = {w["text"]: w for w in doc.pages[0].extract_words(
                extra_attrs=["size", "fontname"])}
        value = words[DIGITS]
        assert value["bottom"] - value["top"] < 0.5
        assert value["fontname"] == "unknown"
        assert value["size"] < 0.5
        caption = words["Number:"]
        assert 6.0 < caption["bottom"] - caption["top"] < 9.0


class TestTheRepair:
    def test_height_becomes_the_words_own_advance(self, spans):
        value = spans[DIGITS]
        assert value["height"] == pytest.approx(ADVANCE_PT, abs=0.11)

    def test_bottom_is_kept_and_top_is_raised(self, spans):
        """Bottom is the baseline-anchored trustworthy edge; only top moves."""
        value = spans[DIGITS]
        assert value["y"] + value["height"] == pytest.approx(69.4, abs=0.15)
        assert value["y"] == pytest.approx(69.4 - ADVANCE_PT, abs=0.15)

    def test_the_caption_row_now_groups_with_its_value(self, spans):
        """The half-min-height rule (VisualLines/SpanJoin, mirrored): before
        the repair Δcenter 4.8pt vs threshold 0.1pt split the printed row."""
        caption = spans["Number:"]
        value = spans[DIGITS]
        caption_center = caption["y"] + caption["height"] / 2
        value_center = value["y"] + value["height"] / 2
        threshold = 0.5 * min(caption["height"], value["height"])
        assert abs(caption_center - value_center) <= threshold

    def test_fontsize_keeps_the_raw_report_as_the_breadcrumb(self, spans):
        assert spans[DIGITS]["fontSize"] == pytest.approx(TF, abs=0.06)

    def test_the_two_char_stripe_repairs_by_the_same_rule(self, spans):
        assert spans[TINY]["height"] == pytest.approx(ADVANCE_PT, abs=0.11)


class TestTheRefusals:
    def test_an_honest_box_is_untouched(self, spans, pdf_bytes):
        with pdfplumber.open(io.BytesIO(pdf_bytes)) as doc:
            raw = {w["text"]: w for w in doc.pages[0].extract_words(
                extra_attrs=["size", "fontname"])}
        for text in ("Account", "Number:", DECOY):
            span, word = spans[text], raw[text]
            assert span["y"] == pytest.approx(word["top"], abs=0.06), text
            assert span["height"] == pytest.approx(
                word["bottom"] - word["top"], abs=0.06), text

    def test_consistent_micro_print_is_untouched(self, spans):
        """Small box AND small advance: tiny but self-consistent — repairing
        it would fabricate a scale the page never printed."""
        assert spans["mm"]["height"] < 1.0

    def test_the_next_row_stays_a_separate_row(self, spans):
        """DECOY: the repaired value must group with ITS caption row and never
        reach the honest reference printed one row below (row annexation)."""
        value, decoy = spans[DIGITS], spans[DECOY]
        value_center = value["y"] + value["height"] / 2
        decoy_center = decoy["y"] + decoy["height"] / 2
        threshold = 0.5 * min(value["height"], decoy["height"])
        assert abs(value_center - decoy_center) > threshold
