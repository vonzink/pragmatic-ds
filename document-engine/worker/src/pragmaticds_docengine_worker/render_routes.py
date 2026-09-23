"""/v1/render — multipart wiring (docs/WORKER_CONTRACT.md).

In:  multipart/form-data with a `file` part (PDF **or image** bytes — render.py
     decides which from the magic bytes, never from a declared type) and a
     `request` JSON part.
Out: multipart/mixed — a `metadata` JSON part FIRST, then one image/png part per
     page, part name = the metadata's `pngPart` value.

Requests are parsed with the stdlib email parser rather than FastAPI's Form/File:
python-multipart is not in worker/requirements.txt (another module's file), and a
dependency the Docker image does not install must not be relied on in tests. The
parser is shared with text_routes; its natural home is wire.py once the Phase-2
branches merge (wire.py is owned by the OCR module this phase).
"""

import json
import uuid
from email.parser import BytesParser
from email.policy import HTTP

from fastapi import APIRouter, Depends, Request, Response

from pragmaticds_docengine_worker.render import parse_render_request, render_source
from pragmaticds_docengine_worker.wire import error, require_worker_secret, worker_block
from pragmaticds_docengine_worker.work import run_stage_work

router = APIRouter(dependencies=[Depends(require_worker_secret)])


async def read_multipart_request(request: Request, require_file: bool = True) -> dict[str, bytes]:
    """The contract's request envelope: named parts of a multipart/form-data body.

    Byte-exact for binary payloads (policy=HTTP keeps cte_type 8bit); anything that
    is not well-formed multipart with a `file` part is INVALID_REQUEST — never an
    echo of what was actually sent. `/v1/burst` alone passes ``require_file=False``:
    its sources arrive as `file-0`..`file-N` named by the request sequence, and it
    validates each referenced part itself.
    """
    content_type = request.headers.get("content-type", "")
    if not content_type.lower().startswith("multipart/"):
        raise error(400, "INVALID_REQUEST", reason="MULTIPART_FORM_DATA_REQUIRED")
    body = await request.body()
    message = BytesParser(policy=HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body
    )
    if not message.is_multipart():
        raise error(400, "INVALID_REQUEST", reason="MULTIPART_BODY_UNPARSEABLE")
    parts: dict[str, bytes] = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name:
            parts[str(name)] = part.get_payload(decode=True) or b""
    if require_file and "file" not in parts:
        raise error(400, "INVALID_REQUEST", reason="FILE_PART_MISSING")
    return parts


def multipart_mixed_response(parts: list[tuple[str, str, bytes]]) -> Response:
    """Assemble the contract's multipart/mixed response body by hand — the part
    order is meaningful (metadata first) and nothing in Starlette produces one."""
    boundary = "pds-worker-" + uuid.uuid4().hex
    delimiter = f"--{boundary}\r\n".encode("ascii")
    chunks = []
    for name, content_type, payload in parts:
        chunks.append(delimiter)
        chunks.append(
            (
                f'Content-Disposition: form-data; name="{name}"\r\n'
                f"Content-Type: {content_type}\r\n\r\n"
            ).encode("ascii")
        )
        chunks.append(payload)
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("ascii"))
    return Response(
        content=b"".join(chunks),
        media_type=f'multipart/mixed; boundary="{boundary}"',
    )


@router.post("/v1/render")
async def render(request: Request) -> Response:
    parts = await read_multipart_request(request)
    pages, dpi = parse_render_request(parts.get("request"))

    rendered = await run_stage_work(request, "render", render_source, parts["file"], pages, dpi)

    metadata = {
        "worker": worker_block("pypdfium2"),
        "pages": [page.metadata() for page in rendered],
    }
    response_parts = [("metadata", "application/json", json.dumps(metadata).encode("utf-8"))]
    response_parts += [(page.png_part, "image/png", page.png) for page in rendered]
    return multipart_mixed_response(response_parts)
