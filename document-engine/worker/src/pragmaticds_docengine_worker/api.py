"""Parser worker API.

Stateless by construction: no database, no object-storage credentials, nothing
persisted, and no document content logged. Every endpoint is a pure function of
its request body. See ARCHITECTURE.md section 7 for why that boundary matters.

Phase 0 ships only the operational surface. Stage endpoints (/v1/render,
/v1/text, /v1/ocr, /v1/layout) arrive in Phase 2.
"""

import logging
from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as installed_version

from fastapi import FastAPI

from pragmaticds_docengine_worker import __version__

# The worker's own loggers emit numbers-only diagnostics at INFO (text.py: how many
# uncovered regions a MIXED candidate kept; ocr_routes: which page's ladder failed).
# uvicorn configures only ITS loggers and leaves the root without a handler, so
# anything below WARNING from this package was silently dropped. One handler on the
# package logger, no propagation — never a second copy through the root's last resort.
_package_log = logging.getLogger("pragmaticds_docengine_worker")
if not _package_log.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    _package_log.addHandler(_handler)
    _package_log.setLevel(logging.INFO)
    _package_log.propagate = False

#: Every library whose version is persisted to processing_stage.parser_versions.
#: A parse is only reproducible if we can say exactly what produced it, and a
#: golden-file regression is only attributable if we can diff these.
PARSER_LIBRARIES: tuple[str, ...] = (
    "pypdfium2",
    "pdfplumber",
    "pypdf",
    "rapidocr-onnxruntime",
    "pytesseract",
    "opencv-python-headless",
    "pillow",
    "numpy",
    "fastapi",
)

app = FastAPI(
    title="Pragmatic DS Document Engine — Parser Worker",
    version=__version__,
    description="Stateless document parsing stages. No persistence, no credentials.",
)

# The contract error envelope is TOP-LEVEL {"error": CODE, "detail": {...}}
# (WORKER_CONTRACT.md invariant 4). FastAPI's default handler wraps HTTPException
# detail as {"detail": ...} — one nesting level the contract does not have, which
# both implementation agents flagged independently. Unwrap here, once.
# Registered on STARLETTE's HTTPException: FastAPI's subclasses it, but the
# framework raises the PARENT for routing errors (404/405) — a handler on the
# subclass never sees those and they bypassed the envelope (Phase 2 review).
from starlette.exceptions import HTTPException as _HTTPException  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402


@app.exception_handler(_HTTPException)
async def _contract_error_envelope(request, exception: _HTTPException):
    detail = exception.detail
    if isinstance(detail, dict) and "error" in detail:
        body = {"error": detail["error"], "detail": detail.get("detail", {})}
    else:
        # Framework-raised HTTPExceptions (404 route miss, 405...) — still the
        # contract shape, still nothing request-derived in the body.
        body = {"error": "INVALID_REQUEST", "detail": {"status": exception.status_code}}
    return JSONResponse(status_code=exception.status_code, content=body)


# Stage routers live in their own modules so the render/text and OCR work can evolve
# independently; this file owns only the operational surface (/health, /version).
from pragmaticds_docengine_worker.render_routes import router as _render_router  # noqa: E402
from pragmaticds_docengine_worker.text_routes import router as _text_router  # noqa: E402
from pragmaticds_docengine_worker.ocr_routes import router as _ocr_router  # noqa: E402
from pragmaticds_docengine_worker.layout_routes import router as _layout_router  # noqa: E402
from pragmaticds_docengine_worker.burst_routes import router as _burst_router  # noqa: E402

app.include_router(_render_router)
app.include_router(_text_router)
app.include_router(_ocr_router)
app.include_router(_layout_router)
app.include_router(_burst_router)


def _installed(name: str) -> str | None:
    """Real installed version, or None. Never a fabricated string."""
    try:
        return installed_version(name)
    except PackageNotFoundError:
        return None


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/version")
def version() -> dict:
    return {
        "worker": __version__,
        "stateless": True,
        "libraries": {name: _installed(name) for name in PARSER_LIBRARIES},
    }
