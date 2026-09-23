"""The per-page text-layer verdict matrix — EVERY fixture class, including rotated scans.

Verdict inputs (contract + text.py): word count, total word area vs page area, and
embedded-image coverage; a low-DPI ink check separates SCANNED (ink, no text layer)
from NONE (no ink at all — the blank fixture is a full-page WHITE image, so image
coverage alone cannot make that call).

Expected verdicts come from the fixtures' own truth files (expectedVerdict), so this
matrix can never drift from fixtures/generate.py.
"""

import pytest

ALL_FIXTURES = [
    "native_paystub",
    "native_multipage",
    "scanned_paystub",
    "scanned_rot90",
    "scanned_rot180",
    "scanned_rot270",
    "degraded_paystub",
    "blank_page",
    "mixed_page",
]


@pytest.mark.parametrize("fixture_name", ALL_FIXTURES)
def test_verdict_matches_the_fixture_truth(client, fixture_bytes, fixture_truth, text_of, fixture_name):
    truth = fixture_truth(fixture_name + ".json")

    body = text_of(client, fixture_bytes(fixture_name + ".pdf")).json()

    assert len(body["pages"]) == len(truth["pages"])
    for got, expected in zip(body["pages"], truth["pages"]):
        assert got["verdict"] == expected["expectedVerdict"], (
            f"{fixture_name} page {expected['pageIndex']}: "
            f"got {got['verdict']}, truth says {expected['expectedVerdict']}"
        )


@pytest.mark.parametrize("fixture_name", ["scanned_rot90", "scanned_rot180", "scanned_rot270"])
def test_rotated_scans_are_scanned_with_no_spans(client, fixture_bytes, text_of, fixture_name):
    """Rotated scans have NO text layer — the verdict must be SCANNED and the spans
    empty; rotation recovery is the OCR ladder's job (OSD), not /v1/text's."""
    body = text_of(client, fixture_bytes(fixture_name + ".pdf")).json()

    page = body["pages"][0]
    assert page["verdict"] == "SCANNED"
    assert page["spans"] == []


class TestMixedPage:
    def test_mixed_page_returns_uncovered_regions(self, client, fixture_bytes, text_of):
        """mixed_page: native words in the top half, a pasted scan (no text layer) in
        the lower half — generate.py draws the image at exactly (0, 396, 612x396)."""
        body = text_of(client, fixture_bytes("mixed_page.pdf")).json()

        page = body["pages"][0]
        assert page["verdict"] == "MIXED"
        assert page["uncoveredRegions"] == [
            {"x": 0.0, "y": 396.0, "width": 612.0, "height": 396.0}
        ]

    def test_native_and_scanned_pages_omit_uncovered_regions(self, client, fixture_bytes, text_of):
        for name in ("native_paystub", "scanned_paystub", "blank_page"):
            body = text_of(client, fixture_bytes(name + ".pdf")).json()

            assert "uncoveredRegions" not in body["pages"][0], name

    def test_mixed_page_still_returns_its_native_spans(self, client, fixture_bytes, text_of, fixture_truth):
        truth_words = fixture_truth("mixed_page.json")["pages"][0]["words"]

        body = text_of(client, fixture_bytes("mixed_page.pdf")).json()

        spans = body["pages"][0]["spans"]
        assert [s["text"] for s in spans] == [w["text"] for w in truth_words]
