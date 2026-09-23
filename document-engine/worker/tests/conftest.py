"""Shared worker test fixtures: an authenticated client, the generated fixture
set, and the multipart/mixed decoder every /v1/render caller needs.

Also the one gate on golden REFRESH, which lives here because all three stage
suites (render, ocr, layout) register `--update-goldens` independently and the
rule must not be three rules. See `pytest_configure` below.
"""

import json
import os
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"
TEST_SECRET = "test-worker-secret"

#: Set only by the `test` stage of worker/Dockerfile. Its presence is what makes a
#: golden refresh legitimate.
GOLDEN_ENV_VAR = "PDS_GOLDEN_ENV"
GOLDEN_ENV_VALUE = "worker-image"

#: The one sanctioned way to refresh a golden. --platform is pinned because an
#: arm64 laptop and an amd64 runner are not the same environment either.
GOLDEN_COMMAND = (
    "docker build --platform linux/amd64 --target test -t pds-worker-test worker/\n"
    '    docker run --rm --platform linux/amd64 -v "$PWD:/repo" pds-worker-test \\\n'
    "        pytest worker/tests/render worker/tests/text --update-goldens"
)


def in_golden_environment() -> bool:
    """True inside the worker's own test image — the environment goldens describe."""
    return os.environ.get(GOLDEN_ENV_VAR) == GOLDEN_ENV_VALUE


def pytest_configure(config):
    """Refuse to rewrite goldens outside the image they are pinned to.

    The goldens carry the sha256 of PDFium-rendered PNGs and the literal text an OCR
    engine returned. Both are properties of an ENVIRONMENT as much as of the code: a
    macOS PDFium and a Debian one disagree on the raster, and tesseract 5.3.4 and
    5.5.0 disagree on the words. So `--update-goldens` on a laptop produces files
    that are correct there and wrong in CI — and the diff looks exactly like an
    intentional refresh, which is what makes it dangerous rather than merely wrong.

    Failing closed here costs one clear error message. The alternative costs a
    reviewer working out why a green local run turned red on the runner.
    """
    if config.getoption("--update-goldens", default=False) and not in_golden_environment():
        raise pytest.UsageError(
            "--update-goldens outside the worker test image would pin this machine's "
            "renderer and OCR engine, which CI does not have.\n\n"
            f"    {GOLDEN_COMMAND}\n"
        )


@pytest.fixture()
def client(monkeypatch):
    """Client with the shared secret configured AND presented."""
    monkeypatch.setenv("DOCENGINE_WORKER_SECRET", TEST_SECRET)
    from pragmaticds_docengine_worker.api import app

    test_client = TestClient(app)
    test_client.headers["X-Worker-Secret"] = TEST_SECRET
    return test_client


@pytest.fixture()
def anon_client(monkeypatch):
    """Secret configured but NOT presented — must be rejected."""
    monkeypatch.setenv("DOCENGINE_WORKER_SECRET", TEST_SECRET)
    from pragmaticds_docengine_worker.api import app

    return TestClient(app)


def fixture_bytes(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def fixture_truth(name: str) -> dict:
    return json.loads((FIXTURES / "truth" / name).read_text())


def parse_multipart_mixed(body: bytes, content_type: str) -> list[dict]:
    """Decode a multipart/mixed response into ordered parts.

    Returns [{"name", "contentType", "content"}] in wire order. Deliberately strict:
    a malformed body should fail the test, not be papered over.
    """
    match = re.search(r'boundary="?([^";]+)"?', content_type)
    assert match, f"no boundary in content-type: {content_type}"
    delimiter = b"--" + match.group(1).encode("ascii")

    chunks = body.split(delimiter)
    assert len(chunks) >= 3, "multipart body has no parts"
    assert chunks[-1].strip() == b"--", "multipart body is not terminated"

    parts = []
    for chunk in chunks[1:-1]:
        assert chunk.startswith(b"\r\n"), "part does not start with CRLF"
        header_block, _, payload = chunk[2:].partition(b"\r\n\r\n")
        assert payload.endswith(b"\r\n"), "part payload not CRLF-terminated"
        headers = {}
        for line in header_block.split(b"\r\n"):
            key, _, value = line.decode("latin-1").partition(":")
            headers[key.strip().lower()] = value.strip()
        name_match = re.search(r'name="?([^";]+)"?', headers.get("content-disposition", ""))
        parts.append(
            {
                "name": name_match.group(1) if name_match else None,
                "contentType": headers.get("content-type"),
                "content": payload[:-2],
            }
        )
    return parts


@pytest.fixture()
def mixed_parts():
    """Decode a /v1/render multipart/mixed response. Shared: the render suite is
    no longer the only caller — the image suite renders too."""

    def decode(response) -> list[dict]:
        assert response.headers["content-type"].startswith("multipart/mixed"), (
            f"expected multipart/mixed, got {response.headers['content-type']}"
        )
        return parse_multipart_mixed(response.content, response.headers["content-type"])

    return decode
