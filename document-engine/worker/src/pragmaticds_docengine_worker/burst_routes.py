"""/v1/burst — multipart wiring (docs/WORKER_CONTRACT.md).

In:  multipart/form-data with one part per source file (engine-chosen names,
     `file-0`..`file-N` — PDF or image bytes, burst.py decides which from the
     magic bytes, never from a declared type) and a `request` JSON part carrying
     the ordered (part, index) page sequence.
Out: multipart/mixed — a `metadata` JSON part FIRST (contract invariant 5:
     every success carries the worker version block), then one application/pdf
     part named by the metadata's `pdfPart` value.

The response is multipart rather than a bare application/pdf body so the
version block travels the same way it does on every other stage endpoint —
in-band, parsed by the same client code — instead of inventing a header
convention for one route.
"""

import json

from fastapi import APIRouter, Depends, Request, Response

from pragmaticds_docengine_worker.burst import burst_sources, parse_burst_request
from pragmaticds_docengine_worker.render_routes import (
    multipart_mixed_response,
    read_multipart_request,
)
from pragmaticds_docengine_worker.wire import require_worker_secret, worker_block
from pragmaticds_docengine_worker.work import run_stage_work

router = APIRouter(dependencies=[Depends(require_worker_secret)])


@router.post("/v1/burst")
async def burst(request: Request) -> Response:
    parts = await read_multipart_request(request, require_file=False)
    sequence = parse_burst_request(parts.get("request"))

    pdf_bytes = await run_stage_work(request, "burst", burst_sources, parts, sequence)

    metadata = {
        "worker": worker_block("pypdf", "pillow"),
        "pageCount": len(sequence),
        "pdfPart": "pdf",
    }
    return multipart_mixed_response(
        [
            ("metadata", "application/json", json.dumps(metadata).encode("utf-8")),
            ("pdf", "application/pdf", pdf_bytes),
        ]
    )
