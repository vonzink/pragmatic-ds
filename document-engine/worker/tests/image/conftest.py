"""Image-suite fixtures: the synthetic documents and the four stage POST helpers.

The documents themselves live in `image_fixtures.py` so the test modules can reach
the drawing helpers directly (a conftest is not importable by name); this file only
wraps them as fixtures and owns the request shapes.
"""

import json

import pytest
from image_fixtures import blank_bytes, paystub_bytes, two_frame_tiff


@pytest.fixture()
def paystub_image():
    """`(bytes, truth_boxes_px)` for a paystub-shaped photo of a page."""
    return paystub_bytes


@pytest.fixture()
def blank_image():
    return blank_bytes


@pytest.fixture()
def multiframe_tiff():
    return two_frame_tiff


def post_render(client, file_bytes: bytes, request_json: dict | None = None):
    data = {} if request_json is None else {"request": json.dumps(request_json)}
    return client.post(
        "/v1/render",
        files={"file": ("upload.bin", file_bytes, "application/octet-stream")},
        data=data,
    )


def post_text(client, file_bytes: bytes, request_json: dict | None = None):
    data = {} if request_json is None else {"request": json.dumps(request_json)}
    return client.post(
        "/v1/text",
        files={"file": ("upload.bin", file_bytes, "application/octet-stream")},
        data=data,
    )


def post_ocr(client, png_bytes: bytes, request_json: dict):
    return client.post(
        "/v1/ocr",
        files={"file": ("page.png", png_bytes, "image/png")},
        data={"request": json.dumps(request_json)},
    )


def post_layout(client, file_bytes: bytes | None, request_json: dict):
    files = (
        {"file": ("upload.bin", file_bytes, "application/octet-stream")}
        if file_bytes is not None
        else None
    )
    return client.post("/v1/layout", files=files, data={"request": json.dumps(request_json)})


@pytest.fixture()
def render_of():
    return post_render


@pytest.fixture()
def text_of():
    return post_text


@pytest.fixture()
def ocr_of():
    return post_ocr


@pytest.fixture()
def layout_of():
    return post_layout
