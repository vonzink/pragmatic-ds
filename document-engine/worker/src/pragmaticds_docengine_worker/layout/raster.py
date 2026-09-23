"""Page rasters for the /v1/layout pixel path (Spec 3).

Layout Boxes stay canonical (PDF points, top-left, rotation-0, 0.1pt) — but the
checkbox/signature detectors need PIXELS, so /v1/layout renders the requested
pages internally when the caller attached the PDF. Rendering mirrors /v1/render
(render.py): pypdfium2, display-space rasters (pixels are rendered AT the page
/Rotate — what a viewer shows), rotation-0 page dims derived by un-swapping.

Detector boxes measured on `image` are display-space PIXELS and must come back
through px_box_to_pt(box, dpi) plus unrotate_box(...) when rotation != 0 before
they may join an Element (geometry.py owns those conversions; its tests are
hand-computed). This module renders and carries — it never converts.

An IMAGE source needs no rendering: it IS the raster. It is nevertheless RESAMPLED
to DETECTOR_DPI here, and this is the ONE place in the pipeline an uploaded image's
pixels are resampled. The reason is this module's contract, not convenience — its
whole job is "pixels at DETECTOR_DPI", and the T3/T4 detectors are tuned to that
scale, so handing them a 367-DPI phone photo would silently move every threshold.
These rasters never leave the worker; the page raster /v1/render persists and OCR
reads keeps the upload's own pixels untouched.
"""

from dataclasses import dataclass

import pypdfium2 as pdfium
from PIL import Image

from pragmaticds_docengine_worker.render import rotation0_dims
from pragmaticds_docengine_worker.source import looks_like_image, open_image_document
from pragmaticds_docengine_worker.wire import error

#: The pixel-path render resolution — the contract's render convention
#: (render.py DEFAULT_DPI). The T3/T4 detectors are tuned against it.
DETECTOR_DPI = 200


@dataclass(frozen=True)
class PageRaster:
    """One rendered page for the pixel detectors. `image` is the display-space
    raster (rendered AT `rotation`); `width_pt`/`height_pt` are the ROTATION-0
    canonical page dims (what unrotate_box needs)."""

    page_index: int
    image: Image.Image
    dpi: int
    rotation: int
    width_pt: float
    height_pt: float


def render_rasters(file_bytes: bytes, page_indexes: list[int]) -> dict[int, PageRaster]:
    """Render each requested page at DETECTOR_DPI.

    Failure stance matches the rest of /v1/layout's file handling: bytes that do
    not open are CORRUPT_PDF or CORRUPT_IMAGE by source kind (loud — the caller
    attached garbage); a page index beyond the file renders nothing for that page —
    the ABSENT key is the per-page availability signal the T3/T4 detectors key
    their notImplemented retirement on (the spans are authoritative, the file is
    advisory — a raster-less page must never look like the pixel path looked).
    """
    if looks_like_image(file_bytes):
        return _image_rasters(file_bytes, page_indexes)
    try:
        document = pdfium.PdfDocument(file_bytes)
    except pdfium.PdfiumError:
        raise error(400, "CORRUPT_PDF") from None
    try:
        rasters: dict[int, PageRaster] = {}
        for index in page_indexes:
            if index < 0 or index >= len(document):
                continue
            page = document[index]
            rotation = page.get_rotation()
            width_pt, height_pt = rotation0_dims(page.get_size(), rotation)
            image = page.render(scale=DETECTOR_DPI / 72.0).to_pil()
            rasters[index] = PageRaster(
                page_index=index,
                image=image,
                dpi=DETECTOR_DPI,
                rotation=rotation,
                width_pt=width_pt,
                height_pt=height_pt,
            )
        return rasters
    finally:
        document.close()


def _image_rasters(image_bytes: bytes, page_indexes: list[int]) -> dict[int, PageRaster]:
    """Image pages at DETECTOR_DPI. `rotation` is 0 by construction (source.py bakes
    EXIF orientation into the pixels), so detector boxes need only px_box_to_pt."""
    with open_image_document(image_bytes) as document:
        rasters: dict[int, PageRaster] = {}
        for index in page_indexes:
            if index < 0 or index >= len(document):
                continue
            page = document.page(index)
            target = (
                max(1, round(page.width_pt * DETECTOR_DPI / 72.0)),
                max(1, round(page.height_pt * DETECTOR_DPI / 72.0)),
            )
            image = (
                page.image
                if target == page.image.size
                else page.image.resize(target, Image.Resampling.LANCZOS)
            )
            rasters[index] = PageRaster(
                page_index=index,
                image=image,
                dpi=DETECTOR_DPI,
                rotation=page.rotation,
                width_pt=page.width_pt,
                height_pt=page.height_pt,
            )
        return rasters
