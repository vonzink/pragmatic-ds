"""Real-engine adapter tests against the generated fixtures.

Engines emit PIXEL-frame word spans; canonical conversion is service.py's job. The
assertions here convert px -> pt by hand (dpi 200 -> x0.36) purely to compare against
truth, which is recorded in canonical points.
"""

import io
from collections import Counter

import numpy as np
import pypdfium2 as pdfium
import pytest
from ocr_fixture_helpers import fixture_image, truth, truth_word
from PIL import Image
from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas as rl_canvas

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.ocr.engines import (
    RECOGNITION_CROP_PAD_FRACTION,
    RECOGNITION_CROP_PAD_MIN,
    OcrEngine,
    OcrSpan,
    RapidOcrEngine,
    TesseractOcrEngine,
    is_whitespace_refinement,
    pad_crop_for_recognition,
    same_characters,
    shared_rapidocr_engine,
    shared_tesseract_engine,
)

PT_PER_PX_200DPI = 72.0 / 200.0
TRUTH = truth("scanned_paystub.json")


def _pt(px: float) -> float:
    return px * PT_PER_PX_200DPI


# ── the digit probe ──────────────────────────────────────────────────────────────
# Drawn here rather than committed to fixtures/ because #83 is a worker-only change,
# and because a probe whose truth is the STRING WE DREW needs no provenance file to be
# trustworthy. Same recipe as fixtures/generate.py's scanned set: reportlab Helvetica
# rasterised by pypdfium2 at 200 DPI.

DIGIT_PROBE_LINES = (
    # Runs of one repeated digit — the CTC collapse case, where the recogniser must
    # find a blank column between two identical glyphs or emit only one of them.
    "11", "111", "1111", "11111", "2000000", "100000.00", "3000.00", "22.22",
    # Grouped money. A digit lost here is a factor of ten, and it reads as plausible.
    "1,234,567.89", "10,000,000.00", "1,100,220.33", "4,000.00", "55.00", "8,800.88",
    # Account-number shapes: long, unseparated, and nothing on the page checks them.
    "000123456789", "4111111111111111", "987654321000", "5500000000004",
)
#: A crop's width:height ratio moves with the point size, and so does the column
#: budget the pad spends. One size would measure one ratio.
DIGIT_PROBE_SIZES = (9, 11, 14)
#: A pad this far past the useful range destroyed the probe before the refinement gate
#: existed (40/54 exact). It is the control that proves the gate is what protects the
#: digits, rather than the fraction happening to be small enough.
PAD_THAT_DESTROYS_UNGATED_TEXT = 0.40


def _digit_probe_page(size_pt: int):
    """(200-DPI raster, [(string, baseline_pt)]) for the probe drawn at `size_pt`."""
    page_w, page_h = letter
    buffer = io.BytesIO()
    canvas = rl_canvas.Canvas(buffer, pagesize=letter, invariant=1)
    canvas.setFont("Helvetica", size_pt)
    baseline, lines = page_h - 40, []
    for text in DIGIT_PROBE_LINES:
        canvas.drawString(72, baseline, text)
        lines.append((text, page_h - baseline))  # top-left frame, like every truth box
        baseline -= size_pt + 12
    assert baseline > 40, "the probe must fit on one page"
    canvas.showPage()
    canvas.save()
    document = pdfium.PdfDocument(buffer.getvalue())
    image = document[0].render(scale=200 / 72.0).to_pil().convert("RGB")
    document.close()
    return image, lines


def _read_probe(engine, size_pt: int) -> list[tuple[str, str]]:
    """[(printed, recognised)] for every probe line, matched BY POSITION.

    Not by searching for the expected string: the interesting case is when the answer
    is wrong, and a wrong answer still has to be attributable to the line that made it.
    """
    image, lines = _digit_probe_page(size_pt)
    spans = engine.recognize(image, [])
    out = []
    for text, baseline_pt in lines:
        band = size_pt * 0.62
        members = [
            span
            for span in spans
            if baseline_pt - size_pt * 1.05 - band
            <= _pt(span.box_px.y + span.box_px.height / 2.0)
            <= baseline_pt + band * 0.4
        ]
        members.sort(key=lambda span: span.box_px.x)
        out.append((text, " ".join(span.text for span in members)))
    return out


class TestProtocol:
    def test_both_adapters_satisfy_the_engine_protocol(self):
        assert isinstance(shared_rapidocr_engine(), OcrEngine)
        assert isinstance(shared_tesseract_engine(), OcrEngine)

    def test_engines_carry_their_wire_names(self):
        assert shared_rapidocr_engine().name == "RAPIDOCR"
        assert shared_tesseract_engine().name == "TESSERACT"

    def test_rapidocr_is_a_lazy_singleton(self):
        """Model load is expensive; the shared instance must be the same object, and
        constructing a fresh adapter must not load the model."""
        assert shared_rapidocr_engine() is shared_rapidocr_engine()
        fresh = RapidOcrEngine()
        assert fresh._ocr is None  # nothing loaded until first recognize()


@pytest.mark.slow
class TestRapidOcrEngine:
    def test_finds_the_header_words_on_the_scanned_paystub(self):
        spans = shared_rapidocr_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        text = " ".join(span.text for span in spans)
        for header_word in ("ACME", "WIDGETS", "LLC"):
            assert header_word in text

    def test_line_results_split_into_word_spans_with_proportional_boxes(self):
        spans = shared_rapidocr_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        employee = next(span for span in spans if span.text == "Employee:")
        expected = truth_word(TRUTH, "Employee:")
        assert _pt(employee.box_px.x) == pytest.approx(expected["x"], abs=3.0)
        assert _pt(employee.box_px.y) == pytest.approx(expected["y"], abs=3.0)
        assert _pt(employee.box_px.width) == pytest.approx(expected["width"], abs=3.0)

    def test_confidences_are_zero_to_one(self):
        spans = shared_rapidocr_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        assert spans
        assert all(0.0 <= span.confidence <= 1.0 for span in spans)

    def test_regions_restrict_recognition_and_boxes_stay_in_page_frame(self):
        image = fixture_image("scanned_paystub_page0.png")
        header_region = Box(150, 120, 1400, 130)  # px band around the ACME header line
        spans = shared_rapidocr_engine().recognize(image, [header_region])
        text = " ".join(span.text for span in spans)
        assert "ACME" in text
        assert "Employee" not in text
        for span in spans:  # offsets are page-frame, not crop-frame
            assert span.box_px.x >= 150
            assert 120 <= span.box_px.y <= 250

    def test_blank_image_yields_no_spans(self):
        assert shared_rapidocr_engine().recognize(Image.new("RGB", (800, 800), "white"), []) == []


class TestRecognitionCropPadding:
    """The pure half of the #83 fix: what the pad does to one crop array."""

    def test_pad_grows_only_the_height_symmetrically(self):
        crop = np.zeros((40, 400, 3), dtype=np.uint8)
        padded = pad_crop_for_recognition(crop, 0.25)
        assert padded.shape == (40 + 2 * 10, 400, 3)
        assert np.array_equal(padded[10:50], crop)  # the crop itself is untouched

    def test_pad_colour_is_the_crops_own_background_not_white(self):
        """A reversed banner — white caps on a black bar — is a real paystub header.
        White padding there lays a bright stripe against light glyphs; the crop's own
        median keeps the pad in the background it belongs to. Measured on a synthetic
        reversed banner: white padding recovered 1 of 3 word boundaries, the median
        pad all 3."""
        crop = np.zeros((40, 400, 3), dtype=np.uint8)  # black bar
        crop[15:25, 100:140] = 255  # a white glyph, a minority of the pixels
        padded = pad_crop_for_recognition(crop, 0.25)
        assert padded[0].max() == 0  # padded with the bar's black, not with white
        assert padded[-1].max() == 0

    def test_a_zero_fraction_is_the_identity(self):
        """The escape hatch the geometry-neutrality test compares against."""
        crop = np.full((40, 400, 3), 200, dtype=np.uint8)
        assert pad_crop_for_recognition(crop, 0.0) is crop

    def test_a_crop_too_short_to_pad_is_returned_unchanged(self):
        crop = np.full((1, 20, 3), 200, dtype=np.uint8)
        assert pad_crop_for_recognition(crop, 0.25) is crop  # round(0.25) == 0

    def test_the_shipped_fraction_is_the_measured_one(self):
        assert RECOGNITION_CROP_PAD_FRACTION == 0.16
        assert RECOGNITION_CROP_PAD_MIN < RECOGNITION_CROP_PAD_FRACTION
        assert RapidOcrEngine()._crop_pad_fraction == RECOGNITION_CROP_PAD_FRACTION


class TestWhitespaceRefinementGate:
    """What the padded reading is ALLOWED to change. Pure, so it is cheap to be
    exhaustive here and expensive only once, in TestDigitIntegrity."""

    def test_pure_whitespace_difference_is_accepted(self):
        assert is_whitespace_refinement("ACMEWIDGETSLLC", "ACME WIDGETS LLC")
        assert is_whitespace_refinement("Total NetPay", "Total Net Pay")

    def test_an_identical_reading_is_accepted_and_changes_nothing(self):
        assert is_whitespace_refinement("Net Pay", "Net Pay")

    def test_a_changed_character_is_refused(self):
        """The reported defect: a printed 2000000 served as 200000 at 0.98."""
        assert not is_whitespace_refinement("2000000", "200000")
        assert not is_whitespace_refinement("Gross Pay 2000000", "Gross Pay 200000")

    def test_a_dropped_or_added_character_is_refused(self):
        assert not is_whitespace_refinement("ACME WIDGETS LLC", "ACME WDGETS LLC")
        assert not is_whitespace_refinement("10,000,000.00", "10,000,00.00")
        assert not is_whitespace_refinement("2000000", "20000000")

    def test_an_empty_padded_reading_is_refused(self):
        """A crop the pad destroyed outright must not blank out a real word."""
        assert not is_whitespace_refinement("612.44", "")

    def test_a_boundary_the_pad_would_erase_is_refused(self):
        """The pad may REFINE the segmentation, never re-cut it. Losing a boundary the
        unpadded reading already had is a regression dressed as a whitespace change."""
        assert not is_whitespace_refinement("Net Pay", "NetPay")
        assert not is_whitespace_refinement("A B C", "AB C")

    @pytest.mark.parametrize(
        "unpadded,padded",
        [
            ("1,100,220.33", "1, 100,220.33"),  # cut after the comma
            ("1,234,567.89", "1,234, 567.89"),
            ("2000000", "200 0000"),  # cut between two digits
            ("48.0771", "48. 0771"),  # cut after the decimal point
            ("3,846.17", "3,846 .17"),
        ],
    )
    def test_a_new_boundary_inside_a_number_is_refused(self, unpadded, padded):
        """Splitting an amount is a wrong value even though every character survives:
        downstream reads a money field from ONE span and would take the first half."""
        assert same_characters(unpadded, padded), "the probe itself must be characters-equal"
        assert not is_whitespace_refinement(unpadded, padded)

    @pytest.mark.parametrize(
        "unpadded,padded",
        [
            ("GrossPay2000000", "Gross Pay 2000000"),  # letter/digit joins are fine
            ("Employee:Jordan", "Employee: Jordan"),
            ("NetPay3,846.17", "Net Pay 3,846.17"),
            ("YTDUsage", "YTD Usage"),
        ],
    )
    def test_a_boundary_between_a_word_and_a_number_is_still_allowed(self, unpadded, padded):
        """The rule is 'not INSIDE a number', not 'never near one' — the merged
        'NetPay3,846.17' is exactly the shape #83 exists to split."""
        assert is_whitespace_refinement(unpadded, padded)


@pytest.mark.slow
class TestRapidOcrWordBoundaries:
    """#83 at the recogniser seam, against the unpadded engine as its own control.

    RapidOCR resizes every detection crop to 48px height before recognition, so a
    crop that hugs the glyph band is stretched horizontally out of the recogniser's
    training distribution and it stops emitting its space token. Padding the crop
    vertically puts the aspect ratio back in range; the SPACE THEN COMES FROM THE
    RECOGNISER, which is what makes it evidence and not a guess.
    """

    @staticmethod
    def _tokens(engine) -> list[str]:
        image = fixture_image("scanned_paystub_page0.png")
        return [span.text for span in engine.recognize(image, [])]

    @staticmethod
    def _quads(engine) -> list[tuple]:
        """Detection quads, read from the recogniser's own output.

        Deliberately reaching past recognize() into the loaded RapidOCR: the claim
        under test is precisely that DETECTION is untouched, and span boxes cannot
        show that once the line has been split into words."""
        image = fixture_image("scanned_paystub_page0.png")
        result, _elapse = engine._load()(np.asarray(image))
        return [tuple(tuple(point) for point in quad) for quad, _text, _conf in result or []]

    def test_unpadded_recogniser_merges_the_header_the_way_83_reported(self):
        """The control. If this ever stops failing, the fix has stopped being needed
        and the padding should be re-measured rather than trusted."""
        assert "ACMEWIDGETSLLC" in self._tokens(RapidOcrEngine(crop_pad_fraction=0.0))

    def test_tightly_set_capitals_keep_their_word_boundaries(self):
        tokens = self._tokens(shared_rapidocr_engine())
        assert "ACMEWIDGETSLLC" not in tokens
        for word in ("ACME", "WIDGETS", "LLC"):
            assert word in tokens, f"{word!r} did not arrive as its own span"

    def test_the_pad_only_inserts_boundaries_and_never_rewrites_a_character(self):
        """THE negative test. Whitespace-stripped, the two token streams must be the
        same string: the fix may add a boundary the recogniser saw, and may do
        nothing else. A split landing inside a printed token — 'ACME WID GETSLLC' —
        would still pass this, so the amount check below closes that gap."""
        unpadded = "".join(self._tokens(RapidOcrEngine(crop_pad_fraction=0.0)))
        padded = "".join(self._tokens(shared_rapidocr_engine()))
        assert padded == unpadded

    def test_every_amount_survives_as_one_token(self):
        """A token with no printed gap must survive whole. Amounts are the case that
        matters: a fabricated space inside '3,846.17' is a wrong value, and a wrong
        value is worse than a missing boundary."""
        tokens = self._tokens(shared_rapidocr_engine())
        for amount in ("3,846.17", "4,170.69", "612.44", "324.52", "80.00", "48.0771"):
            assert amount in tokens, f"{amount!r} did not survive as one token"

    def test_no_new_boundary_lands_between_two_digits(self):
        """The exact form of the negative test, for the case that costs money.

        The padded stream is a REFINEMENT of the unpadded one — same characters, some
        tokens split further. Walk the two together to recover the exact offset of
        every boundary the pad introduced, and require that none of them separates
        two digits. This is stronger than spot-checking known amounts: it holds for
        every number on the page, including ones the truth file never named."""
        unpadded = self._tokens(RapidOcrEngine(crop_pad_fraction=0.0))
        padded = list(self._tokens(shared_rapidocr_engine()))

        introduced = []
        for token in unpadded:
            rebuilt = ""
            while rebuilt != token:
                assert padded, f"padded stream ran out rebuilding {token!r}"
                piece = padded.pop(0)
                if rebuilt:
                    introduced.append((token, len(rebuilt)))
                rebuilt += piece
                assert token.startswith(rebuilt), (
                    f"padded stream diverged from {token!r} at {rebuilt!r}"
                )
        assert not padded, f"padded stream had {len(padded)} tokens left over"

        for token, offset in introduced:
            assert not (token[offset - 1].isdigit() and token[offset].isdigit()), (
                f"a boundary was inserted between two digits of {token!r} at {offset}"
            )
        assert introduced, "the fixture must exercise at least one recovered boundary"

    @staticmethod
    def _worst_box_error(engine) -> tuple[float, str, int]:
        """Worst x/right-edge error against truth, over words that appear EXACTLY once
        in truth and once in the output — 'Pay' occurs four times and pairing an
        instance to a truth row is undefined, so a repeated word measures the pairing
        rule and not the box."""
        spans = engine.recognize(fixture_image("scanned_paystub_page0.png"), [])
        truth_counts = Counter(w["text"] for w in TRUTH["pages"][0]["words"])
        span_counts = Counter(s.text for s in spans)
        worst, culprit, compared = 0.0, "", 0
        for word in TRUTH["pages"][0]["words"]:
            if truth_counts[word["text"]] != 1 or span_counts[word["text"]] != 1:
                continue
            span = next(s for s in spans if s.text == word["text"])
            compared += 1
            error = max(
                abs(_pt(span.box_px.x) - word["x"]),
                abs(_pt(span.box_px.x + span.box_px.width) - (word["x"] + word["width"])),
            )
            if error > worst:
                worst, culprit = error, word["text"]
        return worst, culprit, compared

    def test_word_box_apportionment_stays_inside_its_measured_envelope(self):
        """Splitting a line apportions its box by CHARACTER COUNT, which under-serves
        wide capitals. Pin the envelope rather than let it drift upward behind a
        tolerance somebody else has to widen again."""
        worst, culprit, compared = self._worst_box_error(shared_rapidocr_engine())
        assert compared >= 30
        assert worst <= 5.0, f"apportionment drifted to {worst:.1f}pt on {culprit!r}"

    def test_the_pad_widens_no_box_it_did_not_also_create(self):
        """The honest accounting for the envelope above. The pad takes the worst error
        from 4.1pt ('Fixture') to 4.5pt ('ACME') — and 'ACME' had NO box of its own
        before, because it was buried inside 'ACMEWIDGETSLLC'. Every word that already
        had a box keeps one at least as good, and six more words gain one."""
        padded, _, padded_n = self._worst_box_error(shared_rapidocr_engine())
        unpadded, _, unpadded_n = self._worst_box_error(
            RapidOcrEngine(crop_pad_fraction=0.0)
        )
        assert padded_n > unpadded_n, "the pad must add words, not merely move boxes"
        assert padded - unpadded <= 0.5, (
            f"the pad widened the envelope by {padded - unpadded:.1f}pt, which is more "
            "than the new words it earned can account for"
        )

    def test_the_pad_changes_text_only_never_detection_geometry(self):
        """Padding happens BELOW the detector and BELOW the orientation classifier,
        so every detection quad must be bit-identical — order and value. Spec 4/5a
        box-grid, cellWindow and ROW/COLUMN grouping all read these boxes."""
        assert self._quads(shared_rapidocr_engine()) == self._quads(
            RapidOcrEngine(crop_pad_fraction=0.0)
        )


@pytest.mark.slow
class TestDigitIntegrity:
    """#83's blocker, through the real engine, on every run.

    The pad buys vertical margin by spending horizontal resolution: the recogniser
    resizes to 48px of height with the aspect preserved, so padding divides the width
    that reaches the model — and the CTC columns each character gets — by (1 + 2f).
    Two identical characters in a row need a blank column between them or CTC emits
    only one. Pad far enough and a printed 2000000 is served as 200000, at 0.98
    confidence: a confident wrong number, which is worse than the merged token the pad
    exists to fix.

    These assertions are EXACT string equality against what was drawn. Not 'mostly
    right', not a distance: a digit is either there or it is not.
    """

    @staticmethod
    def _wrong(engine, size_pt: int) -> list[tuple[str, str]]:
        return [(want, got) for want, got in _read_probe(engine, size_pt) if got != want]

    @pytest.mark.parametrize("size_pt", DIGIT_PROBE_SIZES)
    def test_every_digit_run_survives_the_shipped_engine_exactly(self, size_pt):
        wrong = self._wrong(shared_rapidocr_engine(), size_pt)
        assert not wrong, "\n".join(
            f"printed {want!r} came back {got!r}" for want, got in wrong
        )

    @pytest.mark.parametrize("size_pt", DIGIT_PROBE_SIZES)
    def test_an_amount_is_never_split_in_two(self, size_pt):
        """The subtler corruption, and the one a characters-only check misses:
        '1,100,220.33' arriving as '1, 100,220.33' keeps every character and is still
        a wrong value, because every rung downstream reads a money field from ONE
        span. The ungated pad does this at 0.20 and 0.40."""
        for want, got in _read_probe(shared_rapidocr_engine(), size_pt):
            assert " " not in got, f"{size_pt}pt: printed {want!r} was split into {got!r}"

    def test_the_gate_is_what_protects_the_digits_not_the_fraction(self):
        """The teeth, and the actual claim of this change.

        A pad far past the useful range is fed to BOTH engines. Un-gated it wrecks the
        probe — that is #83's blocker reproduced on demand. Gated, at the very same
        fraction, every line comes back exactly as printed. If this ever stops failing
        on the left-hand side, the pad has stopped being dangerous and the gate can be
        re-argued; until then it is the gate, not the smallness of the constant, that
        makes the shipped engine safe.
        """
        ungated = RapidOcrEngine(
            crop_pad_fraction=PAD_THAT_DESTROYS_UNGATED_TEXT, gate_refinements=False
        )
        damage = self._wrong(ungated, 9) + self._wrong(ungated, 14)
        assert any(
            sum(c.isdigit() for c in got) != sum(c.isdigit() for c in want)
            for want, got in damage
        ), (
            f"pad {PAD_THAT_DESTROYS_UNGATED_TEXT} was expected to lose digits without "
            f"the gate and did not; got {damage}"
        )

        gated = RapidOcrEngine(crop_pad_fraction=PAD_THAT_DESTROYS_UNGATED_TEXT)
        assert not (self._wrong(gated, 9) + self._wrong(gated, 14)), (
            "the refinement gate let damage through at a fraction it is supposed to "
            "reject wholesale"
        )


@pytest.mark.slow
class TestTesseractOcrEngine:
    def test_recovers_every_truth_word_on_the_clean_scan(self):
        spans = shared_tesseract_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        assert len(spans) == len(TRUTH["pages"][0]["words"])

    def test_word_boxes_land_on_truth(self):
        spans = shared_tesseract_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        acme = next(span for span in spans if span.text == "ACME")
        expected = truth_word(TRUTH, "ACME")
        assert _pt(acme.box_px.x) == pytest.approx(expected["x"], abs=3.0)
        assert _pt(acme.box_px.y) == pytest.approx(expected["y"], abs=3.0)
        assert _pt(acme.box_px.width) == pytest.approx(expected["width"], abs=3.0)

    def test_confidence_rescaled_from_percent_to_unit(self):
        spans = shared_tesseract_engine().recognize(fixture_image("scanned_paystub_page0.png"), [])
        assert all(0.0 <= span.confidence <= 1.0 for span in spans)
        assert max(span.confidence for span in spans) > 0.5  # not accidentally /10000

    def test_blank_image_yields_no_spans(self):
        assert shared_tesseract_engine().recognize(Image.new("RGB", (800, 800), "white"), []) == []
