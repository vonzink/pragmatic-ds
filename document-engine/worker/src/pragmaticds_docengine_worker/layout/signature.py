"""Signature detection — presence of handwritten ink, never identity (Spec 3).

Connected-component based: Otsu ink (REUSED from ocr/gates.ink_mask — the
LICENSING.md 5.1 election's single binarisation entry point), components
that are wide (>= 54 pt), stroke-shaped (aspect >= 2.0, height <= 72 pt —
handwriting-sized, so a drawn table grid can never qualify), lie mostly
OUTSIDE the caller's text-span boxes (>= 60% of the component's ink —
machine text is claimed by spans; handwriting is not), and turn continuously
(stroke-direction variance above threshold — a drawn rule or an empty
signature line points one way and never qualifies). Candidates below
CONFIDENCE_FLOOR are OMITTED, never emitted at reduced confidence (design
D6: refuse to guess — a phantom SIGNED at full confidence is
underwriting-critical misinformation).

Frames: the raster is DISPLAY space (the page rendered AT its /Rotate); span
boxes arrive CANONICAL (rotation-0 pt) and are mapped INTO the display frame
via rotate_box + a dpi/72 scale, while detected boxes ride px_box_to_pt +
unrotate_box back OUT (geometry.py — the single conversion point in both
directions; its hand-computed tests own the math).

Every GATE measures the display frame, deliberately. "Wide, flat handwriting"
is a claim about ink as READ, and a /Rotate 90 page renders upright — so a
signature is horizontal in the raster and rotating a page can never change
whether its ink is a signature. In canonical rotation-0 space that same
signature is tall and narrow, as are the page's own text spans; gating there
would reject every signature on every rotated scan.
"""

import math

import cv2
import numpy as np
from PIL import Image

from pragmaticds_docengine_worker import __version__
from pragmaticds_docengine_worker.geometry import Box, px_box_to_pt, rotate_box, unrotate_box
from pragmaticds_docengine_worker.layout.model import Element
from pragmaticds_docengine_worker.ocr.gates import ink_mask

SIGNATURE_DETECTOR = "signature-cv"

#: Candidates below this confidence are dropped entirely (D6). Test-pinned;
#: the checkbox detector shares the value by contract, not by import.
CONFIDENCE_FLOOR = 0.5

#: A signature is a WIDE stroke: minimum component width, canonical points.
MIN_WIDTH_PT = 54.0

#: ... and a FLAT one: width/height at least this.
ASPECT_MIN = 2.0

#: Handwriting height band ceiling. Without it, the one 8-connected component
#: a drawn table grid binarizes into (ruled_table.pdf: ~494x110 pt, aspect
#: ~4.5, ink outside every span, mixed-direction strokes above the variance
#: gate) becomes a phantom signature.
MAX_HEIGHT_PT = 72.0

#: At least this fraction of the component's ink must lie OUTSIDE every
#: text-span box — printed words are claimed by spans, handwriting is not.
OUTSIDE_SPAN_FRACTION_MIN = 0.6

#: Doubled-angle circular variance gate: gradients point ACROSS a stroke, so
#: a rule/underline concentrates at one doubled angle (variance ~0) while
#: handwriting turns continuously (variance high).
STROKE_VARIANCE_MIN = 0.25
#: Variance at (and above) which the direction term of the confidence
#: saturates at 1.0.
STROKE_VARIANCE_FULL = 0.5


def detect_signatures(
    image: Image.Image,
    dpi: int,
    rotation: int,
    page_w_pt: float,
    page_h_pt: float,
    span_boxes: list[Box],
) -> list[Element]:
    """SIGNATURE elements for one page raster, boxes canonical rotation-0 pt.

    `image` is the page rendered AT its /Rotate (`rotation`); `page_w_pt`/
    `page_h_pt` are the ROTATION-0 page dims; `span_boxes` are the page's
    text-span boxes in CANONICAL rotation-0 pt. Elements come back sorted
    top-to-bottom, left-to-right in canonical space.
    """
    ink = ink_mask(image)
    gray = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    span_cover = _span_cover(ink.shape, span_boxes, dpi, rotation, page_w_pt, page_h_pt)
    gx = cv2.Sobel(gray, cv2.CV_64F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_64F, 0, 1, ksize=3)

    scale = dpi / 72.0
    min_width_px = MIN_WIDTH_PT * scale
    max_height_px = MAX_HEIGHT_PT * scale

    count, labels, stats, _ = cv2.connectedComponentsWithStats(
        ink.astype(np.uint8), connectivity=8
    )
    elements = []
    for label in range(1, count):
        x, y, w, h, area = (int(value) for value in stats[label])
        if w < min_width_px:
            continue
        if w / h < ASPECT_MIN:
            continue
        if h > max_height_px:
            continue
        component = labels == label
        outside_fraction = float((component & ~span_cover).sum()) / float(area)
        if outside_fraction < OUTSIDE_SPAN_FRACTION_MIN:
            continue
        variance = _stroke_direction_variance(gx, gy, component)
        if variance < STROKE_VARIANCE_MIN:
            continue
        confidence = round(
            min(1.0, variance / STROKE_VARIANCE_FULL)
            * _contrast(gray, ink, x, y, w, h),
            4,
        )
        if confidence < CONFIDENCE_FLOOR:
            continue
        box = px_box_to_pt(Box(x, y, w, h), dpi)
        if rotation:
            box = unrotate_box(box, rotation, page_w_pt, page_h_pt)
        elements.append(
            Element(
                element_type="SIGNATURE",
                box=box,
                spans=(),
                confidence=confidence,
                attributes={"inkFraction": round(area / float(w * h), 4)},
                detector=SIGNATURE_DETECTOR,
                detector_version=__version__,
            )
        )
    elements.sort(key=lambda element: (element.box.y, element.box.x))
    return elements


def _span_cover(
    shape, span_boxes: list[Box], dpi: int, rotation: int,
    page_w_pt: float, page_h_pt: float,
) -> np.ndarray:
    """True where some text-span box covers the DISPLAY-FRAME raster. Spans
    arrive canonical; rotate_box maps them into the frame the raster was
    rendered at, then dpi/72 scales pt to px (same clip discipline as
    ocr/gates.evaluate_gates' span rectangles)."""
    cover = np.zeros(shape, dtype=bool)
    height_px, width_px = shape
    scale = dpi / 72.0
    for span_box in span_boxes:
        display = (
            rotate_box(span_box, rotation, page_w_pt, page_h_pt)
            if rotation
            else span_box
        )
        x0 = _clip(int(display.x * scale), 0, width_px)
        y0 = _clip(int(display.y * scale), 0, height_px)
        x1 = _clip(int((display.x + display.width) * scale) + 1, 0, width_px)
        y1 = _clip(int((display.y + display.height) * scale) + 1, 0, height_px)
        cover[y0:y1, x0:x1] = True
    return cover


def _clip(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def _stroke_direction_variance(gx: np.ndarray, gy: np.ndarray, component: np.ndarray) -> float:
    """Circular variance of DOUBLED gradient angles over the component, 0..1.

    Doubling folds the two opposing edges of one stroke (gradient +theta on
    one side, -theta+pi on the other) onto a single angle, so a straight line
    cannot fake variance with its own two edges — it scores ~0, handwriting
    scores high. Rotation-invariant, so the gate behaves identically on
    rotated rasters."""
    magnitude = np.hypot(gx, gy)
    selected = component & (magnitude > 1.0)
    if not selected.any():
        return 0.0
    doubled = 2.0 * np.arctan2(gy[selected], gx[selected])
    resultant = math.hypot(float(np.cos(doubled).mean()), float(np.sin(doubled).mean()))
    return round(1.0 - resultant, 4)


def _contrast(gray: np.ndarray, ink: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """Otsu-class separation inside the bounding rect, 0..1. A washed-out
    scan separates weakly and the candidate falls below the floor. (Same
    measurement as checkbox.py's — repeated, not imported: each detector
    owns its own measurement code.)"""
    region_gray = gray[y:y + h, x:x + w].astype(np.float64)
    region_ink = ink[y:y + h, x:x + w]
    if not region_ink.any() or region_ink.all():
        return 0.0
    return float(region_gray[~region_ink].mean() - region_gray[region_ink].mean()) / 255.0
