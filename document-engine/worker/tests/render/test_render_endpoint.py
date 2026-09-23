"""Contract tests for POST /v1/render (docs/WORKER_CONTRACT.md).

Shapes are the contract's, verbatim: multipart/form-data in (file + request JSON part),
multipart/mixed out (metadata JSON part first, then one image/png part per page).
Boxes and page dims are canonical: PDF points, top-left, ROTATION-0, 0.1pt rounding.
"""

import json

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def post_render(client, pdf_bytes: bytes, request_json: dict | None = None):
    data = {} if request_json is None else {"request": json.dumps(request_json)}
    return client.post(
        "/v1/render",
        files={"file": ("upload.bin", pdf_bytes, "application/pdf")},
        data=data,
    )


def error_code(response) -> str:
    return response.json()["error"]


class TestAuth:
    def test_missing_secret_is_401_unauthorized(self, anon_client, fixture_bytes):
        response = post_render(anon_client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 401
        assert "UNAUTHORIZED" in response.text

    def test_wrong_secret_is_401_unauthorized(self, anon_client, fixture_bytes):
        anon_client.headers["X-Worker-Secret"] = "not-the-secret"
        response = post_render(anon_client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 401


class TestRenderSinglePage:
    def test_returns_multipart_mixed_with_metadata_first(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_paystub.pdf"))

        assert response.status_code == 200
        parts = mixed_parts(response)
        assert parts[0]["name"] == "metadata"
        assert parts[0]["contentType"].startswith("application/json")

    def test_metadata_carries_the_contract_page_shape(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_paystub.pdf"))

        metadata = json.loads(mixed_parts(response)[0]["content"])
        assert [page["pageIndex"] for page in metadata["pages"]] == [0]
        page = metadata["pages"][0]
        # US Letter, rotation-0 canonical dims; 200 DPI default => 1700x2200 px.
        assert page == {
            "pageIndex": 0,
            "widthPt": 612.0,
            "heightPt": 792.0,
            "rotation": 0,
            "dpi": 200,
            "widthPx": 1700,
            "heightPx": 2200,
            "pngPart": "page-0",
        }

    def test_metadata_carries_the_worker_version_block(self, client, fixture_bytes, mixed_parts):
        from importlib.metadata import version as installed_version

        from pragmaticds_docengine_worker import __version__

        response = post_render(client, fixture_bytes("native_paystub.pdf"))

        metadata = json.loads(mixed_parts(response)[0]["content"])
        assert metadata["worker"]["version"] == __version__
        assert metadata["worker"]["libraries"]["pypdfium2"] == installed_version("pypdfium2")

    def test_png_part_is_named_by_pngPart_and_is_a_real_png(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_paystub.pdf"))

        parts = mixed_parts(response)
        assert len(parts) == 2
        png = parts[1]
        assert png["name"] == "page-0"
        assert png["contentType"] == "image/png"
        assert png["content"].startswith(PNG_MAGIC)

    def test_png_pixel_dimensions_match_the_metadata(self, client, fixture_bytes, mixed_parts):
        import io

        from PIL import Image

        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"dpi": 100})

        parts = mixed_parts(response)
        page = json.loads(parts[0]["content"])["pages"][0]
        image = Image.open(io.BytesIO(parts[1]["content"]))
        assert (page["widthPx"], page["heightPx"]) == image.size == (850, 1100)
        assert page["dpi"] == 100


class TestPageSelection:
    def test_absent_pages_means_all_pages(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_multipage.pdf"))

        parts = mixed_parts(response)
        metadata = json.loads(parts[0]["content"])
        assert [page["pageIndex"] for page in metadata["pages"]] == [0, 1, 2]
        assert [part["name"] for part in parts[1:]] == ["page-0", "page-1", "page-2"]

    def test_empty_pages_list_means_all_pages(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_multipage.pdf"), {"pages": []})

        metadata = json.loads(mixed_parts(response)[0]["content"])
        assert [page["pageIndex"] for page in metadata["pages"]] == [0, 1, 2]

    def test_selected_pages_render_in_request_order(self, client, fixture_bytes, mixed_parts):
        response = post_render(client, fixture_bytes("native_multipage.pdf"), {"pages": [2, 0]})

        parts = mixed_parts(response)
        metadata = json.loads(parts[0]["content"])
        assert [page["pageIndex"] for page in metadata["pages"]] == [2, 0]
        assert [part["name"] for part in parts[1:]] == ["page-2", "page-0"]

    def test_out_of_range_page_is_PAGE_OUT_OF_RANGE(self, client, fixture_bytes):
        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"pages": [1]})

        assert response.status_code == 400
        assert error_code(response) == "PAGE_OUT_OF_RANGE"

    def test_negative_page_is_PAGE_OUT_OF_RANGE(self, client, fixture_bytes):
        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"pages": [-1]})

        assert response.status_code == 400
        assert error_code(response) == "PAGE_OUT_OF_RANGE"


class TestDpiValidation:
    def test_dpi_below_72_is_INVALID_REQUEST(self, client, fixture_bytes):
        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"dpi": 71})

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"

    def test_dpi_above_600_is_INVALID_REQUEST(self, client, fixture_bytes):
        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"dpi": 601})

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"

    def test_boundary_dpi_values_are_accepted(self, client, fixture_bytes, mixed_parts):
        for dpi in (72, 600):
            response = post_render(client, fixture_bytes("native_paystub.pdf"), {"dpi": dpi})

            assert response.status_code == 200
            assert json.loads(mixed_parts(response)[0]["content"])["pages"][0]["dpi"] == dpi

    def test_non_integer_dpi_is_INVALID_REQUEST(self, client, fixture_bytes):
        response = post_render(client, fixture_bytes("native_paystub.pdf"), {"dpi": "high"})

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"


class TestMalformedInput:
    def test_garbage_bytes_are_CORRUPT_PDF(self, client):
        response = post_render(client, b"this is not a pdf at all" * 40)

        assert response.status_code == 400
        assert error_code(response) == "CORRUPT_PDF"

    def test_error_detail_never_echoes_document_bytes(self, client):
        marker = b"SENSITIVE-DOC-CONTENT-MARKER"
        response = post_render(client, marker * 10)

        assert response.status_code == 400
        assert "SENSITIVE" not in response.text

    def test_unparseable_request_json_is_INVALID_REQUEST(self, client, fixture_bytes):
        response = client.post(
            "/v1/render",
            files={"file": ("upload.bin", fixture_bytes("native_paystub.pdf"), "application/pdf")},
            data={"request": "{not json"},
        )

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"

    def test_missing_file_part_is_INVALID_REQUEST(self, client):
        response = client.post("/v1/render", data={"request": "{}"})

        assert response.status_code == 400
        assert error_code(response) == "INVALID_REQUEST"


class TestRotatedPages:
    """pypdfium2's page.get_size() is rotation-AWARE; the contract wants ROTATION-0 dims
    with the /Rotate value reported separately, pixels rendered AT the rotation."""

    def test_rotate_90_reports_rotation0_dims_and_rotated_pixels(
        self, client, fixture_bytes, mixed_parts, rotate_pdf
    ):
        rotated = rotate_pdf(fixture_bytes("native_paystub.pdf"), 90)

        response = post_render(client, rotated)

        page = json.loads(mixed_parts(response)[0]["content"])["pages"][0]
        assert page["rotation"] == 90
        assert (page["widthPt"], page["heightPt"]) == (612.0, 792.0)  # rotation-0, NOT swapped
        assert (page["widthPx"], page["heightPx"]) == (2200, 1700)  # raster IS rotated

    def test_rotate_180_keeps_dims_and_reports_rotation(
        self, client, fixture_bytes, mixed_parts, rotate_pdf
    ):
        rotated = rotate_pdf(fixture_bytes("native_paystub.pdf"), 180)

        response = post_render(client, rotated)

        page = json.loads(mixed_parts(response)[0]["content"])["pages"][0]
        assert page["rotation"] == 180
        assert (page["widthPt"], page["heightPt"]) == (612.0, 792.0)
        assert (page["widthPx"], page["heightPx"]) == (1700, 2200)

    def test_rotate_270_reports_rotation0_dims_and_rotated_pixels(
        self, client, fixture_bytes, mixed_parts, rotate_pdf
    ):
        rotated = rotate_pdf(fixture_bytes("native_paystub.pdf"), 270)

        response = post_render(client, rotated)

        page = json.loads(mixed_parts(response)[0]["content"])["pages"][0]
        assert page["rotation"] == 270
        assert (page["widthPt"], page["heightPt"]) == (612.0, 792.0)
        assert (page["widthPx"], page["heightPx"]) == (2200, 1700)
