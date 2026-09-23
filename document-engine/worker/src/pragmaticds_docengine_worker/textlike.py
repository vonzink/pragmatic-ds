"""Does an uncovered image region carry TEXT, or is it a graphic?

The MIXED verdict (text.py) sends every embedded-image area not under a native word
to OCR. That is right for a pasted scan and wrong for a chart, a banner, a logo, or
a background image: OCR of a graphic costs two engine passes plus OSD per page and
yields nothing. This module is the discriminator, run on a coarse render of the
region BEFORE the verdict is published — a region that fails it never reaches OCR.

The test is structural, not statistical: text is many small ink blobs of similar
height (glyphs), and glyphs own most of the ink. A graphic is a few large blobs (a
fill, a photo, a line) with, at most, a handful of small ones (tick labels, a
wordmark). Concretely, on an Otsu ink mask of the region:

    glyph blob     height within [MIN_GLYPH_PT, MAX_GLYPH_PT] scaled to the DPI,
                   not a ruling (width <= MAX_GLYPH_ASPECT x height) and not a
                   vertical line (height <= MAX_GLYPH_ASPECT_TALL x width)
    text-like      at least MIN_GLYPHS glyph blobs
                   AND glyphs own at least MIN_GLYPH_INK_SHARE of the ink
                   AND at least MIN_HEIGHT_AGREEMENT of them sit within
                       [0.5, 2.0] x the median glyph height (one print run, not
                       scatter)

Two ink masks are tried and the region is text-like if EITHER says so. Global
Otsu is right for print and scans. A photographed page (a phone shot of a paystub
pasted into a letter) defeats it: under uneven light Otsu calls half the paper ink
and the glyph share collapses to ~0.01 (measured), which would silently skip a
readable document. A local (adaptive-mean) mask follows the lighting and recovers
the glyphs; on a flat graphic it finds only edges, which never look like a print
run. Every threshold errs toward "text": the cost of a wrong "text" is one wasted
OCR call; the cost of a wrong "graphic" is a silently unread document. A dense
chart with many tick labels or a long wordmark can pass — accepted, one OCR call.
"""

from dataclasses import dataclass

import cv2
import numpy as np

#: Coarse render DPI for the check. Glyphs at 6pt are ~8px tall here — enough to
#: count blobs, far too little to read them, which is the point: this is a
#: classifier, not a recogniser.
TEXT_CHECK_DPI = 100

#: Glyph height band in POINTS: below 3pt is noise/speckle, above 44pt is a display
#: headline or a graphic element. Scaled to the check DPI at evaluation time.
MIN_GLYPH_PT = 3.0
MAX_GLYPH_PT = 44.0
#: A blob wider than this many times its height is a ruling, not a glyph.
MAX_GLYPH_ASPECT = 4.0
#: A blob taller than this many times its width is a vertical line ("l" and "|"
#: at body sizes sit around 5-6).
MAX_GLYPH_ASPECT_TALL = 10.0
#: Blobs that fill nearly their whole bounding box are solid marks (an icon, a
#: checkbox fill, a bullet square), never letterforms.
MAX_GLYPH_FILL = 0.92
#: Fewer glyph blobs than this is a wordmark or a label, not a document.
MIN_GLYPHS = 12
#: Glyph pixels / all ink pixels. Text regions measure 0.6-1.0; a chart with tick
#: labels 0.01-0.05; a shadowed photo of a noisy scan measured 0.20.
MIN_GLYPH_INK_SHARE = 0.15
#: Fraction of glyph blobs within [0.5, 2.0] x the median glyph height.
MIN_HEIGHT_AGREEMENT = 0.5
#: Below this ink fraction the region is blank paper (or a white background image).
MIN_INK_FRACTION = 0.002
#: Adaptive-mask neighbourhood in POINTS (~a line of body text): the local mean
#: over this window is the "paper" a pixel is compared against.
ADAPTIVE_BLOCK_PT = 18.0
#: How much darker than its local paper a pixel must be to count as ink (0-255).
ADAPTIVE_OFFSET = 12


@dataclass(frozen=True)
class TextLikeness:
    """The decision and the numbers behind it — logged, never text."""

    text_like: bool
    ink_fraction: float
    glyph_count: int
    glyph_ink_share: float
    height_agreement: float


def assess(gray: np.ndarray, dpi: int = TEXT_CHECK_DPI) -> TextLikeness:
    """Classify one grayscale region raster (uint8, ink dark) rendered at `dpi`.

    Returns the Otsu-mask reading when it is text-like, else the adaptive-mask
    reading (so the numbers logged are the ones the decision rests on)."""
    if gray.ndim != 2 or gray.shape[0] < 2 or gray.shape[1] < 2:
        return TextLikeness(False, 0.0, 0, 0.0, 0.0)

    # Callers hand over crops of a rot90'd page — strided views OpenCV must not see.
    gray = np.ascontiguousarray(gray, dtype=np.uint8)
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    reading = _assess_mask(otsu, dpi)
    if reading.text_like:
        return reading

    block = int(round(ADAPTIVE_BLOCK_PT * dpi / 72.0)) | 1  # adaptiveThreshold wants odd
    adaptive = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, max(block, 3),
        ADAPTIVE_OFFSET,
    )
    return _assess_mask(adaptive, dpi)


def _assess_mask(binary: np.ndarray, dpi: int) -> TextLikeness:
    ink_total = int(np.count_nonzero(binary))
    ink_fraction = ink_total / binary.size
    if ink_fraction < MIN_INK_FRACTION:
        return TextLikeness(False, round(ink_fraction, 4), 0, 0.0, 0.0)

    count, _, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    if count <= 1:  # row 0 is the background
        return TextLikeness(False, round(ink_fraction, 4), 0, 0.0, 0.0)
    stats = stats[1:]

    scale = dpi / 72.0
    widths = stats[:, cv2.CC_STAT_WIDTH].astype(np.float64)
    heights = stats[:, cv2.CC_STAT_HEIGHT].astype(np.float64)
    areas = stats[:, cv2.CC_STAT_AREA].astype(np.float64)
    fill = areas / np.maximum(widths * heights, 1.0)
    glyph = (
        (heights >= MIN_GLYPH_PT * scale)
        & (heights <= MAX_GLYPH_PT * scale)
        & (widths <= MAX_GLYPH_ASPECT * heights)
        & (heights <= MAX_GLYPH_ASPECT_TALL * widths)
        & (fill <= MAX_GLYPH_FILL)
    )
    glyph_count = int(glyph.sum())
    glyph_ink_share = float(areas[glyph].sum() / ink_total)

    if glyph_count:
        median = float(np.median(heights[glyph]))
        agreeing = (heights[glyph] >= 0.5 * median) & (heights[glyph] <= 2.0 * median)
        height_agreement = float(agreeing.mean())
    else:
        height_agreement = 0.0

    text_like = (
        glyph_count >= MIN_GLYPHS
        and glyph_ink_share >= MIN_GLYPH_INK_SHARE
        and height_agreement >= MIN_HEIGHT_AGREEMENT
    )
    return TextLikeness(
        text_like,
        round(ink_fraction, 4),
        glyph_count,
        round(glyph_ink_share, 4),
        round(height_agreement, 4),
    )
