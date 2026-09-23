"""The MIXED verdict needs an uncovered-area floor, not just an image-coverage floor.

A browser-printed statement carries a logo, icons, and often a chart as embedded
images. Every one of them is "uncovered" (no words are printed over a logo), so
without a floor the page is MIXED, goes to OCR, both engines find no words in a
graphic, and the page comes back flagged OCR_LOW_CONFIDENCE — a review flag on a
perfectly readable native page. The floor is on the TOTAL uncovered area
(MIN_UNCOVERED_AREA_RATIO of the page): a pasted scan clears it easily, a logo
never does. _uncovered_regions itself is untouched — it still over-covers.
"""

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.text import (
    MIN_IMAGE_COVERAGE_RATIO,
    MIN_UNCOVERED_AREA_RATIO,
    VERDICT_MIXED,
    VERDICT_NATIVE,
    Span,
    _verdict,
)

PAGE_W, PAGE_H = 612.0, 792.0
PAGE_AREA = PAGE_W * PAGE_H


def _span(x, y, w, h, ordinal=0):
    return Span(text="w", box=Box(x, y, w, h), font_size=None, font_name=None, ordinal=ordinal)


def _text_layer():
    """A meaningful native layer: well past MIN_MEANINGFUL_WORDS, spread down the page."""
    return [_span(72.0, 72.0 + 14.0 * i, 200.0, 10.0, ordinal=i) for i in range(20)]


class TestUncoveredAreaFloor:
    def test_images_over_the_coverage_floor_but_mostly_under_words_stay_native(self):
        """Images total 12% of the page (over MIN_IMAGE_COVERAGE_RATIO), but words
        cover all of them except a remainder under 1% of the page: NATIVE."""
        assert MIN_IMAGE_COVERAGE_RATIO <= 0.12
        # One image 612 x 95pt ~= 12% of the page, with a word row covering all but
        # a 612 x 6pt strip (0.76% of the page — over the 4pt sliver, under the floor).
        image = Box(0.0, 400.0, PAGE_W, 95.0)
        cover = [_span(0.0, 400.0, PAGE_W, 89.0, ordinal=100)]

        verdict, regions = _verdict(_text_layer() + cover, [image], PAGE_W, PAGE_H, 0.05)

        assert verdict == VERDICT_NATIVE
        assert regions is None

    def test_a_logo_and_icons_on_a_native_page_stay_native(self):
        """The browser-print case: a 120 x 36pt logo (0.9% of the page — on its own
        under the image-coverage floor, so add a full-width 90pt banner with its
        caption printed over it). Uncovered total < 1%: NATIVE."""
        logo = Box(36.0, 36.0, 120.0, 36.0)  # 0.89% of the page, no words over it
        banner = Box(0.0, 700.0, PAGE_W, 90.0)  # 11.4% of the page
        caption = [_span(0.0, 700.0, PAGE_W, 90.0, ordinal=100)]  # fully covers the banner

        verdict, regions = _verdict(_text_layer() + caption, [logo, banner], PAGE_W, PAGE_H, 0.05)

        assert verdict == VERDICT_NATIVE
        assert regions is None

    def test_a_pasted_scan_clears_the_floor_and_is_mixed(self):
        """A half-page image with no words over it is exactly the MIXED case."""
        scan = Box(0.0, PAGE_H / 2, PAGE_W, PAGE_H / 2)

        verdict, regions = _verdict(_text_layer(), [scan], PAGE_W, PAGE_H, 0.05)

        assert verdict == VERDICT_MIXED
        assert regions and sum(r.width * r.height for r in regions) / PAGE_AREA >= 0.49

    def test_floor_is_on_the_total_not_the_largest_region(self):
        """Many small uncovered slices that TOGETHER exceed the floor are MIXED —
        the floor must never let a fragmented scan through as NATIVE."""
        image = Box(0.0, 400.0, PAGE_W, 200.0)  # 25% of the page
        # Word rows every 20pt leaving 6pt uncovered gaps: 10 gaps x 612 x 6 = 7.6% of page.
        rows = [_span(0.0, 400.0 + 20.0 * i, PAGE_W, 14.0, ordinal=100 + i) for i in range(10)]

        verdict, regions = _verdict(_text_layer() + rows, [image], PAGE_W, PAGE_H, 0.05)

        assert verdict == VERDICT_MIXED
        assert regions is not None
        assert all(r.height < 10.0 for r in regions), "each gap on its own is tiny"
        assert sum(r.width * r.height for r in regions) / PAGE_AREA >= MIN_UNCOVERED_AREA_RATIO
