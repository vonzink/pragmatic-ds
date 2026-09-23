"""Render-test conftest: registers --update-goldens and the multipart/mixed decoder.

The flag lives HERE (the render/text owner's subpackage) rather than in the shared
worker/tests/conftest.py, per Phase-2 file ownership. pytest only registers options
from conftests loaded at startup — conftests at or above the *initial args* — so a
plain `pytest` run never sees the flag (and never rewrites goldens). Refreshing them
requires naming this directory explicitly, AND running in the worker's own test
image, because these goldens pin a PDFium raster's sha256:

    docker build --platform linux/amd64 --target test -t pds-worker-test worker/
    docker run --rm --platform linux/amd64 -v "$PWD:/repo" pds-worker-test \\
        pytest worker/tests/render worker/tests/text --update-goldens

Two fail-closed guards, not one. `pytest worker/tests --update-goldens` is rejected
as an unrecognised argument (verified empirically), and worker/tests/conftest.py
refuses the flag outside the image — a refresh on a laptop pins that laptop's
renderer and looks exactly like an intentional diff.
"""

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


def pytest_addoption(parser):
    # Sibling suites (ocr/) register the same flag; whichever conftest pytest loads
    # first wins and the second registration must not explode the whole run.
    try:
        parser.addoption(
            "--update-goldens",
            action="store_true",
            default=False,
            help="Rewrite golden files under worker/tests/golden/ from current output.",
        )
    except ValueError:
        pass


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
            f"golden {name} drifted — if intentional, refresh it IN THE WORKER IMAGE "
            "(see this module's docstring). Outside it, a raster sha256 differs for "
            "reasons that have nothing to do with the change under test."
        )

    return check
