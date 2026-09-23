"""How a source file becomes pages — the ONE place PDF and image diverge.

Ingestion accepts five MIME types: application/pdf, image/png, image/jpeg,
image/tiff and image/heic (ingestion/.../MimeSniffer.java). Every stage endpoint
used to assume the first one, so a borrower's phone photo of a paystub reached
/v1/render and died CORRUPT_PDF with zero pages persisted. Photographed and
screenshotted income documents are the common case, not an edge case.

The insight this module is built on: **for an image there is nothing to render.**
The image already IS the page raster — a one-page document (or an n-page one, for
a multi-frame TIFF) whose pixels arrived pre-rasterised. So the image path is not
a second rendering pipeline; it is the absence of one.

## Why the bytes are never converted to a PDF at ingest

Wrapping the JPEG in a one-page PDF would give every downstream stage a single
code path, and it was seriously considered. It loses on four counts:

  1. `source_file.sha256` is the identity of the uploaded bytes and the key
     duplicate detection joins on. Hashing a generated PDF makes identity depend
     on a PDF producer's timestamps and /ID, not on the document.
  2. `SignedDownloadController` serves those bytes back. A reviewer who uploaded
     a JPEG must get their JPEG, not a re-wrapped derivative, and the row's
     `content_type` must keep describing what is actually stored.
  3. The conversion would immediately be undone: the worker would rasterise the
     PDF back to pixels it already had, re-encoding a photo twice for nothing.
  4. It does not even remove the decision this module has to make — a converted
     PDF still needs a page size, which is the same derivation as `nominal_dpi`.

So the bytes stay pristine and the four PDF-shaped call sites (render.py, text.py,
layout/rulings.py, layout/raster.py) each ask this module which kind of source
they hold. That is four short branches against one silent change of identity.

## The canonical page box of an image

An image carries pixels, not points, and its embedded density metadata is
worthless (every phone writes 72 dpi regardless). The canonical frame still needs
a page size, and downstream point-space heuristics — label proximity, font size,
box padding — are all tuned on ~612x792 pt pages.

The rule: **assume the image is a capture of one page, and fit its long edge to
US Letter's long edge.** The implied scan resolution is then

    dpi = clamp(round(long_edge_px * 72 / 792), 72, 600)

and the page box is `px * 72 / dpi` on each axis. This is not arbitrary: for any
straight full-page scan it recovers the real resolution exactly (a 2550x3300
letter scan comes back as 300 DPI and 612.0 x 792.0 pt), and for a 12 MP phone
photo it yields 593.3 x 791.0 pt instead of the 3024 x 4032 pt page a naive
72-DPI assumption would produce. Points are derived FROM the rounded integer dpi,
so the `px -> pt` conversion OCR performs with that same integer is exact.

## Rotation

`ImagePage.rotation` is always 0, and that is a statement, not a default. EXIF
orientation is applied to the pixels at decode (`ImageOps.exif_transpose`), so the
raster this module hands out is the image as the borrower saw it — which makes it
the rotation-0 canonical frame by construction. There is no /Rotate on an image
and nothing downstream ever needs `unrotate_box` for one.

HEIC reaches the same place by a different road: it stores orientation in the
container (an `irot` box), not in EXIF, and libheif applies it while decoding, then
reports EXIF orientation 1 so a second transpose cannot double-rotate the page. So
`exif_transpose` is a no-op for HEIC rather than the correction it is for JPEG, and
the invariant holds either way.

## HEIC is decoded HERE and nowhere else

The JVM has no HEIF reader — ImageIO ships none and no permissive plugin exists —
so `ImageProbe` accepts a HEIC on its magic bytes without decoding it, and this
module is the ONLY decode gate for the format. A truncated HEIC therefore passes
upload and fails here, as CORRUPT_IMAGE at PARSE rather than at the door. Same
error code, later stage; see the ImageProbe javadoc, which states the same split
from the other side.

The decoder is `pi-heif` (libheif + libde265, decode only). Its sibling
`pillow-heif` can also encode and is GPLv2 for it, because encoding drags in x265 —
which is why no test in this repo can generate a HEIC, and why the fixtures are
constants. See docs/LICENSING.md 5.4.
"""

import io
from dataclasses import dataclass

import numpy as np
import pi_heif
from PIL import Image, ImageOps

from pragmaticds_docengine_worker.wire import error

# Teaches Pillow the HEIF family. Without it `Image.open` raises
# UnidentifiedImageError on an iPhone photo, so this belongs at import time: every
# entry point into this module decodes, and a lazily-registered opener would make
# the format work or not depending on call order.
pi_heif.register_heif_opener()

#: The nominal page an uploaded image is assumed to be a capture of: US Letter's
#: long edge in points. See the module docstring.
NOMINAL_PAGE_LONG_EDGE_PT = 792.0
MIN_IMAGE_DPI = 72
MAX_IMAGE_DPI = 600

#: EXACTLY the magics MimeSniffer.java accepts as images. Keeping the two lists
#: identical is deliberate: bytes ingestion called an image must take the image
#: path here, and bytes it did not must keep reporting the PDF failure they always
#: did rather than being re-diagnosed as a broken image.
_IMAGE_MAGICS = (
    b"\x89PNG\r\n\x1a\n",
    b"\xff\xd8\xff",
    b"II\x2a\x00",
    b"MM\x00\x2a",
)

#: HEIC is the one accepted format whose signal is not a prefix: it is ISO-BMFF, so
#: bytes 0..4 are a box length that varies with the file and the identity lives in
#: the `ftyp` box — 'ftyp' at 4..8, the major brand at 8..12.
_HEIF_FTYP_RANGE = slice(4, 8)
_HEIF_BRAND_RANGE = slice(8, 12)
_HEIF_FTYP = b"ftyp"

#: The MAJOR brand only, and the same four MimeSniffer.java matches. Compatible
#: brands are deliberately NOT scanned: an AVIF lists `mif1` among its own, and
#: libheif here has no AVIF decoder (libde265 alone), so scanning them would accept
#: at upload what cannot be decoded at parse.
_HEIF_BRANDS = (b"heic", b"heix", b"hevc", b"mif1")

#: Frames are a PAGE concept for exactly one format. Multi-page TIFF is what
#: scanners and fax gateways emit and dropping frames 1..n would be a silent
#: data loss; an animated PNG or GIF is one picture, not thirty pages, and neither
#: is a HEIC burst or Live Photo, which also carries several coded images.
_MULTI_PAGE_FORMATS = ("TIFF",)

#: Coarse resolution for the ink measurement, mirroring text.py's PDF path.
INK_CHECK_DPI = 36
INK_DARK_THRESHOLD = 230  # 0-255 grayscale; below = ink


def _looks_like_heif(file_bytes: bytes) -> bool:
    """True for an ISO-BMFF file whose MAJOR brand is one this worker can decode."""
    return (
        file_bytes[_HEIF_FTYP_RANGE] == _HEIF_FTYP
        and file_bytes[_HEIF_BRAND_RANGE] in _HEIF_BRANDS
    )


def looks_like_image(file_bytes: bytes | None) -> bool:
    """True when the leading bytes are one of the image formats ingestion accepts."""
    if not file_bytes:
        return False
    return any(file_bytes.startswith(magic) for magic in _IMAGE_MAGICS) or _looks_like_heif(
        file_bytes
    )


def nominal_dpi(width_px: int, height_px: int) -> int:
    """The assumed scan resolution of a full-page image capture (module docstring)."""
    long_edge = max(int(width_px), int(height_px))
    if long_edge <= 0:
        raise ValueError(f"image dimensions must be positive, got {width_px}x{height_px}")
    derived = round(long_edge * 72.0 / NOMINAL_PAGE_LONG_EDGE_PT)
    return max(MIN_IMAGE_DPI, min(MAX_IMAGE_DPI, int(derived)))


@dataclass(frozen=True)
class ImagePage:
    """One page of an image document: the raster plus its canonical page box.

    `image` is RGB and upright. `width_pt`/`height_pt` are the rotation-0 canonical
    dims; `rotation` is always 0 (module docstring).
    """

    page_index: int
    image: Image.Image
    dpi: int
    width_pt: float
    height_pt: float
    rotation: int = 0

    def ink_fraction(self) -> float:
        """Fraction of dark pixels at INK_CHECK_DPI — the same coarse measurement
        text.py makes on a PDF page, so the SCANNED/NONE split reads identically for
        both source kinds. Rounded to 4 decimals: the Java side persists it as
        numeric(5,4) blank_score."""
        target = (
            max(1, round(self.width_pt * INK_CHECK_DPI / 72.0)),
            max(1, round(self.height_pt * INK_CHECK_DPI / 72.0)),
        )
        coarse = self.image.convert("L").resize(target, Image.Resampling.BILINEAR)
        return round(float((np.asarray(coarse) < INK_DARK_THRESHOLD).mean()), 4)


class ImageDocument:
    """An uploaded image, seen as a document of one or more pages.

    Frames are decoded on demand rather than up front: a multi-page TIFF is a scan,
    and holding every frame of one in memory to answer "how many pages" is how a
    worker runs out of it.
    """

    def __init__(self, image: Image.Image):
        self._image = image
        self._page_count = (
            int(getattr(image, "n_frames", 1)) if image.format in _MULTI_PAGE_FORMATS else 1
        )

    def __len__(self) -> int:
        return self._page_count

    def page(self, index: int) -> ImagePage:
        if index < 0 or index >= self._page_count:
            raise error(
                400, "PAGE_OUT_OF_RANGE", pageIndex=index, pageCount=self._page_count
            )
        try:
            if self._page_count > 1:
                self._image.seek(index)
            # exif_transpose bakes the viewer's orientation into the pixels; convert
            # detaches the result from the shared frame cursor and normalises the mode
            # (a 1-bit fax TIFF and a phone JPEG must look the same to the detectors).
            frame = ImageOps.exif_transpose(self._image).convert("RGB")
        except Exception:
            # A file whose header parsed but whose pixel data does not decode is
            # still an undecodable image, never a PDF failure.
            raise error(400, "CORRUPT_IMAGE", pageIndex=index) from None
        dpi = nominal_dpi(frame.width, frame.height)
        return ImagePage(
            page_index=index,
            image=frame,
            dpi=dpi,
            width_pt=round(frame.width * 72.0 / dpi, 1),
            height_pt=round(frame.height * 72.0 / dpi, 1),
        )

    def close(self) -> None:
        self._image.close()

    def __enter__(self) -> "ImageDocument":
        return self

    def __exit__(self, *_exception) -> None:
        self.close()


def open_image_document(file_bytes: bytes) -> ImageDocument:
    """Decode an uploaded image. Undecodable bytes are CORRUPT_IMAGE — never
    CORRUPT_PDF, which is what the pipeline reported for every JPEG before this
    module existed and what made the failure impossible to read."""
    try:
        image = Image.open(io.BytesIO(file_bytes))
        image.load()
    except Exception:
        # Pillow raises UnidentifiedImageError, OSError, SyntaxError and
        # DecompressionBombError from here; none carries a stable contract meaning
        # beyond "not a readable image", and none may reach a message (they can
        # quote file content).
        raise error(400, "CORRUPT_IMAGE") from None
    return ImageDocument(image)
