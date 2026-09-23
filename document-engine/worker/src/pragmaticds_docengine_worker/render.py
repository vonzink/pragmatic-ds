"""Page rasterisation for /v1/render — pypdfium2 (docs/WORKER_CONTRACT.md).

Canonical-space rule (contract invariant 1): `widthPt`/`heightPt` are the ROTATION-0
page dimensions. pypdfium2's `page.get_size()` is rotation-AWARE — on a /Rotate 90/270
page it returns the displayed (swapped) size — so the rotation-0 dims are derived by
un-swapping against the /Rotate value. Pixels, by contrast, are rendered AT the
rotation (what a viewer shows), which is exactly what the contract specifies.

An IMAGE source has nothing to rasterise — it already IS the page raster — so it
takes the second path here: transcode to the contract's PNG at the pixels that
arrived, with the canonical page box derived by source.py. See that module for why
images are carried end to end rather than converted to PDFs at ingest.

No document content ever reaches an error message: failures map to stable codes
(CORRUPT_PDF, CORRUPT_IMAGE, PAGE_OUT_OF_RANGE, INVALID_REQUEST, RENDER_FAILED) with
numeric detail only.
"""

import io
import json
from dataclasses import dataclass

import pypdfium2 as pdfium

from pragmaticds_docengine_worker.source import looks_like_image, open_image_document
from pragmaticds_docengine_worker.wire import error

DEFAULT_DPI = 200
MIN_DPI = 72
MAX_DPI = 600


@dataclass(frozen=True)
class RenderedPage:
    """One rendered page: contract metadata plus the PNG bytes."""

    page_index: int
    width_pt: float
    height_pt: float
    rotation: int
    dpi: int
    width_px: int
    height_px: int
    png: bytes

    @property
    def png_part(self) -> str:
        return f"page-{self.page_index}"

    def metadata(self) -> dict:
        return {
            "pageIndex": self.page_index,
            "widthPt": self.width_pt,
            "heightPt": self.height_pt,
            "rotation": self.rotation,
            "dpi": self.dpi,
            "widthPx": self.width_px,
            "heightPx": self.height_px,
            "pngPart": self.png_part,
        }


def parse_render_request(raw: bytes | None) -> tuple[list[int], int]:
    """Validate the `request` JSON part: {"pages": [...], "dpi": 72..600}."""
    if raw is None or raw == b"":
        payload = {}
    else:
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_JSON") from None
    if not isinstance(payload, dict):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_OBJECT")

    pages = payload.get("pages") or []
    if not isinstance(pages, list) or any(not isinstance(p, int) or isinstance(p, bool) for p in pages):
        raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_INT_LIST")

    dpi = payload.get("dpi", DEFAULT_DPI)
    if not isinstance(dpi, int) or isinstance(dpi, bool) or not MIN_DPI <= dpi <= MAX_DPI:
        raise error(400, "INVALID_REQUEST", reason="DPI_OUT_OF_RANGE", min=MIN_DPI, max=MAX_DPI)

    return pages, dpi


def resolve_page_indices(requested: list[int], page_count: int) -> list[int]:
    """Empty request = all pages. Out-of-range (either side) is a contract error.
    Duplicates collapse to the first occurrence so part names stay unique."""
    if not requested:
        return list(range(page_count))
    for index in requested:
        if index < 0 or index >= page_count:
            raise error(400, "PAGE_OUT_OF_RANGE", pageIndex=index, pageCount=page_count)
    return list(dict.fromkeys(requested))


def rotation0_dims(size: tuple[float, float], rotation: int) -> tuple[float, float]:
    """Un-swap pypdfium2's rotation-aware size back to the rotation-0 canonical box."""
    width, height = size
    if rotation in (90, 270):
        width, height = height, width
    return round(width, 1), round(height, 1)


def render_source(file_bytes: bytes, requested_pages: list[int], dpi: int) -> list[RenderedPage]:
    """Render whatever kind of source this is. The endpoint's only entry point."""
    if looks_like_image(file_bytes):
        return render_image(file_bytes, requested_pages)
    return render_pdf(file_bytes, requested_pages, dpi)


def render_image(image_bytes: bytes, requested_pages: list[int]) -> list[RenderedPage]:
    """Transcode an image document's pages to PNG at their own pixels.

    The requested DPI is deliberately IGNORED. A PDF page can be re-rendered at any
    resolution because it is drawing instructions; an image has exactly the pixels it
    has, and resampling them up to 200 (or the 300-DPI reprocess escalation) would
    fabricate a raster while degrading what OCR sees. The reported `dpi` is therefore
    the honest derived one — and it is the number OCR divides its pixel boxes by, so
    the page box and the raster stay in one frame.
    """
    with open_image_document(image_bytes) as document:
        indices = resolve_page_indices(requested_pages, len(document))
        rendered = []
        for index in indices:
            page = document.page(index)
            try:
                buffer = io.BytesIO()
                page.image.save(buffer, format="PNG")
            except Exception:
                raise error(500, "RENDER_FAILED", pageIndex=index) from None
            rendered.append(
                RenderedPage(
                    page_index=index,
                    width_pt=page.width_pt,
                    height_pt=page.height_pt,
                    rotation=page.rotation,
                    dpi=page.dpi,
                    width_px=page.image.width,
                    height_px=page.image.height,
                    png=buffer.getvalue(),
                )
            )
        return rendered


def render_pdf(pdf_bytes: bytes, requested_pages: list[int], dpi: int) -> list[RenderedPage]:
    try:
        document = pdfium.PdfDocument(pdf_bytes)
    except pdfium.PdfiumError:
        raise error(400, "CORRUPT_PDF") from None

    try:
        indices = resolve_page_indices(requested_pages, len(document))
        rendered = []
        for index in indices:
            page = document[index]
            rotation = page.get_rotation()
            width_pt, height_pt = rotation0_dims(page.get_size(), rotation)
            try:
                pil_image = page.render(scale=dpi / 72.0).to_pil()
                buffer = io.BytesIO()
                pil_image.save(buffer, format="PNG")
            except Exception:
                raise error(500, "RENDER_FAILED", pageIndex=index) from None
            rendered.append(
                RenderedPage(
                    page_index=index,
                    width_pt=width_pt,
                    height_pt=height_pt,
                    rotation=rotation,
                    dpi=dpi,
                    width_px=pil_image.width,
                    height_px=pil_image.height,
                    png=buffer.getvalue(),
                )
            )
        return rendered
    finally:
        document.close()
