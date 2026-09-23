"""Shared wire-level pieces: auth, the error envelope, and the version block.

Contract: docs/WORKER_CONTRACT.md. Error detail NEVER contains document text, file names,
or byte content — stable codes plus non-sensitive numbers only.
"""

import hmac
import os
from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as installed_version

from fastapi import HTTPException, Request

from pragmaticds_docengine_worker import __version__

SECRET_ENV = "DOCENGINE_WORKER_SECRET"


def require_worker_secret(request: Request) -> None:
    """FastAPI dependency enforcing the shared secret on every stage endpoint.

    An empty configured secret REFUSES all stage traffic rather than allowing it —
    fail-closed, same posture as the tenancy layer.
    """
    configured = os.environ.get(SECRET_ENV, "")
    presented = request.headers.get("X-Worker-Secret", "")
    # Compare as bytes: compare_digest on str demands ASCII and CRASHES on a stray
    # UTF-8 byte (Phase 2 review) — garbage credentials must be rejected, not 500.
    if not configured or not hmac.compare_digest(
        configured.encode("utf-8"), presented.encode("utf-8", errors="replace")
    ):
        raise HTTPException(status_code=401, detail={"error": "UNAUTHORIZED"})


def error(status_code: int, code: str, **detail) -> HTTPException:
    """The contract error envelope. Callers pass only non-sensitive params."""
    return HTTPException(status_code=status_code, detail={"error": code, "detail": detail})


def worker_block(*libraries: str) -> dict:
    """The version block every success response carries (contract invariant 5)."""

    def version_of(name: str) -> str | None:
        try:
            return installed_version(name)
        except PackageNotFoundError:
            return None

    return {"version": __version__, "libraries": {name: version_of(name) for name in libraries}}
