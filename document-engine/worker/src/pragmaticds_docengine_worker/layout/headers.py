"""Header detection.

HEADER = a line that is visually PROMINENT and positionally ISOLATED:

 * prominent: median span size > SIZE_RATIO x the page median span size, or the
   line is bold throughout. Sizes are the size PROXY (fontSize, falling back to
   span height), so OCR pages — which carry neither fontSize nor fontName — still
   get size-ratio headers; bold detection is simply unavailable there.
 * isolated: clear vertical gap (relative to the line's own height) above AND
   below its nearest X-OVERLAPPING neighbours. Column-scoped neighbours matter:
   on a two-column page the right column's lines must not spoil a left heading's
   isolation. A missing neighbour (top/bottom of page or column) counts as
   isolated on that side.

A big line jammed against its neighbour is emphasis, not a header — the earnings
column-header row of a paystub grid sits 11pt above its first data row and must
stay body text.
"""

from statistics import median

from pragmaticds_docengine_worker.layout.model import LayoutSpan, Line

SIZE_RATIO = 1.15
#: Required neighbour gap, in units of the candidate line's own box height.
ISOLATION_FACTOR = 1.1


def detect_headers(lines: list[Line], page_spans: list[LayoutSpan]) -> list[Line]:
    """The subset of `lines` that are headers, in input order."""
    if not lines or not page_spans:
        return []
    page_median = median(span.size for span in page_spans)
    return [
        line
        for line in lines
        if (line.size > SIZE_RATIO * page_median or line.bold)
        and _isolated(line, lines)
    ]


def _isolated(line: Line, lines: list[Line]) -> bool:
    box = line.box
    height = box.height
    gap_above = None
    gap_below = None
    for other in lines:
        if other is line or not _x_overlaps(line, other):
            continue
        if other.bottom <= box.y:
            gap = box.y - other.bottom
            gap_above = gap if gap_above is None else min(gap_above, gap)
        elif other.top >= box.y + box.height:
            gap = other.top - (box.y + box.height)
            gap_below = gap if gap_below is None else min(gap_below, gap)
        else:
            return False  # vertical overlap with a neighbour: nothing isolated here
    threshold = ISOLATION_FACTOR * height
    return (gap_above is None or gap_above >= threshold) and (
        gap_below is None or gap_below >= threshold
    )


def _x_overlaps(a: Line, b: Line) -> bool:
    a_box, b_box = a.box, b.box
    return a_box.x < b_box.x + b_box.width and b_box.x < a_box.x + a_box.width
