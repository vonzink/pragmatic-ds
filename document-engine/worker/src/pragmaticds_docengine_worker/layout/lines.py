"""Y-band grouping of spans into visual lines.

Two decisions live here and everything downstream builds on them:

1. BANDS — a span joins the current band when its vertical extent overlaps the
   band envelope by at least half the shorter height, OR its centre sits within
   half the larger font-size proxy of the band centre. The second clause is the
   font-size-relative tolerance the contract asks for: OCR boxes jitter a couple
   of points and carry no fontSize, so the span HEIGHT stands in for it.

2. FRAGMENTS — within a band, a horizontal gap wider than GAP_FACTOR x the band's
   size proxy splits the band into separate visual lines. That split is what keeps
   a two-column page two columns (the inter-column gutter is enormous relative to
   the type size) and what turns a table row band into per-cell fragments whose
   x-starts the table detector histograms. Ordinary inter-word gaps (~0.5em) are
   far below the threshold.
"""

from statistics import median

from pragmaticds_docengine_worker.layout.model import LayoutSpan, Line

#: A horizontal gap wider than this many line-sizes splits a band into fragments.
#: Word gaps run ~0.5em; table gutters and column gutters run 4em+.
GAP_FACTOR = 2.0
#: Centre-distance tolerance for band membership, in units of the larger size proxy.
BAND_CENTER_TOLERANCE = 0.5


def build_lines(spans: list[LayoutSpan]) -> list[Line]:
    """Group spans into visual lines: top-to-bottom bands, split at wide gaps,
    spans left-to-right within each fragment."""
    bands: list[dict] = []
    for span in sorted(spans, key=lambda s: (s.box.y, s.box.x)):
        top, bottom = span.box.y, span.box.y + span.box.height
        band = bands[-1] if bands else None
        if band is not None and _joins(band, span, top, bottom):
            band["spans"].append(span)
            band["top"] = min(band["top"], top)
            band["bottom"] = max(band["bottom"], bottom)
        else:
            bands.append({"top": top, "bottom": bottom, "spans": [span]})

    lines: list[Line] = []
    for index, band in enumerate(bands):
        ordered = sorted(band["spans"], key=lambda s: s.box.x)
        size = median(s.size for s in ordered)
        fragment: list[LayoutSpan] = []
        for span in ordered:
            if fragment:
                gap = span.box.x - (fragment[-1].box.x + fragment[-1].box.width)
                if gap > GAP_FACTOR * size:
                    lines.append(Line(spans=tuple(fragment), band=index))
                    fragment = []
            fragment.append(span)
        lines.append(Line(spans=tuple(fragment), band=index))
    return lines


def _joins(band: dict, span: LayoutSpan, top: float, bottom: float) -> bool:
    overlap = min(band["bottom"], bottom) - max(band["top"], top)
    shorter = min(band["bottom"] - band["top"], bottom - top)
    if shorter > 0 and overlap >= 0.5 * shorter:
        return True
    band_center = (band["top"] + band["bottom"]) / 2.0
    span_center = (top + bottom) / 2.0
    band_size = median(s.size for s in band["spans"])
    tolerance = BAND_CENTER_TOLERANCE * max(span.size, band_size)
    return abs(span_center - band_center) <= tolerance
