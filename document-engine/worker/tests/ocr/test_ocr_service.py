"""Ladder logic unit tests: fake engines, synthetic rasters, injected rotation.

Real engines and real fixtures are exercised at the route level (test_ocr_route.py);
here the fixture geometry is constructed so each scenario controls exactly which
gates trip. Page: 500x500 px at 200 DPI = 180x180 pt.
"""

import pytest
from PIL import Image, ImageDraw

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.ocr.engines import OcrSpan
from pragmaticds_docengine_worker.ocr.gates import OcrConfig
from pragmaticds_docengine_worker.ocr.reconcile import AttributedSpan, reading_order, reconcile
from pragmaticds_docengine_worker.ocr.service import run_ocr_ladder

DPI = 200
PAGE_PT = 180.0
NO_ROTATION = lambda image, config: (0, 0.0)  # noqa: E731


class FakeEngine:
    def __init__(self, name: str, spans: list[OcrSpan]):
        self.name = name
        self.spans = spans
        self.calls: list[list[Box]] = []

    def recognize(self, image, regions):
        self.calls.append(regions)
        return self.spans


def _page_with_ink():
    """8100 ink px in a 90x90 block at (100,100) -> G2 expects ~10 words."""
    image = Image.new("RGB", (500, 500), "white")
    ImageDraw.Draw(image).rectangle([100, 100, 189, 189], fill="black")
    return image


def _healthy_spans(conf=0.9):
    """Ten strips tiling the ink block: G1 ~1.0, G2 1.0, G3 fine, G6 fine."""
    return [OcrSpan(f"w{i}", Box(100, 100 + 9 * i, 90, 9), conf) for i in range(10)]


def _run(image, primary, fallback, regions=(), config=None):
    return run_ocr_ladder(
        image,
        page_index=0,
        width_pt=PAGE_PT,
        height_pt=PAGE_PT,
        dpi=DPI,
        regions=list(regions),
        config=config or OcrConfig(),
        primary=primary,
        fallback=fallback,
        rotation_detector=NO_ROTATION,
    )


class TestSingleEnginePath:
    def test_no_gate_trip_means_fallback_never_runs(self):
        primary = FakeEngine("RAPIDOCR", _healthy_spans())
        fallback = FakeEngine("TESSERACT", [])
        body = _run(_page_with_ink(), primary, fallback)

        assert fallback.calls == []
        assert body["engine"] == "RAPIDOCR"
        assert body["fallbackReason"] is None
        assert body["raw"]["tesseract"] is None
        assert body["raw"]["rapidocr"]["spans"]

    def test_boxes_are_converted_to_canonical_points(self):
        """px (100, 109, 90, 9) at 200 DPI -> pt (36.0, 39.2, 32.4, 3.2)."""
        primary = FakeEngine("RAPIDOCR", _healthy_spans())
        body = _run(_page_with_ink(), primary, FakeEngine("TESSERACT", []))

        first = body["spans"][0]
        assert (first["x"], first["y"]) == (36.0, 36.0)
        assert (first["width"], first["height"]) == (32.4, 3.2)

    def test_every_gate_reported_and_g5_never_present(self):
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", _healthy_spans()),
                    FakeEngine("TESSERACT", []))
        assert list(body["gates"]) == [
            "G1_COVERAGE", "G2_WORD_YIELD", "G3_CONFIDENCE", "G4_NUMERIC", "G6_GEOMETRY"
        ]
        for gate in body["gates"].values():
            assert set(gate) == {"tripped", "value", "threshold"}

    def test_confidence_median_and_span_shape(self):
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", _healthy_spans(conf=0.85)),
                    FakeEngine("TESSERACT", []))
        assert body["confidenceMedian"] == pytest.approx(0.85)
        span = body["spans"][0]
        assert set(span) == {"ordinal", "text", "x", "y", "width", "height", "engine",
                             "confidence"}
        assert span["engine"] == "RAPIDOCR"

    def test_injected_rotation_detector_feeds_the_response(self):
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", _healthy_spans()),
                    FakeEngine("TESSERACT", []))
        assert body["detectedRotation"] == 0
        assert body["osdConfidence"] == 0.0
        assert body["pageIndex"] == 0


class TestFallbackPath:
    def test_g3_trip_runs_fallback_and_better_engine_wins(self):
        primary = FakeEngine("RAPIDOCR", _healthy_spans(conf=0.5))  # G3 trips
        fallback = FakeEngine("TESSERACT", _healthy_spans(conf=0.95))
        body = _run(_page_with_ink(), primary, fallback)

        assert len(fallback.calls) == 1
        assert body["fallbackReason"] == "G3_CONFIDENCE"
        assert body["engine"] == "TESSERACT"
        assert all(span["engine"] == "TESSERACT" for span in body["spans"])
        assert body["raw"]["rapidocr"]["spans"] and body["raw"]["tesseract"]["spans"]

    def test_reconciliation_mixes_engines_per_region(self):
        """Primary owns the top of the page but emits corrupt geometry at the bottom;
        fallback fills the bottom. Every span carries ITS engine."""
        corrupt = [OcrSpan("x", Box(200, 300 + i * 12, 0, 10), 0.9) for i in range(2)]
        primary = FakeEngine("RAPIDOCR", _healthy_spans() + corrupt)  # 2/12 invalid -> G6
        bottom = [OcrSpan("b", Box(300, 300 + i * 25, 80, 20), 0.8) for i in range(2)]
        fallback = FakeEngine("TESSERACT", bottom)
        body = _run(_page_with_ink(), primary, fallback)

        assert body["fallbackReason"] == "G6_GEOMETRY"
        engines = {span["engine"] for span in body["spans"]}
        assert engines == {"RAPIDOCR", "TESSERACT"}
        assert body["engine"] == "RAPIDOCR"  # majority of surviving spans

    def test_all_gates_tripped_on_both_engines_yields_engine_none(self):
        garbage = [
            OcrSpan("9,999.99", Box(400, 400, 30, 10), 0.2),
            OcrSpan("??", Box(420, 460, 30, 10), 0.2),
            OcrSpan("!!", Box(430, 430, 0, 10), 0.2),  # zero-width -> G6
        ]
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", list(garbage)),
                    FakeEngine("TESSERACT", list(garbage)))

        assert body["engine"] == "NONE"
        assert body["spans"] == []
        assert all(gate["tripped"] for gate in body["gates"].values())
        assert body["raw"]["rapidocr"]["spans"] and body["raw"]["tesseract"]["spans"]

    def test_no_spans_from_either_engine_yields_engine_none(self):
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", []),
                    FakeEngine("TESSERACT", []))
        assert body["engine"] == "NONE"
        assert body["spans"] == []
        assert body["confidenceMedian"] == 0.0

    def test_g2_trip_hands_the_page_to_the_engine_that_read_words(self):
        """A 72-DPI prose scan: the primary reads each LINE as one space-less token at
        0.97 (two tokens for ~10 words of ink -> G2 trips), the fallback reads the ten
        words at 0.9. Mean confidence alone hands every region back to the merged
        reading — the reading whose word yield summoned the fallback in the first place.
        Measured on SSA's own sample letter: 63 line tokens vs 220 words, and not one
        of the pack's five anchor phrases survived in the served text."""
        merged_lines = [
            OcrSpan("w0w1w2w3w4", Box(100, 100, 90, 45), 0.97),
            OcrSpan("w5w6w7w8w9", Box(100, 145, 90, 45), 0.97),
        ]
        primary = FakeEngine("RAPIDOCR", merged_lines)
        fallback = FakeEngine("TESSERACT", _healthy_spans(conf=0.9))
        body = _run(_page_with_ink(), primary, fallback)

        assert body["fallbackReason"] == "G2_WORD_YIELD"
        assert body["engine"] == "TESSERACT"
        assert [span["text"] for span in body["spans"]] == [f"w{i}" for i in range(10)]
        assert all(span["engine"] == "TESSERACT" for span in body["spans"])

    def test_a_fallback_that_merged_the_lines_loses_too(self):
        """The trigger is EITHER engine's G2. Here the primary trips G4 (one amount at
        0.70) and the fallback reads the page as two merged lines at 0.99: by confidence
        the merged reading would be served. The fallback's own G2 trip must count."""
        primary_words = [OcrSpan(f"w{i}", Box(100, 100 + 9 * i, 90, 9), 0.9) for i in range(9)]
        primary_words.append(OcrSpan("$1,234.56", Box(100, 181, 90, 9), 0.7))  # G4 trips
        merged_lines = [
            OcrSpan("w0w1w2w3w4", Box(100, 100, 90, 45), 0.99),
            OcrSpan("w5w6w7w8w9", Box(100, 145, 90, 45), 0.99),
        ]
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", primary_words),
                    FakeEngine("TESSERACT", merged_lines))

        assert body["fallbackReason"] == "G4_NUMERIC"
        assert body["engine"] == "RAPIDOCR"
        assert len(body["spans"]) == 10
        assert all(span["engine"] == "RAPIDOCR" for span in body["spans"])


class TestReconcileWordYield:
    """reconcile() with a G2 trip on record: per region, an engine that read fewer than
    g2_yield_threshold of the other's tokens under-read that region and its per-token
    confidence is not comparable; the denser reading wins if its tokens look like the
    sparse reading's LINES split into words (most of the sparse reading's characters sit
    in tokens longer than any dense token; character mass comparable) and its mean
    confidence is at the G3 median threshold. Everything else is the mean-confidence
    rule, primary winning ties."""

    PAGE = 180.0

    @staticmethod
    def _words(engine, count, conf, y=10.0, length=2):
        """`count` tokens of `length` characters each, side by side on one line."""
        return [AttributedSpan(f"{engine[0]}{i}".ljust(length, "x"), Box(10 + 8 * i, y, 7, 5),
                               conf, engine)
                for i in range(count)]

    @staticmethod
    def _line(engine, conf, y=10.0, text="merged"):
        return [AttributedSpan(text, Box(10, y, 70, 5), conf, engine)]

    def _reconcile(self, primary, fallback, **kwargs):
        return reconcile(primary, fallback, page_w_pt=self.PAGE, page_h_pt=self.PAGE,
                         config=OcrConfig(), **kwargs)

    def test_denser_confident_fallback_wins_the_region_when_primary_under_yielded(self):
        survivors = self._reconcile(self._line("RAPIDOCR", 0.97), self._words("TESSERACT", 8, 0.9),
                                    word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"TESSERACT"}
        assert len(survivors) == 8

    def test_denser_fallback_below_the_g3_floor_does_not_take_the_region(self):
        survivors = self._reconcile(self._line("RAPIDOCR", 0.97), self._words("TESSERACT", 8, 0.5),
                                    word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"RAPIDOCR"}

    @pytest.mark.parametrize("fragment_count", [6, 12])
    def test_denser_fragmented_reading_does_not_take_the_region(self, fragment_count):
        """Denser is not the same as 'read the words'. Tesseract turns dotted leaders,
        rules and underlines into runs of one-character tokens: the same four words plus
        six (or twelve) fragments at 0.85 is 2.5x (4x) the primary's token count, but
        the primary's tokens are words, not lines — none is longer than the fallback's
        own copy of that word, so nothing here was split and nothing is served. Twelve
        fragments is the case a mean-token-length test lets through (5.0 vs 2.0)."""
        primary = self._words("RAPIDOCR", 4, 0.95, length=5)
        fragments = [AttributedSpan(".", Box(50 + 2 * i, 10.0, 1, 5), 0.85, "TESSERACT")
                     for i in range(fragment_count)]
        fallback = self._words("TESSERACT", 4, 0.85, length=5) + fragments
        survivors = self._reconcile(primary, fallback, word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"RAPIDOCR"}
        assert len(survivors) == 4

    def test_a_denser_reading_with_little_character_mass_does_not_take_the_region(self):
        """A logo region: the primary read one long token, the fallback three short
        scraps at 0.9. Three scraps are not a line split into its words."""
        primary = self._line("RAPIDOCR", 0.97, text="x" * 40)
        fallback = self._words("TESSERACT", 3, 0.9)
        survivors = self._reconcile(primary, fallback, word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"RAPIDOCR"}

    def test_comparable_yield_still_chooses_by_confidence(self):
        survivors = self._reconcile(self._words("RAPIDOCR", 6, 0.97), self._words("TESSERACT", 8, 0.9),
                                    word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"RAPIDOCR"}

    def test_without_the_g2_trip_the_mean_confidence_rule_is_unchanged(self):
        survivors = self._reconcile(self._line("RAPIDOCR", 0.97), self._words("TESSERACT", 8, 0.9))
        assert {s.engine for s in survivors} == {"RAPIDOCR"}

    def test_the_rule_is_symmetric_a_sparse_fallback_loses_to_a_dense_primary(self):
        survivors = self._reconcile(self._words("RAPIDOCR", 8, 0.8), self._line("TESSERACT", 0.99),
                                    word_yield_tripped=True)
        assert {s.engine for s in survivors} == {"RAPIDOCR"}

    def test_regions_are_judged_independently(self):
        """Top region: primary merged a line, fallback read the words. Bottom region:
        both read words, primary more confidently. One page, two winners."""
        primary = self._line("RAPIDOCR", 0.97, y=10.0) + self._words("RAPIDOCR", 6, 0.97, y=150.0)
        fallback = self._words("TESSERACT", 8, 0.9, y=10.0) + self._words("TESSERACT", 6, 0.9, y=150.0)
        survivors = self._reconcile(primary, fallback, word_yield_tripped=True)
        top = {s.engine for s in survivors if s.box.y < 90}
        bottom = {s.engine for s in survivors if s.box.y >= 90}
        assert (top, bottom) == ({"TESSERACT"}, {"RAPIDOCR"})


class TestRegions:
    def test_request_regions_reach_engines_as_pixel_boxes(self):
        """Canonical (36, 36, 72, 18) pt at 200 DPI -> px (100, 100, 200, 50)."""
        primary = FakeEngine("RAPIDOCR", _healthy_spans())
        _run(_page_with_ink(), primary, FakeEngine("TESSERACT", []),
             regions=[Box(36.0, 36.0, 72.0, 18.0)])
        assert primary.calls[0] == [Box(100.0, 100.0, 200.0, 50.0)]


class TestReadingOrder:
    def test_top_to_bottom_then_left_to_right(self):
        spans = [
            AttributedSpan("right-top", Box(200, 50, 40, 10), 0.9, "RAPIDOCR"),
            AttributedSpan("bottom", Box(50, 120, 40, 10), 0.9, "RAPIDOCR"),
            AttributedSpan("left-top", Box(50, 50.4, 40, 10), 0.9, "RAPIDOCR"),
        ]
        ordered = reading_order(spans)
        assert [span.text for span in ordered] == ["left-top", "right-top", "bottom"]

    def test_ordinals_in_response_follow_reading_order(self):
        shuffled = list(reversed(_healthy_spans()))
        body = _run(_page_with_ink(), FakeEngine("RAPIDOCR", shuffled),
                    FakeEngine("TESSERACT", []))
        ys = [span["y"] for span in body["spans"]]
        assert ys == sorted(ys)
        assert [span["ordinal"] for span in body["spans"]] == list(range(10))
