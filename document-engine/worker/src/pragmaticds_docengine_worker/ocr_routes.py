"""POST /v1/ocr — OCR of a rendered page raster (docs/WORKER_CONTRACT.md).

Owns only wire concerns: multipart parsing, request validation, the error envelope,
and the worker version block. The ladder itself lives in ocr/service.py. Error
detail never contains document text, file names, or byte content — stable codes and
non-sensitive numbers only.
"""

import io
import json
import logging

from fastapi import APIRouter, Depends, Request
from PIL import Image, UnidentifiedImageError
from starlette.exceptions import HTTPException

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.ocr.service import run_ocr_ladder
from pragmaticds_docengine_worker.wire import error, require_worker_secret, worker_block
from pragmaticds_docengine_worker.work import run_stage_work

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(require_worker_secret)])

#: Contract example pins exactly these two in the /v1/ocr worker block.
_OCR_LIBRARIES = ("rapidocr-onnxruntime", "pytesseract")


def _require_number(payload: dict, field: str) -> float:
    value = payload.get(field)
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
        raise error(400, "INVALID_REQUEST", field=field)
    return float(value)


def _parse_regions(payload: dict) -> list[Box]:
    regions = payload.get("regions", [])
    if not isinstance(regions, list):
        raise error(400, "INVALID_REQUEST", field="regions")
    parsed = []
    for region in regions:
        if not isinstance(region, dict):
            raise error(400, "INVALID_REQUEST", field="regions")
        try:
            parsed.append(
                Box(
                    float(region["x"]),
                    float(region["y"]),
                    float(region["width"]),
                    float(region["height"]),
                )
            )
        except (KeyError, TypeError, ValueError):
            raise error(400, "INVALID_REQUEST", field="regions")
    return parsed


@router.post("/v1/ocr")
async def ocr(http_request: Request) -> dict:
    try:
        form = await http_request.form()
    except Exception:
        raise error(400, "INVALID_REQUEST", part="form")

    upload = form.get("file")
    request_raw = form.get("request")
    if upload is None or request_raw is None or isinstance(request_raw, bytes):
        raise error(400, "INVALID_REQUEST", part="file" if upload is None else "request")

    try:
        payload = json.loads(request_raw if isinstance(request_raw, str) else "")
    except (json.JSONDecodeError, TypeError):
        raise error(400, "INVALID_REQUEST", part="request")
    if not isinstance(payload, dict):
        raise error(400, "INVALID_REQUEST", part="request")

    page_index = payload.get("pageIndex")
    if not isinstance(page_index, int) or isinstance(page_index, bool) or page_index < 0:
        raise error(400, "INVALID_REQUEST", field="pageIndex")
    width_pt = _require_number(payload, "widthPt")
    height_pt = _require_number(payload, "heightPt")
    dpi = payload.get("dpi")
    if not isinstance(dpi, int) or isinstance(dpi, bool) or not 1 <= dpi <= 1200:
        raise error(400, "INVALID_REQUEST", field="dpi")
    regions = _parse_regions(payload)
    # The page /Rotate the raster was rendered at (render emits display-space pixels).
    # Absent means 0 — but a page whose render metadata said otherwise MUST send it,
    # or OCR boxes land a quarter-turn out of the page frame (Phase 2 review, critical).
    rotation = payload.get("rotation", 0)
    if rotation not in (0, 90, 180, 270):
        raise error(400, "INVALID_REQUEST", field="rotation")

    file_bytes = await upload.read() if hasattr(upload, "read") else None
    if not file_bytes:
        raise error(400, "INVALID_REQUEST", part="file")
    try:
        image = Image.open(io.BytesIO(file_bytes))
        image.load()
    except (UnidentifiedImageError, OSError):
        raise error(400, "INVALID_REQUEST", part="file")

    try:
        body = await run_stage_work(
            http_request,
            "ocr",
            run_ocr_ladder,
            image,
            page_index=page_index,
            width_pt=width_pt,
            height_pt=height_pt,
            dpi=dpi,
            regions=regions,
            page_rotate=rotation,
        )
    except HTTPException:
        # CALLER_GONE from the gate: the caller hung up, there is nobody to tell.
        raise
    except Exception as exception:
        # A worker 5xx means the PROCESS failed; a hard page is a 200 with gates shown.
        # Log the exception CLASS (never its message — messages can quote content):
        # a silent 500 made a live-only failure undiagnosable for a full session.
        logger.error(
            "ocr ladder failed pageIndex=%s exception=%s",
            page_index,
            type(exception).__name__,
        )
        raise error(500, "OCR_FAILED")

    return {"worker": worker_block(*_OCR_LIBRARIES), **body}
