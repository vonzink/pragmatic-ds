"""Native text extraction and the per-page text-layer verdict for /v1/text.

Extraction is pdfplumber words. pdfplumber reports TOP-LEFT points (x0/top), so a
box is a straight pass-through into geometry.Box — the rounding-and-canonical
gate — with one exception: pages carrying a /Rotate value report display-space
coordinates, which are mapped back to rotation-0 through geometry.unrotate_box.
There is no second conversion path. Reading order is decided AFTER that mapping,
on the canonical boxes: display space on a /Rotate page is a differently-oriented
page, and sorting there scrambles the words (see _reading_order).

Verdict (contract: NATIVE / SCANNED / MIXED / NONE) — decision inputs are the
word count, total word area vs page area, and embedded-image coverage
(pdfplumber page.images), plus a low-DPI ink check for wordless pages: the blank
fixture is a full-page WHITE image, so image coverage alone cannot distinguish a
scan from a blank.

    no words          -> NONE if ink fraction < INK_FLOOR else SCANNED
    words, but junk   -> SCANNED   (fewer than MIN_MEANINGFUL_WORDS and text area
                                    below MIN_WORD_AREA_RATIO, over a mostly-image page)
    meaningful words  -> MIXED if any significant image region is uncovered by words,
                         with those regions returned as uncoveredRegions
                      -> NATIVE otherwise

uncoveredRegions is the exact image-minus-words decomposition by y-bands
(_uncovered_regions): every point of an embedded image not under a word box lands
in some region, and only sub-glyph slivers are dropped. Coarse by design — OCR
over-coverage is cheap, missed regions are not. The one page-level floor is
MIN_UNCOVERED_AREA_RATIO: when the regions TOGETHER cover less of the page than
that, the page is NATIVE — a browser-printed statement's logo and icons are not a
pasted scan, and sending them to OCR costs two engine passes per page and, when
they yield nothing, a spurious OCR_LOW_CONFIDENCE flag on a perfectly readable page.

A MIXED candidate that clears the floor is then CONFIRMED on pixels (_confirm_mixed):
the page is rendered once at a coarse DPI and every uncovered region is classified
by textlike.assess — many small same-height ink blobs owning most of the ink is
print; a few large blobs is a chart, a banner, a photo, or a background. Only
text-like regions are published as uncoveredRegions; when none survive the floor
the page is NATIVE. The classifier errs toward "text" (module docstring), and a
page that cannot be rendered keeps every region: fail toward OCR, never away.

An IMAGE source (source.py) has no text layer at all — there are no glyph
operators to extract, only pixels — so it skips pdfplumber entirely and lands on
the same wordless split the PDF path uses: ink below INK_FLOOR is NONE (a blank
page), anything above it is SCANNED. SCANNED is what puts the page in the Java
side's OCR_ELIGIBLE set, which is the link that makes a photographed paystub
readable at all.
"""

import io
import json
import logging
import math
from dataclasses import dataclass

import numpy as np
import pdfplumber
import pypdfium2 as pdfium
from pdfplumber.utils.text import WordExtractor

from pragmaticds_docengine_worker.geometry import Box, unrotate_box, unrotate_extent
from pragmaticds_docengine_worker.punctuation import repair_image_punctuation
from pragmaticds_docengine_worker.render import resolve_page_indices
from pragmaticds_docengine_worker.source import looks_like_image, open_image_document
from pragmaticds_docengine_worker.textlike import TEXT_CHECK_DPI, assess
from pragmaticds_docengine_worker.wire import error

logger = logging.getLogger(__name__)

VERDICT_NATIVE = "NATIVE"
VERDICT_SCANNED = "SCANNED"
VERDICT_MIXED = "MIXED"
VERDICT_NONE = "NONE"

#: Below this fraction of dark pixels (low-DPI grayscale render) a wordless page is blank.
INK_FLOOR = 0.005
INK_CHECK_DPI = 36
INK_DARK_THRESHOLD = 230  # 0-255 grayscale; below = ink
#: A text layer is "meaningful" with at least this many words OR this much text area.
MIN_MEANINGFUL_WORDS = 5
MIN_WORD_AREA_RATIO = 0.005
#: Images only matter to the verdict once they cover this much of the page.
MIN_IMAGE_COVERAGE_RATIO = 0.10
#: A page whose uncovered image regions TOGETHER cover less of the page than this
#: is NATIVE, not MIXED. Sized for the page, not the region: a full-width banner
#: with only a caption printed on it still clears it; a logo or a row of icons does not.
MIN_UNCOVERED_AREA_RATIO = 0.01


@dataclass(frozen=True)
class Span:
    """One extracted word in canonical space, with reading-order ordinal."""

    ordinal: int
    text: str
    box: Box
    font_size: float | None
    font_name: str | None

    def payload(self) -> dict:
        item = {
            "ordinal": self.ordinal,
            "text": self.text,
            "x": self.box.x,
            "y": self.box.y,
            "width": self.box.width,
            "height": self.box.height,
        }
        if self.font_size is not None:
            item["fontSize"] = self.font_size
        if self.font_name is not None:
            item["fontName"] = self.font_name
        return item


@dataclass(frozen=True)
class PageText:
    page_index: int
    width_pt: float
    height_pt: float
    rotation: int
    verdict: str
    spans: list[Span]
    uncovered_regions: list[Box] | None
    ink_fraction: float | None

    def payload(self) -> dict:
        item = {
            "pageIndex": self.page_index,
            "widthPt": self.width_pt,
            "heightPt": self.height_pt,
            "rotation": self.rotation,
            "verdict": self.verdict,
            "inkFraction": self.ink_fraction,
            "spans": [span.payload() for span in self.spans],
        }
        if self.uncovered_regions is not None:
            item["uncoveredRegions"] = [
                {"x": region.x, "y": region.y, "width": region.width, "height": region.height}
                for region in self.uncovered_regions
            ]
        return item


def parse_text_request(raw: bytes | None) -> list[int]:
    """Validate the `request` JSON part: {"pages": [...]}. Empty/absent = all pages."""
    if raw is None or raw == b"":
        return []
    try:
        payload = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_JSON") from None
    if not isinstance(payload, dict):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_OBJECT")
    pages = payload.get("pages") or []
    if not isinstance(pages, list) or any(not isinstance(p, int) or isinstance(p, bool) for p in pages):
        raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_INT_LIST")
    return pages


def extract_text(file_bytes: bytes, requested_pages: list[int]) -> list[PageText]:
    """The endpoint's only entry point — dispatches on what kind of source this is."""
    if looks_like_image(file_bytes):
        return _extract_image_text(file_bytes, requested_pages)
    return _extract_pdf_text(file_bytes, requested_pages)


def _extract_image_text(image_bytes: bytes, requested_pages: list[int]) -> list[PageText]:
    """An image's "text layer" verdict: no spans, ever, and the ink check decides
    blank-vs-scanned. The page box is source.py's, so it is byte-identical to what
    /v1/render reported for the same file — the two must describe one frame."""
    with open_image_document(image_bytes) as document:
        indices = resolve_page_indices(requested_pages, len(document))
        pages = []
        for index in indices:
            page = document.page(index)
            ink = page.ink_fraction()
            pages.append(
                PageText(
                    page_index=index,
                    width_pt=page.width_pt,
                    height_pt=page.height_pt,
                    rotation=page.rotation,
                    verdict=VERDICT_NONE if ink < INK_FLOOR else VERDICT_SCANNED,
                    spans=[],
                    # uncoveredRegions is a MIXED-page concept: there is no native
                    # text here to leave anything uncovered, so OCR takes the page whole.
                    uncovered_regions=None,
                    ink_fraction=ink,
                )
            )
        return pages


def _extract_pdf_text(pdf_bytes: bytes, requested_pages: list[int]) -> list[PageText]:
    try:
        pdf = pdfplumber.open(io.BytesIO(pdf_bytes))
    except Exception:
        # pdfminer raises a zoo of exception types on malformed input; none carry
        # a stable contract meaning beyond "not a readable PDF".
        raise error(400, "CORRUPT_PDF") from None

    try:
        page_count = len(pdf.pages)
        if requested_pages:
            for index in requested_pages:
                if index < 0 or index >= page_count:
                    raise error(400, "PAGE_OUT_OF_RANGE", pageIndex=index, pageCount=page_count)
            indices = list(dict.fromkeys(requested_pages))
        else:
            indices = list(range(page_count))

        extracted = [_extract_page(pdf.pages[i], i, pdf_bytes) for i in indices]
    finally:
        pdf.close()

    # Phase 2 computed ink only for wordless pages (the SCANNED/NONE split); Phase 3
    # promotes inkFraction to a universal per-page signal (contract /v1/text), so
    # every page pays for the coarse render. Absent from the map = could not be
    # rendered for the check = null on the wire, never a fabricated zero.
    ink_fractions = _ink_fractions(pdf_bytes, [raw.page_index for raw in extracted])

    pages = []
    for raw in extracted:
        verdict, uncovered = _verdict(
            raw.spans, raw.image_boxes, raw.width_pt, raw.height_pt,
            ink_fractions.get(raw.page_index),
        )
        if verdict == VERDICT_MIXED:
            verdict, uncovered = _confirm_mixed(pdf_bytes, raw, uncovered)
        pages.append(
            PageText(
                page_index=raw.page_index,
                width_pt=raw.width_pt,
                height_pt=raw.height_pt,
                rotation=raw.rotation,
                verdict=verdict,
                spans=raw.spans,
                uncovered_regions=uncovered,
                ink_fraction=ink_fractions.get(raw.page_index),
            )
        )
    return pages


@dataclass(frozen=True)
class _RawPage:
    """A page after extraction but before its verdict (which may need an ink check)."""

    page_index: int
    width_pt: float
    height_pt: float
    rotation: int
    spans: list[Span]
    image_boxes: list[Box]


#: Which way pdfplumber must be told a word's characters advance, per /Rotate value.
#:
#: DO NOT "simplify" this back to the defaults. pdfplumber 0.11.10 decides character
#: order from the `upright` FLAG ALONE (WordExtractor.get_char_dir) and never looks at
#: the text-advance direction in the char matrix, so it gets two of the four quarter
#: turns backwards — silently, with perfect boxes:
#:
#:   /Rotate 180 — advance is -x, upright=True, matrix (-1,0,0,-1,540,72). pdfplumber
#:       treats upright as left-to-right and emits every word reversed: '$3,565.87'
#:       arrives as '78.565,3$'. char_dir="rtl" is the correction.
#:   /Rotate 270 — advance is -y, upright=False, matrix (0,1,-1,0,72,72). The lever
#:       here is line_dir, NOT char_dir: for non-upright chars pdfplumber uses
#:       char_dir_rotated, which defaults to `line_dir` ("Default is to flip the
#:       directions for rotated text"). line_dir="btt" is therefore what sets the
#:       ROTATED character direction to bottom-to-top.
#:
#: 0 and 90 are already right and get pdfplumber's own defaults, spelled out so the
#: table reads as one decision. Only the characters INSIDE each word are at stake
#: here; the order of the words themselves is _reading_order's problem, and it has
#: to solve it in canonical space — this table cannot and does not help with it.
#:
#: Rejected: use_text_flow=True, which orders by the content stream and would sidestep
#: the flag entirely, but shatters words into single characters ('ACME' -> A,C,M,E).
_CHAR_ORDER_BY_ROTATION = {
    0: {"char_dir": "ltr", "line_dir": "ttb"},
    90: {"char_dir": "ltr", "line_dir": "ttb"},
    180: {"char_dir": "rtl", "line_dir": "ttb"},
    270: {"char_dir": "ltr", "line_dir": "btt"},
}


#: DPI of the render backing the punctuation-mark ink check — the resolution
#: the real defect was measured at.
_PUNCTUATION_CHECK_DPI = 300


#: How far apart two copies of one character may sit and still be the SAME printed
#: glyph, as a fraction of that character's own box. A producer faking bold offsets
#: each copy by a fraction of a point (measured: ~0.2pt at 10pt on the ADP stub),
#: while two characters the page really printed twice sit a full advance apart — for
#: 10pt Helvetica "X", 6.7pt against a 1.7pt ceiling. Expressed relative to the char's
#: own box so it scales with font size instead of pinning one point value.
_OVERPRINT_TOLERANCE = 0.25


def _without_overprint(chars):
    """Drop the redundant copies a producer drew to fake bold.

    Measured on a real ADP earnings statement (engine package ``1b228f6e``, 2026-09-09):
    every bold caption is drawn FOUR times, each copy a fraction of a point right of the
    last, so ``Gross Pay`` reaches the text layer as ``GGGGrrrroooossssssss PPPPaaaayyyy``.
    The copies sit far closer together than any inter-word gap, so pdfplumber groups them
    into one word and every literal anchor over that page misses — which is how a paystub
    scored 0.20 as PAYSTUB against 0.30 as BANK_STATEMENT (on the unbolded word
    "Checking"), landed UNKNOWN, drew no extraction schema and produced zero fields.

    A char is redundant only when the SAME character is drawn at the same point, within a
    fraction of its own box. That is the producer's trick stated geometrically, and it is
    deliberately not a spelling rule: the doubled letters of an ordinarily-set word
    ("Mississippi") and the repeated X's of a masked account number ("XXXXXX9423") sit a
    full advance apart, and every one of them survives.

    Duplicates are found in POSITION order, never in content-stream order. A producer
    is free to draw the whole run four times rather than each glyph four times — the
    measured ADP file does exactly that — so the copies of one glyph need not be
    adjacent in the stream, and it is pdfplumber's own left-to-right char sort that
    interleaves them into "GGGG". Comparing each char with the last one KEPT at the
    same place is the only formulation that catches both layouts.

    Stream order is then restored for the survivors, so nothing downstream sees a
    reordering this function did not have to make.
    """
    order = sorted(range(len(chars)), key=lambda i: (float(chars[i].get("top", 0.0)),
                                                     float(chars[i].get("x0", 0.0))))
    dropped = set()
    previous = None
    for index in order:
        char = chars[index]
        if previous is not None and previous.get("text") == char.get("text"):
            width = abs(float(previous.get("x1", 0.0)) - float(previous.get("x0", 0.0)))
            height = abs(float(previous.get("bottom", 0.0)) - float(previous.get("top", 0.0)))
            dx = abs(float(char.get("x0", 0.0)) - float(previous.get("x0", 0.0)))
            dy = abs(float(char.get("top", 0.0)) - float(previous.get("top", 0.0)))
            if dx <= width * _OVERPRINT_TOLERANCE and dy <= height * _OVERPRINT_TOLERANCE:
                dropped.add(index)
        # Compared step to step, against the last char SEEN rather than the last one
        # kept: the copies drift, so a narrow glyph's fourth copy is further from the
        # first than the tolerance allows even though each copy is a hair from the one
        # before ("Earnings" survived as "Earniings" when this measured from the first).
        # Chaining is safe because each step must still be within a quarter of a glyph,
        # while two genuinely printed characters sit a full advance apart.
        previous = char
    return [char for index, char in enumerate(chars) if index not in dropped]


def _extract_page(page, page_index: int, pdf_bytes: bytes) -> _RawPage:
    rotation = _quarter_turn(page.rotation)
    width_pt, height_pt = float(page.width), float(page.height)
    if rotation in (90, 270):
        # pdfplumber dims are display-space; canonical dims are rotation-0.
        width_pt, height_pt = height_pt, width_pt
    width_pt, height_pt = round(width_pt, 1), round(height_pt, 1)

    try:
        # extra_attrs must match across a word's chars to group. On /Rotate pages
        # pdfplumber derives `size` PER GLYPH from the display-space height of
        # non-upright text, which shatters every word into fragments — so font
        # metadata is only requested (and only emitted) on rotation-0 pages.
        # Overprinted copies are dropped BEFORE grouping: they sit closer together than
        # any inter-word gap, so once pdfplumber has folded them into a word its text is
        # already "GGGGrrrroooossssssss" and only a spelling heuristic could get it back.
        # WordExtractor is what Page.extract_words delegates to, called here with the
        # same kwargs over the surviving chars — so this changes WHICH chars are grouped
        # and nothing about HOW they are grouped.
        raw_words = WordExtractor(
            extra_attrs=["size", "fontname"] if rotation == 0 else [],
            **_CHAR_ORDER_BY_ROTATION[rotation],
        ).extract_words(_without_overprint(page.chars))
        raw_images = [
            (float(im["x0"]), float(im["top"]), float(im["x1"]), float(im["bottom"]))
            for im in page.images
        ]
        if rotation == 0:
            # Both repairs read display-space boxes, which on a rotation-0 page
            # ARE canonical. On /Rotate pages neither runs: font metadata is
            # not even requested there, and the advance/row axes are turned.
            _repair_degenerate_boxes(raw_words)
            repair_image_punctuation(
                raw_words, raw_images, lambda: _page_gray(pdf_bytes, page_index))
        image_boxes = [
            _canonical_box(x0, top, x1, bottom, rotation, width_pt, height_pt)
            for x0, top, x1, bottom in raw_images
        ]
    except Exception:
        raise error(500, "TEXT_EXTRACTION_FAILED", pageIndex=page_index) from None

    # Canonicalise FIRST, then order, so the ordinals and the boxes describe one
    # page. The order reads the EXACT extent rather than the emitted box: rounding
    # to 0.1pt invents ties, and an invented tie is settled by pdfplumber's emission
    # order (measured — it moved two words on page 1 of a filled 1040).
    placed = [
        (
            unrotate_extent(
                word["x0"], word["top"], word["x1"], word["bottom"], rotation, width_pt, height_pt
            ),
            word,
        )
        for word in raw_words
    ]
    spans = []
    for ordinal, (_, word) in enumerate(_reading_order(placed)):
        box = _canonical_box(
            word["x0"], word["top"], word["x1"], word["bottom"], rotation, width_pt, height_pt
        )
        size = word.get("size")
        spans.append(
            Span(
                ordinal=ordinal,
                text=word["text"],
                box=box,
                font_size=round(float(size), 1) if size is not None else None,
                font_name=word.get("fontname") or None,
            )
        )

    return _RawPage(
        page_index=page_index,
        width_pt=width_pt,
        height_pt=height_pt,
        rotation=rotation,
        spans=spans,
        image_boxes=image_boxes,
    )


#: A word box shorter than this is not a text height any reader ever saw — the
#: smallest print in the committed fixture corpus is 2.3pt, and the degenerate
#: producers this repairs report 0.0-0.5pt.
_DEGENERATE_HEIGHT_PT = 1.5
#: ...and only counts as DEGENERATE (rather than merely tiny) when the word's
#: own per-char advance implies an em several times taller than the box says.
_DEGENERATE_ADVANCE_RATIO = 3.0
#: Advance floor: genuinely microscopic print (sub-2pt advance AND sub-2pt
#: height) is left exactly as reported — it is consistent, not degenerate.
_DEGENERATE_MIN_ADVANCE_PT = 2.0


def _repair_degenerate_boxes(raw_words: list[dict]) -> None:
    """Rebuild word boxes a metricless embedded font collapsed to baseline stripes.

    Measured defect (real bank statement, pdfplumber 0.11.10): the data font
    reports size 0.24, so every word's box is a 0.24pt-tall stripe AT THE
    BASELINE while the glyphs render at reading size — and every downstream
    proximity rule derives its reach from span height, so a caption row whose
    value is a stripe splits into two visual lines and the value is never
    offered to the caption's rung. The page's own per-char ADVANCE still
    carries the true scale (≈5.5pt/char vs the 0.24pt box), and the BOTTOM
    edge is baseline-anchored and trustworthy (label bottom 68.4 vs value
    bottom 69.4 on the measured row).

    The repair fixes the MEASUREMENT, not the rules: keep bottom, set height
    to the word's own mean char advance (a lower bound on the em — always a
    smaller reach than the true glyph height, so the repair can under-group
    but never over-group), raise top to match. Deliberately NOT a threshold
    floor inside the Java grouping rules: a floor wide enough to heal a 4.8pt
    center gap would exceed real 6-8pt row pitch and annex neighbouring rows.

    Both trigger conditions must hold — a sub-1.5pt box AND an advance at
    least 3x the reported height (and 2pt absolute) — so consistent tiny print
    is untouched. The committed fixture corpus has ZERO sub-2pt spans
    (min 2.3pt), so this is provably inert on every green fixture; the emitted
    fontSize keeps the RAW reported value as the breadcrumb that a span's box
    was rebuilt. Rotation-0 pages only: on /Rotate pages font metadata is not
    even requested, and the advance direction lives on another axis.
    """
    for word in raw_words:
        height = float(word["bottom"]) - float(word["top"])
        if height >= _DEGENERATE_HEIGHT_PT:
            continue
        advance = (float(word["x1"]) - float(word["x0"])) / max(len(word["text"]), 1)
        if advance < _DEGENERATE_MIN_ADVANCE_PT:
            continue
        if advance < _DEGENERATE_ADVANCE_RATIO * max(height, 0.0):
            continue
        word["top"] = float(word["bottom"]) - advance


def _page_gray(pdf_bytes: bytes, page_index: int):
    """(grayscale array, px-per-pt scale) of one page — the pixel evidence the
    punctuation-mark check reads. Raises on failure: the caller only invokes
    this when a candidate chain exists, and a page that cannot be rendered
    cannot prove its ink, so the words stay split (the repair simply never
    fires — _extract_page's except turns a raise here into TEXT_EXTRACTION_
    FAILED, so fail toward no-repair instead)."""
    scale = _PUNCTUATION_CHECK_DPI / 72.0
    try:
        document = pdfium.PdfDocument(pdf_bytes)
    except pdfium.PdfiumError:
        return np.full((1, 1), 255, dtype=np.uint8), scale
    try:
        image = document[page_index].render(scale=scale).to_pil().convert("L")
        return np.asarray(image), scale
    except Exception:
        # Unrenderable page: a 1x1 white raster proves no ink, so every ink
        # gate fails and no merge happens — never an extraction failure.
        return np.full((1, 1), 255, dtype=np.uint8), scale
    finally:
        document.close()


def _quarter_turn(rotation) -> int:
    normalized = int(rotation or 0) % 360
    return normalized if normalized in (0, 90, 180, 270) else 0


def _canonical_box(
    x0: float, top: float, x1: float, bottom: float, rotation: int, width_pt: float, height_pt: float
) -> Box:
    box = Box(x0, top, x1 - x0, bottom - top)
    if rotation:
        box = unrotate_box(box, rotation, width_pt, height_pt)
    return box


#: A word and the CANONICAL extent it is ordered by: (x0, top, x1, bottom), exact.
_Placed = tuple[tuple[float, float, float, float], dict]


def _reading_order(placed: list[_Placed]) -> list[_Placed]:
    """Top-to-bottom line bands, left-to-right within a band. A word joins the
    current band while its vertical range overlaps the band's running envelope by
    at least half the shorter height.

    Two things about the coordinates it reads, both of them load-bearing:

    CANONICAL, not the display space pdfplumber reported. On a /Rotate page those
    are different pages — /Rotate 180 turns "top-to-bottom, left-to-right" into
    bottom-to-top, right-to-left, i.e. every line and every word of the document
    backwards. _CHAR_ORDER_BY_ROTATION keeps the characters inside each word
    correct through it, so the wreckage is invisible to any check that matches
    words BY BOX; test_span_order.py pins span i to truth word i instead.

    EXACT, not the 0.1pt-rounded box the span carries. Rounding cannot reverse two
    coordinates, but it can collapse them into a tie, and the tie is then broken by
    pdfplumber's emission order rather than by anything on the page."""
    bands: list[dict] = []
    for item in sorted(placed, key=lambda p: (p[0][1], p[0][0])):
        _, top, _, bottom = item[0]
        band = bands[-1] if bands else None
        if band is not None:
            overlap = min(band["bottom"], bottom) - max(band["top"], top)
            shorter = min(band["bottom"] - band["top"], bottom - top)
            if shorter > 0 and overlap >= 0.5 * shorter:
                band["items"].append(item)
                band["top"] = min(band["top"], top)
                band["bottom"] = max(band["bottom"], bottom)
                continue
        bands.append({"top": top, "bottom": bottom, "items": [item]})
    ordered: list[_Placed] = []
    for band in bands:
        ordered.extend(sorted(band["items"], key=lambda p: p[0][0]))
    return ordered


def _verdict(
    spans: list[Span],
    image_boxes: list[Box],
    width_pt: float,
    height_pt: float,
    ink_fraction: float | None,
) -> tuple[str, list[Box] | None]:
    page_area = width_pt * height_pt
    if page_area <= 0:
        return VERDICT_NONE, None

    if not spans:
        if ink_fraction is not None and ink_fraction < INK_FLOOR:
            return VERDICT_NONE, None
        return VERDICT_SCANNED, None

    word_area = sum(span.box.width * span.box.height for span in spans)
    image_area = sum(_clip_area(box, width_pt, height_pt) for box in image_boxes)
    meaningful = len(spans) >= MIN_MEANINGFUL_WORDS or word_area / page_area >= MIN_WORD_AREA_RATIO
    if not meaningful and image_area / page_area >= 0.5:
        return VERDICT_SCANNED, None

    if image_area / page_area >= MIN_IMAGE_COVERAGE_RATIO:
        uncovered = _uncovered_regions(spans, image_boxes, width_pt, height_pt)
        if _clears_uncovered_floor(uncovered, page_area):
            return VERDICT_MIXED, uncovered
    return VERDICT_NATIVE, None


def _clears_uncovered_floor(regions: list[Box], page_area: float) -> bool:
    """The MIN_UNCOVERED_AREA_RATIO floor, on the regions' TOTAL area."""
    uncovered_area = sum(region.width * region.height for region in regions)
    return bool(regions) and uncovered_area / page_area >= MIN_UNCOVERED_AREA_RATIO


def _confirm_mixed(
    pdf_bytes: bytes, raw: _RawPage, regions: list[Box]
) -> tuple[str, list[Box] | None]:
    """Keep only the uncovered regions that look like text on a coarse render.

    Two readings, and a region survives on either. Per REGION: the chart-beside-a-
    scan case, where one image is print and the other is not. Per IMAGE, with the
    native words painted out: the form-behind-values case. A payroll-portal W-2 is
    one page image carrying every caption, under a text layer holding only the
    values; the values' edges slice that image into strips a few points tall, and
    no strip on its own has enough whole glyphs to read as print (measured on a
    Dayforce W-2: 173 strips, 153 dropped, every identifying caption among them).
    An image that is print as a whole is published as ONE region — its own box —
    so OCR reads whole caption lines instead of clipped slices.

    Returns (MIXED, text-like regions) when those still clear the floor, else
    (NATIVE, None). A page that cannot be rendered keeps every region — a region
    OCR did not need to read is cheap, a region it never saw is not."""
    canonical = _canonical_render(pdf_bytes, raw)
    if canonical is None:
        logger.info(
            "mixed page=%d regions=%d kept=%d reason=unrenderable",
            raw.page_index, len(regions), len(regions),
        )
        return VERDICT_MIXED, regions

    readings = [assess(_crop(canonical, region), TEXT_CHECK_DPI) for region in regions]
    print_images = _print_images(canonical, raw)
    kept = [
        region for region, reading in zip(regions, readings)
        if reading.text_like and not any(_inside(region, image) for image in print_images)
    ] + print_images
    logger.info(
        "mixed page=%d regions=%d kept=%d printImages=%d",
        raw.page_index, len(regions), len(kept), len(print_images),
    )
    for region, reading in zip(regions, readings):
        # Numbers only — a region's geometry and blob statistics, never its text.
        logger.debug(
            "mixed page=%d region=%.1f,%.1f,%.1fx%.1f textLike=%s ink=%.4f glyphs=%d"
            " share=%.3f agree=%.3f",
            raw.page_index, region.x, region.y, region.width, region.height,
            reading.text_like, reading.ink_fraction, reading.glyph_count,
            reading.glyph_ink_share, reading.height_agreement,
        )
    if _clears_uncovered_floor(kept, raw.width_pt * raw.height_pt):
        return VERDICT_MIXED, kept
    return VERDICT_NATIVE, None


#: Padding (pt) around a native word before it is painted out of the whole-image
#: reading — anti-aliased glyph edges past the word box must not count as print.
_WORD_MASK_PAD_PT = 1.0


def _print_images(canonical: np.ndarray, raw: _RawPage) -> list[Box]:
    """The embedded images that read as print once the native words are painted out,
    each clipped to the page. Without the mask a white background image under a
    native statement would read as print — the words ARE print."""
    scale = TEXT_CHECK_DPI / 72.0
    masked = canonical.copy()
    for span in raw.spans:
        box = Box(
            span.box.x - _WORD_MASK_PAD_PT, span.box.y - _WORD_MASK_PAD_PT,
            span.box.width + 2 * _WORD_MASK_PAD_PT, span.box.height + 2 * _WORD_MASK_PAD_PT,
        )
        y0, y1, x0, x1 = _pixel_bounds(masked, box, scale)
        masked[y0:y1, x0:x1] = 255

    images = []
    for image in raw.image_boxes:
        x0 = max(image.x, 0.0)
        y0 = max(image.y, 0.0)
        x1 = min(image.x + image.width, raw.width_pt)
        y1 = min(image.y + image.height, raw.height_pt)
        if x1 - x0 < _SLIVER_PT or y1 - y0 < _SLIVER_PT:
            continue
        clipped = Box(x0, y0, x1 - x0, y1 - y0)
        if assess(_crop(masked, clipped), TEXT_CHECK_DPI).text_like:
            images.append(clipped)
    return images


def _inside(region: Box, image: Box) -> bool:
    slack = 0.5
    return (
        region.x >= image.x - slack
        and region.y >= image.y - slack
        and region.x + region.width <= image.x + image.width + slack
        and region.y + region.height <= image.y + image.height + slack
    )


def _canonical_render(pdf_bytes: bytes, raw: _RawPage) -> np.ndarray | None:
    """The page as a canonical (rotation-0) grayscale raster at TEXT_CHECK_DPI, or
    None when it could not be rendered.

    pypdfium2 renders DISPLAY orientation (the page /Rotate applied); the regions
    are canonical (rotation-0), so the raster is turned back first — rot90 is
    counter-clockwise, which undoes a clockwise /Rotate (measured against the
    paystub_complete_rot* fixtures)."""
    try:
        document = pdfium.PdfDocument(pdf_bytes)
    except pdfium.PdfiumError:
        return None
    try:
        image = document[raw.page_index].render(scale=TEXT_CHECK_DPI / 72.0).to_pil()
    except Exception:
        return None
    finally:
        document.close()
    return np.rot90(np.asarray(image.convert("L")), k=raw.rotation // 90)


def _pixel_bounds(raster: np.ndarray, box: Box, scale: float) -> tuple[int, int, int, int]:
    height_px, width_px = raster.shape
    x0 = max(int(math.floor(box.x * scale)), 0)
    y0 = max(int(math.floor(box.y * scale)), 0)
    x1 = min(int(math.ceil((box.x + box.width) * scale)), width_px)
    y1 = min(int(math.ceil((box.y + box.height) * scale)), height_px)
    return y0, y1, x0, x1


def _crop(raster: np.ndarray, box: Box) -> np.ndarray:
    y0, y1, x0, x1 = _pixel_bounds(raster, box, TEXT_CHECK_DPI / 72.0)
    return raster[y0:y1, x0:x1]


def _clip_area(box: Box, width_pt: float, height_pt: float) -> float:
    w = max(0.0, min(box.x + box.width, width_pt) - max(box.x, 0.0))
    h = max(0.0, min(box.y + box.height, height_pt) - max(box.y, 0.0))
    return w * h


#: Uncovered slices narrower than this in either dimension are inter-word slivers,
#: not unread content — below any glyph size, safe to drop without under-covering
#: anything a reader could read.
_SLIVER_PT = 4.0


def _uncovered_regions(
    spans: list[Span], image_boxes: list[Box], width_pt: float, height_pt: float
) -> list[Box]:
    """Exact image-minus-words decomposition by y-bands — structurally OVER-covering.

    Phase 2 review finding: the previous union-bbox-remainder shape UNDER-covered —
    two words at an image's opposite edges made the union bbox span the whole image,
    the remainder collapsed to nothing, and the page's unread body silently skipped
    OCR as "NATIVE". This version slices the image at every word's top/bottom edge;
    within each horizontal band the uncovered x-intervals are the band minus the
    words overlapping it. Every point of the image not inside a word box lies in
    some emitted region (only sub-glyph slivers under {_SLIVER_PT}pt are dropped).
    Over-coverage costs an OCR pass; under-coverage is a silently unread document.
    """
    regions: list[Box] = []
    for image in image_boxes:
        ix0 = max(image.x, 0.0)
        iy0 = max(image.y, 0.0)
        ix1 = min(image.x + image.width, width_pt)
        iy1 = min(image.y + image.height, height_pt)
        if ix1 - ix0 <= 0 or iy1 - iy0 <= 0:
            continue

        words = [
            (
                max(span.box.x, ix0),
                max(span.box.y, iy0),
                min(span.box.x + span.box.width, ix1),
                min(span.box.y + span.box.height, iy1),
            )
            for span in spans
            if span.box.x < ix1
            and span.box.x + span.box.width > ix0
            and span.box.y < iy1
            and span.box.y + span.box.height > iy0
        ]
        if not words:
            regions.append(Box(ix0, iy0, ix1 - ix0, iy1 - iy0))
            continue

        cuts = sorted({iy0, iy1, *(w[1] for w in words), *(w[3] for w in words)})
        for band_top, band_bottom in zip(cuts, cuts[1:]):
            band_height = band_bottom - band_top
            if band_height < _SLIVER_PT:
                continue
            # x-intervals of words overlapping this band, merged.
            intervals = sorted(
                (w[0], w[2]) for w in words if w[1] < band_bottom and w[3] > band_top
            )
            cursor = ix0
            for start, end in intervals:
                if start - cursor >= _SLIVER_PT:
                    regions.append(Box(cursor, band_top, start - cursor, band_height))
                cursor = max(cursor, end)
            if ix1 - cursor >= _SLIVER_PT:
                regions.append(Box(cursor, band_top, ix1 - cursor, band_height))
    return regions


def _ink_fractions(pdf_bytes: bytes, indices: list[int]) -> dict[int, float]:
    """Fraction of dark pixels per page at a coarse DPI — the wire inkFraction and
    the SCANNED/NONE splitter for wordless pages; never a coordinate source.
    Rounded to 4 decimals: the Java side persists it as numeric(5,4) blank_score."""
    try:
        document = pdfium.PdfDocument(pdf_bytes)
    except pdfium.PdfiumError:
        # pdfplumber accepted the bytes; treat unrenderable as "has ink" (fail toward
        # OCR review, never toward silently blank).
        return {}
    try:
        fractions = {}
        for index in indices:
            # Per-page containment (Phase 3 review, confirmed): one corrupt image
            # XObject must cost ONE page's signal, not the whole file's — a blanket
            # except here nulled blank_score package-wide and flipped genuinely
            # blank pages to SCANNED because their ink was "unknown".
            try:
                image = document[index].render(scale=INK_CHECK_DPI / 72.0).to_pil().convert("L")
            except Exception:
                continue
            pixels = np.asarray(image)
            fractions[index] = round(float((pixels < INK_DARK_THRESHOLD).mean()), 4)
        return fractions
    finally:
        document.close()
