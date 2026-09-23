"""The OCR selection ladder (PARSER_EVALUATION.md §4) behind POST /v1/ocr.

    OSD -> de-rotate raster -> RapidOCR -> gates G1-G4,G6
        -> on any trip: Tesseract on the SAME de-rotated raster -> per-region reconcile

Coordinate discipline — THREE frames, two geometry hops (Phase 2 review, critical
finding: an earlier version conflated "content upright" with "rotation-0" and
emitted quarter-turned boxes for every rotated scan):

    upright frame   what the engines see: the raster after OSD de-rotation
    display frame   the raster as posted: the rotation-0 page rendered AT /Rotate
    rotation-0      the page frame — what widthPt/heightPt describe, what native
                    spans live in, THE canonical output frame

    engine px -> pt -> rotate_box(osd)   [upright -> display]
                    -> unrotate_box(pageRotate) [display -> rotation-0]

Every hop goes through geometry.py's tested primitives; regions travel the exact
inverse chain. "Upright" equals "rotation-0" only when the page stores upright
content — scanned_rot90.pdf is a LANDSCAPE page with sideways content, and the
old code emitted its boxes 180pt out of frame.

Both engines' raw (unreconciled, canonical-space) outputs ride in the response
whenever both ran; the Java side persists them to parser_output. All-gates-tripped
on both engines is still HTTP 200 with engine NONE — a hard page is not a worker
failure (docs/WORKER_CONTRACT.md /v1/ocr).
"""

import statistics
from typing import Callable

from PIL import Image

from pragmaticds_docengine_worker.geometry import Box, px_box_to_pt, rotate_box, unrotate_box
from pragmaticds_docengine_worker.ocr.engines import (
    OcrEngine,
    OcrSpan,
    shared_rapidocr_engine,
    shared_tesseract_engine,
)
from pragmaticds_docengine_worker.ocr.gates import GateResult, OcrConfig, evaluate_gates
from pragmaticds_docengine_worker.ocr.osd import detect_rotation
from pragmaticds_docengine_worker.ocr.reconcile import AttributedSpan, reading_order, reconcile

RotationDetector = Callable[[Image.Image, OcrConfig], tuple[int, float]]


class FrameMap:
    """The three-frame coordinate mapping for one page raster (module docstring)."""

    def __init__(self, page_w_pt: float, page_h_pt: float, page_rotate: int, osd_rotation: int):
        self.page_w = page_w_pt
        self.page_h = page_h_pt
        self.page_rotate = page_rotate % 360
        self.osd_rotation = osd_rotation % 360
        swap_display = self.page_rotate in (90, 270)
        self.display_w = page_h_pt if swap_display else page_w_pt
        self.display_h = page_w_pt if swap_display else page_h_pt
        swap_upright = self.osd_rotation in (90, 270)
        self.upright_w = self.display_h if swap_upright else self.display_w
        self.upright_h = self.display_w if swap_upright else self.display_h

    def upright_pt_to_page(self, box: Box) -> Box:
        display = rotate_box(
            box, self.osd_rotation, page_w_pt=self.upright_w, page_h_pt=self.upright_h
        )
        return unrotate_box(
            display, self.page_rotate, page_w_pt=self.page_w, page_h_pt=self.page_h
        )

    def page_to_upright_px(self, box: Box, dpi: int) -> Box:
        display = rotate_box(box, self.page_rotate, page_w_pt=self.page_w, page_h_pt=self.page_h)
        upright = unrotate_box(
            display, self.osd_rotation, page_w_pt=self.upright_w, page_h_pt=self.upright_h
        )
        scale = dpi / 72.0
        return Box(upright.x * scale, upright.y * scale, upright.width * scale, upright.height * scale)


def _to_canonical(
    span: OcrSpan, engine: str, frames: FrameMap, dpi: int
) -> AttributedSpan:
    """Engine px (upright frame) -> canonical rotation-0 pt, via the FrameMap hops."""
    box_pt = px_box_to_pt(span.box_px, dpi)
    return AttributedSpan(span.text, frames.upright_pt_to_page(box_pt), span.confidence, engine)


def _gates_json(gates: dict[str, GateResult]) -> dict:
    return {
        name: {"tripped": g.tripped, "value": g.value, "threshold": g.threshold}
        for name, g in gates.items()
    }


def _raw_json(spans: list[AttributedSpan]) -> dict:
    return {
        "spans": [
            {
                "text": s.text,
                "x": s.box.x,
                "y": s.box.y,
                "width": s.box.width,
                "height": s.box.height,
                "confidence": round(s.confidence, 4),
            }
            for s in spans
        ]
    }


def _first_tripped(gates: dict[str, GateResult]) -> str | None:
    for name, gate in gates.items():  # dict preserves contract order G1..G6
        if gate.tripped:
            return name
    return None


def run_ocr_ladder(
    image: Image.Image,
    *,
    page_index: int,
    width_pt: float,
    height_pt: float,
    dpi: int,
    regions: list[Box],
    page_rotate: int = 0,
    config: OcrConfig | None = None,
    primary: OcrEngine | None = None,
    fallback: OcrEngine | None = None,
    rotation_detector: RotationDetector | None = None,
) -> dict:
    """Run the full ladder for one page raster; returns the /v1/ocr response body
    minus the worker version block (the route owns wire concerns).

    width_pt/height_pt are the ROTATION-0 page dims; page_rotate is the page /Rotate
    the raster was rendered at (contract: /v1/render emits display-space pixels)."""
    config = config or OcrConfig()
    primary = primary or shared_rapidocr_engine()
    fallback = fallback or shared_tesseract_engine()
    detect = rotation_detector or detect_rotation

    rotation, osd_confidence = detect(image, config)
    # PIL rotates counter-clockwise; content rotated `rotation` clockwise needs +rotation.
    derotated = image.rotate(rotation, expand=True) if rotation else image
    frames = FrameMap(width_pt, height_pt, page_rotate, rotation)

    regions_px = [frames.page_to_upright_px(region, dpi) for region in regions]
    primary_raw = primary.recognize(derotated, regions_px)
    primary_gates = evaluate_gates(primary_raw, derotated, dpi, config, regions_px=regions_px)
    primary_canonical = [_to_canonical(s, primary.name, frames, dpi) for s in primary_raw]

    fallback_reason = _first_tripped(primary_gates)
    fallback_canonical: list[AttributedSpan] | None = None

    if fallback_reason is None:
        final = list(primary_canonical)
    else:
        fallback_raw = fallback.recognize(derotated, regions_px)
        fallback_gates = evaluate_gates(fallback_raw, derotated, dpi, config, regions_px=regions_px)
        fallback_canonical = [_to_canonical(s, fallback.name, frames, dpi) for s in fallback_raw]
        both_all_tripped = all(g.tripped for g in primary_gates.values()) and all(
            g.tripped for g in fallback_gates.values()
        )
        if both_all_tripped:
            final = []  # the Java side reads this as OCR_LOW_CONFIDENCE
        else:
            final = reconcile(
                primary_canonical,
                fallback_canonical,
                page_w_pt=width_pt,
                page_h_pt=height_pt,
                config=config,
                regions=regions or None,
                # A G2 trip means an engine read too few tokens for the ink — on a
                # low-resolution prose scan, lines instead of words (reconcile.py). Either
                # engine can be the one that merged, so either engine's G2 is the trigger.
                word_yield_tripped=(
                    primary_gates["G2_WORD_YIELD"].tripped
                    or fallback_gates["G2_WORD_YIELD"].tripped
                ),
            )

    final = reading_order(final)

    if not final:
        page_engine = "NONE"
    else:
        primary_count = sum(1 for s in final if s.engine == primary.name)
        page_engine = primary.name if primary_count * 2 >= len(final) else fallback.name

    confidence_median = (
        round(statistics.median(s.confidence for s in final), 4) if final else 0.0
    )

    return {
        "pageIndex": page_index,
        "detectedRotation": rotation,
        "osdConfidence": round(osd_confidence, 4),
        "engine": page_engine,
        "fallbackReason": fallback_reason,
        "confidenceMedian": confidence_median,
        "spans": [
            {
                "ordinal": ordinal,
                "text": s.text,
                "x": s.box.x,
                "y": s.box.y,
                "width": s.box.width,
                "height": s.box.height,
                "engine": s.engine,
                "confidence": round(s.confidence, 4),
            }
            for ordinal, s in enumerate(final)
        ],
        "gates": _gates_json(primary_gates),
        "raw": {
            primary.name.lower(): _raw_json(primary_canonical),
            fallback.name.lower(): (
                _raw_json(fallback_canonical) if fallback_canonical is not None else None
            ),
        },
    }
