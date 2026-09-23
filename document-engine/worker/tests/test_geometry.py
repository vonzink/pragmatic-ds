"""Tests for the canonical coordinate space — the single conversion point.

Every box the worker emits is PDF points, top-left origin, rotation-0, rounded to 0.1pt
(docs/WORKER_CONTRACT.md invariant 1). Three sources disagree and all are normalised here:
pdfminer (bottom-left points), raster pixels at a DPI, and rasters whose content is rotated.

Every expected value below is hand-computed. Coordinate drift produces plausible-looking
output with no exception anywhere, which is why this file exists.
"""

import pytest

from pragmaticds_docengine_worker.geometry import (
    Box,
    from_bottom_left,
    px_box_to_pt,
    px_to_pt,
    unrotate_box,
    unrotate_extent,
)


class TestPxToPt:
    def test_at_200_dpi_a_pixel_is_0_36_points(self):
        # 72 pt/inch ÷ 200 px/inch = 0.36 pt/px
        assert px_to_pt(100, dpi=200) == 36.0

    def test_at_72_dpi_pixels_are_points(self):
        assert px_to_pt(612, dpi=72) == 612.0

    def test_rounds_to_tenth_of_a_point(self):
        # 100 px at 300 dpi = 24.0 exactly; 101 px = 24.24 -> 24.2
        assert px_to_pt(101, dpi=300) == 24.2

    def test_rejects_nonpositive_dpi(self):
        with pytest.raises(ValueError):
            px_to_pt(10, dpi=0)


class TestPxBoxToPt:
    def test_letter_page_at_200_dpi(self):
        # Raster 1700x2200 px = US Letter 612x792 pt. A word at px (400, 300, 200x50):
        # 400*0.36=144.0, 300*0.36=108.0, 200*0.36=72.0, 50*0.36=18.0
        box = px_box_to_pt(Box(400, 300, 200, 50), dpi=200)
        assert box == Box(144.0, 108.0, 72.0, 18.0)


class TestFromBottomLeft:
    def test_flips_the_y_axis(self):
        # pdfminer: origin bottom-left. A 10pt-tall box whose BOTTOM edge is 100pt above
        # the page bottom, on a 792pt page, has its TOP edge 792-100-10 = 682pt from the top.
        box = from_bottom_left(x=50.0, y_bottom=100.0, width=200.0, height=10.0, page_height_pt=792.0)
        assert box == Box(50.0, 682.0, 200.0, 10.0)

    def test_box_touching_the_top_of_the_page(self):
        box = from_bottom_left(x=0.0, y_bottom=782.0, width=100.0, height=10.0, page_height_pt=792.0)
        assert box == Box(0.0, 0.0, 100.0, 10.0)


class TestUnrotateBox:
    """A raster whose CONTENT appears rotated clockwise by `rotation` degrees, un-mapped to
    rotation-0 canonical points.

    Worked example used throughout: rotation-0 page is 612w x 792h pt. Content rotated 90 cw
    means the raster is 792w x 612h pt-equivalent (landscape). A word whose rotation-0 box is
    (x=100, y=200, w=50, h=10):

      rotate 90 cw maps a rotation-0 point (x, y) to raster (W_r - y, x) where W_r = 792.
      The box corners (100,200)-(150,210) land at raster x in [792-210, 792-200] = [582, 592],
      raster y in [100, 150] -> raster box (582, 100, 10, 50).

    So unrotate(raster_box=(582,100,10,50), rotation=90, page 612x792) must return (100,200,50,10).
    """

    def test_rotation_0_is_identity(self):
        assert unrotate_box(Box(100, 200, 50, 10), rotation=0, page_w_pt=612, page_h_pt=792) == Box(
            100, 200, 50, 10
        )

    def test_rotation_90_clockwise(self):
        assert unrotate_box(
            Box(582.0, 100.0, 10.0, 50.0), rotation=90, page_w_pt=612, page_h_pt=792
        ) == Box(100.0, 200.0, 50.0, 10.0)

    def test_rotation_180(self):
        # 180: raster (x, y) = (W - x0 - w, H - y0 - h) with W=612, H=792.
        # rotation-0 (100,200,50,10) -> raster (612-100-50, 792-200-10) = (462, 582, 50, 10)
        assert unrotate_box(
            Box(462.0, 582.0, 50.0, 10.0), rotation=180, page_w_pt=612, page_h_pt=792
        ) == Box(100.0, 200.0, 50.0, 10.0)

    def test_rotation_270_clockwise(self):
        # 270 cw (=90 ccw): rotation-0 (x,y) maps to raster (y, H_r - x - w') where the raster
        # is 792x612... corners (100,200)-(150,210): raster x in [200,210], raster y in
        # [612-150, 612-100] = [462, 512] -> raster box (200, 462, 10, 50).
        assert unrotate_box(
            Box(200.0, 462.0, 10.0, 50.0), rotation=270, page_w_pt=612, page_h_pt=792
        ) == Box(100.0, 200.0, 50.0, 10.0)

    def test_round_trip_all_rotations_preserves_the_box(self):
        from pragmaticds_docengine_worker.geometry import rotate_box

        original = Box(73.4, 641.2, 118.6, 12.8)
        for rotation in (0, 90, 180, 270):
            rotated = rotate_box(original, rotation=rotation, page_w_pt=612, page_h_pt=792)
            back = unrotate_box(rotated, rotation=rotation, page_w_pt=612, page_h_pt=792)
            assert back == original, f"rotation {rotation} did not round-trip"

    def test_rejects_rotations_outside_the_four(self):
        with pytest.raises(ValueError):
            unrotate_box(Box(0, 0, 1, 1), rotation=45, page_w_pt=612, page_h_pt=792)


class TestUnrotateExtent:
    """The same quarter-turn as unrotate_box, on edges instead of an origin-and-size,
    and WITHOUT the 0.1pt rounding a Box applies. It exists for ordering decisions,
    which need the exact comparison — rounding invents ties, and an invented tie is
    broken by whatever the extractor happened to emit first.

    Two spellings of one mapping is exactly the drift this module's docstring warns
    about, so the first test below pins them to each other on every rotation. It is
    the weld: change one formula without the other and it breaks.
    """

    def test_it_agrees_with_unrotate_box_on_every_rotation(self):
        # Values chosen on the 0.1pt grid so rounding cannot mask a disagreement.
        box = Box(73.4, 641.2, 118.6, 12.8)
        for rotation in (0, 90, 180, 270):
            x0, top, x1, bottom = unrotate_extent(
                box.x, box.y, box.x + box.width, box.y + box.height,
                rotation=rotation, page_w_pt=612.0, page_h_pt=792.0,
            )
            assert Box(x0, top, x1 - x0, bottom - top) == unrotate_box(
                box, rotation=rotation, page_w_pt=612.0, page_h_pt=792.0
            ), f"rotation {rotation} disagrees with unrotate_box"

    def test_rotation_0_returns_the_edges_untouched(self):
        # Byte-identity for unrotated pages depends on this being the literal identity:
        # not "rounds to the same tenth", the same floats back.
        raw = (72.00000001, 61.923456789, 113.2, 74.9)
        assert unrotate_extent(*raw, rotation=0, page_w_pt=612.0, page_h_pt=792.0) == raw

    def test_rotation_180_flips_both_axes_without_rounding(self):
        # Same worked example as TestUnrotateBox: rotation-0 (100,200,50,10) is drawn
        # at raster (462, 582)-(512, 592) on a 612x792 page. Carried to 4 decimals.
        assert unrotate_extent(
            462.0625, 582.0625, 512.0625, 592.0625,
            rotation=180, page_w_pt=612.0, page_h_pt=792.0,
        ) == (99.9375, 199.9375, 149.9375, 209.9375)

    def test_rejects_rotations_outside_the_four(self):
        with pytest.raises(ValueError):
            unrotate_extent(0, 0, 1, 1, rotation=45, page_w_pt=612, page_h_pt=792)


class TestBoxRounding:
    def test_boxes_round_to_tenth_of_a_point_on_construction(self):
        assert Box(1.23456, 2.34567, 3.45678, 4.56789) == Box(1.2, 2.3, 3.5, 4.6)
