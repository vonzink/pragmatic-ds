"""POST /v1/layout — wire-level behaviour: auth, envelope, validation, and the
optional file part. Spans always come IN on the request; the PDF only confirms
ruled tables."""

import json

from worker.tests.layout.conftest import FIXTURES, post_layout


def _request(pages=None):
    return {
        "pages": pages
        if pages is not None
        else [
            {
                "pageIndex": 0,
                "widthPt": 612.0,
                "heightPt": 792.0,
                "spans": [
                    {"ordinal": 0, "text": "Hello", "x": 72.0, "y": 100.0,
                     "width": 30.0, "height": 11.0, "fontSize": 11.0,
                     "fontName": "Helvetica"},
                ],
            }
        ]
    }


class TestAuth:
    def test_missing_secret_is_401_unauthorized(self, anon_client):
        response = post_layout(anon_client, _request())

        assert response.status_code == 401
        assert "UNAUTHORIZED" in response.text

    def test_wrong_secret_is_401_unauthorized(self, anon_client):
        anon_client.headers["X-Worker-Secret"] = "not-the-secret"

        response = post_layout(anon_client, _request())

        assert response.status_code == 401


class TestValidation:
    def test_missing_request_part_is_invalid_request(self, client):
        response = client.post(
            "/v1/layout",
            files={"file": ("upload.bin", b"%PDF-fake", "application/pdf")},
        )

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_unparseable_request_json_is_invalid_request(self, client):
        response = client.post(
            "/v1/layout",
            files={"request": ("request.json", b"{not json", "application/json")},
        )

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_pages_must_be_a_list(self, client):
        response = post_layout(client, {"pages": {"pageIndex": 0}})

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_page_missing_dimensions_is_invalid(self, client):
        response = post_layout(client, {"pages": [{"pageIndex": 0, "spans": []}]})

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_malformed_span_is_invalid(self, client):
        response = post_layout(client, {"pages": [{
            "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0,
            "spans": [{"ordinal": 0, "text": "x"}],  # no box
        }]})

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_error_detail_never_echoes_content(self, client):
        response = post_layout(client, {"pages": [{
            "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0,
            "spans": [{"ordinal": 0, "text": "SENSITIVE-DOCUMENT-TEXT"}],
        }]})

        assert response.status_code == 400
        assert "SENSITIVE-DOCUMENT-TEXT" not in response.text

    def test_non_multipart_body_is_invalid_request(self, client):
        response = client.post("/v1/layout", content=json.dumps(_request()),
                               headers={"Content-Type": "application/json"})

        assert response.status_code == 400
        assert response.json()["error"] == "INVALID_REQUEST"

    def test_corrupt_optional_pdf_is_reported_not_ignored(self, client):
        response = post_layout(client, _request(), pdf_bytes=b"not a pdf at all")

        assert response.status_code == 400
        assert response.json()["error"] == "CORRUPT_PDF"


class TestResponseShape:
    def test_worker_block_and_page_shape(self, client):
        response = post_layout(client, _request())

        assert response.status_code == 200
        body = response.json()
        assert body["worker"]["version"]
        assert "pdfplumber" in body["worker"]["libraries"]
        page = body["pages"][0]
        assert page["pageIndex"] == 0
        assert page["notImplemented"] == ["CHECKBOX", "SIGNATURE"]
        assert isinstance(page["elements"], list)

    def test_file_part_is_genuinely_optional(self, client):
        response = post_layout(client, _request())

        assert response.status_code == 200

    def test_every_page_carries_not_implemented(self, client):
        pages = [
            {"pageIndex": i, "widthPt": 612.0, "heightPt": 792.0, "spans": []}
            for i in range(3)
        ]

        response = post_layout(client, _request(pages))

        body = response.json()
        assert len(body["pages"]) == 3
        for page in body["pages"]:
            assert page["notImplemented"] == ["CHECKBOX", "SIGNATURE"]

    def test_ocr_spans_without_font_fields_are_accepted(self, client):
        response = post_layout(client, {"pages": [{
            "pageIndex": 0, "widthPt": 612.0, "heightPt": 792.0,
            "spans": [
                {"ordinal": 0, "text": "ocr", "x": 72.0, "y": 100.0,
                 "width": 25.0, "height": 11.0},
                {"ordinal": 1, "text": "words", "x": 103.0, "y": 100.5,
                 "width": 38.0, "height": 10.8},
            ],
        }]})

        assert response.status_code == 200
        elements = response.json()["pages"][0]["elements"]
        assert any(e["elementType"] == "PARAGRAPH" for e in elements)


class TestRasterPlumbing:
    """Spec 3 T2: with a `file` part /v1/layout renders every requested page
    worker-side (pypdfium2, 200 DPI) and hands each page its raster. Pure
    plumbing — no pixel detector exists yet, so page payloads are UNCHANGED
    and notImplemented still declares the pair unconditionally (pinned in
    test_engine.py; T3/T4 stage the per-detector retirement)."""

    def _empty_span_page(self, page_index=0):
        return {
            "pageIndex": page_index,
            "widthPt": 612.0,
            "heightPt": 792.0,
            "spans": [],
        }

    def test_file_part_renders_the_requested_pages(
        self, client, fixture_bytes, monkeypatch
    ):
        import pragmaticds_docengine_worker.layout_routes as routes

        seen = []

        def recording_render(pdf_bytes, page_indexes):
            seen.append((bytes(pdf_bytes[:5]), list(page_indexes)))
            return {}

        monkeypatch.setattr(routes, "render_rasters", recording_render)

        response = post_layout(
            client,
            {"pages": [self._empty_span_page()]},
            pdf_bytes=fixture_bytes("native_paystub.pdf"),
        )

        assert response.status_code == 200, response.text
        assert seen == [(b"%PDF-", [0])]

    def test_no_file_part_renders_nothing(self, client, monkeypatch):
        import pragmaticds_docengine_worker.layout_routes as routes

        def forbidden_render(pdf_bytes, page_indexes):
            raise AssertionError("render_rasters must not run without a file part")

        monkeypatch.setattr(routes, "render_rasters", forbidden_render)

        response = post_layout(client, {"pages": [self._empty_span_page()]})

        assert response.status_code == 200, response.text
        assert response.json()["pages"][0]["notImplemented"] == ["CHECKBOX", "SIGNATURE"]


class TestPixelPathRetirement:
    """With the PDF attached the worker rendered pixels and RAN both pixel
    detectors — notImplemented is [] (T3 retired CHECKBOX, T4 SIGNATURE)."""

    def test_file_present_retires_both(self, client, text_to_layout_request):
        request = text_to_layout_request(client, "native_paystub.pdf")
        pdf = (FIXTURES / "native_paystub.pdf").read_bytes()

        response = post_layout(client, request, pdf_bytes=pdf)

        assert response.status_code == 200
        page = response.json()["pages"][0]
        assert page["notImplemented"] == []
        # Printed glyphs are claimed by their span boxes and sit far below
        # the 54 pt width floor; drawn rules fail the variance gate; the
        # border-coverage/quadrilateral (T3) and height-ceiling (T4) gates
        # hold the rest.
        assert not any(
            e["elementType"] in ("CHECKBOX", "SIGNATURE") for e in page["elements"]
        )

    def test_no_file_still_declares_both(self, client):
        response = post_layout(client, _request())

        assert response.json()["pages"][0]["notImplemented"] == ["CHECKBOX", "SIGNATURE"]
