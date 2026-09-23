"""Contract tests for POST /v1/text (docs/WORKER_CONTRACT.md).

Spans are pdfplumber WORDS in canonical space (PDF points, top-left, rotation-0,
0.1pt); ordinal is reading order — top-to-bottom line bands, left-to-right within
a band. fontSize/fontName are attached when the PDF provides them.
"""

from collections import Counter

import pytest


def error_code(response) -> str:
    return response.json()["error"]


class TestAuth:
    def test_missing_secret_is_401_unauthorized(self, anon_client, fixture_bytes, text_of):
        response = text_of(anon_client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 401
        assert "UNAUTHORIZED" in response.text

    def test_wrong_secret_is_401_unauthorized(self, anon_client, fixture_bytes, text_of):
        anon_client.headers["X-Worker-Secret"] = "not-the-secret"
        response = text_of(anon_client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 401


class TestResponseShape:
    def test_page_carries_contract_fields(self, client, fixture_bytes, text_of):
        response = text_of(client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 200
        body = response.json()
        page = body["pages"][0]
        assert page["pageIndex"] == 0
        assert page["widthPt"] == 612.0
        assert page["heightPt"] == 792.0
        assert page["rotation"] == 0
        assert page["verdict"] == "NATIVE"
        assert len(page["spans"]) == 43  # exactly the words drawn by fixtures/generate.py

    def test_worker_version_block_names_pdfplumber(self, client, fixture_bytes, text_of):
        from importlib.metadata import version as installed_version

        from pragmaticds_docengine_worker import __version__

        body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

        assert body["worker"]["version"] == __version__
        assert body["worker"]["libraries"]["pdfplumber"] == installed_version("pdfplumber")

    def test_spans_carry_font_metadata_when_present(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

        by_text = {span["text"]: span for span in body["pages"][0]["spans"]}
        assert by_text["ACME"]["fontName"] == "Helvetica-Bold"
        assert by_text["ACME"]["fontSize"] == 14.0
        assert by_text["Employee:"]["fontName"] == "Helvetica"
        assert by_text["Employee:"]["fontSize"] == 11.0

    def test_boxes_are_rounded_to_tenth_of_a_point(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

        for span in body["pages"][0]["spans"]:
            for key in ("x", "y", "width", "height"):
                assert round(span[key] * 10) == round(span[key] * 10, 6), (
                    f"{key}={span[key]} not on the 0.1pt grid"
                )


class TestReadingOrder:
    def test_ordinals_are_dense_and_start_at_zero(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

        ordinals = [span["ordinal"] for span in body["pages"][0]["spans"]]
        assert ordinals == list(range(43))

    def test_order_is_top_to_bottom_then_left_to_right(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

        spans = sorted(body["pages"][0]["spans"], key=lambda s: s["ordinal"])
        assert [s["text"] for s in spans[:3]] == ["ACME", "WIDGETS", "LLC"]
        assert [s["text"] for s in spans[-2:]] == ["Page", "1"]  # footer is the last band
        # Within every band x must increase; across bands y must not decrease.
        previous = None
        for span in spans:
            if previous is not None:
                same_band = span["y"] < previous["y"] + previous["height"]
                if same_band:
                    assert span["x"] > previous["x"], "left-to-right violated within a band"
                else:
                    assert span["y"] > previous["y"], "top-to-bottom violated across bands"
            previous = span


class TestPageSelection:
    def test_pages_filter_returns_only_requested(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_multipage.pdf"), {"pages": [2]}).json()

        assert [page["pageIndex"] for page in body["pages"]] == [2]

    def test_absent_pages_means_all(self, client, fixture_bytes, text_of):
        body = text_of(client, fixture_bytes("native_multipage.pdf")).json()

        assert [page["pageIndex"] for page in body["pages"]] == [0, 1, 2]

    def test_out_of_range_page_is_PAGE_OUT_OF_RANGE(self, client, fixture_bytes, text_of):
        response = text_of(client, fixture_bytes("native_paystub.pdf"), {"pages": [9]})

        assert response.status_code == 400
        assert error_code(response) == "PAGE_OUT_OF_RANGE"


class TestMalformedInput:
    def test_garbage_bytes_are_CORRUPT_PDF(self, client, text_of):
        response = text_of(client, b"definitely not a pdf" * 50)

        assert response.status_code == 400
        assert error_code(response) == "CORRUPT_PDF"

    def test_error_detail_never_echoes_document_bytes(self, client, text_of):
        response = text_of(client, b"SENSITIVE-DOC-CONTENT-MARKER" * 10)

        assert response.status_code == 400
        assert "SENSITIVE" not in response.text

    def test_unparseable_request_json_is_INVALID_REQUEST(self, client, fixture_bytes):
        response = client.post(
            "/v1/text",
            files={"file": ("upload.bin", fixture_bytes("native_paystub.pdf"), "application/pdf")},
            data={"request": "{not json"},
        )

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"


class TestRotatedNativePage:
    """A /Rotate 90 native page must land its boxes in ROTATION-0 canonical space —
    identical to the un-rotated extraction — via geometry.unrotate_box, the single
    conversion point. (pdfplumber reports display-space coordinates on rotated pages.)"""

    def test_rotate_90_boxes_match_the_unrotated_extraction(
        self, client, fixture_bytes, text_of, rotate_pdf
    ):
        raw = fixture_bytes("native_paystub.pdf")
        flat = text_of(client, raw).json()["pages"][0]
        rotated = text_of(client, rotate_pdf(raw, 90)).json()["pages"][0]

        assert rotated["rotation"] == 90
        assert rotated["widthPt"] == 612.0 and rotated["heightPt"] == 792.0
        assert len(rotated["spans"]) == len(flat["spans"])
        for span in rotated["spans"]:
            # Words repeat ("Pay" appears four times) — match the nearest same-text span.
            same_text = [s for s in flat["spans"] if s["text"] == span["text"]]
            assert same_text, f"no flat span for {span['text']!r}"
            reference = min(same_text, key=lambda s: abs(s["x"] - span["x"]) + abs(s["y"] - span["y"]))
            for key in ("x", "y", "width", "height"):
                assert abs(span[key] - reference[key]) <= 0.2, (
                    f"{span['text']} {key}: rotated {span[key]} vs flat {reference[key]}"
                )


class TestRotatedPageWordText:
    """A quarter turn must not change a single CHARACTER. The class above proves the
    boxes land in canonical space; this one proves the STRINGS inside them, which is a
    separate failure with a separate cause and no shared guard.

    The fixtures are real /Rotate pages carrying a text layer (paystub_complete_rot*),
    because nothing else in the repo can reach this code path: scanned_rot* are rotated
    rasters with no /Rotate key and no text at all, so they exercise OCR and never
    pdfplumber. Rotation-0 is parameterized in as the control.

    A reversal here is silent and total — boxes stay perfect, so every geometry
    assertion passes while the document reaches classification as gibberish and lands
    in human review as UNKNOWN with zero fields.
    """

    @staticmethod
    def _page(client, fixture_bytes, text_of, rotation: int) -> dict:
        name = (
            "paystub_complete.pdf"
            if rotation == 0
            else f"paystub_complete_rot{rotation}.pdf"
        )
        response = text_of(client, fixture_bytes(name))
        assert response.status_code == 200
        return response.json()["pages"][0]

    @pytest.mark.parametrize("rotation", [0, 90, 180, 270])
    def test_words_are_the_same_words_at_every_quarter_turn(
        self, client, fixture_bytes, text_of, rotation
    ):
        flat = self._page(client, fixture_bytes, text_of, 0)
        page = self._page(client, fixture_bytes, text_of, rotation)

        assert page["rotation"] == rotation
        # Reading ORDER legitimately differs — display space is what a reader sees, and
        # an upside-down page is read from its other end. The word BAG must not.
        assert Counter(s["text"] for s in page["spans"]) == Counter(
            s["text"] for s in flat["spans"]
        )

    @pytest.mark.parametrize("rotation", [0, 90, 180, 270])
    def test_load_bearing_values_survive_the_turn(
        self, client, fixture_bytes, text_of, rotation
    ):
        page = self._page(client, fixture_bytes, text_of, rotation)
        texts = [span["text"] for span in page["spans"]]

        # Spelled out rather than derived: these are the tokens the paystub schema
        # extracts, and each one reversed is a distinct string, so the assertion fails
        # loudly instead of matching its own mirror image.
        for expected in ("$3,565.87", "612.44", "Employee:", "WIDGETS", "Bi-Weekly"):
            assert expected in texts, f"rot{rotation}: {expected!r} missing from {texts}"

    @pytest.mark.parametrize("rotation", [90, 180, 270])
    def test_every_span_keeps_its_unrotated_box_and_its_text_together(
        self, client, fixture_bytes, text_of, rotation
    ):
        """Text and geometry pinned as a PAIR — the defect this guards moved the
        characters while leaving the boxes exactly right, so neither half alone sees it."""
        flat = self._page(client, fixture_bytes, text_of, 0)
        page = self._page(client, fixture_bytes, text_of, rotation)

        assert page["widthPt"] == 612.0 and page["heightPt"] == 792.0
        assert len(page["spans"]) == len(flat["spans"])
        for span in page["spans"]:
            same_text = [s for s in flat["spans"] if s["text"] == span["text"]]
            assert same_text, f"rot{rotation}: no unrotated span says {span['text']!r}"
            reference = min(
                same_text, key=lambda s: abs(s["x"] - span["x"]) + abs(s["y"] - span["y"])
            )
            for key in ("x", "y", "width", "height"):
                assert abs(span[key] - reference[key]) <= 0.2, (
                    f"rot{rotation} {span['text']} {key}: {span[key]} vs flat {reference[key]}"
                )
