"""/v1/burst core — a logical document emitted as its own PDF (Phase A, design
docs/superpowers/specs/2026-08-22-ai-document-splitting-design.md §9).

The engine resolves a logical document to an ORDERED sequence of (source part,
0-based page index) — a document may legally span source files, and its pages
may interleave between them — and asks this module for one PDF holding exactly
those pages, in that order. All PDF manipulation stays here: the engine never
grows a PDF library of its own for cross-source concatenation.

A PDF source contributes PAGE-SUBSET COPIES via pypdf: the original page
objects — content streams, fonts, images — are carried over verbatim, never
re-rendered, so the burst is fidelity-faithful by construction. Re-ingesting a
burst must yield the same span text and boxes those pages had in the original
package; the engine-side fidelity IT pins that.

An image source has no PDF to copy from. Its ORIGINAL pixels are wrapped one
frame per page — not the render blob, for the same reason source.py gives: the
image already IS the page. The PDF page box is derived from the same
nominal-dpi rule the canonical frame uses, so the wrapped page and the
persisted page rows agree on geometry.

Request semantics are fail-closed, deliberately stricter than /v1/render's,
because the caller is our own engine and not a person:

  * An EMPTY sequence is an error, never "all pages". A burst is always "this
    document's pages"; an accidental empty list silently returning an entire
    source file would hand a consumer the whole package where one document was
    intended — a data-leak-shaped bug that must die loudly.
  * A DUPLICATE (part, index) is an error, never collapsed.
    `logical_document_page` cannot link the same page twice, so a duplicate
    here is an engine bug.

No cover page, ever (owner decision 2026-08-22): an injected sheet would shift
page offsets forever and break the re-ingest fidelity gate. Document identity
travels in the engine's response headers, not in pages.
"""

import io
import json
import logging

import pypdf

from pragmaticds_docengine_worker.source import looks_like_image, open_image_document
from pragmaticds_docengine_worker.wire import error

logger = logging.getLogger(__name__)


def parse_burst_request(raw: bytes | None) -> list[tuple[str, int]]:
    """Validate the `request` JSON part:

        {"pages": [{"part": "file-0", "index": 3}, ...]}

    Returns the ordered (part, index) sequence. Part names are engine-chosen
    constants ("file-0", "file-1", ...), never user content, so they may appear
    in error detail. Range is checked against each opened source."""
    if raw is None or raw == b"":
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_MISSING")
    try:
        payload = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_JSON") from None
    if not isinstance(payload, dict):
        raise error(400, "INVALID_REQUEST", reason="REQUEST_PART_NOT_OBJECT")

    entries = payload.get("pages")
    if not isinstance(entries, list):
        raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_OBJECT_LIST")
    if not entries:
        raise error(400, "INVALID_REQUEST", reason="PAGES_REQUIRED")

    sequence: list[tuple[str, int]] = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_OBJECT_LIST")
        part = entry.get("part")
        index = entry.get("index")
        if not isinstance(part, str) or not part:
            raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_OBJECT_LIST")
        if not isinstance(index, int) or isinstance(index, bool):
            raise error(400, "INVALID_REQUEST", reason="PAGES_NOT_OBJECT_LIST")
        sequence.append((part, index))

    if len(set(sequence)) != len(sequence):
        raise error(400, "INVALID_REQUEST", reason="DUPLICATE_PAGE_INDEX")
    return sequence


class _PdfSource:
    """One PDF part, opened once however many pages it contributes."""

    def __init__(self, pdf_bytes: bytes):
        try:
            self._reader = pypdf.PdfReader(io.BytesIO(pdf_bytes))
            if self._reader.is_encrypted and not self._reader.decrypt(""):
                # A true user password means the content is actually locked.
                # Ingestion would have rejected the file; unreadable is unreadable.
                raise error(400, "CORRUPT_PDF")
            self._page_count = len(self._reader.pages)
        except pypdf.errors.PyPdfError:
            raise error(400, "CORRUPT_PDF") from None

    def append_page(self, writer: pypdf.PdfWriter, part: str, index: int) -> None:
        if index < 0 or index >= self._page_count:
            raise error(
                400,
                "PAGE_OUT_OF_RANGE",
                partName=part,
                pageIndex=index,
                pageCount=self._page_count,
            )
        writer.add_page(self._reader.pages[index])

    def close(self) -> None:
        pass


class _ImageSource:
    """One image part; each requested frame becomes a wrapped single-page PDF.

    Pillow's PDF writer sizes a page as pixels * 72 / resolution; passing the
    frame's own nominal dpi reproduces the canonical page box exactly, so the
    wrapped page and the persisted page row agree on geometry per frame."""

    def __init__(self, image_bytes: bytes):
        self._document = open_image_document(image_bytes)

    def append_page(self, writer: pypdf.PdfWriter, part: str, index: int) -> None:
        if index < 0 or index >= len(self._document):
            raise error(
                400,
                "PAGE_OUT_OF_RANGE",
                partName=part,
                pageIndex=index,
                pageCount=len(self._document),
            )
        frame = self._document.page(index)
        wrapped = io.BytesIO()
        frame.image.save(wrapped, format="PDF", resolution=float(frame.dpi))
        writer.append(pypdf.PdfReader(wrapped))

    def close(self) -> None:
        self._document.close()


def burst_sources(parts: dict[str, bytes], sequence: list[tuple[str, int]]) -> bytes:
    """Assemble the output PDF: one page per sequence entry, in sequence order.

    Each distinct part is opened ONCE and sniffed from magic bytes, never from a
    declared type. A referenced part absent from the multipart body is the same
    contract error a missing `file` part is elsewhere."""
    sources: dict[str, _PdfSource | _ImageSource] = {}
    try:
        writer = pypdf.PdfWriter()
        for part, index in sequence:
            source = sources.get(part)
            if source is None:
                file_bytes = parts.get(part)
                if file_bytes is None:
                    raise error(
                        400, "INVALID_REQUEST", reason="FILE_PART_MISSING", partName=part
                    )
                source = (
                    _ImageSource(file_bytes)
                    if looks_like_image(file_bytes)
                    else _PdfSource(file_bytes)
                )
                sources[part] = source
            source.append_page(writer, part, index)
        try:
            buffer = io.BytesIO()
            writer.write(buffer)
        except Exception as exception:
            # A structurally damaged page that survived opening but not copying.
            # Log the exception CLASS only (messages can quote content) — the OCR
            # route learned this the hard way; every 5xx path follows it.
            logger.error("burst write failed exception=%s", type(exception).__name__)
            raise error(500, "BURST_FAILED", pageCount=len(sequence)) from None
        return buffer.getvalue()
    finally:
        for source in sources.values():
            source.close()
