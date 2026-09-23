"""Rulings from the OPTIONAL /v1/layout PDF part.

pdfplumber reports lines and rects in top-left points already — a straight read,
no conversion (geometry.py stays the only converter; nothing here touches
pixels). Rulings CONFIRM tables; they never create geometry, so every per-page
failure mode degrades to "no confirmation" rather than failing the layout:

 * page index beyond the attached file -> no rulings for that page (the spans
   are authoritative; the file is advisory);
 * page carrying /Rotate -> skipped: pdfplumber would report display-space
   coordinates there, and advisory data in the wrong frame is worse than none;
 * IMAGE source -> no rulings at all. There is no vector ink in a photograph to
   confirm anything with; a ruled table in a scan is pixels, which is the pixel
   detectors' job, not pdfplumber's. Quiet, like the /Rotate case — and not the
   place the undecodable-image error is raised either: render_rasters runs on the
   same bytes immediately after and reports CORRUPT_IMAGE loudly.

Only the file-level failure is loud: PDF bytes that do not open are CORRUPT_PDF —
the caller attached garbage and silence would hide it.
"""

import io

import pdfplumber

from pragmaticds_docengine_worker.layout.model import Rulings
from pragmaticds_docengine_worker.source import looks_like_image
from pragmaticds_docengine_worker.wire import error

#: A segment is axis-aligned when its off-axis drift stays under this.
_AXIS_TOLERANCE_PT = 0.5


def extract_rulings(file_bytes: bytes, page_indices: list[int]) -> dict[int, Rulings]:
    if looks_like_image(file_bytes):
        return {}
    try:
        pdf = pdfplumber.open(io.BytesIO(file_bytes))
    except Exception:
        raise error(400, "CORRUPT_PDF") from None

    rulings: dict[int, Rulings] = {}
    try:
        for index in page_indices:
            if index < 0 or index >= len(pdf.pages):
                continue
            page = pdf.pages[index]
            if int(page.rotation or 0) % 360 != 0:
                continue
            try:
                segments = list(page.lines) + _rect_edges(page.rects)
            except Exception:
                continue  # advisory data only — a broken page confirms nothing
            verticals = []
            horizontals = []
            for segment in segments:
                x0, x1 = float(segment["x0"]), float(segment["x1"])
                top, bottom = float(segment["top"]), float(segment["bottom"])
                if abs(x0 - x1) <= _AXIS_TOLERANCE_PT:
                    verticals.append(((x0 + x1) / 2.0, min(top, bottom), max(top, bottom)))
                elif abs(top - bottom) <= _AXIS_TOLERANCE_PT:
                    horizontals.append(((top + bottom) / 2.0, min(x0, x1), max(x0, x1)))
            rulings[index] = Rulings(
                verticals=tuple(verticals), horizontals=tuple(horizontals)
            )
    finally:
        pdf.close()
    return rulings


def _rect_edges(rects) -> list[dict]:
    """A drawn rectangle contributes its four edges as segments."""
    edges = []
    for rect in rects:
        x0, x1 = rect["x0"], rect["x1"]
        top, bottom = rect["top"], rect["bottom"]
        edges.append({"x0": x0, "x1": x0, "top": top, "bottom": bottom})
        edges.append({"x0": x1, "x1": x1, "top": top, "bottom": bottom})
        edges.append({"x0": x0, "x1": x1, "top": top, "bottom": top})
        edges.append({"x0": x0, "x1": x1, "top": bottom, "bottom": bottom})
    return edges
