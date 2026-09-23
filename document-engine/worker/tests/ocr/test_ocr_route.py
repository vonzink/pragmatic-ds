"""POST /v1/ocr integration tests: real engines, real fixtures, contract shapes.

The four-rotation ACME assertion is the OCR half of the coordinate contract: a word
recognised on a rotated raster must land within 5pt of its constructed truth box in
canonical space (see _assert_agrees for where that number comes from). A rotation bug
displaces boxes by hundreds of points — nothing subtle survives this test.
"""

import io
import json

import pytest
from ocr_fixture_helpers import GOLDEN_DIR, fixture_bytes, truth, truth_word
from PIL import Image

GOLDEN = GOLDEN_DIR / "ocr_scanned_paystub.json"
LETTER = {"widthPt": 612.0, "heightPt": 792.0}
#: SSA's own fictional sample letter — the one reference form that is a low-resolution
#: image-only scan rather than a native PDF (see TestLowResolutionProseScan).
SSAL_SAMPLE = GOLDEN_DIR.parents[2] / "docs" / "reference-forms" / "SSAL.pdf"


def _assert_agrees(span: dict, truth_box: dict, tolerance: float = 5.0):
    """Every edge of the found span must agree with the truth word's, within the
    word-box apportionment envelope.

    This was CONTAINMENT at 3pt while #83 was open: 'ACME WIDGETS LLC' came back as
    the single token 'ACMEWIDGETSLLC' carrying the whole LINE box, so 'ACME' was
    trivially inside it and only the coordinate FRAME was ever under test. Now that
    the recogniser emits the spaces, the assertion also meets engines.py's
    apportion-by-character-count, whose error is a pre-existing property of splitting
    and not of the pad: measured on this fixture, 'Fixture' 4.1pt, 'Q.' 3.7pt,
    'Period:' 3.6pt and 'YTD' 3.6pt were all outside 3pt BEFORE the pad existed, and
    the pad moved no word's box by more than 0.8pt.

    5pt keeps every tooth this test has. It exists to catch a de-rotation bug, and
    those displace boxes by HUNDREDS of points (module docstring); the tolerance
    would have to grow a hundredfold to let one through.
    """
    assert abs(span["x"] - truth_box["x"]) <= tolerance, (span, truth_box)
    assert abs(span["y"] - truth_box["y"]) <= tolerance, (span, truth_box)
    assert abs((span["x"] + span["width"]) - (truth_box["x"] + truth_box["width"])) <= (
        tolerance
    ), (span, truth_box)
    assert abs((span["y"] + span["height"]) - (truth_box["y"] + truth_box["height"])) <= (
        tolerance
    ), (span, truth_box)


def _post(client, png_bytes: bytes, request_json: dict | None = None, raw_request: str = None):
    payload = raw_request if raw_request is not None else json.dumps(
        {"pageIndex": 0, "dpi": 200, "regions": [], **LETTER, **(request_json or {})}
    )
    return client.post(
        "/v1/ocr",
        files={"file": ("page.png", png_bytes, "image/png")},
        data={"request": payload},
    )


def _blank_png() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (1700, 2200), "white").save(buffer, format="PNG")
    return buffer.getvalue()


class TestAuth:
    def test_missing_secret_is_401(self, anon_client):
        response = _post(anon_client, _blank_png())
        assert response.status_code == 401
        assert "UNAUTHORIZED" in response.text


class TestValidation:
    def test_unparseable_request_json_is_invalid_request(self, client):
        response = _post(client, _blank_png(), raw_request="{not json")
        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_missing_dimensions_is_invalid_request(self, client):
        response = _post(client, _blank_png(), raw_request=json.dumps({"pageIndex": 0}))
        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_undecodable_image_is_invalid_request(self, client):
        response = _post(client, b"this is not a png")
        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"


@pytest.mark.slow
class TestCleanScan:
    def test_rapidocr_alone_handles_the_clean_scan(self, client):
        response = _post(client, fixture_bytes("scanned_paystub_page0.png"))
        assert response.status_code == 200
        body = response.json()

        assert body["engine"] == "RAPIDOCR"
        assert body["fallbackReason"] is None
        assert body["raw"]["tesseract"] is None  # fallback never ran
        assert body["detectedRotation"] == 0
        assert body["osdConfidence"] >= 0.5
        text = " ".join(span["text"] for span in body["spans"])
        for header_word in ("ACME", "WIDGETS", "LLC"):
            assert header_word in text
        assert all(span["engine"] == "RAPIDOCR" for span in body["spans"])

    def test_worker_version_block_names_the_ocr_stack(self, client):
        body = _post(client, fixture_bytes("scanned_paystub_page0.png")).json()
        assert body["worker"]["version"]
        assert body["worker"]["libraries"]["rapidocr-onnxruntime"]
        assert body["worker"]["libraries"]["pytesseract"]

    def test_gate_block_shows_every_gate_and_never_g5(self, client):
        body = _post(client, fixture_bytes("scanned_paystub_page0.png")).json()
        assert list(body["gates"]) == [
            "G1_COVERAGE", "G2_WORD_YIELD", "G3_CONFIDENCE", "G4_NUMERIC", "G6_GEOMETRY"
        ]
        for gate in body["gates"].values():
            assert set(gate) == {"tripped", "value", "threshold"}


@pytest.mark.slow
class TestRotationContract:
    """The OCR half of the coordinate contract, in the REAL page frame.

    Phase 2 review finding (critical): the old version of this test posted portrait
    dims for every rotation while the rot90/270 fixture pages are LANDSCAPE 792x612 —
    truth and test agreed with each other and both disagreed with the actual PDFs, so
    out-of-frame spans passed. Dims now come from the truth file (which carries the
    real page geometry), every span must be in-bounds, and ACME must land on its
    page-frame truth box.
    """

    @pytest.mark.parametrize(
        "fixture,rotation",
        [
            ("scanned_paystub_page0.png", 0),
            ("scanned_rot90_page0.png", 90),
            ("scanned_rot180_page0.png", 180),
            ("scanned_rot270_page0.png", 270),
        ],
    )
    def test_acme_lands_on_its_page_frame_truth_box(self, client, fixture, rotation):
        truth_name = "scanned_paystub.json" if rotation == 0 else f"scanned_rot{rotation}.json"
        truth_doc = truth(truth_name)
        page = truth_doc["pages"][0]
        expected = truth_word(truth_doc, "ACME")

        body = _post(
            client,
            fixture_bytes(fixture),
            {"widthPt": page["widthPt"], "heightPt": page["heightPt"]},
        ).json()

        assert body["detectedRotation"] == rotation
        assert body["spans"], "rotation must not silently drop every span"
        for span in body["spans"]:
            assert 0 <= span["x"] <= page["widthPt"] + 1, span
            assert 0 <= span["y"] <= page["heightPt"] + 1, span
            assert span["x"] + span["width"] <= page["widthPt"] + 3, span
            assert span["y"] + span["height"] <= page["heightPt"] + 3, span
        acme = next(span for span in body["spans"] if span["text"].startswith("ACME"))
        _assert_agrees(acme, expected)

    def test_page_Rotate_maps_display_boxes_into_the_rotation0_frame(self, client):
        """A page displayed UPRIGHT under /Rotate 90 stores its content at 270 degrees
        in the page frame (PDF /Rotate = degrees the viewer rotates the page CW for
        display) — so the expected page-frame boxes are scanned_rot270's truth. The
        first draft of this test asserted rot90 truth and the CODE was right."""
        expected = truth_word(truth("scanned_rot270.json"), "ACME")

        body = _post(
            client,
            fixture_bytes("scanned_paystub_page0.png"),
            {"widthPt": 792.0, "heightPt": 612.0, "rotation": 90},
        ).json()

        assert body["detectedRotation"] == 0
        acme = next(span for span in body["spans"] if span["text"].startswith("ACME"))
        _assert_agrees(acme, expected)


@pytest.mark.slow
class TestDegradedFallback:
    def test_a_gate_trips_fallback_runs_and_both_raws_return(self, client):
        response = _post(client, fixture_bytes("degraded_paystub_page0.png"))
        assert response.status_code == 200
        body = response.json()

        tripped = [name for name, gate in body["gates"].items() if gate["tripped"]]
        assert tripped, "the degraded fixture must trip at least one gate"
        assert body["fallbackReason"] == tripped[0]
        assert body["raw"]["rapidocr"] is not None
        assert body["raw"]["tesseract"] is not None  # fallback ran -> both raws persist
        for span in body["spans"]:
            assert span["engine"] in ("RAPIDOCR", "TESSERACT")


@pytest.mark.slow
class TestLowResolutionProseScan:
    """SSA's published sample Benefit Verification Letter (docs/reference-forms/SSAL.pdf,
    the fictional "Jonathan Doe") is a 72-DPI image-only PDF — a 781x911 px scan of a
    prose page. Rendered at the contract's 200 DPI it is an upscaled blur on which the
    primary recogniser drops the spaces inside every line: 63 tokens such as
    'BenefitVerificationLetter' at 0.97, against the fallback's 220 words. Word yield
    trips (0.13 of the ink expectation), the fallback runs, and reconciliation must then
    serve the reading that has words — every one of the SSA pack's anchors is a
    multi-word phrase, and the merged reading matched none of them."""

    def test_the_word_reading_is_served_when_the_primary_merged_the_lines(self, client):
        from pragmaticds_docengine_worker.render import render_pdf

        page = render_pdf(SSAL_SAMPLE.read_bytes(), [0], 200)[0]
        body = _post(client, page.png, {"widthPt": page.width_pt, "heightPt": page.height_pt}).json()

        assert body["fallbackReason"] == "G2_WORD_YIELD"
        assert body["engine"] == "TESSERACT"
        text = " ".join(span["text"] for span in body["spans"])
        for phrase in ("Benefit Verification Letter", "Social Security Administration"):
            assert phrase in text, phrase
        # Whichever engine wins a region, no region is served twice: a merged line and
        # its constituent words must never both survive.
        merged = [s["text"] for s in body["spans"] if "Verification" in s["text"] and s["text"] != "Verification"]
        assert merged == []


class TestBlankPage:
    def test_blank_image_returns_engine_none_without_crashing(self, client):
        response = _post(client, _blank_png())
        assert response.status_code == 200
        body = response.json()
        assert body["engine"] == "NONE"
        assert body["spans"] == []
        assert body["detectedRotation"] == 0
        assert body["confidenceMedian"] == 0.0


@pytest.mark.slow
class TestGolden:
    def test_scanned_paystub_response_matches_golden(self, client, update_goldens):
        """The pinned /v1/ocr output. Refresh ONLY via:
        .venv/bin/python -m pytest worker/tests/ocr --update-goldens"""
        body = _post(client, fixture_bytes("scanned_paystub_page0.png")).json()
        body.pop("worker")  # library versions drift legitimately; shapes must not

        if update_goldens:
            GOLDEN.parent.mkdir(parents=True, exist_ok=True)
            GOLDEN.write_text(json.dumps(body, indent=2, sort_keys=True) + "\n")

        assert GOLDEN.exists(), "golden missing — run with --update-goldens once"
        assert body == json.loads(GOLDEN.read_text())
