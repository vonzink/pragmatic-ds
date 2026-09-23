#!/usr/bin/env python3
"""Draft an extraction schema from a worked example — geometry, never guesswork.

    .venv/bin/python tools/schema_draft.py corpus/<file>.pdf \
        [--type W2] [--version 0.1.0-draft] [--out draft.json]

Schema authoring is the brake on document-type coverage: 37 schema versions across
23 Flyway migrations, every regex hand-written. This tool turns that job from
"write regexes" into "review a generated draft".

THE INSIGHT
-----------
A corpus PDF has positioned text with real coordinates, and a
``<basename>.answers.json`` ground-truth key beside it says what the correct value
is. Knowing the value AND the box it occupies, the label printed to its LEFT (or
ABOVE, or BELOW) can be read off the page mechanically — and that is exactly what
an ``ANCHOR_LABEL`` / ``LABEL_BELOW`` / ``LABEL_ABOVE`` rung names. No LLM, no
fuzzy bridging: page geometry in, extractor ladder out.

HOW IT WORKS
------------
1. Spans come from ``pragmaticds_docengine_worker.text.extract_text`` — the WORKER'S OWN
   extractor, so the boxes this tool reasons over are the boxes production will
   see, not a second reading of the same PDF.
2. Every answer-key value is LOCATED: the contiguous span run on one visual line
   whose printed text is that value. Not found ⇒ unresolved, never guessed.
3. Label candidates are enumerated around the located box — contiguous caption
   runs to the left on its own line, above it within ``maxDropPt``, below it
   within ``maxRisePt`` — each filtered by the PII guard below.
4. Every candidate rung is then SIMULATED against the page by a faithful port of
   the engine's own geometry (``VisualLines``, ``SpanJoin``, ``TextFold``,
   ``cellWindow``, ``ownsSpan``, ``rightOfLabel``, ``ColumnBand``). A rung is
   emitted ONLY if the simulation recovers the expected value. A candidate that
   merely looks plausible is dropped.
5. Surviving rungs become an ordered extractor ladder; fields with no surviving
   rung are PARKED in ``_draft.unresolved`` with a reason code, so the emitted
   ``fields`` array stays loadable by ``ExtractionSchemaLoader`` and the gap stays
   loud. A wrong generated regex that looks plausible is worse than an obvious gap.

PII DISCIPLINE IS ABSOLUTE
--------------------------
Corpus documents carry NPI. The draft contains LABELS, REGEX PATTERNS and
GEOMETRY only:

- Value patterns are chosen from a FIXED CATALOG of shape families (``money``,
  ``ssn``, ``date``, ...) copied from already-shipped schemas. Only the family
  CHOICE comes from the observed value; no character of the value ever reaches
  the pattern.
- A candidate label is rejected if it equals or contains any answer-key value, or
  if it matches a data shape itself — a "label" that is really the neighbouring
  field's value never becomes a literal anchor.
- Nothing prints a captured value. The stderr report names fields and reasons, the
  way ``corpus_score.py`` prints verdicts rather than values.

WHAT THE SIMULATION IS AND IS NOT
---------------------------------
It is a port of the engine's geometry, close enough to reject bad rungs and to
pin ``occurrence`` indices. It is NOT the engine: it has no OCR path, no detected
grid (``TABLE_CLUSTER``), no checkbox/signature detectors, and it reads the PDF
directly rather than through a parse the stack has stored. A drafted schema is a
starting point a human reviews and a real ``corpus_score.py`` run verifies.

Requires pdfplumber (via ``worker/src``), which ``tools/gen_realworld_fixtures.py``
already depends on. Everything below the "── pure core ──" marker is pure and
unit-tested from canned spans in tools/tests/test_schema_draft.py — no PDF, no
Docker, no stack.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# ── pure core ───────────────────────────────────────────────────────────────

#: TextFold's table (classification/match/TextFold.java), length-preserving.
FOLDINGS = {
    "‘": "'", "’": "'", "‛": "'", "ʼ": "'",
    "“": '"', "”": '"', "‟": '"',
    "‐": "-", "‑": "-", "‒": "-", "–": "-",
    "—": "-", "―": "-",
    " ": " ", " ": " ",
}

#: SpanJoin.EM_FRACTION — below this fraction of the em two spans print as one token.
EM_FRACTION = 0.10
#: VisualLines' row test: centres within half the SMALLER height.
ROW_HALF = 0.5
#: DefaultFieldExtractionEngine.EDGE_EPSILON.
EDGE_EPSILON = 0.01
#: cellWindow's RUN_GAP_EM — one em separates a caption's own tail from the next caption.
RUN_GAP_EM = 1.0
#: ExtractionSchemaLoader.DEFAULT_MAX_DROP_PT / DEFAULT_MAX_RISE_PT / DEFAULT_CELL_OVERLAP.
DEFAULT_MAX_DROP_PT = 24.0
DEFAULT_MAX_RISE_PT = 24.0
DEFAULT_CELL_OVERLAP = 0.5
#: ExtractionSchemaLoader.MAX_ROWS_CEILING / MAX_LINE_OFFSET.
MAX_ROWS_CEILING = 99
MAX_LINE_OFFSET = 10

#: How far left of a value a caption may run and still be one caption. Words
#: within a caption sit 0.3-0.5 em apart (SpanJoin's own measurement); 1.5 em
#: leaves margin for a dot-leader gap without bridging the next box's caption.
LEFT_RUN_GAP_EM = 1.5

#: The shortest run of normalized characters a caption may share with a document
#: value before it is refused as an anchor. Eight is long enough that ordinary
#: English overlap between a caption and a value does not trip it, and short
#: enough to catch a caption window that ran three words into a typed entry.
VALUE_NGRAM = 8


def fold(text: str) -> str:
    """TextFold.fold — 1 char in, 1 char out, so offsets never shift."""
    return "".join(FOLDINGS.get(char, char) for char in text)


def collapse_ws(text) -> str:
    return re.sub(r"\s+", " ", str(text)).strip()


def strip_ws(text) -> str:
    return re.sub(r"\s+", "", str(text))


@dataclass(frozen=True)
class Span:
    """One positioned word, in canonical points (top-left origin, y downward)."""

    text: str
    x: float
    y: float
    width: float
    height: float
    ordinal: int = 0

    @property
    def x1(self) -> float:
        return self.x + self.width

    @property
    def y1(self) -> float:
        return self.y + self.height

    @property
    def cx(self) -> float:
        return self.x + self.width / 2.0

    @property
    def cy(self) -> float:
        return self.y + self.height / 2.0


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    width: float
    height: float

    @property
    def x1(self) -> float:
        return self.x + self.width

    @property
    def y1(self) -> float:
        return self.y + self.height


def bounding_box(spans: list[Span]) -> Box:
    x = min(s.x for s in spans)
    y = min(s.y for s in spans)
    return Box(x, y, max(s.x1 for s in spans) - x, max(s.y1 for s in spans) - y)


# ── VisualLines / SpanJoin ports ────────────────────────────────────────────

def _same_row(a: Span, b: Span) -> bool:
    em = min(a.height, b.height)
    if em <= 0:
        return False
    return abs(b.cy - a.cy) <= em * ROW_HALF


def visual_lines(spans: list[Span]) -> list[list[Span]]:
    """VisualLines.group — scan in centre order, split when centres are more than
    half the SMALLER height apart. Lines top-to-bottom, spans within a line by x."""
    if len(spans) < 2:
        return [list(spans)] if spans else []
    ordered = sorted(spans, key=lambda s: (s.cy, s.x))
    lines: list[list[Span]] = []
    current: list[Span] = []
    previous: Span | None = None
    for span in ordered:
        if previous is None or not _same_row(previous, span):
            current = []
            lines.append(current)
        current.append(span)
        previous = span
    return [sorted(line, key=lambda s: s.x) for line in lines]


def in_row_order(spans: list[Span]) -> list[Span]:
    """VisualLines.inRowOrder — rows in the order their first span was READ, each
    row in its own spans' reading order. Splits runs, never re-sequences them."""
    if len(spans) < 2:
        return list(spans)
    position = {id(span): i for i, span in enumerate(spans)}
    rows = [sorted(row, key=lambda s: position[id(s)]) for row in visual_lines(spans)]
    rows.sort(key=lambda row: position[id(row[0])])
    return [span for row in rows for span in row]


def join_separator(left: Span, right: Span) -> str:
    """SpanJoin.separator — nothing when the page printed one token, else a space."""
    em = min(left.height, right.height)
    if em <= 0:
        return " "
    if not _same_row(left, right):
        return " "
    return "" if abs(right.x - left.x1) < EM_FRACTION * em else " "


class ScopeText:
    """SpanText: spans joined the way the page printed them, with the offset table
    that maps a matched range back to the spans it covers."""

    def __init__(self, spans: list[Span]) -> None:
        self.spans = list(spans)
        parts: list[str] = []
        starts: list[int] = []
        length = 0
        for i, span in enumerate(self.spans):
            if i > 0:
                sep = join_separator(self.spans[i - 1], span)
                parts.append(sep)
                length += len(sep)
            starts.append(length)
            parts.append(span.text)
            length += len(span.text)
        self.text = "".join(parts)
        self.matchable = fold(self.text)
        self.starts = starts

    def find_occurrence(self, pattern: re.Pattern, occurrence: int) -> tuple[int, int] | None:
        if not self.text:
            return None
        found = list(pattern.finditer(self.matchable))
        if occurrence >= len(found):
            return None
        match = found[occurrence]
        return match.start(), match.end()

    def occurrences(self, pattern: re.Pattern) -> list[tuple[int, int]]:
        if not self.text:
            return []
        return [(m.start(), m.end()) for m in pattern.finditer(self.matchable)]

    def overlapping(self, span_range: tuple[int, int]) -> list[Span]:
        start, end = span_range
        hit = []
        for i, span in enumerate(self.spans):
            span_start = self.starts[i]
            if span_start < end and span_start + len(span.text) > start:
                hit.append(span)
        return hit

    def slice(self, span_range: tuple[int, int]) -> str:
        return self.text[span_range[0]:span_range[1]]


def literal_pattern(literal: str) -> re.Pattern:
    """TextFold.literalPattern — case-insensitive containment of the FOLDED phrase."""
    return re.compile(re.escape(fold(literal)), re.IGNORECASE)


def value_pattern(authored: str) -> re.Pattern:
    return re.compile(authored)


# ── page context ────────────────────────────────────────────────────────────

class Page:
    """A page's spans, prepared exactly as the engine prepares one."""

    def __init__(self, spans: list[Span], index: int = 0) -> None:
        self.index = index
        self.spans = list(spans)
        self.lines = visual_lines(self.spans)
        self.text = ScopeText(in_row_order(self.spans))

    def line_containing(self, span: Span) -> list[Span]:
        for line in self.lines:
            for candidate in line:
                if candidate is span:
                    return line
        return [span]

    def find_label_spans(self, literal: str) -> list[Span]:
        found = self.text.find_occurrence(literal_pattern(literal), 0)
        if found is None:
            return []
        return self.text.overlapping(found)

    def literal_hits(self, literal: str) -> int:
        return len(self.text.occurrences(literal_pattern(literal)))


def right_of_label(line: list[Span], label_spans: list[Span]) -> list[Span]:
    """DefaultFieldExtractionEngine.rightOfLabel."""
    on_line = [s for s in label_spans if any(c is s for c in line)]
    if not on_line:
        return []
    cutoff = max(s.x1 for s in on_line) - EDGE_EPSILON
    return [s for s in line if not any(c is s for c in label_spans) and s.x >= cutoff]


def cell_window(label_box: Box, label_spans: list[Span], page: Page) -> Box:
    """DefaultFieldExtractionEngine.cellWindow — the caption's own extent, widened
    only as far as the next caption ON ITS OWN LINE. Fail-closed: no printed
    boundary, no widening."""
    cutoff = label_box.x1 - EDGE_EPSILON
    line = page.line_containing(label_spans[0])
    rightward = sorted(
        (s for s in line if not any(c is s for c in label_spans) and s.x >= cutoff),
        key=lambda s: s.x,
    )
    run_right = label_box.x1
    next_caption_x = None
    for span in rightward:
        em = min(span.height, label_box.height)
        if em > 0 and (span.x - run_right) <= RUN_GAP_EM * em:
            run_right = max(run_right, span.x1)
            continue
        next_caption_x = span.x
        break
    right = run_right if next_caption_x is None else max(next_caption_x, run_right)
    return Box(label_box.x, label_box.y, right - label_box.x, label_box.height)


def owns_span(cell: Box, span: Span, fraction: float) -> bool:
    """DefaultFieldExtractionEngine.ownsSpan — at least `fraction` of the SPAN'S
    OWN width lies inside the cell window. Touching edges are not an overlap."""
    overlap = min(cell.x1, span.x1) - max(cell.x, span.x)
    if overlap <= 0 or span.width <= 0:
        return False
    return overlap >= span.width * fraction


@dataclass(frozen=True)
class ColumnBand:
    """A COLUMN group's half-open [min, max) band; a span belongs to the band that
    contains its box's CENTRE."""

    key: str
    min: float
    max: float

    def contains(self, span: Span) -> bool:
        return self.min <= span.cx < self.max


def column_bands(keys: list[str], centers: list[float]) -> list[ColumnBand]:
    bands = []
    for i, center in enumerate(centers):
        low = centers[0] - (centers[1] - centers[0]) / 2.0 if i == 0 else (centers[i - 1] + center) / 2.0
        high = (
            center + (center - centers[i - 1]) / 2.0
            if i == len(centers) - 1
            else (center + centers[i + 1]) / 2.0
        )
        bands.append(ColumnBand(keys[i], low, high))
    return bands


def _ordered_key_centers(keys: list[str], line: list[Span]) -> list[float] | None:
    centers = []
    for key in keys:
        hit = next((s for s in line if fold(s.text.strip()) == fold(key)), None)
        if hit is None:
            return None
        centers.append(hit.cx)
    if any(centers[i - 1] >= centers[i] for i in range(1, len(centers))):
        return None
    return centers


def _exact_ordered_key_centers(keys: list[str], line: list[Span]) -> list[float] | None:
    folded = [fold(k) for k in keys]
    key_spans = [s for s in line if fold(s.text.strip()) in folded]
    if len(key_spans) != len(keys):
        return None
    if any(fold(key_spans[i].text.strip()) != folded[i] for i in range(len(keys))):
        return None
    return _ordered_key_centers(keys, key_spans)


def resolve_column_bands(header: str, keys: list[str], page: Page) -> list[ColumnBand] | None:
    """DefaultFieldExtractionEngine.columnBands — measured from the PRINTED key row."""
    if len(keys) < 2:
        return None
    label_spans = page.find_label_spans(header)
    if not label_spans:
        return None
    header_line = page.line_containing(label_spans[0])
    centers = _ordered_key_centers(keys, header_line)
    if centers is None:
        try:
            index = page.lines.index(header_line)
        except ValueError:
            return None
        if index + 1 >= len(page.lines):
            return None
        centers = _exact_ordered_key_centers(keys, page.lines[index + 1])
        exact_rows = sum(
            1 for line in page.lines if _exact_ordered_key_centers(keys, line) is not None
        )
        if centers is None or exact_rows != 1:
            return None
    return column_bands(keys, centers)


def confine(spans: list[Span], band: ColumnBand | None) -> list[Span]:
    return spans if band is None else [s for s in spans if band.contains(s)]


# ── rung simulation ─────────────────────────────────────────────────────────

def _scope_spans(
    method: str,
    label: str,
    scope: str,
    page: Page,
    max_drop: float,
    max_rise: float,
    cell_overlap: float,
) -> list[Span] | None:
    if method == "REGEX":
        return list(page.text.spans)
    label_spans = page.find_label_spans(label)
    if not label_spans:
        return None
    label_box = bounding_box(label_spans)
    if method == "ANCHOR_LABEL":
        line = page.line_containing(label_spans[0])
        if scope == "LINE":
            return line
        if scope == "LINE_RIGHT":
            return right_of_label(line, label_spans)
        return list(page.text.spans)
    cell = cell_window(label_box, label_spans, page)
    if method == "LABEL_BELOW":
        candidates = [
            s
            for s in page.spans
            if s.y >= label_box.y1 and (s.y - label_box.y1) <= max_drop
        ]
    elif method == "LABEL_ABOVE":
        candidates = [
            s
            for s in page.spans
            if s.y1 <= label_box.y and (label_box.y - s.y1) <= max_rise
        ]
    else:
        return None
    inside = [s for s in candidates if owns_span(cell, s, cell_overlap)]
    return sorted(inside, key=lambda s: (s.y, s.x))


def simulate(rung: dict, page: Page, band: ColumnBand | None = None) -> str | None:
    """Run one drafted rung against the page and return the text it captures, or
    None. The tool emits a rung ONLY when this recovers the expected value."""
    method = rung["method"]
    label = (rung.get("label") or {}).get("pattern", "")
    value = rung["value"]
    scope = value.get("scope", "LINE")
    spans = _scope_spans(
        method,
        label,
        scope,
        page,
        rung.get("maxDropPt", DEFAULT_MAX_DROP_PT),
        rung.get("maxRisePt", DEFAULT_MAX_RISE_PT),
        rung.get("cellOverlap", DEFAULT_CELL_OVERLAP),
    )
    if spans is None:
        return None
    spans = confine(spans, band)
    if not spans:
        return None
    scope_text = ScopeText(spans)
    found = scope_text.find_occurrence(value_pattern(value["pattern"]), value["occurrence"])
    if found is None:
        return None
    return scope_text.slice(found)


def _match_index(scope_text: ScopeText, pattern: str, expected: str) -> int | None:
    """The 0-based occurrence index whose captured text IS the expected value."""
    for index, span_range in enumerate(scope_text.occurrences(value_pattern(pattern))):
        if values_equal(scope_text.slice(span_range), expected):
            return index
    return None


def values_equal(captured, expected) -> bool:
    """Whitespace-tolerant equality. The Schedule E note is why the second arm
    exists: values typed over a preprinted '( )' extract as '( 1 8 , 5 9 0 )'."""
    if collapse_ws(captured) == collapse_ws(expected):
        return True
    return strip_ws(captured) == strip_ws(expected) and strip_ws(expected) != ""


# ── the value-pattern catalog ───────────────────────────────────────────────

@dataclass(frozen=True)
class Family:
    """One shape family. `recognize` classifies the OBSERVED value; `pattern` is a
    FIXED literal copied from an already-shipped schema. No character of the value
    ever reaches the emitted pattern — that is the PII firewall."""

    name: str
    recognize: str
    pattern: str
    data_type: str
    normalizer: str | None = None
    sensitive: bool = False


MONEY_CENTS = r"(?<![\d,.])\$?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2}(?!\d)"
MONEY_WHOLE = r"(?<![\d,.])\$?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{2})?(?!\d)"
MONEY_PARENS = r"\(\s*\$?\s*(?:\d[\d, ]*)(?:\.\s*\d\s*\d)?\s*\)"
PERSON_NAME = r"(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])"
CAPS_NAME = r"(?<![A-Za-z])[A-Z][A-Z.'-]+(?: [A-Z][A-Z.'-]*){1,3}(?![A-Za-z])"
ORG_NAME = (
    r"(?<![A-Za-z])[A-Z][A-Za-z&'-]*(?: [A-Z&][A-Za-z&'-]*){0,4}"
    r"(?:,? (?:LLC|L\.L\.C\.|Inc\.?|Corp\.?|Co\.|Ltd\.?|Company))(?![A-Za-z])"
)
#: The last resort: whatever the cell holds. Emitted only when the located scope
#: contains the value and NOTHING else, and always listed for review.
OPEN_TEXT = r"\S+(?: \S+)*"

FAMILIES: tuple[Family, ...] = (
    Family("ssn", r"\d{3}-\d{2}-\d{4}", r"(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)", "STRING", None, True),
    # An EIN is a business identifier, not the employee's NPI: not masked.
    Family("ein", r"\d{2}-\d{7}", r"(?<!\d)\d{2}-\d{7}(?!\d)", "STRING"),
    Family(
        "date_mdy",
        r"\d{1,2}/\d{1,2}/\d{2,4}",
        r"(?<!\d)\d{1,2}/\d{1,2}/\d{4}(?!\d)",
        "DATE",
        "date",
    ),
    Family(
        "date_long",
        r"(?:January|February|March|April|May|June|July|August|September|October|"
        r"November|December) \d{1,2}, \d{4}",
        r"(?:January|February|March|April|May|June|July|August|September|October|"
        r"November|December)\s+\d{1,2},\s+\d{4}",
        "DATE",
        "date",
    ),
    Family("year", r"(?:19|20)\d{2}", r"(?<!\d)(?:19|20)\d{2}(?!\d)", "STRING"),
    Family("money_parens", r"\(\s*\$?[\d, ]+(?:\.\s*\d\s*\d)?\s*\)", MONEY_PARENS, "MONEY", "money"),
    Family("money_cents", r"\$?\d{1,3}(?:,\d{3})*\.\d{2}", MONEY_CENTS, "MONEY", "money"),
    Family("money_whole", r"\$?\d{1,3}(?:,\d{3})+", MONEY_WHOLE, "MONEY", "money"),
    Family("state_code", r"[A-Z]{2}", r"(?<![A-Za-z])[A-Z]{2}(?![A-Za-z])", "STRING"),
    Family("code_token", r"[A-Z]{1,4}", r"(?<![A-Za-z])[A-Z]{1,4}(?![A-Za-z])", "STRING"),
    Family("integer", r"\d{1,6}", r"(?<![\d,.])\d{1,6}(?!\d)", "NUMBER"),
    Family("org_name", r"[A-Z][\w&'.-]*(?: [\w&'.-]+)*,? (?:LLC|L\.L\.C\.|Inc\.?|Corp\.?|Co\.|Ltd\.?|Company)", ORG_NAME, "STRING"),
    Family("person_name", r"[A-Z][a-z]+(?: [A-Z]\.?){0,2}(?: [A-Z][a-z]+)?", PERSON_NAME, "STRING", "personName"),
    Family("caps_name", r"[A-Z][A-Z.'-]+(?: [A-Z][A-Z.'-]*){1,3}", CAPS_NAME, "STRING", "personName"),
)

OPEN_TEXT_FAMILY = Family("open_text", r".+", OPEN_TEXT, "STRING")

#: Labels naming values that must persist masked. `sensitive` is also set by the
#: ssn/ein families; a label hit widens that to the fields whose shape is ordinary.
SENSITIVE_LABEL = re.compile(
    # "social security NUMBER", never bare "social security" — box 3 is
    # "Social security wages", an amount, and masking it would be wrong.
    r"social security number|\bssn\b|taxpayer identification|\btax id\b|account number|"
    r"routing number|date of birth|driver'?s licen[cs]e|passport",
    re.IGNORECASE,
)


def classify_value(value: str) -> Family | None:
    """The shape family the observed value belongs to, most specific first."""
    text = collapse_ws(value)
    if not text:
        return None
    for family in FAMILIES:
        if re.fullmatch(family.recognize, text):
            return family
    return None


#: The families whose shape a printed CAPTION can never have. Deliberately the
#: STRUCTURED ones only: a person/organisation/caps name is shaped exactly like a
#: two-word Title Case caption ("Pay Date", "Present Position", "Account Number"),
#: and refusing that shape outright refuses most of the anchors a real form
#: prints. The values guard in `is_usable_label` is what keeps a NAME out of a
#: label; this tuple only catches text that is unmistakably data.
NAME_FAMILIES = {"person_name", "caps_name", "org_name"}
DATA_SHAPES = tuple(
    re.compile(rf"^(?:{f.recognize})$") for f in FAMILIES if f.name not in NAME_FAMILIES
)
#: ...and the mirror: a caption that DOES have a name's shape and that the answer
#: key does not corroborate is flagged for review, because the one PII the values
#: guard cannot see is a person printed on the page whom the key never lists.
NAME_SHAPES = tuple(
    re.compile(rf"^(?:{f.recognize})$") for f in FAMILIES if f.name in NAME_FAMILIES
)


def looks_like_a_name(text: str) -> bool:
    collapsed = collapse_ws(text)
    return any(shape.match(collapsed) for shape in NAME_SHAPES)


def looks_like_data(text: str) -> bool:
    collapsed = collapse_ws(text)
    if not collapsed:
        return True
    return any(shape.match(collapsed) for shape in DATA_SHAPES)


def is_usable_label(text: str, forbidden: set[str]) -> bool:
    """The PII guard. A caption is usable only when it reads like printed form
    text AND cannot be any document value — a 'label' that is really the
    neighbouring field's value must never become a literal anchor."""
    collapsed = collapse_ws(text)
    if not collapsed or len(collapsed) > 90:
        return False
    # A printed caption carries at least one real word. Two-letter debris ("de DD",
    # a split "Code" column head beside a box-12 code) is not a caption, and an
    # anchor built from it would bind to noise on the next document.
    if not re.search(r"[A-Za-z]{3,}", collapsed):
        return False
    if looks_like_data(collapsed):
        return False
    haystacks = {collapsed.casefold(), strip_ws(collapsed).casefold()}
    tokens = {t.casefold() for t in re.findall(r"[^\s]+", collapsed)}
    tokens |= {t.strip(".,;:()").casefold() for t in tokens}
    for value in forbidden:
        # A value INSIDE the caption ("... Cascade Ridge Logistics, Inc.").
        if len(value) >= 3 and any(value in haystack for haystack in haystacks):
            return False
        # The caption inside a VALUE — the fragment case: "Inc." and "Denver,"
        # are pieces of the employer block, and a piece of NPI is still NPI.
        if len(collapsed) >= 3 and any(haystack in value for haystack in haystacks):
            return False
        # A short ALPHABETIC value printed as its own word: "DD", "CO". Too short
        # for either containment test to be safe, and exactly the shape a box-12
        # code or a state abbreviation takes. Alphabetic and 2+ chars on purpose:
        # a numeric value ("1", "12") shares its spelling with the box numbers
        # every IRS caption opens with, and rejecting those would throw away the
        # best anchors on the form.
        if len(value) >= 2 and re.fullmatch(r"[a-z][a-z.'-]*", value) and value in tokens:
            return False
    # PARTIAL overlap. A caption run can end mid-value — a Schedule E "Other
    # (list)" caption is printed on the same line as the typed description, and a
    # window that takes the caption plus three words of the description contains
    # neither the whole value nor is contained by it. Any run of NGRAM normalized
    # characters shared with a value is enough to refuse.
    normalized = normalize_caption(collapsed)
    for value in forbidden:
        folded = normalize_caption(value)
        if len(folded) < VALUE_NGRAM:
            continue
        if any(folded[i:i + VALUE_NGRAM] in normalized
               for i in range(len(folded) - VALUE_NGRAM + 1)):
            return False
    return True


def forbidden_values(entries: list[dict]) -> set[str]:
    """Every answer-key value, in both collapsed and whitespace-stripped form,
    case-folded — the set no emitted label may contain."""
    out: set[str] = set()
    for entry in entries:
        raw = entry.get("value")
        if raw is None:
            continue
        for chunk in str(raw).split("\n"):
            collapsed = collapse_ws(chunk)
            if collapsed:
                out.add(collapsed.casefold())
                out.add(strip_ws(collapsed).casefold())
    return out


# ── locating a value on the page ────────────────────────────────────────────

def locate_value(page: Page, value: str) -> list[list[Span]]:
    """Every contiguous span run on one visual line whose printed text IS the
    value. Empty ⇒ the value is not in the text layer (a checkbox glyph, an
    unflattened form field, an OCR drop) and the field is unresolved, not guessed."""
    wanted = collapse_ws(value)
    if not wanted:
        return []
    stripped = strip_ws(wanted)
    hits: list[list[Span]] = []
    for line in page.lines:
        scope = ScopeText(line)
        for match in re.finditer(re.escape(fold(wanted)), scope.matchable):
            hits.append(scope.overlapping((match.start(), match.end())))
        if hits:
            continue
        # The preprinted-parens case: the typed value interleaves with the form's
        # own space glyphs, so compare with whitespace removed.
        dense, index_of = [], []
        for i, char in enumerate(scope.matchable):
            if not char.isspace():
                dense.append(char)
                index_of.append(i)
        joined = "".join(dense)
        for match in re.finditer(re.escape(strip_ws(fold(wanted))), joined):
            if not stripped:
                continue
            start = index_of[match.start()]
            end = index_of[match.end() - 1] + 1
            hits.append(scope.overlapping((start, end)))
    return hits


# ── label candidate discovery ───────────────────────────────────────────────

@dataclass(frozen=True)
class Candidate:
    method: str
    label: str
    scope: str | None
    distance: float


LEADER = re.compile(r"^[.·•_\-–—\s]+$")


def page_literal(page: Page, run: list[Span]) -> str | None:
    """The caption AS THE PAGE'S OWN JOINED TEXT SPELLS IT, or None when the run
    is not contiguous there.

    A literal anchor is matched against the page-wide text the engine builds with
    ``VisualLines.inRowOrder`` — rows in READING order, each row in its own spans'
    reading order — which on a dense form is NOT left-to-right. A caption assembled
    by x from the same spans can therefore be a string the page never contains, and
    every rung built on it fails at ``findFirst``. Slicing the page's own text is
    the only spelling guaranteed to bind."""
    position = {id(span): i for i, span in enumerate(page.text.spans)}
    try:
        indexes = sorted(position[id(span)] for span in run)
    except KeyError:
        return None
    if indexes != list(range(indexes[0], indexes[0] + len(indexes))):
        return None
    start = page.text.starts[indexes[0]]
    last = indexes[-1]
    end = page.text.starts[last] + len(page.text.spans[last].text)
    return collapse_ws(page.text.text[start:end])


def _trim_run(run: list[Span]) -> list[Span]:
    """Drop dot leaders and rule glyphs from both ends of a caption run."""
    trimmed = list(run)
    while trimmed and LEADER.match(trimmed[-1].text):
        trimmed.pop()
    while trimmed and LEADER.match(trimmed[0].text):
        trimmed.pop(0)
    return trimmed


#: The most words a drafted caption may carry. Longer than a printed caption ever
#: is, and short enough that the window walk stays cheap.
MAX_CAPTION_WORDS = 8


def caption_windows(page: Page, words: list[Span]) -> list[tuple[str, Span]]:
    """Every printable caption a run of words can spell, as (literal, last span).

    A window must be CONTIGUOUS in the page's own reading order — see
    ``page_literal`` — and unbroken by a printed gap wider than
    ``LEFT_RUN_GAP_EM``. Windows rather than one maximal run because the caption a
    reader would name is rarely the whole left margin: on a 1040 line the words
    are "b Taxable interest", then a rule of periods, then the line number "2b",
    then the amount. "Taxable interest" is the anchor; the run ending at the value
    is not."""
    out: list[tuple[str, Span]] = []
    for j in range(len(words) - 1, -1, -1):
        for i in range(j, max(-1, j - MAX_CAPTION_WORDS), -1):
            window = words[i:j + 1]
            if any(
                min(a.height, b.height) <= 0
                or (b.x - a.x1) > LEFT_RUN_GAP_EM * min(a.height, b.height)
                for a, b in zip(window, window[1:])
            ):
                break
            literal = page_literal(page, window)
            if literal:
                out.append((literal, words[j]))
    return out


def left_candidates(page: Page, value_spans: list[Span], forbidden: set[str]) -> list[Candidate]:
    """Captions printed to the LEFT of the value on its own visual line — the
    ANCHOR_LABEL / LINE_RIGHT shape."""
    line = page.line_containing(value_spans[0])
    box = bounding_box(value_spans)
    words = [
        s for s in sorted(line, key=lambda s: s.x)
        if s.x1 <= box.x + EDGE_EPSILON and not LEADER.match(s.text)
    ]
    if not words:
        return []
    out = []
    for literal, last in caption_windows(page, words):
        if is_usable_label(literal, forbidden):
            # The distance is the PRINTED GAP from the caption's right edge to the
            # value, in points — the same unit the vertical candidates measure in,
            # so "nearest printed caption" means one thing across directions.
            out.append(Candidate("ANCHOR_LABEL", literal, "LINE_RIGHT", max(0.0, box.x - last.x1)))
    return out


def _vertical_candidates(
    page: Page,
    value_spans: list[Span],
    forbidden: set[str],
    method: str,
    reach: float,
) -> list[Candidate]:
    box = bounding_box(value_spans)
    out: list[Candidate] = []
    for line in page.lines:
        line_box = bounding_box(line)
        if method == "LABEL_BELOW":
            gap = box.y - line_box.y1
        else:
            gap = line_box.y - box.y1
        if gap < 0 or gap > reach:
            continue
        # Contiguous caption runs on that line, cut where the page prints a real gap.
        runs: list[list[Span]] = []
        current: list[Span] = []
        for span in line:
            if not current:
                current = [span]
                continue
            em = min(current[-1].height, span.height)
            if em > 0 and (span.x - current[-1].x1) <= RUN_GAP_EM * em:
                current.append(span)
            else:
                runs.append(current)
                current = [span]
        if current:
            runs.append(current)
        for run in runs:
            run = _trim_run(run)
            if not run:
                continue
            for text, _ in caption_windows(page, run):
                if not is_usable_label(text, forbidden):
                    continue
                spans = page.find_label_spans(text)
                if not spans:
                    continue
                cell = cell_window(bounding_box(spans), spans, page)
                if not all(owns_span(cell, s, DEFAULT_CELL_OVERLAP) for s in value_spans):
                    continue
                out.append(Candidate(method, text, None, gap))
    return out


def normalize_caption(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(text).lower())


def echoes_key_label(candidate: str, key_label: str | None) -> bool:
    """Whether the printed caption and the answer key's own label are the same
    caption. The key ALREADY names the printed wording, and that is the only
    discriminator available when one value's text appears twice on a page — a
    W-2's box 16 repeats box 1 verbatim, and geometry alone cannot say which
    occurrence the key entry meant."""
    if not key_label:
        return False
    a, b = normalize_caption(candidate), normalize_caption(key_label)
    if len(a) < 6 or len(b) < 6:
        return False
    return a in b or b in a


def candidates_for(page: Page, value_spans: list[Span], forbidden: set[str]) -> list[Candidate]:
    found = left_candidates(page, value_spans, forbidden)
    found += _vertical_candidates(page, value_spans, forbidden, "LABEL_BELOW", DEFAULT_MAX_DROP_PT)
    found += _vertical_candidates(page, value_spans, forbidden, "LABEL_ABOVE", DEFAULT_MAX_RISE_PT)
    return found


BASE_STRENGTH = {"ANCHOR_LABEL": 0.9, "LABEL_BELOW": 0.9, "LABEL_ABOVE": 0.85, "REGEX": 0.6}


def build_rung(candidate: Candidate, family: Family, occurrence: int, strength: float) -> dict:
    value: dict = {"pattern": family.pattern, "occurrence": occurrence}
    rung: dict = {"method": candidate.method, "strength": round(strength, 2)}
    if candidate.method == "REGEX":
        value["scope"] = "PAGE"
        rung["value"] = value
        return rung
    rung["label"] = {"kind": "literal", "pattern": candidate.label}
    if candidate.method == "ANCHOR_LABEL":
        value["scope"] = candidate.scope or "LINE_RIGHT"
    elif candidate.method == "LABEL_BELOW":
        rung["maxDropPt"] = DEFAULT_MAX_DROP_PT
        rung["cellOverlap"] = DEFAULT_CELL_OVERLAP
    elif candidate.method == "LABEL_ABOVE":
        rung["maxRisePt"] = DEFAULT_MAX_RISE_PT
        rung["cellOverlap"] = DEFAULT_CELL_OVERLAP
    rung["value"] = value
    return rung


@dataclass
class DraftedRungs:
    rungs: list[dict]
    notes: list[str]


def draft_rungs(
    page: Page,
    expected: str,
    family: Family,
    forbidden: set[str],
    bands: list[ColumnBand] | None = None,
    max_rungs: int = 1,
    allow_regex_fallback: bool = False,
    key_label: str | None = None,
) -> DraftedRungs:
    """Every candidate rung that ROUND-TRIPS, best first. A candidate the
    simulation cannot make recover the expected value is dropped, not softened."""
    notes: list[str] = []
    hits = locate_value(page, expected)
    if not hits:
        return DraftedRungs([], ["value_not_in_text_layer"])
    if len(hits) > 1:
        notes.append("value_text_appears_more_than_once_on_page")

    scored: list[tuple] = []
    seen: set[tuple] = set()
    for value_spans in hits:
        for candidate in candidates_for(page, value_spans, forbidden):
            key = (candidate.method, candidate.label, candidate.scope)
            if key in seen:
                continue
            probe = build_rung(candidate, family, 0, BASE_STRENGTH[candidate.method])
            band = bands[0] if bands else None
            spans = _scope_spans(
                candidate.method,
                candidate.label,
                candidate.scope or "LINE",
                page,
                DEFAULT_MAX_DROP_PT,
                DEFAULT_MAX_RISE_PT,
                DEFAULT_CELL_OVERLAP,
            )
            if spans is None:
                continue
            spans = confine(spans, band)
            if not spans:
                continue
            occurrence = _match_index(ScopeText(spans), family.pattern, expected)
            if occurrence is None:
                continue
            rung = build_rung(candidate, family, occurrence, BASE_STRENGTH[candidate.method])
            if not values_equal(simulate(rung, page, band) or "", expected):
                continue
            seen.add(key)
            unique = page.literal_hits(candidate.label) == 1
            # Page-unique label first, then the rung's own strength, then the
            # NEAREST printed caption, then the most specific (longest) wording.
            rank = (
                0 if echoes_key_label(candidate.label, key_label) else 1,
                0 if unique else 1,
                0 if occurrence == 0 else 1,
                -BASE_STRENGTH[candidate.method],
                round(candidate.distance, 3),
                -len(candidate.label),
            )
            scored.append((rank, rung, candidate, unique, occurrence))

    if allow_regex_fallback and not bands:
        page_scope = ScopeText(page.text.spans)
        matches = page_scope.occurrences(value_pattern(family.pattern))
        if len(matches) == 1 and values_equal(page_scope.slice(matches[0]), expected):
            fallback = Candidate("REGEX", "", "PAGE", 0.0)
            rung = build_rung(fallback, family, 0, BASE_STRENGTH["REGEX"])
            scored.append(((2, 2, 0, -BASE_STRENGTH["REGEX"], 0.0, 0), rung, fallback, True, 0))

    scored.sort(key=lambda pair: pair[0])
    # At most ONE rung per method. A single page cannot supply a genuine ALTERNATE
    # for a rung — a real alternate is the way a SECOND provider captions the same
    # box (V43's ADP wordings), which this document does not know. What a single
    # page does supply is decoys: on a W-2 the box-1 amount round-trips under
    # "OMB No. 1545-0029" as readily as under its own caption, because both
    # captions' cells happen to cover it. Keeping the best of each direction and
    # no more is the honest ceiling on what one worked example can assert.
    rungs, methods_used = [], set()
    for _, rung, candidate, unique, occurrence in scored:
        if rung["method"] in methods_used or len(rungs) >= max_rungs:
            continue
        methods_used.add(rung["method"])
        rungs.append(rung)
        # Notes describe what was EMITTED. A note about a candidate the selection
        # threw away would send a reviewer looking for a rung that is not there.
        if occurrence > 0:
            notes.append(f"occurrence_{occurrence}_on_{candidate.method.lower()}")
        if not unique:
            notes.append(f"label_not_unique_on_page:{candidate.method.lower()}")
        if looks_like_a_name(candidate.label) and not echoes_key_label(
                candidate.label, key_label):
            # Shaped like a person's name and not corroborated by the key's own
            # wording. Almost always a Title Case caption; occasionally a name the
            # answer key never listed, which is the one NPI the values guard is
            # blind to. Loud rather than silent.
            notes.append("label_is_name_shaped_and_uncorroborated")
        if key_label and not echoes_key_label(candidate.label, key_label):
            # The rung round-trips, but the caption it anchors on is not the one
            # the answer key names for this field. That is the signature of a
            # label that WORKS on this page for the wrong reason.
            notes.append(f"label_does_not_echo_key_label:{candidate.method.lower()}")
    # Ladder order must read as a ladder: each rung no stronger than the one above.
    for i in range(1, len(rungs)):
        rungs[i]["strength"] = round(min(rungs[i]["strength"], rungs[i - 1]["strength"] - 0.05), 2)
    if not rungs:
        notes.append("no_rung_round_tripped")
    return DraftedRungs(rungs, notes)


# ── answer-key reading ──────────────────────────────────────────────────────

#: corpus_score.py's occurrence discriminators, most specific first.
OCCURRENCE_KEYS = ("propertyColumn", "column", "row", "entity", "occurrence")


def occurrence_of(entry: dict) -> tuple[str | None, str | None]:
    for name in OCCURRENCE_KEYS:
        if entry.get(name) not in (None, ""):
            return name, str(entry[name])
    return None, None


def field_name_from(label: str, line: str | None) -> str:
    tokens = re.findall(r"[A-Za-z0-9]+", collapse_ws(label))
    tokens = [t for t in tokens if t.lower() not in {"the", "of", "a", "an"}][:6]
    if not tokens:
        tokens = ["field"]
    if line:
        tokens.append("line")
        tokens += re.findall(r"[A-Za-z0-9]+", str(line))
    head = tokens[0].lower()
    return head + "".join(t[:1].upper() + t[1:].lower() for t in tokens[1:])


@dataclass
class KeyEntry:
    name: str
    label: str
    line: str | None
    page: int
    value: str
    occurrence_key: str | None
    occurrence_value: str | None
    multiline: bool
    #: The key's value verbatim, every line of it. Never emitted, never printed —
    #: it exists so the label guard can refuse a caption that IS a document value.
    raw_value: str


def read_entries(key: dict) -> list[KeyEntry]:
    """The `fields` layout — a flat array, one entry per expected occurrence."""
    raw = key.get("fields")
    if not isinstance(raw, list):
        return []
    grouped: dict[tuple, list[dict]] = {}
    for entry in raw:
        if not isinstance(entry, dict) or entry.get("value") in (None, ""):
            continue
        grouped.setdefault((collapse_ws(entry.get("label", "")), str(entry.get("line") or "")), []).append(entry)

    # A field NAME is built from the key's LABEL, and a key label is normally the
    # form's printed caption — but nothing enforces that, and one corpus key
    # spells an authoring note into a label that quotes the value itself. The name
    # is checked against every value before it is used, and a name that would
    # carry one is replaced by a positional one.
    values = [
        normalize_caption(chunk)
        for entry in raw if isinstance(entry, dict) and entry.get("value") not in (None, "")
        for chunk in str(entry["value"]).split("\n")
    ]
    values = [v for v in values if len(v) >= 5]

    def safe(name: str, ordinal: int) -> str:
        normalized = normalize_caption(name)
        return f"field{ordinal}" if any(v in normalized for v in values) else name

    lines_per_label: dict[str, set[str]] = {}
    for label, line in grouped:
        lines_per_label.setdefault(label, set()).add(line)

    entries: list[KeyEntry] = []
    used: set[str] = set()
    for (label, line), members in grouped.items():
        # The printed line number only enters the name when one label captions
        # several lines ("See instructions for box 12" is 12a's caption and 12b's).
        needs_line = len(lines_per_label[label]) > 1
        name = safe(field_name_from(label, line if needs_line else None), len(used) + 1)
        base = name
        suffix = 2
        while name in used:
            name = f"{base}{suffix}"
            suffix += 1
        used.add(name)
        for entry in members:
            occ_key, occ_value = occurrence_of(entry)
            raw_value = str(entry["value"])
            entries.append(
                KeyEntry(
                    name=name,
                    label=label,
                    line=line or None,
                    page=int(entry.get("page") or 0),
                    value=collapse_ws(raw_value.split("\n")[0]),
                    occurrence_key=occ_key,
                    occurrence_value=occ_value,
                    multiline="\n" in raw_value,
                    raw_value=raw_value,
                )
            )
    return entries


# ── group (COLUMN) discovery ────────────────────────────────────────────────

def discover_column_header(
    page: Page, keys: list[str], value_boxes: dict[str, list[Box]], forbidden: set[str]
) -> str | None:
    """The printed caption that locates the key columns: a line carrying every key
    as its own span, each inside its column's x-extent, with a usable caption to
    the left of the first key. Verified by re-running the engine's own banding."""
    bands_by_key: dict[str, tuple[float, float]] = {}
    for key, boxes in value_boxes.items():
        if not boxes:
            return None
        bands_by_key[key] = (min(b.x for b in boxes), max(b.x1 for b in boxes))
    for line in page.lines:
        centers = _ordered_key_centers(keys, line)
        if centers is None:
            continue
        if any(
            not (bands_by_key[key][0] - 24.0 <= center <= bands_by_key[key][1] + 24.0)
            for key, center in zip(keys, centers)
        ):
            continue
        first_key_x = min(
            s.x for s in line if fold(s.text.strip()) in {fold(k) for k in keys}
        )
        left = _trim_run([s for s in line if s.x1 <= first_key_x + EDGE_EPSILON])
        for start in range(len(left) - 1, -1, -1):
            text = page_literal(page, left[start:])
            if not text or not is_usable_label(text, forbidden):
                continue
            if resolve_column_bands(text, keys, page) is not None:
                return text
    return None


# ── drafting ────────────────────────────────────────────────────────────────

@dataclass
class Draft:
    fields: list[dict]
    unresolved: list[dict]
    review: list[dict]


def draft_schema(
    entries: list[KeyEntry],
    pages: dict[int, Page],
    *,
    allow_open_text: bool = True,
    allow_regex_fallback: bool = False,
    max_rungs: int = 1,
) -> Draft:
    # Every LINE of every key value, not just the first: a multi-line employer
    # block's second line is NPI too, and a caption that contains it must never
    # become a literal anchor.
    forbidden = forbidden_values([{"value": e.raw_value} for e in entries])
    by_name: dict[str, list[KeyEntry]] = {}
    for entry in entries:
        by_name.setdefault(entry.name, []).append(entry)

    fields: list[dict] = []
    unresolved: list[dict] = []
    review: list[dict] = []
    #: The header is a property of the GROUP, not of one field over it. Discovering
    #: it once per (page, key set) is what keeps one table from resolving for its
    #: rent column and not for its taxes column.
    header_memo: dict[tuple, str] = {}

    for name, members in by_name.items():
        head = members[0]
        page = pages.get(head.page)
        if page is None:
            unresolved.append({"field": name, "reason": "page_not_parsed"})
            continue

        family = classify_value(head.value)
        open_text = False
        if family is None:
            if not allow_open_text:
                unresolved.append({"field": name, "reason": "no_shape_family"})
                continue
            family = OPEN_TEXT_FAMILY
            open_text = True

        grouped = len(members) > 1 and head.occurrence_key is not None
        if grouped:
            field, notes, reason = _draft_grouped(name, members, page, family, forbidden,
                                                  max_rungs, header_memo)
        else:
            extra_notes = []
            if len(members) > 1:
                if len({m.page for m in members}) != len(members):
                    unresolved.append(
                        {"field": name, "reason": "repeats_with_no_occurrence_discriminator"}
                    )
                    continue
                # The same caption on several PAGES is one single-valued field the
                # engine will find on whichever page it reaches first — a masthead
                # name repeated on page 2 is not a second occurrence.
                extra_notes.append("value_repeats_on_several_pages")
            field, notes, reason = _draft_single(name, head, page, family, forbidden,
                                                 max_rungs, allow_regex_fallback)
            notes = notes + extra_notes

        if field is None:
            # Name and reason ONLY. An answer-key label is not guaranteed to be the
            # form's printed caption, and echoing one verbatim is how a value that
            # a key's author quoted into a label would ride into the draft.
            unresolved.append({"field": name, "reason": reason})
            continue
        field["sensitive"] = bool(family.sensitive or SENSITIVE_LABEL.search(head.label))
        fields.append(field)
        if open_text:
            notes = notes + ["open_ended_cell_pattern"]
        if head.multiline:
            notes = notes + ["key_value_is_multiline_first_line_only"]
        for note in dict.fromkeys(notes):
            review.append({"field": name, "note": note})

    return Draft(fields, unresolved, review)


def _field_shell(name: str, family: Family) -> dict:
    return {
        "name": name,
        "dataType": family.data_type,
        "required": False,
        "normalizer": family.normalizer,
        "sensitive": False,
        "extractors": [],
    }


def _draft_single(name, entry, page, family, forbidden, max_rungs, allow_regex_fallback):
    drafted = draft_rungs(page, entry.value, family, forbidden, max_rungs=max_rungs,
                          allow_regex_fallback=allow_regex_fallback, key_label=entry.label)
    if not drafted.rungs:
        return None, drafted.notes, drafted.notes[-1] if drafted.notes else "no_rung_round_tripped"
    field = _field_shell(name, family)
    field["extractors"] = drafted.rungs
    return field, drafted.notes, None


def _draft_grouped(name, members, page, family, forbidden, max_rungs, header_memo=None):
    keys, boxes = [], {}
    for member in sorted(members, key=lambda m: m.occurrence_value or ""):
        hits = locate_value(page, member.value)
        if not hits:
            return None, [], "group_occurrence_not_in_text_layer"
        keys.append(member.occurrence_value)
        boxes[member.occurrence_value] = [bounding_box(h) for h in hits]
    if len(set(keys)) != len(keys) or len(keys) < 2:
        return None, [], "group_keys_not_distinct"
    # Keys in PAGE order, which is the order the engine bands them in.
    keys.sort(key=lambda k: min(b.x for b in boxes[k]))

    memo_key = (page.index, tuple(keys))
    if header_memo is not None and memo_key in header_memo:
        header = header_memo[memo_key]
    else:
        header = discover_column_header(page, keys, boxes, forbidden)
        if header_memo is not None and header is not None:
            header_memo[memo_key] = header
    if header is None:
        return None, [], "group_header_unresolved"
    bands = resolve_column_bands(header, keys, page)
    if bands is None:
        return None, [], "group_header_unresolved"

    by_key = {m.occurrence_value: m for m in members}
    ladder: list[dict] | None = None
    notes: list[str] = []
    for band in bands:
        drafted = draft_rungs(page, by_key[band.key].value, family, forbidden, bands=[band],
                              max_rungs=max_rungs, allow_regex_fallback=False,
                              key_label=by_key[band.key].label)
        notes += drafted.notes
        keep = [r for r in drafted.rungs
                if values_equal(simulate(r, page, band) or "", by_key[band.key].value)]
        if not keep:
            return None, notes, "group_rung_failed_for_a_key"
        signatures = {json.dumps(r, sort_keys=True) for r in keep}
        ladder = keep if ladder is None else [
            r for r in ladder if json.dumps(r, sort_keys=True) in signatures
        ]
        if not ladder:
            return None, notes, "no_rung_round_tripped_for_every_key"
    field = _field_shell(name, family)
    field["extractors"] = ladder
    field["group"] = {"kind": "COLUMN", "header": {"kind": "literal", "pattern": header},
                      "keys": keys}
    notes.append("column_group_header_discovered_geometrically")
    return field, notes, None


# ── validation (ExtractionSchemaLoader's rules, without a stack) ─────────────

class SchemaInvalid(ValueError):
    """The draft would not load. Mirrors ExtractionSchemaLoader's own refusals."""


LABEL_METHODS = {"ANCHOR_LABEL", "LABEL_BELOW", "LABEL_ABOVE"}
CELL_METHODS = {"LABEL_BELOW", "LABEL_ABOVE", "ROW_CELL"}
METHODS = {
    "ANCHOR_LABEL", "TABLE_CLUSTER", "REGEX", "FORM_FIELD", "OCR_LINE", "LLM", "AI",
    "HUMAN", "NONE", "CHECKBOX_STATE", "SIGNATURE_PRESENCE", "LABEL_BELOW", "ROW_CELL",
    "LABEL_ABOVE",
}
DATA_TYPES = {"STRING", "NUMBER", "DATE", "ENUM", "MONEY"}
SCOPES = {"LINE_RIGHT", "LINE", "PAGE"}


def validate_definition(definition: dict) -> None:
    """Reject anything ExtractionSchemaLoader.parse would reject. Not a
    reimplementation of the engine — a gate on the wire shape, so a generated
    draft never reaches a migration in a state the loader throws on."""
    fields = definition.get("fields")
    if not isinstance(fields, list) or not fields:
        raise SchemaInvalid("schema has no fields")
    names = []
    for field in fields:
        name = field.get("name")
        if not isinstance(name, str) or not name:
            raise SchemaInvalid("field has no name")
        names.append(name)
        if str(field.get("dataType", "")).upper() not in DATA_TYPES:
            raise SchemaInvalid(f"{name}: unknown dataType")
        extractors = field.get("extractors")
        if not isinstance(extractors, list) or not extractors:
            raise SchemaInvalid(f"{name}: field has no extractors")
        group = field.get("group")
        if group is not None:
            _validate_group(name, group)
        for extractor in extractors:
            _validate_extractor(name, extractor, group)
    for key in definition.get("instanceKey", []) or []:
        if key not in names:
            raise SchemaInvalid("instanceKey names no such field")


def _validate_group(name: str, group: dict) -> None:
    kind = str(group.get("kind", "")).upper()
    if kind not in {"COLUMN", "ROW"}:
        raise SchemaInvalid(f"{name}: unknown group kind")
    if kind == "COLUMN":
        if "rowLabels" in group:
            raise SchemaInvalid(f"{name}: COLUMN group cannot declare rowLabels")
        if not group.get("header"):
            raise SchemaInvalid(f"{name}: COLUMN group has no header")
        keys = group.get("keys") or []
        if not keys:
            raise SchemaInvalid(f"{name}: COLUMN group has no keys")
        if any(not str(k).strip() for k in keys) or len(set(keys)) != len(keys):
            raise SchemaInvalid(f"{name}: COLUMN group keys must be distinct and non-blank")
        return
    region = group.get("region")
    if not region:
        raise SchemaInvalid(f"{name}: ROW group has no region")
    if not region.get("start") or not region.get("end"):
        raise SchemaInvalid(f"{name}: ROW group region needs start and end")
    if group.get("maxRows") is None:
        raise SchemaInvalid(f"{name}: ROW group has no maxRows")
    max_rows = int(group["maxRows"])
    if not 1 <= max_rows <= MAX_ROWS_CEILING:
        raise SchemaInvalid(f"{name}: ROW group maxRows must be 1..99")
    if "rowLabels" in group:
        labels = group.get("rowLabels") or []
        if not labels:
            raise SchemaInvalid(f"{name}: rowLabels must not be empty")
        previous = None
        for label in labels:
            if len(label) != 1 or not ("A" <= label <= "Z"):
                raise SchemaInvalid(f"{name}: row labels must be single letters A..Z")
            if previous is not None and previous >= label:
                raise SchemaInvalid(f"{name}: row labels must be distinct and strictly ascending")
            previous = label
        if len(labels) > max_rows:
            raise SchemaInvalid(f"{name}: rowLabels cannot exceed maxRows")


def _validate_extractor(name: str, extractor: dict, group: dict | None) -> None:
    method = str(extractor.get("method", "")).upper()
    if method not in METHODS:
        raise SchemaInvalid(f"{name}: unknown method {method!r}")
    if method in LABEL_METHODS and not extractor.get("label"):
        raise SchemaInvalid(f"{name}: {method} extractor has no label")
    if method == "TABLE_CLUSTER" and not extractor.get("table"):
        raise SchemaInvalid(f"{name}: TABLE_CLUSTER extractor has no table")
    if method == "ROW_CELL" and not extractor.get("columnHeader"):
        raise SchemaInvalid(f"{name}: ROW_CELL extractor has no columnHeader")
    if method == "CHECKBOX_STATE":
        if not extractor.get("options"):
            raise SchemaInvalid(f"{name}: CHECKBOX_STATE extractor has no options")
        if extractor.get("proximityPt") is None:
            raise SchemaInvalid(f"{name}: CHECKBOX_STATE extractor has no proximityPt")
        return
    if method == "SIGNATURE_PRESENCE":
        region = extractor.get("region")
        if not region:
            raise SchemaInvalid(f"{name}: SIGNATURE_PRESENCE extractor has no region")
        if not region.get("label") or not region.get("windowPt"):
            raise SchemaInvalid(f"{name}: region needs label and windowPt")
        return
    value = extractor.get("value")
    if not value:
        raise SchemaInvalid(f"{name}: extractor has no value")
    pattern = value.get("pattern")
    if not isinstance(pattern, str) or not pattern:
        raise SchemaInvalid(f"{name}: value has no pattern")
    try:
        re.compile(pattern)
    except re.error as error:
        raise SchemaInvalid(f"{name}: value pattern does not compile: {error}") from None
    if method not in CELL_METHODS and str(value.get("scope", "")).upper() not in SCOPES:
        raise SchemaInvalid(f"{name}: value has no scope")
    offset = value.get("lineOffset", 0)
    if not isinstance(offset, int) or isinstance(offset, bool):
        raise SchemaInvalid(f"{name}: lineOffset must be an integer")
    if not 0 <= offset <= MAX_LINE_OFFSET:
        raise SchemaInvalid(f"{name}: lineOffset must be 0..10")
    if offset != 0 and (
        group is None
        or str(group.get("kind", "")).upper() != "COLUMN"
        or method != "ANCHOR_LABEL"
        or str(value.get("scope", "")).upper() != "LINE"
        or value.get("occurrence", 0) != 0
    ):
        raise SchemaInvalid(f"{name}: nonzero lineOffset requires ANCHOR_LABEL COLUMN LINE occurrence 0")


def compose(
    document_type: str, version: str, draft: Draft, type_provenance: str = "given"
) -> dict:
    """The emitted document. `fields`/`instanceKey` are what the loader reads;
    `_draft` is the honest half — unresolved gaps and things to look at — and the
    loader's parse walks past unknown keys, so it stays loadable.

    `_draft.documentTypeProvenance` records how the type code was arrived at, so a
    reviewer can tell a code the engine confirmed from a slug nobody has checked.
    """
    return {
        "documentTypeCode": document_type,
        "version": version,
        "fields": draft.fields,
        "instanceKey": [],
        "_draft": {
            "generator": "tools/schema_draft.py",
            "documentTypeProvenance": type_provenance,
            "resolved": len(draft.fields),
            "unresolved": draft.unresolved,
            "review": draft.review,
        },
    }


# ── impure edges: PDF, files, CLI ───────────────────────────────────────────

def load_pages(pdf: Path, indices: set[int]) -> dict[int, Page]:
    """Spans from the WORKER'S OWN extractor, so the tool reasons over exactly the
    boxes production sees. Local read only — nothing is uploaded anywhere."""
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "worker" / "src"))
    from pragmaticds_docengine_worker.text import extract_text  # noqa: PLC0415

    parsed = extract_text(pdf.read_bytes(), sorted(indices))
    pages: dict[int, Page] = {}
    for page in parsed:
        pages[page.page_index] = Page(
            [
                Span(s.text, float(s.box.x), float(s.box.y), float(s.box.width),
                     float(s.box.height), s.ordinal)
                for s in page.spans
            ],
            page.page_index,
        )
    return pages


# The engine's own document_type vocabulary, keyed by the slug this module derives
# from an answer key's `form`. Re-derive the right-hand side with:
#
#   select code from document_type where org_id is null order by code;
#
# WHY a table and not a rule: a slug built from the printed form name is the
# DOCUMENT's identity, and the engine's code is the ENGINE's. "W-2" slugs to W_2
# and the engine calls it W2; Form 1040 IS the tax return. No normalisation maps
# one to the other, and a draft carrying the wrong code is refused at
# POST /v1/extraction-schemas with a 400 on documentTypeCode — after the work.
#
# This table WILL drift as types are seeded. A slug absent from it is not an
# error; it means the author must confirm a type exists before authoring.
_TYPE_ALIASES = {
    "W_2": "W2",
    "FORM_1040": "TAX_RETURN",
    "SCHEDULE_B_FORM_1040": "SCHEDULE_B",
    "SCHEDULE_C_FORM_1040": "SCHEDULE_C",
    "SCHEDULE_E_FORM_1040": "SCHEDULE_E",
    "SCHEDULE_K_1_FORM_1065": "SCHEDULE_K1_1065",
    "SCHEDULE_K_1_FORM_1041": "SCHEDULE_K1_1041",
    "SCHEDULE_K_1_FORM_1120_S": "SCHEDULE_K1_1120S",
    "FANNIE_MAE_FORM_1005_REQUEST_FOR_VERIFICATION_OF_EMPLOYMENT": "VOE",
    "FORM_1005": "VOE",
    "FORM_1099_MISC": "FORM_1099_MISC",
    "FORM_1099_NEC": "FORM_1099_NEC",
    "FORM_1099_G": "FORM_1099_G",
    "FORM_1099_R": "FORM_1099_R",
    "FORM_SSA_1099": "FORM_SSA_1099",
    "FORM_4506": "FORM_4506",
    "FORM_4506_C": "FORM_4506",
    "SSA_AWARD_LETTER": "SSA_AWARD_LETTER",
}

# Forms with NO seeded document_type today. A schema alone cannot reach these:
# without a type there is nothing to classify a page AS, so extraction never runs.
# Measured, not guessed — both classify UNKNOWN at 0.20 against the live engine.
# They need a document_type plus a classification rule pack FIRST; the draft is
# still worth keeping, it just cannot be installed yet.
_NO_ENGINE_TYPE = {
    "SCHEDULE_D_FORM_1040",
    "SCHEDULE_F_FORM_1040",
}

# The authoring API's version grammar (SchemaAuthoringService.requireAuthorableVersion).
# Strict MAJOR.MINOR.PATCH: a suffixed "0.1.0-draft" is refused with a 400 naming
# the version field. The draft-ness of a draft belongs in `_draft`, not in a
# version string the server will not accept.
_VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def derive_type_code(key: dict, pdf: Path) -> tuple[str, str]:
    """The engine document_type code for this form, and how it was arrived at.

    Returns ``(code, provenance)`` where provenance is ``aliased`` (a known form
    mapped to the engine's own code), ``derived`` (a slug nobody has confirmed) or
    ``no-engine-type`` (a form the engine cannot classify at all today).
    """
    raw = key.get("form") or pdf.stem
    slug = re.sub(r"[^A-Z0-9]+", "_", str(raw).upper()).strip("_") or "UNKNOWN"
    if slug in _TYPE_ALIASES:
        return _TYPE_ALIASES[slug], "aliased"
    if slug in _NO_ENGINE_TYPE:
        return slug, "no-engine-type"
    return slug, "derived"


def report(draft: Draft, stream) -> None:
    """Field names, reasons and counts. Never a captured value — the corpus holds
    real borrower documents and this tool follows corpus_score.py's discipline."""
    print(f"  resolved:   {len(draft.fields)} field(s)", file=stream)
    for field in draft.fields:
        methods = ",".join(e["method"] for e in field["extractors"])
        grouped = " group=COLUMN" if field.get("group") else ""
        print(f"    + {field['name']:<38} {field['dataType']:<7} [{methods}]{grouped}", file=stream)
    print(f"  unresolved: {len(draft.unresolved)} field(s)", file=stream)
    for gap in draft.unresolved:
        print(f"    ? {gap['field']:<38} {gap['reason']}", file=stream)
    if draft.review:
        print(f"  review:     {len(draft.review)} note(s)", file=stream)
        for note in draft.review:
            print(f"    ! {note['field']:<38} {note['note']}", file=stream)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Draft an extraction schema from a filled PDF and its answer key.")
    parser.add_argument("pdf", type=Path, help="the filled PDF (its .answers.json sits beside it)")
    parser.add_argument("--answers", type=Path, default=None,
                        help="answer key path (default: <basename>.answers.json)")
    parser.add_argument("--type", dest="type_code", default=None,
                        help="documentTypeCode (default: derived from the key's `form`)")
    parser.add_argument("--version", default="0.1.0",
                        help="schema version, strict MAJOR.MINOR.PATCH (the only grammar "
                             "POST /v1/extraction-schemas accepts; draft-ness lives in _draft)")
    parser.add_argument("--out", type=Path, default=None,
                        help="write the draft here (default: stdout)")
    parser.add_argument("--no-open-text", action="store_true",
                        help="never emit the open-ended cell pattern; park those fields instead")
    parser.add_argument("--regex-fallback", action="store_true",
                        help="also emit a page-scoped REGEX rung when the shape matches exactly "
                             "once on the whole page (off by default: page-unique here is not "
                             "page-unique on a two-employer package)")
    parser.add_argument("--max-rungs", type=int, default=1,
                        help="ladder depth, at most one rung per method (default 1)")
    parser.add_argument("--strict", action="store_true",
                        help="exit non-zero when any field is unresolved")
    args = parser.parse_args(argv)

    # Checked BEFORE a page is parsed. The authoring API's grammar is strict
    # MAJOR.MINOR.PATCH, and a bad version invalidates the whole run — so it costs
    # a second here and a full drafting pass if it surfaces at POST time instead.
    if not _VERSION.match(args.version):
        print(
            f"--version {args.version!r} is not MAJOR.MINOR.PATCH, which is the only "
            "grammar POST /v1/extraction-schemas accepts",
            file=sys.stderr,
        )
        return 2

    # `<basename>.answers.json` beside the PDF — corpus_score.py's own convention.
    answers = args.answers or args.pdf.parent / f"{args.pdf.stem}.answers.json"
    if not args.pdf.exists():
        print(f"no such PDF: {args.pdf}", file=sys.stderr)
        return 2
    if not answers.exists():
        print(f"no answer key beside {args.pdf.name} (expected {answers.name})", file=sys.stderr)
        return 2

    key = json.loads(answers.read_text())
    entries = read_entries(key)
    if not entries:
        print(f"{answers.name}: no `fields` array to draft from", file=sys.stderr)
        return 2

    pages = load_pages(args.pdf, {e.page for e in entries})
    draft = draft_schema(
        entries,
        pages,
        allow_open_text=not args.no_open_text,
        allow_regex_fallback=args.regex_fallback,
        max_rungs=max(1, args.max_rungs),
    )
    if args.type_code:
        type_code, provenance = args.type_code, "given"
    else:
        type_code, provenance = derive_type_code(key, args.pdf)
    definition = compose(type_code, args.version, draft, provenance)

    # Said on stderr, not buried in the draft: both cost an author a round trip to
    # a 400, and the second cannot be fixed by authoring at all.
    if provenance == "no-engine-type":
        print(
            f"{args.pdf.name}: the engine has NO document_type for {type_code} — it "
            "classifies UNKNOWN and extraction never runs. This draft needs a "
            "document_type and a classification rule pack before it can be installed.",
            file=sys.stderr,
        )
    elif provenance == "derived":
        print(
            f"{args.pdf.name}: documentTypeCode {type_code} was DERIVED from the key's "
            "`form` and is not a code this tool knows the engine seeds. Confirm it "
            "exists (or pass --type) before authoring.",
            file=sys.stderr,
        )

    if not definition["fields"]:
        print(f"{args.pdf.name}: no field round-tripped — nothing loadable to emit",
              file=sys.stderr)
        report(draft, sys.stderr)
        return 1
    validate_definition(definition)

    body = json.dumps(definition, indent=2) + "\n"
    if args.out:
        args.out.write_text(body)
    else:
        sys.stdout.write(body)
    print(f"{args.pdf.name} → {type_code}@{args.version}", file=sys.stderr)
    report(draft, sys.stderr)
    return 1 if (args.strict and draft.unresolved) else 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main(sys.argv[1:]))
