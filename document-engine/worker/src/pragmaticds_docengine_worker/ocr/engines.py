"""OCR engine adapters. Spans here are PIXEL-space in the frame of the input raster;
service.py owns the single conversion into canonical points via geometry.py.

RapidOCR (rapidocr-onnxruntime 1.4.4, PP-OCRv4 on ONNX Runtime) returns LINE-level
4-point quads. The contract's evidence unit is the word, so lines are split on
whitespace with box widths apportioned proportionally to character counts. That is an
approximation, and its error is larger than this file used to claim: measured against
truth on scanned_paystub_page0.png it reaches 4.5pt on 'ACME' and 4.1pt on 'Fixture',
because a character-count share under-serves wide capitals and over-serves narrow
letters. RapidOCR's own per-character boxes (return_word_box=True) were measured as a
replacement and are NOT better — it centres every character on its true CTC column
but gives them all one averaged width, which clips the outer glyphs: 3.4pt on 'ACME',
and worse than apportionment on 'LLC'. So apportionment stays, with the real envelope
recorded here and pinned by a test.

WHY THE RECOGNITION CROP IS PADDED (#83)
----------------------------------------
RapidOCR used to return a printed 'ACME WIDGETS LLC' as one token 'ACMEWIDGETSLLC',
and the space was not merely undetected — it was never emitted. The cause is not
tight typesetting and not capitals. ch_ppocr_rec resizes EVERY detection crop to 48
pixels of height before recognition, so what reaches the model is decided by the
crop's width:height ratio. A crop that hugs the glyph band with no leading is
stretched horizontally past the model's training distribution and it stops emitting
its space token. All-caps text has no ascenders or descenders for the detector's box
to include, so its crops are ~1.5x wider relative to their height than the same words
in mixed case — which is the whole of the "capitals" correlation. Measured:

    'ACME WIDGETS LLC'   tight crop w:h 13.84 -> 'ACMEWIDGETSLLC'   MERGED
    'Acme Widgets Llc'   tight crop w:h  9.22 -> 'Acme Widgets Llc' correct
    the SAME caps line forced to w:h 10.0     -> 'ACME WIDGETS LLC' correct
    the SAME mixed line forced to w:h 14.0    -> 'TotalNetPay'      MERGED

So the fix is to put the ratio back in range: pad each crop VERTICALLY with its own
background before recognition, and let the recogniser emit the space itself. The
boundary is then the model's own evidence. The pad colour is the crop's per-channel
MEDIAN rather than white — a reversed banner (white caps on a black bar) is a real
paystub header, and on a synthetic one white padding recovered 1 of 3 boundaries
where the median pad recovered all 3.

THE PAD BUYS VERTICAL MARGIN BY SPENDING HORIZONTAL RESOLUTION
--------------------------------------------------------------
And that is why the pad ALONE is not the fix. The resize is aspect-PRESERVING to 48px
of height, so a crop reaches the model at width 48*(w/h); padding multiplies h by
(1+2f) and therefore divides that width by (1+2f). PP-OCRv4's recogniser emits one CTC
column per 8px of that width, so the columns available to each character fall in the
same proportion — and CTC cannot emit two identical characters in a row without a
blank column between them. Squeeze the budget far enough and a repeated digit has
nowhere to put its separator. Measured at the recogniser, one crop at a time, 200 DPI:

    printed '2000000'        f=0.00  21 cols  3.00/char  -> '2000000'
                             f=0.16  15 cols  2.14/char  -> '2000000'
                             f=0.40  11 cols  1.57/char  -> '20000'    ONE 0 LOST
                             f=0.60   9 cols  1.29/char  -> '200000'   ONE 0 LOST
    printed '5500000000004'  f=0.00  40 cols  3.08/char  -> '5500000000004'
                             f=0.40  22 cols  1.69/char  -> '550000000040'
                             f=0.60  18 cols  1.38/char  -> '500000'

A CONFIDENT wrong number, not a low-confidence one: the 0.40 '20000' came back at
0.98. And the damage is not confined to a badly chosen fraction. Every fraction that
recovers spaces also changes characters somewhere:

    47 pages (21 real corpus pages with answer keys + 26 fixture pages), pad 0.16,
    whitespace-stripped comparison against the unpadded engine
      corpus: 21 of 21 pages changed characters, -1216 / +1151 chars
      answer-key values: 0 pages improved, 5 pages regressed, 8 values LOST
      first digit loss on the probe sheet moves with page composition: 0.23 on a
      26-line sheet, 0.17 on an 18-line one, because RapidOCR batches crops by
      aspect ratio and the batch decides the right-hand padding

So there is no safe fraction to find. A tuned constant would have been a promise that
the next real document is allowed to break.

THE FIX IS THE GATE, NOT THE FRACTION
-------------------------------------
Recognise every crop TWICE — raw and padded — and serve the padded reading only when
`is_whitespace_refinement` says it is the same characters, keeps every boundary the
raw reading found, and puts no new boundary inside a number. Otherwise serve the raw
reading. The character stream is then EXACTLY the pre-#83 engine's, by construction,
and the pad can only add boundaries the recogniser actually saw.

That is what makes the fraction ordinary. Below 0.10 it recovers nothing; far above
it, more crops fail the gate and fall back. It cannot produce a wrong value at any
setting, so it is chosen for yield alone. Measured WITH the gate, per fraction —
SPACES: the caps header on scanned_paystub_page0.png arriving as ACME/WIDGETS/LLC;
DIGITS: 54 repeated-digit, mixed-run and account-number probe lines drawn at 9/11/14pt
with truth by construction, scored on EXACT equality; PAYSTUB: the seeded PAYSTUB
pack's score on a real scanned paystub, page 0 at 200 DPI through run_ocr_ladder:

     pad    spaces   digits   PAYSTUB   spans     ungated digits (what shipped first)
    0.00      no      54/54     0.50      202     54/54
    0.10     YES      54/54     0.50      240     54/54
    0.15     YES      54/54     0.80      259     54/54
    0.16     YES      54/54     0.80      269     54/54   <- SHIPPED
    0.20     YES      54/54     0.60      280     52/54
    0.25     YES      54/54     0.80      298     53/54   <- the first shipped value
    0.30     YES      54/54     0.80      299     53/54
    0.40     YES      54/54     0.80      301     40/54
    0.60     YES      54/54     0.80      265     27/54

The right-hand column is the same probe without the gate: that is the defect this
change closes, and the gate flattens it to 54/54 everywhere. 0.16 is the smallest
fraction that reaches the full PAYSTUB 0.80 and holds it; above it the extra spans are
word splits inside single words, which the gate cannot vouch for because they change
no characters. Small is the conservative direction here.

A RATIO-TARGETED PAD WAS TRIED AND IS WORSE. Padding only the over-wide crops, and
only down to a target w:h, is the obvious shape given the diagnosis; it keeps digits
exact but never lifts PAYSTUB above 0.50 at any target from 8 to 12, because the crops
it pads hardest are the long labelled lines and it destroys them
('Employer Match 1111 and 100000.00' -> 'EmployeMah1and 0'). A flat fraction wins.

    cost: the second recognition pass takes one page of the real paystub through
      run_ocr_ladder from 4.1s to 5.8s. Detection and the model load are unchanged.
    geometry: the detection quads stay BIT-IDENTICAL to the unpadded run (187/187 on
      the real fixture, order and value), because the pad sits BELOW the detector.

WHAT THIS DOES NOT DO — the documented limit
--------------------------------------------
It does not split a token that came back merged. That is not a tuning gap, it is a
measurement: at the merged scale the per-character CTC column deltas for
'ACMEWIDGETSLLC' are [6,7,6,9,5,4,6,6,5,6,7,5,6]. The two real inter-word gaps are 9
and 7 — but the intra-word C->M gap is also 7, because M is a wide glyph. The
distributions OVERLAP, so any threshold that catches the S->L space also fires inside
'ACME' and fabricates 'AC ME'. RapidOCR's own word grouping (col_width > 4) shatters
that line into 13 pieces. A merged token therefore stays merged: a missing boundary
is a missing value, an invented one is a wrong value, and a wrong value is worse.

The other direction is a DIFFERENT pipeline: SpanJoin removes fabricated spaces from
native-PDF spans. Nothing here is shared with it and nothing here reaches it.

Tesseract (image_to_data) is already word-level: rows with conf >= 0 and non-blank
text, confidence rescaled from 0-100 to 0..1.
"""

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

import numpy as np
import pytesseract
from PIL import Image

from pragmaticds_docengine_worker.geometry import Box

#: Fraction of a detection crop's height added as background above AND below it before
#: recognition. Safe to tune ONLY because `is_whitespace_refinement` gates what the
#: padded reading is allowed to change; without that gate this constant decided
#: whether a printed 2000000 was served as 200000 (module docstring).
#:
#: Below the floor the recogniser emits no extra spaces at all and the second pass is
#: pure cost. The value is the smallest fraction that reaches the measured PAYSTUB
#: 0.80 on the real scanned paystub, chosen small on purpose: a larger pad buys more
#: word splits than the gate can vouch for.
RECOGNITION_CROP_PAD_MIN = 0.10  # below this the pad recovers nothing
RECOGNITION_CROP_PAD_FRACTION = 0.16


@dataclass(frozen=True)
class OcrSpan:
    """One recognised word in the input raster's pixel frame."""

    text: str
    box_px: Box
    confidence: float


@runtime_checkable
class OcrEngine(Protocol):
    """The seam every OCR backend sits behind (PARSER_EVALUATION.md §4, §11)."""

    name: str

    def recognize(self, image: Image.Image, regions: list[Box]) -> list[OcrSpan]:
        """OCR `image`, restricted to `regions` (pixel boxes; empty = whole image)."""
        ...


def _crop_regions(image: Image.Image, regions: list[Box]) -> list[tuple[Image.Image, float, float]]:
    """(crop, x_offset, y_offset) per region; the whole page when regions is empty."""
    if not regions:
        return [(image, 0.0, 0.0)]
    crops = []
    for region in regions:
        left = max(int(region.x), 0)
        top = max(int(region.y), 0)
        right = min(int(region.x + region.width), image.width)
        bottom = min(int(region.y + region.height), image.height)
        if right <= left or bottom <= top:
            continue
        crops.append((image.crop((left, top, right, bottom)), float(left), float(top)))
    return crops


def pad_crop_for_recognition(crop: np.ndarray, fraction: float) -> np.ndarray:
    """Add `fraction` x height of the crop's OWN background above and below it.

    The pad colour is the crop's per-channel median. A text crop is mostly
    background whichever way round it is printed, so this follows the page into a
    reversed banner instead of assuming paper white (module docstring, #83).

    Returns the input untouched when there is nothing to add, so a zero fraction is
    exactly the pre-fix behaviour — which is what the geometry-neutrality test
    compares against.
    """
    height = crop.shape[0]
    pad = int(round(height * fraction))
    if pad <= 0:
        return crop
    background = np.median(crop.reshape(-1, crop.shape[2]), axis=0).astype(crop.dtype)
    padded = np.empty((height + 2 * pad, crop.shape[1], crop.shape[2]), dtype=crop.dtype)
    padded[:] = background
    padded[pad : pad + height] = crop
    return padded


def same_characters(left: str, right: str) -> bool:
    """True when two readings differ only in where the whitespace falls."""
    return "".join(left.split()) == "".join(right.split())


def _boundary_offsets(text: str) -> set[int]:
    """Offsets, into the whitespace-STRIPPED text, where a word boundary falls."""
    offsets, cursor = set(), 0
    for token in text.split():
        cursor += len(token)
        offsets.add(cursor)
    offsets.discard(cursor)  # the end of the string is not an interior boundary
    return offsets


def _splits_a_number(stripped: str, offset: int) -> bool:
    """Would a boundary at `offset` cut a printed number in half?

    '1,100,220.33' arriving as '1, 100,220.33' passes every whitespace test — same
    characters, one more boundary — and is still a wrong value, because every rung
    downstream reads a money field from ONE span and would take the '1'. Digit-digit
    is not enough to catch it: the cut lands after the comma.
    """
    before, after = stripped[offset - 1], stripped[offset]
    if before.isdigit() and (after.isdigit() or after in ".,"):
        return True
    return before in ".," and after.isdigit()


def is_whitespace_refinement(unpadded: str, padded: str) -> bool:
    """May `padded` be served in place of `unpadded`?

    Only when all three hold, and each one is a defect this repo has already paid for:

      1. the same characters, so the pad cannot rewrite '2000000' as '200000';
      2. every boundary the unpadded reading found survives, so the pad REFINES the
         segmentation and never re-cuts it somewhere else;
      3. no new boundary falls inside a number, so an amount cannot be split in two.

    Anything else and the unpadded reading is served unchanged. That is a missing
    boundary, which is a missing value; the alternative is a wrong one.
    """
    if not same_characters(unpadded, padded):
        return False
    before, after = _boundary_offsets(unpadded), _boundary_offsets(padded)
    if not before <= after:
        return False
    stripped = "".join(unpadded.split())
    return not any(_splits_a_number(stripped, offset) for offset in after - before)


class _PaddedRecognizer:
    """RapidOCR's TextRecognizer run TWICE per crop — raw and padded — keeping the
    padded reading ONLY when it spells the same characters.

    This is the whole safety argument, so it is worth being exact about. The pad buys
    the recogniser's space token by spending horizontal resolution (module docstring),
    and past some crop-dependent point that spend also costs CHARACTERS. There is no
    fraction at which that stops being true — measured on 21 real corpus pages, the
    best-scoring fraction still changed characters on all 21 and lost 8 ground-truth
    values. So the fraction is not the safeguard. THIS IS:

        the padded reading is served only when it is a WHITESPACE REFINEMENT of the
        unpadded one, and otherwise the unpadded reading is served unchanged.

    which makes the guarantee structural rather than tuned. The character stream this
    class emits is EXACTLY the character stream of the un-padded engine; only the
    whitespace inside it can differ. A pad that damages a crop cannot reach the
    output — it can only fail to help, and the cost of failing is one wasted crop.

    That also relaxes the constant: outside its useful range the pad degrades into
    the pre-#83 engine instead of into wrong numbers.

    The seam is chosen, not convenient. It sits BELOW the detector and BELOW the
    180-degree orientation classifier, both of which still see the original crops —
    so detection quads and orientation decisions are bit-identical and only the
    recognised TEXT can change. Padding earlier (at get_crop_img_list) would feed the
    classifier a picture it was not calibrated on for no gain.

    `gate` false skips the comparison and serves the padded reading raw. It exists so
    a test can show the SAME fraction wrecking the digit probe with the gate off and
    leaving it untouched with the gate on — a claim that cannot be made from the
    shipped path alone, and the reason to believe the gate rather than the constant.
    """

    def __init__(self, inner, fraction: float, gate: bool = True):
        self._inner = inner
        self._fraction = fraction
        self._gate = gate

    def __call__(self, img_list, return_word_box: bool = False):
        if isinstance(img_list, np.ndarray):  # use_det=False hands over a bare array
            img_list = [img_list]
        padded_crops = [pad_crop_for_recognition(crop, self._fraction) for crop in img_list]
        if not self._gate:
            return self._inner(padded_crops, return_word_box)
        raw, raw_elapse = self._inner(img_list, return_word_box)
        padded, padded_elapse = self._inner(padded_crops, return_word_box)
        merged = []
        for raw_entry, padded_entry in zip(raw, padded):
            if is_whitespace_refinement(raw_entry[0], padded_entry[0]):
                # Text and confidence come from the reading being served; anything
                # POSITIONAL stays with the raw crop it was actually measured on.
                merged.append((padded_entry[0], padded_entry[1], *raw_entry[2:]))
            else:
                merged.append(raw_entry)
        return merged, raw_elapse + padded_elapse


class RapidOcrEngine:
    """Primary engine: PP-OCR (v4 model set) on ONNX Runtime. Lazy — the model load
    is the expensive part, so nothing happens until the first recognize().

    `crop_pad_fraction` is the #83 fix (module docstring). Zero disables it, which
    exists so a test can measure the padded engine against the unpadded one.
    `gate_refinements` false is the OTHER control: the pad without its safety
    argument, which is what the reported digit loss actually was. Never ship it.
    """

    name = "RAPIDOCR"

    def __init__(
        self,
        crop_pad_fraction: float = RECOGNITION_CROP_PAD_FRACTION,
        gate_refinements: bool = True,
    ):
        self._ocr = None
        self._crop_pad_fraction = crop_pad_fraction
        self._gate_refinements = gate_refinements

    def _load(self):
        if self._ocr is None:
            from rapidocr_onnxruntime import RapidOCR

            ocr = RapidOCR()
            if self._crop_pad_fraction > 0:
                if not hasattr(ocr, "text_rec"):
                    raise RuntimeError(
                        "rapidocr-onnxruntime no longer exposes .text_rec, so the #83 "
                        "crop padding cannot be installed. requirements.txt pins 1.4.4; "
                        "re-run the padding sweep in the OCR module docstring against "
                        "the new version before relaxing this."
                    )
                ocr.text_rec = _PaddedRecognizer(
                    ocr.text_rec, self._crop_pad_fraction, gate=self._gate_refinements
                )
            self._ocr = ocr
        return self._ocr

    def recognize(self, image: Image.Image, regions: list[Box]) -> list[OcrSpan]:
        ocr = self._load()
        spans: list[OcrSpan] = []
        for crop, dx, dy in _crop_regions(image.convert("RGB"), regions):
            result, _elapse = ocr(np.asarray(crop))
            for quad, text, confidence in result or []:
                xs = [point[0] for point in quad]
                ys = [point[1] for point in quad]
                line_box = Box(min(xs) + dx, min(ys) + dy, max(xs) - min(xs), max(ys) - min(ys))
                spans.extend(_split_line(text, line_box, float(confidence)))
        return spans


def _split_line(text: str, line_box: Box, confidence: float) -> list[OcrSpan]:
    """Split a recognised line into word spans, apportioning width by char count."""
    stripped = text.strip()
    if not stripped:
        return []
    tokens = stripped.split()
    if len(tokens) <= 1:
        return [OcrSpan(stripped, line_box, confidence)]
    per_char = line_box.width / len(stripped)
    spans = []
    cursor = 0  # character offset into the stripped line, spaces included
    for token in tokens:
        start = stripped.index(token, cursor)
        spans.append(
            OcrSpan(
                token,
                Box(line_box.x + start * per_char, line_box.y, len(token) * per_char,
                    line_box.height),
                confidence,
            )
        )
        cursor = start + len(token)
    return spans


class TesseractOcrEngine:
    """Fallback engine: Tesseract 5 via image_to_data — a classical pipeline that
    fails differently from a neural one, which is the point of having it."""

    name = "TESSERACT"

    def recognize(self, image: Image.Image, regions: list[Box]) -> list[OcrSpan]:
        spans: list[OcrSpan] = []
        for crop, dx, dy in _crop_regions(image.convert("RGB"), regions):
            data = pytesseract.image_to_data(crop, output_type=pytesseract.Output.DICT)
            for text, left, top, width, height, conf in zip(
                data["text"], data["left"], data["top"], data["width"], data["height"],
                data["conf"],
            ):
                if float(conf) < 0 or not text.strip():
                    continue  # structural rows (page/block/line) carry conf -1
                spans.append(
                    OcrSpan(
                        text.strip(),
                        Box(float(left) + dx, float(top) + dy, float(width), float(height)),
                        float(conf) / 100.0,
                    )
                )
        return spans


_shared_rapidocr: RapidOcrEngine | None = None
_shared_tesseract: TesseractOcrEngine | None = None


def shared_rapidocr_engine() -> RapidOcrEngine:
    global _shared_rapidocr
    if _shared_rapidocr is None:
        _shared_rapidocr = RapidOcrEngine()
    return _shared_rapidocr


def shared_tesseract_engine() -> TesseractOcrEngine:
    global _shared_tesseract
    if _shared_tesseract is None:
        _shared_tesseract = TesseractOcrEngine()
    return _shared_tesseract
