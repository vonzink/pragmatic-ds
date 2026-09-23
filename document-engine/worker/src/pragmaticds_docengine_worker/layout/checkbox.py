"""Checkbox detection — the first pixel-consuming layout detector (Spec 3).

Contour-based: Otsu ink (REUSED from ocr/gates.ink_mask — the LICENSING.md
5.1 election's single binarisation entry point), near-square contours in the
8-24 pt band, checked state from the ink fill of the inner 60% of the box.
Candidates below CONFIDENCE_FLOOR are OMITTED, never emitted at reduced
confidence (design D6: refuse to guess — a wrong checked-state at full
confidence is the checkbox equivalent of the unanchored-regex trap).

Boxes are measured on the display-frame raster (the page rendered AT its
/Rotate) and leave this module CANONICAL: px_box_to_pt at the raster's DPI,
then unrotate_box when /Rotate is non-zero (geometry.py — the single
conversion point; its tests own the math).
"""

import cv2
import numpy as np
from PIL import Image

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.geometry import Box, px_box_to_pt, unrotate_box
from pragmaticds_docengine_worker.layout.model import Element
from pragmaticds_docengine_worker.ocr.gates import ink_mask

CHECKBOX_DETECTOR = "checkbox-cv"

#: Candidates below this confidence are dropped entirely (D6). Test-pinned;
#: the signature detector shares the value by contract, not by import.
CONFIDENCE_FLOOR = 0.5

#: Printed-form checkbox size band, canonical points, BOTH sides.
MIN_SIDE_PT = 8.0
MAX_SIDE_PT = 24.0

#: Near-square: width/height inside [0.75, 1.33].
ASPECT_MIN = 0.75
ASPECT_MAX = 1.33

#: Checked = ink fill of the centred INNER_FRACTION crop >= CHECKED_FILL_MIN.
#: The inner crop keeps the box's own border stroke out of the measurement.
CHECKED_FILL_MIN = 0.15
INNER_FRACTION = 0.6

#: A checkbox is a DRAWN square: every side of the bounding rect must carry
#: ink along at least this fraction of its length. Without this gate, bold
#: glyphs in the size band (the 14 pt "M"/"W" of the fixtures' ACME WIDGETS
#: header) clear aspect + rectangularity x contrast and become phantom
#: checked boxes.
BORDER_COVERAGE_MIN = 0.7

#: ...and it is a QUADRILATERAL. Border coverage alone cannot reject a bold
#: "D" — the same ACME WIDGETS header renders one at 8.6 x 10.1 pt that
#: genuinely frames all four sides of its bounding rect, clears aspect (0.86)
#: and rectangularity x contrast (0.84), and whose bowl fills 21% of the inner
#: crop: a phantom CHECKED box on three fixtures. At the standard
#: 2%-of-perimeter epsilon a drawn square approximates to exactly four
#: vertices and a bowl to six. Pinned by its own unit test.
POLYGON_EPSILON = 0.02
POLYGON_VERTICES = 4


def detect_checkboxes(
    image: Image.Image, dpi: int, rotation: int, page_w_pt: float, page_h_pt: float
) -> list[Element]:
    """CHECKBOX elements for one page raster, boxes canonical rotation-0 pt.

    `image` is the page rendered AT its /Rotate (`rotation`); `page_w_pt`/
    `page_h_pt` are the ROTATION-0 page dims the boxes must land in. Elements
    come back sorted top-to-bottom, left-to-right in canonical space.
    """
    ink = ink_mask(image)
    gray = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    contours, _ = cv2.findContours(
        ink.astype(np.uint8) * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE
    )
    scale = dpi / 72.0
    min_side_px = MIN_SIDE_PT * scale
    max_side_px = MAX_SIDE_PT * scale

    candidates = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        if not (min_side_px <= w <= max_side_px and min_side_px <= h <= max_side_px):
            continue
        if not (ASPECT_MIN <= w / h <= ASPECT_MAX):
            continue
        if not _is_quadrilateral(contour):
            continue
        if not _border_covered(ink, x, y, w, h):
            continue
        rectangularity = cv2.contourArea(contour) / float(w * h)
        confidence = round(min(1.0, rectangularity * _contrast(gray, ink, x, y, w, h)), 4)
        if confidence < CONFIDENCE_FLOOR:
            continue
        fill_ratio = _inner_fill(ink, x, y, w, h)
        candidates.append((x, y, w, h, confidence, fill_ratio))

    elements = []
    for x, y, w, h, confidence, fill_ratio in _suppress_nested(candidates):
        box = px_box_to_pt(Box(x, y, w, h), dpi)
        if rotation:
            box = unrotate_box(box, rotation, page_w_pt, page_h_pt)
        elements.append(
            Element(
                element_type="CHECKBOX",
                box=box,
                spans=(),
                confidence=confidence,
                attributes={
                    "checked": fill_ratio >= CHECKED_FILL_MIN,
                    "fillRatio": round(fill_ratio, 4),
                },
                detector=CHECKBOX_DETECTOR,
                detector_version=__version__,
            )
        )
    elements.sort(key=lambda element: (element.box.y, element.box.x))
    return elements


def _is_quadrilateral(contour: np.ndarray) -> bool:
    """The contour approximates to a four-vertex polygon — the classic
    square-detection test. Curved glyph bowls need more vertices however
    square their bounding rect looks."""
    epsilon = POLYGON_EPSILON * cv2.arcLength(contour, True)
    return len(cv2.approxPolyDP(contour, epsilon, True)) == POLYGON_VERTICES


def _border_covered(ink: np.ndarray, x: int, y: int, w: int, h: int) -> bool:
    """Ink frames all four sides of the bounding rect: within a thin margin
    band along each side, the fraction of covered columns (top/bottom) or
    rows (left/right) must clear BORDER_COVERAGE_MIN."""
    margin = max(2, int(round(0.15 * min(w, h))))
    top = ink[y:y + margin, x:x + w]
    bottom = ink[y + h - margin:y + h, x:x + w]
    left = ink[y:y + h, x:x + margin]
    right = ink[y:y + h, x + w - margin:x + w]
    return (
        top.any(axis=0).mean() >= BORDER_COVERAGE_MIN
        and bottom.any(axis=0).mean() >= BORDER_COVERAGE_MIN
        and left.any(axis=1).mean() >= BORDER_COVERAGE_MIN
        and right.any(axis=1).mean() >= BORDER_COVERAGE_MIN
    )


def _inner_fill(ink: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """Ink fraction of the centred INNER_FRACTION crop — the checked signal."""
    dx = int(round(w * (1.0 - INNER_FRACTION) / 2.0))
    dy = int(round(h * (1.0 - INNER_FRACTION) / 2.0))
    inner = ink[y + dy:y + h - dy, x + dx:x + w - dx]
    return float(inner.mean()) if inner.size else 0.0


def _contrast(gray: np.ndarray, ink: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """Otsu-class separation inside the bounding rect, 0..1. A washed-out
    scan separates weakly and the candidate falls below the floor."""
    region_gray = gray[y:y + h, x:x + w].astype(np.float64)
    region_ink = ink[y:y + h, x:x + w]
    if not region_ink.any() or region_ink.all():
        return 0.0
    return float(region_gray[~region_ink].mean() - region_gray[region_ink].mean()) / 255.0


def _suppress_nested(candidates: list[tuple]) -> list[tuple]:
    """A hollow square ring yields TWO contours (outer boundary + the hole's
    boundary), both near-square. Keep the outer: drop any candidate whose
    rect sits inside an already-kept larger one."""
    kept: list[tuple] = []
    for candidate in sorted(candidates, key=lambda c: c[2] * c[3], reverse=True):
        x, y, w, h = candidate[:4]
        if any(
            kx <= x and ky <= y and x + w <= kx + kw and y + h <= ky + kh
            for kx, ky, kw, kh, *_ in kept
        ):
            continue
        kept.append(candidate)
    return kept
