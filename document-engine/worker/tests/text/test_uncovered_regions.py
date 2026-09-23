"""_uncovered_regions must OVER-cover, never under-cover.

Phase 2 review finding: the union-bbox-remainder shape collapsed to zero when words
sat at an image's opposite edges — the whole page body silently skipped OCR with no
flag. The property tested here is the module's own stated invariant: every part of
the image not covered by a word box appears in some returned region (over-coverage
is cheap; a missed region is a silently unread document).
"""

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.text import _uncovered_regions, Span


def _span(x, y, w, h):
    return Span(text="w", box=Box(x, y, w, h), font_size=None, font_name=None, ordinal=0)


PAGE_W, PAGE_H = 612.0, 792.0


def _covered_by(regions, x, y):
    return any(r.x <= x <= r.x + r.width and r.y <= y <= r.y + r.height for r in regions)


class TestOverCoverage:
    def test_opposite_edge_words_do_not_collapse_the_uncovered_middle(self):
        """A full-page image with a header word at its top and a footer word at its
        bottom: the union bbox spans the whole image, but the vast middle is unread
        and MUST come back as uncovered."""
        image = Box(0.0, 0.0, PAGE_W, PAGE_H)
        spans = [_span(50, 10, 200, 14), _span(50, PAGE_H - 24, 200, 14)]

        regions = _uncovered_regions(spans, [image], PAGE_W, PAGE_H)

        assert regions, "under-coverage: the unread middle vanished entirely"
        assert _covered_by(regions, PAGE_W / 2, PAGE_H / 2), regions
        total = sum(r.width * r.height for r in regions)
        assert total >= 0.5 * PAGE_W * PAGE_H, f"middle band too small: {total}"

    def test_words_in_all_four_corners_still_expose_the_center(self):
        image = Box(0.0, 0.0, PAGE_W, PAGE_H)
        spans = [
            _span(10, 10, 80, 14),
            _span(PAGE_W - 90, 10, 80, 14),
            _span(10, PAGE_H - 24, 80, 14),
            _span(PAGE_W - 90, PAGE_H - 24, 80, 14),
        ]

        regions = _uncovered_regions(spans, [image], PAGE_W, PAGE_H)

        assert _covered_by(regions, PAGE_W / 2, PAGE_H / 2), regions

    def test_a_fully_worded_image_reports_nothing(self):
        image = Box(100.0, 100.0, 200.0, 50.0)
        spans = [_span(95, 95, 210, 60)]

        assert _uncovered_regions(spans, [image], PAGE_W, PAGE_H) == []

    def test_word_free_image_is_returned_whole(self):
        image = Box(0.0, 396.0, PAGE_W, 396.0)

        regions = _uncovered_regions([], [image], PAGE_W, PAGE_H)

        assert len(regions) == 1
        assert regions[0] == Box(0.0, 396.0, PAGE_W, 396.0)
