"""/v1/text — native text extraction with word boxes and the text-layer verdict.

Request: multipart/form-data with `file` (PDF bytes) + `request` JSON part
({"pages": []}). Response: JSON per docs/WORKER_CONTRACT.md. The multipart request
parsing is shared with render_routes (see the note there on python-multipart).
"""

from fastapi import APIRouter, Depends, Request

from pragmaticds_docengine_worker.render_routes import read_multipart_request
from pragmaticds_docengine_worker.text import extract_text, parse_text_request
from pragmaticds_docengine_worker.wire import require_worker_secret, worker_block
from pragmaticds_docengine_worker.work import run_stage_work

router = APIRouter(dependencies=[Depends(require_worker_secret)])


@router.post("/v1/text")
async def text(request: Request) -> dict:
    parts = await read_multipart_request(request)
    pages = parse_text_request(parts.get("request"))

    extracted = await run_stage_work(request, "text", extract_text, parts["file"], pages)

    return {
        "worker": worker_block("pdfplumber"),
        "pages": [page.payload() for page in extracted],
    }
