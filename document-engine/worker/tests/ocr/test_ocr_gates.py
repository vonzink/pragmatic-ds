"""Unit tests for the OCR quality gates G1/G2/G3/G4/G6 — synthetic spans, no OCR.

Every gate is evaluated on every call and reported with (tripped, value, threshold);
the ladder never short-circuits the gate block (docs/WORKER_CONTRACT.md /v1/ocr).
G5 is Java-side and must never appear here.
"""

import numpy as np
import pytest
from PIL import Image, ImageDraw

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.ocr.engines import OcrSpan
from pragmaticds_docengine_worker.ocr.gates import (
    GateResult,
    OcrConfig,
    evaluate_gates,
    ink_mask,
    is_numeric_token,
)

CONFIG = OcrConfig()


def _page(width_px=500, height_px=500, rects=()):
    """A synthetic raster: white page with black rectangles as the only ink."""
    image = Image.new("RGB", (width_px, height_px), "white")
    draw = ImageDraw.Draw(image)
    for x, y, w, h in rects:
        draw.rectangle([x, y, x + w - 1, y + h - 1], fill="black")
    return image


def _span(text="word", x=10, y=10, w=50, h=12, conf=0.95):
    return OcrSpan(text=text, box_px=Box(x, y, w, h), confidence=conf)


class TestInkMask:
    def test_counts_only_dark_pixels(self):
        image = _page(rects=[(100, 100, 50, 20)])
        mask = ink_mask(image)
        assert mask.dtype == np.bool_
        assert mask.sum() == 50 * 20

    def test_blank_page_has_no_ink(self):
        assert ink_mask(_page()).sum() == 0


class TestNumericToken:
    @pytest.mark.parametrize(
        "token", ["3,846.17", "48.0771", "80.00", "$3,105.87", "612.44", "4,170.69", "$1,200"]
    )
    def test_currency_and_decimal_tokens_match(self, token):
        assert is_numeric_token(token)

    @pytest.mark.parametrize("token", ["ACME", "Pay", "01/17/2026", "1", "Q.", "", "Page1"])
    def test_words_dates_and_bare_integers_do_not(self, token):
        assert not is_numeric_token(token)


class TestG1Coverage:
    def test_spans_covering_the_ink_do_not_trip(self):
        image = _page(rects=[(100, 100, 200, 40)])
        spans = [_span(x=95, y=95, w=210, h=50)]
        gate = evaluate_gates(spans, image, dpi=200, config=CONFIG)["G1_COVERAGE"]
        assert not gate.tripped
        assert gate.value == pytest.approx(1.0)
        assert gate.threshold == CONFIG.g1_coverage_threshold

    def test_region_restricted_recognition_scopes_g1_to_the_regions(self):
        """Phase 2 review finding: G1's denominator counted ALL page ink even when
        recognition was restricted to regions — so every MIXED page (native text
        outside the regions, by definition) tripped G1 regardless of OCR quality,
        double-OCR'd, and got permanently flagged in review. Ink outside the regions
        can never be covered and must not count."""
        image = _page(400, 400, rects=[
            (10, 10, 150, 40),    # native text area — OUTSIDE the region
            (200, 200, 150, 40),  # the pasted-scan area — INSIDE the region
        ])
        region = Box(190.0, 190.0, 200.0, 100.0)
        spans = [_span("scanword", x=200, y=200, w=150, h=40)]

        unscoped = evaluate_gates(spans, image, dpi=200, config=OcrConfig())
        scoped = evaluate_gates(spans, image, dpi=200, config=OcrConfig(), regions_px=[region])

        assert unscoped["G1_COVERAGE"].tripped, "sanity: full-page denominator trips"
        assert not scoped["G1_COVERAGE"].tripped, scoped["G1_COVERAGE"]
        # G2's ink-density expectation must likewise count region ink only: half the
        # ink in scope -> double the yield value. (A solid synthetic block is ~7
        # words of ink in one span, so absolute G2 judgments are meaningless here —
        # the denominator scoping is the property under test.)
        assert scoped["G2_WORD_YIELD"].value == pytest.approx(
            2 * unscoped["G2_WORD_YIELD"].value, rel=0.05
        ), (scoped["G2_WORD_YIELD"], unscoped["G2_WORD_YIELD"])

    def test_spans_missing_the_ink_trip(self):
        image = _page(rects=[(100, 100, 200, 40)])
        spans = [_span(x=400, y=400, w=50, h=20)]
        gate = evaluate_gates(spans, image, dpi=200, config=CONFIG)["G1_COVERAGE"]
        assert gate.tripped
        assert gate.value < 0.60

    def test_blank_page_coverage_is_vacuously_full(self):
        gate = evaluate_gates([], _page(), dpi=200, config=CONFIG)["G1_COVERAGE"]
        assert not gate.tripped
        assert gate.value == pytest.approx(1.0)


class TestG2WordYield:
    def test_yield_near_expectation_does_not_trip(self):
        # 100x100 ink px at 200 dpi -> expected 10000/810 ~ 12.3 words.
        image = _page(rects=[(50, 50, 100, 100)])
        spans = [_span(x=50 + 4 * i, y=50, w=4, h=100) for i in range(12)]
        gate = evaluate_gates(spans, image, dpi=200, config=CONFIG)["G2_WORD_YIELD"]
        assert not gate.tripped
        assert gate.value > 0.9

    def test_suspiciously_sparse_yield_trips(self):
        image = _page(rects=[(50, 50, 100, 100)])
        spans = [_span(x=50, y=50, w=100, h=100)]
        gate = evaluate_gates(spans, image, dpi=200, config=CONFIG)["G2_WORD_YIELD"]
        assert gate.tripped
        assert gate.value < 0.5

    def test_no_ink_yield_is_vacuously_full(self):
        gate = evaluate_gates([], _page(), dpi=200, config=CONFIG)["G2_WORD_YIELD"]
        assert not gate.tripped


class TestG3ConfidenceFloor:
    def test_healthy_confidences_do_not_trip(self):
        spans = [_span(conf=0.9) for _ in range(10)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G3_CONFIDENCE"]
        assert not gate.tripped
        assert gate.value == pytest.approx(0.9)

    def test_low_median_trips(self):
        spans = [_span(conf=0.5) for _ in range(10)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G3_CONFIDENCE"]
        assert gate.tripped

    def test_low_p10_trips_even_with_healthy_median(self):
        # 11 values: p10 lands exactly on the second-lowest = 0.2 < 0.40; median 0.9.
        confs = [0.2, 0.2] + [0.9] * 9
        spans = [_span(conf=c) for c in confs]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G3_CONFIDENCE"]
        assert gate.tripped
        assert gate.value == pytest.approx(0.9)  # value reports the median

    def test_no_spans_trips(self):
        gate = evaluate_gates([], _page(), dpi=200, config=CONFIG)["G3_CONFIDENCE"]
        assert gate.tripped
        assert gate.value == 0.0


class TestG4NumericIntegrity:
    """THE mortgage gate: numeric tokens are evaluated separately from words."""

    def test_confident_numerics_do_not_trip(self):
        spans = [_span(text="3,846.17", conf=0.97), _span(text="Gross", conf=0.9)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G4_NUMERIC"]
        assert not gate.tripped
        assert gate.value == pytest.approx(0.97)

    def test_one_shaky_currency_token_trips(self):
        spans = [_span(text="$3,105.87", conf=0.60), _span(text="3,846.17", conf=0.99)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G4_NUMERIC"]
        assert gate.tripped
        assert gate.value == pytest.approx(0.60)
        assert gate.threshold == CONFIG.g4_numeric_threshold

    def test_low_confidence_words_never_trip_the_numeric_gate(self):
        """A blurry 'Withholding' is G3's business, not G4's."""
        spans = [_span(text="Withholding", conf=0.10), _span(text="612.44", conf=0.95)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G4_NUMERIC"]
        assert not gate.tripped
        assert gate.value == pytest.approx(0.95)

    def test_no_numeric_tokens_is_vacuously_confident(self):
        spans = [_span(text="hello", conf=0.2)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G4_NUMERIC"]
        assert not gate.tripped
        assert gate.value == pytest.approx(1.0)


class TestG6Geometry:
    def test_valid_boxes_do_not_trip(self):
        spans = [_span(x=10 + i * 20, y=10, w=15, h=10) for i in range(10)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G6_GEOMETRY"]
        assert not gate.tripped
        assert gate.value == pytest.approx(0.0)

    def test_more_than_15_percent_degenerate_boxes_trip(self):
        good = [_span() for _ in range(8)]
        bad = [_span(w=0, h=10), _span(x=10_000, y=10, w=50, h=10)]  # zero-area + out of bounds
        gate = evaluate_gates(good + bad, _page(), dpi=200, config=CONFIG)["G6_GEOMETRY"]
        assert gate.tripped
        assert gate.value == pytest.approx(0.2)

    def test_a_single_bad_box_in_ten_does_not_trip(self):
        spans = [_span() for _ in range(9)] + [_span(w=0, h=0)]
        gate = evaluate_gates(spans, _page(), dpi=200, config=CONFIG)["G6_GEOMETRY"]
        assert not gate.tripped
        assert gate.value == pytest.approx(0.1)


class TestGateBlockShape:
    def test_every_gate_is_always_evaluated_and_g5_never_appears(self):
        gates = evaluate_gates([_span()], _page(), dpi=200, config=CONFIG)
        assert list(gates) == [
            "G1_COVERAGE",
            "G2_WORD_YIELD",
            "G3_CONFIDENCE",
            "G4_NUMERIC",
            "G6_GEOMETRY",
        ]
        assert all(isinstance(g, GateResult) for g in gates.values())

    def test_thresholds_come_from_config_not_constants(self):
        loose = OcrConfig(g3_median_threshold=0.10, g3_p10_threshold=0.01)
        spans = [_span(conf=0.5) for _ in range(10)]
        assert not evaluate_gates(spans, _page(), dpi=200, config=loose)["G3_CONFIDENCE"].tripped
