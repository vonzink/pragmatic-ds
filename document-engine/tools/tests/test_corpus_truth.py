"""The pure half of tools/corpus_truth.py — worker pages → fixtures/truth pages, and the one
place it is allowed to write. No network."""

import pytest

from tools.corpus_truth import CORPUS_EVAL, case_directory, truth_pages


def test_the_case_directory_is_under_corpus_eval_type_id():
    assert case_directory("W2", "adp-2025-a") == (CORPUS_EVAL / "W2" / "adp-2025-a").resolve()


@pytest.mark.parametrize(
    "document_type, case_id",
    [
        ("../../fixtures", "x"),
        ("W2", "../x"),
        ("W2", "sub/dir"),
        ("W2\\evil", "x"),
        ("W2", "..") ,
        ("", "x"),
        ("W2", ""),
    ],
)
def test_a_type_or_id_that_could_leave_the_tree_is_refused(document_type, case_id):
    with pytest.raises(ValueError, match="single directory name"):
        case_directory(document_type, case_id)


def test_main_refuses_a_pdf_stem_that_would_escape(tmp_path):
    from tools.corpus_truth import main

    escaping = tmp_path / "..stem.pdf"
    escaping.write_bytes(b"%PDF-1.4")
    # The stem contains ".." — refused before any network call or directory creation.
    assert main([str(escaping), "--type", "W2"]) == 2
    assert not (CORPUS_EVAL / "W2" / "..stem").exists()

WORKER_RESPONSE = {
    "worker": {"version": "0.2.0"},
    "pages": [
        {
            "pageIndex": 0,
            "widthPt": 612.0,
            "heightPt": 792.0,
            "rotation": 90,
            "verdict": "NATIVE",
            "inkFraction": 0.08,
            "spans": [
                {"ordinal": 0, "text": "Wages", "x": 40.0, "y": 100.0, "width": 30.0,
                 "height": 8.0, "fontSize": 8.0, "fontName": "Helvetica"},
                {"ordinal": 1, "text": "1,234.56", "x": 40.0, "y": 112.0, "width": 40.0,
                 "height": 8.0},
            ],
        },
        {"pageIndex": 1, "widthPt": 612.0, "heightPt": 792.0, "rotation": 0,
         "verdict": "SCANNED", "spans": []},
    ],
}


def test_words_carry_only_the_five_span_keys_the_harness_seeds():
    pages = truth_pages(WORKER_RESPONSE, "W2")

    assert [w["text"] for w in pages[0]["words"]] == ["Wages", "1,234.56"]
    assert set(pages[0]["words"][0]) == {"text", "x", "y", "width", "height"}


def test_page_geometry_and_rotation_ride_through_and_the_type_is_stamped():
    pages = truth_pages(WORKER_RESPONSE, "W2")

    assert [p["pageIndex"] for p in pages] == [0, 1]
    assert pages[0]["contentRotation"] == 90
    assert pages[1]["contentRotation"] == 0
    assert pages[0]["widthPt"] == 612.0 and pages[0]["heightPt"] == 792.0
    assert all(p["expectedType"] == "W2" for p in pages)
    assert pages[1]["expectedVerdict"] == "SCANNED"
    # A span-less page stays a page, so package page indexes line up with the PDF's.
    assert pages[1]["words"] == []
