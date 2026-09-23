"""OCR quality gates G1–G4 and G6 (PARSER_EVALUATION.md §4).

Every gate is evaluated on every pass and reported as (tripped, value, threshold) —
the ladder never short-circuits the gate block, because the contract response shows
every gate (docs/WORKER_CONTRACT.md /v1/ocr). G5 (anchor miss) needs classification
context the worker must not have; it is evaluated Java-side and never appears here.

All thresholds live on OcrConfig: config, not constants (Phase 2 risk table).
Gates operate in the PIXEL frame of the (de-rotated) raster the engine actually saw.
"""

import re
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image

from pragmaticds_docengine_worker.ocr.engines import OcrSpan


@dataclass(frozen=True)
class OcrConfig:
    """Every tunable in the OCR ladder. Defaults per PARSER_EVALUATION.md §4."""

    g1_coverage_threshold: float = 0.60
    g2_yield_threshold: float = 0.50
    #: Calibration constant for G2's expected word count: mean ink pixels per word,
    #: measured on fixtures/scanned_paystub_page0.png (Otsu ink 34,819 px / 43 truth
    #: words ~ 810 at 200 DPI). Ink pixel counts scale with dpi^2, so the expectation
    #: is normalised by (dpi/200)^2.
    g2_ink_px_per_word_200dpi: float = 810.0
    g3_median_threshold: float = 0.70
    g3_p10_threshold: float = 0.40
    g4_numeric_threshold: float = 0.75
    g6_invalid_fraction_threshold: float = 0.15
    #: Below this normalised OSD confidence, osd.py arbitrates with the
    #: projection-profile heuristic.
    osd_confidence_threshold: float = 0.5


@dataclass(frozen=True)
class GateResult:
    tripped: bool
    value: float
    threshold: float


#: Currency/decimal tokens for G4 — the mortgage gate. A token is numeric when it is
#: digits with optional thousands groups and optional decimal part, optionally
#: $-prefixed, AND carries at least one of '$', ',', '.' — bare integers (page
#: numbers) and dates (slashes) are words, not amounts.
_NUMERIC_RE = re.compile(r"^\$?\d{1,3}(?:,\d{3})*(?:\.\d+)?$")


def is_numeric_token(text: str) -> bool:
    return bool(_NUMERIC_RE.match(text)) and any(c in text for c in "$,.")


def ink_mask(image: Image.Image) -> np.ndarray:
    """Boolean ink mask via Otsu binarisation (opencv — the LICENSING.md §5.1 election).

    Deliberately no denoising: on a noisy scan the inflated "ink" is exactly what makes
    G1/G2 trip and route the page to the fallback engine.
    """
    gray = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    return binary > 0


def _clip(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def evaluate_gates(
    spans: list[OcrSpan],
    image: Image.Image,
    dpi: int,
    config: OcrConfig,
    regions_px: list | None = None,
) -> dict[str, GateResult]:
    """Evaluate ALL gates for one engine pass. Returns them in contract order.

    When recognition was RESTRICTED to regions, G1/G2 must be scoped to the same
    regions: ink outside them can never be covered, so a full-page denominator trips
    G1 on every MIXED page regardless of OCR quality (Phase 2 review finding) and
    permanently mis-flags healthy pages in review.
    """
    ink = ink_mask(image)
    width_px, height_px = image.size
    if regions_px:
        scope = np.zeros_like(ink)
        for region in regions_px:
            x0 = _clip(int(region.x), 0, width_px)
            y0 = _clip(int(region.y), 0, height_px)
            x1 = _clip(int(region.x + region.width) + 1, 0, width_px)
            y1 = _clip(int(region.y + region.height) + 1, 0, height_px)
            scope[y0:y1, x0:x1] = True
        ink = ink & scope
    ink_total = int(ink.sum())

    # G1 — coverage: share of ink pixels lying under some recognised span box.
    if ink_total == 0:
        g1_value = 1.0  # vacuous: no ink to miss (blank pages fail on G3 instead)
    else:
        covered = np.zeros_like(ink)
        for span in spans:
            x0 = _clip(int(span.box_px.x), 0, width_px)
            y0 = _clip(int(span.box_px.y), 0, height_px)
            x1 = _clip(int(span.box_px.x + span.box_px.width) + 1, 0, width_px)
            y1 = _clip(int(span.box_px.y + span.box_px.height) + 1, 0, height_px)
            covered[y0:y1, x0:x1] = True
        g1_value = float((ink & covered).sum() / ink_total)
    g1 = GateResult(g1_value < config.g1_coverage_threshold, round(g1_value, 4),
                    config.g1_coverage_threshold)

    # G2 — word yield vs. the ink-density expectation (calibration on OcrConfig).
    expected_words = ink_total / (config.g2_ink_px_per_word_200dpi * (dpi / 200.0) ** 2)
    g2_value = 1.0 if expected_words < 1.0 else min(len(spans) / expected_words, 1.0)
    g2 = GateResult(g2_value < config.g2_yield_threshold, round(g2_value, 4),
                    config.g2_yield_threshold)

    # G3 — confidence floor: median, with a p10 check against a rotten tail.
    if spans:
        confidences = np.array([span.confidence for span in spans], dtype=np.float64)
        median = float(np.median(confidences))
        p10 = float(np.percentile(confidences, 10))
        g3_tripped = median < config.g3_median_threshold or p10 < config.g3_p10_threshold
    else:
        median, g3_tripped = 0.0, True
    g3 = GateResult(g3_tripped, round(median, 4), config.g3_median_threshold)

    # G4 — numeric integrity, THE mortgage gate: a wrong digit is worse than a wrong
    # word, so currency/decimal tokens are held to their own floor.
    numeric_confs = [span.confidence for span in spans if is_numeric_token(span.text)]
    g4_value = min(numeric_confs) if numeric_confs else 1.0
    g4 = GateResult(g4_value < config.g4_numeric_threshold, round(g4_value, 4),
                    config.g4_numeric_threshold)

    # G6 — geometry: fraction of degenerate or out-of-page boxes.
    if spans:
        invalid = sum(1 for span in spans if not _box_valid(span, width_px, height_px))
        g6_value = invalid / len(spans)
    else:
        g6_value = 0.0
    g6 = GateResult(g6_value > config.g6_invalid_fraction_threshold, round(g6_value, 4),
                    config.g6_invalid_fraction_threshold)

    return {
        "G1_COVERAGE": g1,
        "G2_WORD_YIELD": g2,
        "G3_CONFIDENCE": g3,
        "G4_NUMERIC": g4,
        "G6_GEOMETRY": g6,
    }


def _box_valid(span: OcrSpan, width_px: int, height_px: int) -> bool:
    box = span.box_px
    if box.width <= 0 or box.height <= 0:
        return False
    return box.x >= 0 and box.y >= 0 and box.x + box.width <= width_px and (
        box.y + box.height <= height_px
    )
