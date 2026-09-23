"""Table detection: x-start histogram columns over y-band rows.

A candidate grid is a maximal run of consecutive bands in which EVERY band has
fragment starts at >= MIN_COLUMNS shared x-positions (clustered at ±X_TOLERANCE),
and the run is >= MIN_ROWS bands tall. That threshold is deliberate: two text
columns align at two x-starts and flowing text aligns at one (the margin) — three
shared columns over three rows is where whitespace stops being coincidence.

Rows are the bands; a cell is the words of a band falling in a column slot (slot
boundaries = midpoints between neighbouring column centres).

COLUMNS FROM THE PAGE'S OWN GAPS (issue #63). The shared x-starts are only the
grid's SKELETON: a column that is blank on some rows (a paystub's Rate/Hours on a
bonus line) is shared by no complete set of bands and used to vanish into the cell
to its left — an hours figure packed beside a rate across a 57pt hole. Now a cell
is walked span by span: a horizontal gap wider than COLUMN_GAP_FACTOR x the page's
median WORD gap (floored at HOLE_MIN_EM ems) is a HOLE, and a span right of a hole
that the known boundaries still slot into the cell it follows names a column the
skeleton missed. Those orphans, clustered by x-overlap, become columns; slotting
repeats until no orphan is left. The converse guard is the same statistic: a span
that a boundary would push into the next column but that follows its neighbour at
a word gap stays in the cell — "Federal Income Tax" is one cell however far it
reaches under the next column's x-start.

HEADER ROW (issue #63). The band directly above a grid is its header when every
fragment on it lands in exactly ONE column (by x-overlap with the column's ink, or
by lying inside one slot when it overlaps none), no two fragments claim the same
column, none is a figure, and its top-to-top pitch to the first row is at most
HEADER_PITCH_FACTOR x the grid's own row pitch. One fragment that could belong to
two columns keeps the WHOLE row out: ungrouped beats attached to the wrong column.
The row carries `header: true` and the data rows renumber below it. Run growth
defers to this: a band that would cap a run SHORTER than the run without it (a
label row aligned with only the first data rows) is skipped rather than allowed
to start a 3-row table that splits the grid beneath it in two.

RULED CONFIRMATION (only when the optional PDF part supplied rulings): every
interior column boundary must have a vertical ruling in the whitespace gap
between the adjacent column slots, and every interior row boundary a horizontal
ruling in the gap between the adjacent row bands — each ruling spanning >=
RULING_COVERAGE of the table's other extent. The ±RULING_SLACK_PT slack is
applied around the gap because real rulings sit BETWEEN content, not on it (the
ruled fixture draws its verticals 6pt left of the column text starts — a
"within 3pt of the text" rule would reject every honestly ruled table ever
drawn). All boundaries confirmed -> ruled=true at RULED_CONFIDENCE, and the
table box grows to the confirming rulings' extent; anything less stays
unruled at UNRULED_CONFIDENCE — the MVP's measured, published weak point.
"""

from statistics import median

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.layout.model import Element, LayoutSpan, Line, Rulings, union_box
from pragmaticds_docengine_worker.layout.values import is_value_run

MIN_ROWS = 3
MIN_COLUMNS = 3
X_TOLERANCE_PT = 4.0
RULING_SLACK_PT = 3.0
RULING_COVERAGE = 0.8
RULED_CONFIDENCE = 0.95
UNRULED_CONFIDENCE = 0.75

#: An intra-cell gap wider than this multiple of the page's median word gap is a
#: HOLE — column whitespace, not a word space. Word gaps on one page stay within
#: ~2x of each other (justified text at the widest); column gutters run an order
#: of magnitude wider, so 4x separates the two with margin on both sides.
COLUMN_GAP_FACTOR = 4.0
#: Floor on the hole width in ems of the spans beside it: no printed word gap is a
#: whole em, so a page whose measured "word gaps" are sub-word kerning fragments —
#: or one with no multi-word run to measure at all — still cannot open a column at
#: every word.
HOLE_MIN_EM = 1.0
#: A band directly above a grid is its header row only when its top-to-top pitch
#: to the first data row is at most this multiple of the grid's own median row
#: pitch: a label row sits at the leading of the rows it labels; a caption a few
#: lines up does not.
HEADER_PITCH_FACTOR = 1.5
#: A label row names at least this many columns; one fragment above a grid is a title.
MIN_HEADER_LABELS = 2


def detect_tables(
    lines: list[Line], rulings: Rulings | None = None
) -> tuple[list[Element], list[Line]]:
    """(TABLE elements with TABLE_ROW > TABLE_CELL children, untouched lines).

    Column locality (Phase 3 review, confirmed high): page-wide y-bands let a grid
    in one column absorb the neighbouring column's prose into its cells. Two guards
    fix it without splitting real tables:

      1. PITCH PRUNING — edge clusters whose gap to their neighbour exceeds
         DETACH_FACTOR x the median inter-column pitch are not table columns
         (prose aligned at a constant x across the same bands forms such a
         detached cluster; a wide description->amount gap inside a real table
         stays under the factor).
      2. MEMBERSHIP CUT — lines starting beyond the pruned columns' x-extent
         (plus one detach distance) are never consumed into cells; they remain
         for paragraph clustering and reading order.

    Remaining honest limitation: a label:value form block INSIDE one column with
    >=3 aligned starts over >=3 rows still classifies as an unruled TABLE.
    """
    bands = _bands(lines)
    word_gap = _word_gap(lines)
    tables: list[Element] = []
    consumed: set[int] = set()

    index = 0
    while index < len(bands):
        run_end = _grow_run(bands, index)
        if run_end - index >= MIN_ROWS:
            # A band that caps the run SHORTER than the run without it is not a
            # grid row: a label row aligned with only the first data rows would
            # otherwise start a 3-row table and split the grid beneath it in two.
            # Skipped, it is the next run's header candidate or an ordinary line.
            if _grow_run(bands, index + 1) - (index + 1) > run_end - index:
                index += 1
                continue
            run = bands[index:run_end]
            above = bands[index - 1][1] if index > 0 else []
            if any(id(line) in consumed for line in above):
                above = []  # the previous table's last row is nobody's header
            table, member_ids = _build_table(run, above, rulings, word_gap)
            tables.append(table)
            consumed.update(member_ids)
            index = run_end
        else:
            index += 1

    remaining = [line for line in lines if id(line) not in consumed]
    return tables, remaining


def _word_gap(lines: list[Line]) -> float | None:
    """The page's median gap between neighbouring spans INSIDE one fragment —
    the word space, measured rather than assumed. None when no fragment on the
    page has two spans (the em floor then stands alone)."""
    gaps = [
        b.box.x - (a.box.x + a.box.width)
        for line in lines
        for a, b in zip(line.spans, line.spans[1:])
        if b.box.x >= a.box.x + a.box.width  # overlapping boxes are not a gap
    ]
    return median(gaps) if gaps else None


def _hole_threshold(word_gap: float | None, size: float) -> float:
    floor = HOLE_MIN_EM * size
    return floor if word_gap is None else max(COLUMN_GAP_FACTOR * word_gap, floor)


def _bands(lines: list[Line]) -> list[tuple[int, list[Line]]]:
    """Lines regrouped by y-band, bands top-to-bottom, fragments left-to-right."""
    by_band: dict[int, list[Line]] = {}
    for line in lines:
        by_band.setdefault(line.band, []).append(line)
    bands = sorted(by_band.items(), key=lambda item: min(l.top for l in item[1]))
    return [(band_id, sorted(members, key=lambda l: l.left)) for band_id, members in bands]


def _grow_run(bands: list[tuple[int, list[Line]]], start: int) -> int:
    """Largest `end` such that bands[start:end] all share >= MIN_COLUMNS columns."""
    end = start + 1
    while end < len(bands):
        candidate = bands[start : end + 1]
        if len(_shared_columns(candidate)) < MIN_COLUMNS:
            break
        end += 1
    return end if len(_shared_columns(bands[start:end])) >= MIN_COLUMNS else start + 1


def _shared_columns(bands: list[tuple[int, list[Line]]]) -> list[float]:
    """Centres of x-start clusters (±X_TOLERANCE_PT) present in EVERY band."""
    if len(bands) < 2:
        return []
    starts = sorted(
        (line.left, position) for position, (_, members) in enumerate(bands) for line in members
    )
    clusters: list[dict] = []
    for left, band_position in starts:
        if clusters and left - clusters[-1]["min"] <= X_TOLERANCE_PT:
            clusters[-1]["values"].append(left)
            clusters[-1]["bands"].add(band_position)
        else:
            clusters.append({"min": left, "values": [left], "bands": {band_position}})
    shared = [
        sum(c["values"]) / len(c["values"])
        for c in clusters
        if len(c["bands"]) == len(bands)
    ]
    return _prune_detached_edges(shared)


#: An edge cluster whose pitch exceeds this multiple of the median pitch is not a
#: table column — it is separately-aligned content (prose, the other page column).
DETACH_FACTOR = 1.75


def _detach_distance(columns: list[float]) -> float:
    pitches = [b - a for a, b in zip(columns, columns[1:])]
    if not pitches:
        return X_TOLERANCE_PT
    ordered = sorted(pitches)
    median = ordered[len(ordered) // 2] if len(ordered) % 2 else (
        (ordered[len(ordered) // 2 - 1] + ordered[len(ordered) // 2]) / 2.0
    )
    return DETACH_FACTOR * median


def _prune_detached_edges(columns: list[float]) -> list[float]:
    pruned = list(columns)
    while len(pruned) >= 3:
        cutoff = _detach_distance(pruned)
        if pruned[-1] - pruned[-2] > cutoff:
            pruned.pop()
        elif pruned[1] - pruned[0] > cutoff:
            pruned.pop(0)
        else:
            break
    return pruned


#: One row's cells: column index -> the spans in that cell, left to right.
Slots = dict[int, list[LayoutSpan]]


def _build_table(
    run: list[tuple[int, list[Line]]],
    above: list[Line],
    rulings: Rulings | None,
    word_gap: float | None,
) -> tuple[Element, set[int]]:
    skeleton = _shared_columns(run)
    # Membership cut: lines aligned with detached clusters (the neighbouring page
    # column's prose) start beyond the last real column + one detach distance and
    # must never become cells.
    right_cutoff = skeleton[-1] + _detach_distance(skeleton)
    left_cutoff = skeleton[0] - X_TOLERANCE_PT

    def members_of(lines: list[Line]) -> list[Line]:
        return [line for line in lines if left_cutoff <= line.left <= right_cutoff]

    row_members = [members_of(lines) for _, lines in run]
    columns, row_slots = _columns_and_slots(row_members, skeleton, word_gap)
    boundaries = _boundaries(columns)

    header_members = members_of(above)
    header_slots = _header_slots(header_members, boundaries, row_slots)

    member_ids = {id(line) for members in row_members for line in members}
    rows = _rows(row_slots, header_slots)
    box = union_box([row.box for row in rows])
    ruled, ruled_box = _confirm_ruled(rows, len(columns), box, rulings)
    if header_slots is not None and not ruled:
        # The rulings say where the grid is. A label row the drawn box excludes is
        # not this grid's header, and must not cost the grid its confirmation.
        without = _rows(row_slots, None)
        ruled, ruled_box = _confirm_ruled(without, len(columns), union_box(
            [row.box for row in without]), rulings)
        if ruled:
            rows, header_slots = without, None
            box = union_box([row.box for row in rows])
    if header_slots is not None:
        member_ids.update(id(line) for line in header_members)

    confidence = RULED_CONFIDENCE if ruled else UNRULED_CONFIDENCE
    if ruled:
        box = ruled_box
        for row in rows:
            row.confidence = confidence
            for cell in row.children:
                cell.confidence = confidence

    return (
        Element(
            element_type="TABLE",
            box=box,
            spans=(),
            confidence=confidence,
            attributes={"rows": len(rows), "cols": len(columns), "ruled": ruled},
            children=rows,
        ),
        member_ids,
    )


def _boundaries(columns: list[float]) -> list[float]:
    return [(a + b) / 2.0 for a, b in zip(columns, columns[1:])]


def _columns_and_slots(
    row_members: list[list[Line]], skeleton: list[float], word_gap: float | None
) -> tuple[list[float], list[Slots]]:
    """Grow the skeleton by the columns its cells' holes reveal, until every
    span right of a hole has a column of its own. Each pass adds >= 1 column or
    stops, and a page has finitely many spans, so this terminates."""
    columns = list(skeleton)
    while True:
        boundaries = _boundaries(columns)
        row_slots: list[Slots] = []
        orphans: list[LayoutSpan] = []
        for members in row_members:
            spans = sorted((s for line in members for s in line.spans), key=lambda s: s.box.x)
            slots, row_orphans = _slot_row(spans, boundaries, word_gap)
            row_slots.append(slots)
            orphans += row_orphans
        added: list[float] = []
        for start in _run_starts(orphans):
            # Distinct from the known columns AND from each other: two orphan runs
            # 3pt apart on different rows must not yield a sub-tolerance column pair.
            if all(abs(start - column) > X_TOLERANCE_PT for column in columns + added):
                added.append(start)
        if not added:
            return columns, row_slots
        columns = sorted(columns + added)


def _slot_row(
    spans: list[LayoutSpan], boundaries: list[float], word_gap: float | None
) -> tuple[Slots, list[LayoutSpan]]:
    """Walk one row's spans left to right. A span opens a new cell only when it
    sits right of a HOLE and the boundaries agree it is in a later column; a span
    across a mere word gap stays with its neighbour whatever the boundaries say
    (a long cell reaching under the next x-start). A span right of a hole that
    the boundaries slot no further is an ORPHAN: it occupies a column the
    boundaries do not know yet."""
    slots: Slots = {}
    orphans: list[LayoutSpan] = []
    current = 0
    previous: LayoutSpan | None = None
    for span in spans:
        slot = sum(1 for b in boundaries if span.box.x >= b)
        if previous is None:
            current = slot
        else:
            gap = span.box.x - (previous.box.x + previous.box.width)
            if gap > _hole_threshold(word_gap, max(previous.size, span.size)):
                if slot > current:
                    current = slot
                else:
                    orphans.append(span)
        slots.setdefault(current, []).append(span)
        previous = span
    return slots, orphans


def _run_starts(spans: list[LayoutSpan]) -> list[float]:
    """Cluster spans by horizontal OVERLAP — two spans sharing any x are one
    column, so right-aligned figures of different lengths stay one column — and
    return each cluster's left edge, the x-start the new column gets."""
    starts: list[float] = []
    right = None
    for span in sorted(spans, key=lambda s: s.box.x):
        if right is None or span.box.x >= right:
            starts.append(span.box.x)
            right = span.box.x + span.box.width
        else:
            right = max(right, span.box.x + span.box.width)
    return starts


def _header_slots(
    fragments: list[Line], boundaries: list[float], row_slots: list[Slots]
) -> Slots | None:
    """The band above the grid as its header row, or None when it is not one.

    Two refusals guard the rows the grid already has. A grid whose first row
    carries no figure already HAS its label row inside the run (labels printed
    at the data x-starts — the other common paystub shape); a section title one
    pitch above it must not become row 0 and demote the real labels to data,
    because the composer's label-row rule and TABLE_CLUSTER both read the
    topmost row as the header. And a single fragment is a title, not a label
    row: a label row names at least two columns."""
    if not fragments or not row_slots:
        return None
    if not any(is_value_run([s.text for s in spans]) for spans in row_slots[0].values()):
        return None
    row_tops = [min(s.box.y for spans in slots.values() for s in spans) for slots in row_slots]
    pitches = [b - a for a, b in zip(row_tops, row_tops[1:])]
    header_top = min(line.top for line in fragments)
    pitch = row_tops[0] - header_top
    if pitch <= 0 or pitch > HEADER_PITCH_FACTOR * median(pitches):
        return None

    extents: dict[int, tuple[float, float]] = {}
    for slots in row_slots:
        for column, spans in slots.items():
            lo, hi = extents.get(column, (float("inf"), float("-inf")))
            extents[column] = (
                min(lo, min(s.box.x for s in spans)),
                max(hi, max(s.box.x + s.box.width for s in spans)),
            )

    header: Slots = {}
    for fragment in fragments:
        if is_value_run([span.text for span in fragment.spans]):
            return None  # figures above a grid are a row of something, not labels
        column = _label_column(fragment, extents, boundaries)
        if column is None or column in header:
            return None
        header[column] = list(fragment.spans)
    if len(header) < MIN_HEADER_LABELS:
        return None
    return header


def _label_column(
    fragment: Line, extents: dict[int, tuple[float, float]], boundaries: list[float]
) -> int | None:
    """The one column a label sits over, or None when that is not decidable:
    overlapping two columns' ink, or overlapping none while straddling a slot
    boundary."""
    left, right = fragment.left, fragment.right
    over = [column for column, (lo, hi) in extents.items() if left < hi and lo < right]
    if len(over) == 1:
        return over[0]
    if over:
        return None
    if any(left < boundary < right for boundary in boundaries):
        return None
    return sum(1 for boundary in boundaries if left >= boundary)


def _rows(row_slots: list[Slots], header: Slots | None) -> list[Element]:
    """TABLE_ROW elements with (row, col)-addressed cells; a header row leads and
    the data rows number on from it."""
    numbered: list[tuple[Slots, bool]] = [(header, True)] if header is not None else []
    numbered += [(slots, False) for slots in row_slots]
    rows: list[Element] = []
    for row_index, (slots, is_header) in enumerate(numbered):
        cells = [
            Element(
                element_type="TABLE_CELL",
                box=union_box([s.box for s in slot_spans]),
                spans=tuple(slot_spans),
                confidence=UNRULED_CONFIDENCE,
                attributes={"row": row_index, "col": col},
            )
            for col, slot_spans in sorted(slots.items())
        ]
        attributes: dict = {"row": row_index}
        if is_header:
            attributes["header"] = True
        rows.append(
            Element(
                element_type="TABLE_ROW",
                box=union_box([cell.box for cell in cells]),
                spans=(),
                confidence=UNRULED_CONFIDENCE,
                attributes=attributes,
                children=cells,
            )
        )
    return rows


def _confirm_ruled(
    rows: list[Element], column_count: int, box: Box, rulings: Rulings | None
) -> tuple[bool, Box | None]:
    if rulings is None or (not rulings.verticals and not rulings.horizontals):
        return False, None

    top, bottom = box.y, box.y + box.height
    left, right = box.x, box.x + box.width
    matched: list[Box] = []

    # Interior column boundaries: a vertical ruling inside each inter-column gap.
    for col in range(column_count - 1):
        gap_lo = max(
            (c.box.x + c.box.width for r in rows for c in r.children
             if c.attributes["col"] == col),
            default=None,
        )
        gap_hi = min(
            (c.box.x for r in rows for c in r.children if c.attributes["col"] == col + 1),
            default=None,
        )
        if gap_lo is None or gap_hi is None:
            return False, None
        hit = _find_segment(
            rulings.verticals, gap_lo - RULING_SLACK_PT, gap_hi + RULING_SLACK_PT,
            top, bottom,
        )
        if hit is None:
            return False, None
        x, y0, y1 = hit
        matched.append(Box(x, y0, 0.0, y1 - y0))

    # Interior row boundaries: a horizontal ruling inside each inter-row gap.
    for above, below in zip(rows, rows[1:]):
        gap_lo = above.box.y + above.box.height
        gap_hi = below.box.y
        hit = _find_segment(
            rulings.horizontals, gap_lo - RULING_SLACK_PT, gap_hi + RULING_SLACK_PT,
            left, right,
        )
        if hit is None:
            return False, None
        y, x0, x1 = hit
        matched.append(Box(x0, y, x1 - x0, 0.0))

    return True, union_box([box, *matched])


def _find_segment(
    segments: tuple[tuple[float, float, float], ...],
    position_lo: float,
    position_hi: float,
    extent_lo: float,
    extent_hi: float,
) -> tuple[float, float, float] | None:
    """A segment whose position lies in [lo, hi] and which covers >= RULING_COVERAGE
    of [extent_lo, extent_hi] along its length."""
    required = RULING_COVERAGE * (extent_hi - extent_lo)
    for position, start, end in segments:
        if position_lo <= position <= position_hi:
            coverage = min(end, extent_hi) - max(start, extent_lo)
            if coverage >= required:
                return (position, start, end)
    return None
