"""Text-test conftest. --update-goldens is REGISTERED in worker/tests/render/conftest.py
(one registration only — duplicating pytest_addoption is a collection error); here it is
merely read, with a False default so plain runs compare rather than rewrite."""

import json
from pathlib import Path

import pytest

GOLDEN_DIR = Path(__file__).resolve().parents[1] / "golden"
FIXTURES = Path(__file__).resolve().parents[3] / "fixtures"


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
def rotate_pdf():
    """A fixture PDF with /Rotate stamped on every page — built in memory, never on disk
    (fixtures/ is owned by the provenance manifest)."""

    def rotate(pdf_bytes: bytes, degrees: int) -> bytes:
        import io

        import pypdf

        writer = pypdf.PdfWriter()
        writer.append(pypdf.PdfReader(io.BytesIO(pdf_bytes)))
        for page in writer.pages:
            page.rotate(degrees)
        buffer = io.BytesIO()
        writer.write(buffer)
        return buffer.getvalue()

    return rotate


@pytest.fixture()
def golden(request):
    """Compare a payload against a committed golden file; --update-goldens rewrites it."""

    def check(name: str, payload):
        path = GOLDEN_DIR / name
        rendered = json.dumps(payload, indent=2, sort_keys=True) + "\n"
        if request.config.getoption("--update-goldens", default=False):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(rendered)
        assert path.exists(), f"golden {name} missing — refresh with --update-goldens"
        assert rendered == path.read_text(), (
            f"golden {name} drifted — refresh with --update-goldens if intentional"
        )

    return check


def post_text(client, pdf_bytes: bytes, request_json: dict | None = None):
    data = {} if request_json is None else {"request": json.dumps(request_json)}
    return client.post(
        "/v1/text",
        files={"file": ("upload.bin", pdf_bytes, "application/pdf")},
        data=data,
    )


@pytest.fixture()
def text_of():
    return post_text
