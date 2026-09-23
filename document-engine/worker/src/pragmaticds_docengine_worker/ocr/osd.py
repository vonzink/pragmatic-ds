"""Rotation detection: Tesseract OSD, arbitrated by a projection-profile heuristic.

Rotation convention everywhere: degrees the CONTENT appears rotated CLOCKWISE in the
raster. Tesseract's OSD `orientation` field uses the same convention (verified on the
generated rot90/180/270 fixtures), and geometry.unrotate_box expects it.

OSD confidence: Tesseract's `orientation_conf` is an open-ended score, not a
probability — clean 200-DPI fixtures measure 12–13.5. It is normalised here as
min(orientation_conf / 15, 1.0), so the config threshold of 0.5 corresponds to a raw
score of 7.5 — well below any confident reading, well above the ~1.6 the degraded
fixture produces.

The arbitration heuristic (used below the confidence threshold): binarise the raster
and compare the variance of the normalised row-sum ink profile against the column-sum
profile. Upright or upside-down text has strong ROW structure (dense lines separated
by white gaps -> high row variance); 90/270-rotated text has the same structure in
COLUMNS. The heuristic therefore resolves the AXIS only — 0/180 vs 90/270. When a
low-confidence OSD answer disagrees with the measured axis, the axis wins and the
rotation snaps to that axis's prior (0 for horizontal, 90 for vertical): within an
axis the profiles are symmetric and OSD, however unsure, has no better replacement —
down to OSD_NOISE_FLOOR, below which OSD's answer is noise and the prior always wins.
"""

import numpy as np
import pytesseract
from PIL import Image

from pragmaticds_docengine_worker.ocr.gates import OcrConfig, ink_mask

HORIZONTAL = "HORIZONTAL"
VERTICAL = "VERTICAL"

#: Raw orientation_conf that maps to normalised confidence 1.0 (see module docstring).
_OSD_CONF_FULL_SCALE = 15.0

#: Below this normalised confidence (raw score 3.0) OSD has given no usable answer, and
#: the measured axis's prior is used whichever rotation it named. Measured on two
#: upright Dayforce W-2s (2026-09-16): OSD said 180 at raw 0.21 and 0.87, and the page
#: was OCR'd upside down, reversing every line's word order. Confident reads score
#: 12-13.5; the degraded-scan fixture's ~1.6 names the wrong axis and lands on the
#: same prior either way. Above the floor a same-axis OSD answer stands as before.
OSD_NOISE_FLOOR = 0.2


def projection_profile_axis(image: Image.Image) -> str | None:
    """The dominant text-line axis of a raster, or None when there is no ink."""
    ink = ink_mask(image)
    if not ink.any():
        return None
    rows = ink.sum(axis=1).astype(np.float64)
    cols = ink.sum(axis=0).astype(np.float64)
    row_variance = float(np.var(rows / rows.mean()))
    col_variance = float(np.var(cols / cols.mean()))
    return HORIZONTAL if row_variance >= col_variance else VERTICAL


def _axis_of(rotation: int) -> str:
    return HORIZONTAL if rotation in (0, 180) else VERTICAL


def detect_rotation(image: Image.Image, config: OcrConfig) -> tuple[int, float]:
    """(clockwise content rotation, normalised confidence) for a raster.

    OSD on blank/near-blank input raises inside Tesseract ("too few characters");
    that is a readable page with nothing on it, not a failure: (0, 0.0).
    """
    try:
        osd = pytesseract.image_to_osd(image, output_type=pytesseract.Output.DICT)
    except pytesseract.TesseractError:
        return 0, 0.0

    rotation = int(osd["orientation"]) % 360
    confidence = min(float(osd["orientation_conf"]) / _OSD_CONF_FULL_SCALE, 1.0)
    if confidence >= config.osd_confidence_threshold:
        return rotation, confidence

    measured_axis = projection_profile_axis(image)
    if confidence < OSD_NOISE_FLOOR:
        return (90 if measured_axis == VERTICAL else 0), confidence
    if measured_axis is None or measured_axis == _axis_of(rotation):
        return rotation, confidence
    return (0 if measured_axis == HORIZONTAL else 90), confidence
