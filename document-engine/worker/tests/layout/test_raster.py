"""raster.py — the /v1/layout pixel-path render (Spec 3 T2).

Pins the frozen 6-field PageRaster and the per-page availability property
the T3/T4 notImplemented retirement will key on: a page index beyond the
attached PDF gets NO raster. Deliberately asserts NOTHING about
notImplemented — T2 changes no emitted /v1/layout value.
"""

from worker.tests.layout.conftest import FIXTURES

from pragmaticds_docengine_worker.layout.raster import DETECTOR_DPI, render_rasters


def _pdf(name="native_paystub.pdf"):
    return (FIXTURES / name).read_bytes()


class TestRenderRasters:
    def test_renders_the_requested_page_with_all_six_fields(self):
        rasters = render_rasters(_pdf(), [0])

        assert set(rasters) == {0}
        raster = rasters[0]
        assert raster.page_index == 0
        # 612x792 pt at 200 DPI = 1700x2200 px — verified against pypdfium2 on
        # the committed fixture (matches the 200-DPI fixture PNG dimensions).
        assert raster.image.size == (1700, 2200)
        assert raster.dpi == DETECTOR_DPI == 200
        assert raster.rotation == 0
        assert (raster.width_pt, raster.height_pt) == (612.0, 792.0)

    def test_page_beyond_the_pdf_gets_no_raster(self):
        """native_paystub.pdf has ONE page. The file is advisory (same stance
        as rulings): an out-of-range index renders nothing — the ABSENT key is
        what lets T3/T4 keep that page's notImplemented entries declared
        instead of pretending the pixel path looked."""
        rasters = render_rasters(_pdf(), [0, 7])

        assert set(rasters) == {0}
