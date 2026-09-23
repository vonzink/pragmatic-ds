"""Paragraph assembly: consecutive lines with a consistent left edge (±3pt) and
consistent line spacing (±40%) merge into one paragraph.

Lines are walked top-to-bottom, but a line appends to the most recent OPEN
paragraph whose left edge matches — not to "the previous line" — because on a
two-column page the y-sorted lines interleave L,R,L,R and each column must keep
assembling its own paragraph.

Spacing is measured top-to-top (a line-height-independent proxy for leading):
 * paragraph already has spacing: the new gap must be within ±40% of it;
 * fresh pair (no spacing yet): the gap is capped at FIRST_GAP_CAP x the larger
   size proxy — without the cap, a heading and a footer 400pt apart with the
   same left margin would "merge".
"""

from pragmaticds_docengine_worker.layout.model import Line

LEFT_EDGE_TOLERANCE_PT = 3.0
SPACING_TOLERANCE = 0.40
#: A fresh pair only merges when the top-to-top gap is at most this many
#: size-proxies — generous single/double spacing, never a section break.
FIRST_GAP_CAP = 2.5


def group_paragraphs(lines: list[Line]) -> list[list[Line]]:
    """Order-preserving grouping; every input line lands in exactly one group."""
    ordered = sorted(lines, key=lambda l: (l.top, l.left))
    groups: list[dict] = []
    for line in ordered:
        best = None
        for group in reversed(groups):  # most recent compatible group wins
            if _extends(group, line):
                best = group
                break
        if best is None:
            groups.append({"lines": [line], "spacing": None})
        else:
            best["spacing"] = line.top - best["lines"][-1].top
            best["lines"].append(line)
    return [group["lines"] for group in groups]


def _extends(group: dict, line: Line) -> bool:
    last = group["lines"][-1]
    if abs(line.left - last.left) > LEFT_EDGE_TOLERANCE_PT:
        return False
    gap = line.top - last.top
    if gap <= 0:
        return False  # same band (column fragments) or out of order: never merge
    if group["spacing"] is None:
        return gap <= FIRST_GAP_CAP * max(line.size, last.size)
    return abs(gap - group["spacing"]) <= SPACING_TOLERANCE * group["spacing"]
