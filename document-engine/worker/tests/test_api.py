"""Tests for the parser worker's Phase 0 surface.

/version is not cosmetic. Its output is persisted to
processing_stage.parser_versions, which is how a parse becomes reproducible and
how a golden-file regression gets attributed to a library upgrade. So the tests
that matter are the ones proving it reads reality rather than reporting
hardcoded strings.
"""

from importlib.metadata import version as installed_version

from fastapi.testclient import TestClient

from pragmaticds_docengine_worker.api import PARSER_LIBRARIES, app

client = TestClient(app)


def test_health_reports_ok():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_version_reports_the_worker_version():
    body = client.get("/version").json()

    assert "worker" in body
    assert body["worker"]


def test_version_reads_real_installed_versions():
    """A hardcoded version string would silently poison every parse record."""
    body = client.get("/version").json()

    assert body["libraries"]["fastapi"] == installed_version("fastapi")


def test_version_reports_real_versions_for_the_phase2_stack():
    """Phase 2 installs the parsing stack; /version must reflect the real environment."""
    body = client.get("/version").json()

    assert set(body["libraries"]) == set(PARSER_LIBRARIES)
    for library in ("pypdfium2", "pdfplumber", "pypdf"):
        assert body["libraries"][library], f"{library} installed but reported absent"


def test_an_absent_library_reports_null_never_a_fabricated_version():
    from pragmaticds_docengine_worker.api import _installed

    assert _installed("definitely-not-a-real-package") is None


def test_worker_declares_no_persistence_and_no_credentials():
    """The trust boundary, asserted rather than assumed (ARCHITECTURE.md 7)."""
    body = client.get("/version").json()

    assert body["stateless"] is True


def test_non_ascii_secret_header_is_401_not_500(monkeypatch):
    """Phase 2 review finding: hmac.compare_digest on str requires ASCII; a stray
    UTF-8 byte in the header crashed auth with 500. Garbage credentials are exactly
    what auth exists to reject — cleanly."""
    monkeypatch.setenv("DOCENGINE_WORKER_SECRET", "secret")
    from pragmaticds_docengine_worker.api import app
    from fastapi.testclient import TestClient

    # Raw non-ASCII bytes on the wire: Starlette latin-1-decodes them into a str
    # that str-mode compare_digest cannot digest. The client must send bytes —
    # httpx itself refuses to encode a non-ASCII str header.
    response = TestClient(app).post(
        "/v1/text",
        headers={b"X-Worker-Secret": "s\xe9cr\xe9t".encode("utf-8")},
        files={"file": ("x", b"y")},
    )

    assert response.status_code == 401
    assert response.json()["error"] == "UNAUTHORIZED"


def test_unknown_route_gets_the_contract_envelope(monkeypatch):
    """Phase 2 review (unverified lens, triaged manually and confirmed real):
    Starlette raises ITS OWN HTTPException for 404/405; a handler registered on
    FastAPI's subclass never sees it, so framework errors bypassed the envelope."""
    monkeypatch.setenv("DOCENGINE_WORKER_SECRET", "secret")
    from pragmaticds_docengine_worker.api import app
    from fastapi.testclient import TestClient

    response = TestClient(app).get("/v1/nope")

    assert response.status_code == 404
    body = response.json()
    assert body["error"] == "INVALID_REQUEST"
    assert "detail" in body
