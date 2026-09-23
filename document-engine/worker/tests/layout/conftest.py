"""Layout test suite plumbing.

Registers --update-goldens for THIS directory (same try/except pattern as the ocr
suite — whichever stage conftest pytest loads first wins), plus fixture loaders and
the span-building helpers every layout test shares.

Golden refresh for layout goldens, in the worker's own test image like every other
suite (worker/tests/conftest.py refuses the flag anywhere else). The layout goldens
happen to be geometry rather than raster bytes, so they survive a change of machine
— but "happens to" is not a rule anyone should have to know:

    docker build --platform linux/amd64 --target test -t pds-worker-test worker/
    docker run --rm --platform linux/amd64 -v "$PWD:/repo" pds-worker-test \\
        pytest worker/tests/layout --update-goldens
"""

import json
from pathlib import Path

import pytest

from pragmaticds_docengine_worker.geometry import Box

GOLDEN_DIR = Path(__file__).resolve().parents[1] / "golden"
FIXTURES = Path(__file__).resolve().parents[3] / "fixtures"


def pytest_addoption(parser):
    try:
        parser.addoption(
            "--update-goldens",
            action="store_true",
            default=False,
            help="Rewrite worker/tests/golden/ layout golden files from current output.",
        )
    except ValueError:
        # Another stage suite already registered the same flag in this invocation.
        pass


@pytest.fixture()
def fixture_bytes():
    def load(name: str) -> bytes:
        return (FIXTURES / name).read_bytes()

    return load


@pytest.fixture()
def fixture_truth():
    def load(name: str) -> dict:
        return json.loads((FIXTURES / "truth" / name).read_text())

    return load


@pytest.fixture()
def golden(request):
    """Compare a payload against a committed golden file; --update-goldens rewrites it."""

    def check(name: str, payload):
        path = GOLDEN_DIR / name
        rendered = json.dumps(payload, indent=2, sort_keys=True) + "\n"
        try:
            update = request.config.getoption("--update-goldens")
        except ValueError:
            update = False
        if update:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(rendered)
        assert path.exists(), f"golden {name} missing — refresh with --update-goldens"
        assert rendered == path.read_text(), (
            f"golden {name} drifted — refresh with --update-goldens if intentional"
        )

    return check


def make_span(ordinal, text, x, y, width, height, size=None, font=None):
    """A LayoutSpan the short way. size=None/font=None is exactly the OCR shape."""
    from pragmaticds_docengine_worker.layout.model import LayoutSpan

    return LayoutSpan(
        ordinal=ordinal,
        text=text,
        box=Box(x, y, width, height),
        font_size=size,
        font_name=font,
    )


def post_layout(client, request_json, pdf_bytes: bytes | None = None):
    """POST /v1/layout: multipart with a `request` part and an OPTIONAL `file` part."""
    files = {"request": ("request.json", json.dumps(request_json).encode(), "application/json")}
    if pdf_bytes is not None:
        files["file"] = ("upload.bin", pdf_bytes, "application/pdf")
    return client.post("/v1/layout", files=files)


def layout_request_from_text_response(text_body: dict) -> dict:
    """Build the /v1/layout request from a real /v1/text response — the production
    pipeline shape: spans come IN on the request, never re-derived."""
    return {
        "pages": [
            {
                "pageIndex": page["pageIndex"],
                "widthPt": page["widthPt"],
                "heightPt": page["heightPt"],
                "spans": page["spans"],
            }
            for page in text_body["pages"]
        ]
    }


@pytest.fixture()
def layout_of():
    return post_layout


@pytest.fixture()
def text_to_layout_request():
    """fixture name -> the /v1/layout request built from the REAL /v1/text output."""

    def build(client, fixture_name: str) -> dict:
        pdf = (FIXTURES / fixture_name).read_bytes()
        response = client.post(
            "/v1/text",
            files={"file": ("upload.bin", pdf, "application/pdf")},
            data={},
        )
        assert response.status_code == 200, response.text
        return layout_request_from_text_response(response.json())

    return build
