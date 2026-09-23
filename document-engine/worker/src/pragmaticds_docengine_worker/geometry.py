"""The canonical coordinate space — the single conversion point.

Every box the worker emits is PDF points, top-left origin, rotation-0, rounded to 0.1pt
(docs/WORKER_CONTRACT.md invariant 1). Three sources disagree and every one is normalised
here and nowhere else:

    pdfplumber      top-left points          (pass-through + rounding)
    pdfminer        bottom-left points       (from_bottom_left)
    raster pixels   top-left px at a DPI     (px_box_to_pt), possibly rotated (unrotate_box)

Coordinate drift is how evidence systems quietly start lying: a bug here produces
plausible-looking boxes with no exception raised anywhere. Change nothing without a
hand-computed test in test_geometry.py.
"""

from dataclasses import dataclass


def _round_pt(value: float) -> float:
    return round(value + 1e-9, 1)  # epsilon guards 0.05-exact halfway cases from banker's rounding


@dataclass(frozen=True)
class Box:
    """A rectangle in canonical space. Rounds to 0.1pt on construction."""

    x: float
    y: float
    width: float
    height: float

    def __post_init__(self):
        object.__setattr__(self, "x", _round_pt(float(self.x)))
        object.__setattr__(self, "y", _round_pt(float(self.y)))
        object.__setattr__(self, "width", _round_pt(float(self.width)))
        object.__setattr__(self, "height", _round_pt(float(self.height)))


def px_to_pt(px: float, dpi: int) -> float:
    """Pixels at a render DPI -> points. 72 pt/inch is the PDF unit definition."""
    if dpi <= 0:
        raise ValueError(f"dpi must be positive, got {dpi}")
    return _round_pt(px * 72.0 / dpi)


def px_box_to_pt(box: Box, dpi: int) -> Box:
    return Box(
        px_to_pt(box.x, dpi), px_to_pt(box.y, dpi), px_to_pt(box.width, dpi), px_to_pt(box.height, dpi)
    )


def from_bottom_left(
    x: float, y_bottom: float, width: float, height: float, page_height_pt: float
) -> Box:
    """pdfminer's bottom-left origin -> canonical top-left."""
    return Box(x, page_height_pt - y_bottom - height, width, height)


def _require_quarter_turn(rotation: int) -> None:
    if rotation not in (0, 90, 180, 270):
        raise ValueError(f"rotation must be one of 0/90/180/270, got {rotation}")


def rotate_box(box: Box, rotation: int, page_w_pt: float, page_h_pt: float) -> Box:
    """Maps a rotation-0 box into the space of a raster whose content is rotated
    `rotation` degrees clockwise. Exists chiefly so tests can prove unrotate_box
    round-trips; production code goes the other way."""
    _require_quarter_turn(rotation)
    if rotation == 0:
        return box
    if rotation == 90:
        # rotation-0 (x, y) -> raster (H0 - y, x); raster page is H0 wide.
        return Box(page_h_pt - box.y - box.height, box.x, box.height, box.width)
    if rotation == 180:
        return Box(page_w_pt - box.x - box.width, page_h_pt - box.y - box.height, box.width, box.height)
    # 270
    return Box(box.y, page_w_pt - box.x - box.width, box.height, box.width)


def unrotate_extent(
    x0: float, top: float, x1: float, bottom: float, rotation: int, page_w_pt: float, page_h_pt: float
) -> tuple[float, float, float, float]:
    """unrotate_box's quarter-turn on EDGES, and without the 0.1pt rounding.

    Returns canonical (x0, top, x1, bottom) as exact floats. Callers that MEASURE
    want unrotate_box — everything the worker emits is rounded. Callers that COMPARE
    want this: rounding to a tenth is monotonic, so it can never reverse two
    coordinates, but it is not injective, and a tie it invents is then settled by
    whatever order the extractor happened to emit — which is not a reading order.
    text._reading_order is the caller that cares.

    Rotation 0 returns its arguments untouched, by identity and not by rounding; the
    unrotated page's output depends on it.

    This is the same mapping as unrotate_box, spelled a second way, which is exactly
    the drift this module warns about — TestUnrotateExtent pins the two together on
    all four rotations.
    """
    _require_quarter_turn(rotation)
    if rotation == 0:
        return x0, top, x1, bottom
    if rotation == 90:
        return top, page_h_pt - x1, bottom, page_h_pt - x0
    if rotation == 180:
        return page_w_pt - x1, page_h_pt - bottom, page_w_pt - x0, page_h_pt - top
    # 270
    return page_w_pt - bottom, x0, page_w_pt - top, x1


def unrotate_box(box: Box, rotation: int, page_w_pt: float, page_h_pt: float) -> Box:
    """Maps a box measured on a rotated raster back into rotation-0 canonical space.

    `rotation` is how far the CONTENT appears rotated clockwise in the raster (Tesseract
    OSD's convention). `page_w_pt`/`page_h_pt` are the rotation-0 page dimensions.
    """
    _require_quarter_turn(rotation)
    if rotation == 0:
        return box
    if rotation == 90:
        # Inverse of rotate_box(90): raster (rx, ry) came from rotation-0 (ry, H0 - rx - rw).
        return Box(box.y, page_h_pt - box.x - box.width, box.height, box.width)
    if rotation == 180:
        return Box(page_w_pt - box.x - box.width, page_h_pt - box.y - box.height, box.width, box.height)
    # 270
    return Box(page_w_pt - box.y - box.height, box.x, box.height, box.width)
