"""/v1/layout — layout elements clustered from caller-supplied spans (Phase 3),
plus the Spec 3 pixel-path plumbing.

Request: multipart/form-data with a `request` JSON part and an optional `file`
part (the source PDF). The engine now ALWAYS attaches it: pdfplumber
rects/lines confirm ruled tables on native ink, and the worker renders each
requested page internally (pypdfium2, DETECTOR_DPI) so the T3/T4 pixel
detectors will have pixels to look at. Spans still come IN on the request and
are never re-derived, so the endpoint behaves identically for native and
OCR'd pages. No rasters ever ride the wire in either direction.

The multipart body is parsed with the same stdlib approach as render_routes —
but locally, because read_multipart_request there REQUIRES a `file` part and
here the file is optional. (Consolidating both parsers into wire.py remains the
noted follow-up from Phase 2.)

Validation is strict: a malformed `pages`/`spans` shape is INVALID_REQUEST with
a stable reason. Detail carries indices and counts only — never text, fonts, or
anything else derived from document content.
"""

from email.parser import BytesParser
from email.policy import HTTP

import json

from fastapi import APIRouter, Depends, Request

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.layout.engine import ClusteringLayoutEngine, PageSpans
from pragmaticds_docengine_worker.layout.model import LayoutSpan
from pragmaticds_docengine_worker.layout.raster import render_rasters
from pragmaticds_docengine_worker.layout.rulings import extract_rulings
from pragmaticds_docengine_worker.wire import error, require_worker_secret, worker_block
from pragmaticds_docengine_worker.work import run_stage_work

router = APIRouter(dependencies=[Depends(require_worker_secret)])


async def _read_parts(request: Request) -> dict[str, bytes]:
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
    return parts


def parse_layout_request(raw: bytes | None) -> list[PageSpans]:
    if raw is None or raw == b"":
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_MISSING")
    try:
        payload = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_JSON") from None
    if not isinstance(payload, dict):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_OBJECT")
    pages = payload.get("pages")
    if not isinstance(pages, list):
        raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_LIST")
    return [_parse_page(entry, position) for position, entry in enumerate(pages)]


def _parse_page(entry, position: int) -> PageSpans:
    if not isinstance(entry, dict):
        raise error(400, "INVALID_REQUEST", reason="PAGE_NOT_OBJECT", page=position)
    page_index = entry.get("pageIndex")
    if not _is_int(page_index) or page_index < 0:
        raise error(400, "INVALID_REQUEST", reason="PAGE_INDEX_INVALID", page=position)
    width = entry.get("widthPt")
    height = entry.get("heightPt")
    if not _is_number(width) or not _is_number(height) or width <= 0 or height <= 0:
        raise error(400, "INVALID_REQUEST", reason="PAGE_DIMENSIONS_INVALID", page=position)
    raw_spans = entry.get("spans")
    if not isinstance(raw_spans, list):
        raise error(400, "INVALID_REQUEST", reason="SPANS_NOT_LIST", page=position)
    spans = [
        _parse_span(span, position, span_position)
        for span_position, span in enumerate(raw_spans)
    ]
    return PageSpans(
        page_index=page_index,
        width_pt=float(width),
        height_pt=float(height),
        spans=spans,
    )


def _parse_span(entry, page_position: int, span_position: int) -> LayoutSpan:
    def invalid(reason: str):
        # Indices only in detail — never span text or font names (content).
        return error(
            400, "INVALID_REQUEST", reason=reason, page=page_position, span=span_position
        )

    if not isinstance(entry, dict):
        raise invalid("SPAN_NOT_OBJECT")
    ordinal = entry.get("ordinal")
    if not _is_int(ordinal):
        raise invalid("SPAN_ORDINAL_INVALID")
    if not isinstance(entry.get("text"), str):
        raise invalid("SPAN_TEXT_INVALID")
    box = [entry.get(key) for key in ("x", "y", "width", "height")]
    if not all(_is_number(value) for value in box):
        raise invalid("SPAN_BOX_INVALID")
    font_size = entry.get("fontSize")
    if font_size is not None and not _is_number(font_size):
        raise invalid("SPAN_FONT_SIZE_INVALID")
    font_name = entry.get("fontName")
    if font_name is not None and not isinstance(font_name, str):
        raise invalid("SPAN_FONT_NAME_INVALID")
    return LayoutSpan(
        ordinal=ordinal,
        text=entry["text"],
        box=Box(*(float(value) for value in box)),
        font_size=float(font_size) if font_size is not None else None,
        font_name=font_name,
    )


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


@router.post("/v1/layout")
async def layout(request: Request) -> dict:
    parts = await _read_parts(request)
    pages = parse_layout_request(parts.get("request"))

    def analyze() -> list[dict]:
        rulings = {}
        rasters = {}
        if "file" in parts:
            page_indexes = [page.page_index for page in pages]
            rulings = extract_rulings(parts["file"], page_indexes)
            rasters = render_rasters(parts["file"], page_indexes)
        engine = ClusteringLayoutEngine()
        return [
            engine.analyze_page(page, rulings.get(page.page_index), rasters.get(page.page_index))
            for page in pages
        ]

    return {
        "worker": worker_block("pdfplumber", "pypdfium2"),
        "pages": await run_stage_work(request, "layout", analyze),
    }
