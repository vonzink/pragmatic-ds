"""Per-region reconciliation of the two engines' outputs, plus reading order.

When the fallback ran, the page is partitioned into regions — the request's own
regions when it sent any (the MIXED-page case), otherwise a fixed 2-column x 4-row
grid in canonical space. Within each region the winner is the engine whose spans
there pass the G6-style geometry validity bar (invalid fraction <= the configured
threshold) with the higher mean confidence; a region where only one engine passes
goes to that engine, and a region where neither passes contributes nothing. Every
surviving span keeps the name of the engine that produced it — reconciliation must
never destroy traceability (docs/DATA_MODEL.md text_span.ocr_engine).

WORD YIELD OUTRANKS CONFIDENCE WHEN WORD YIELD IS WHY THE FALLBACK RAN
----------------------------------------------------------------------
Mean confidence compares two readings of the same words. It cannot compare a reading
of the LINES with a reading of the WORDS, and that is exactly the pair a G2 trip
produces: on a low-resolution prose scan (SSA's own sample benefit letter is a 72-DPI
image-only PDF, 781x911 px) the primary recogniser drops the spaces inside every line
and emits one token per line — 63 tokens such as 'BenefitVerificationLetter', each at
0.97, for a page the fallback reads as 220 words at 0.96. Per-token confidence is
measured over the merged token and does not fall because the spaces did; so by mean
confidence the merged reading won all eight regions, and the served text matched none
of the five multi-word anchors that identify the document. The page classified UNKNOWN.

So when EITHER engine's G2 (word yield) tripped, each region is first judged on RELATIVE
yield: an engine that read fewer than `g2_yield_threshold` of the other engine's tokens
there under-read that region, and the denser reading wins provided its tokens look like
the sparse reading's lines split into words (SPLIT_LINES_* below — denser alone is not
enough; fragmentation is denser too) and its mean confidence is at the G3 median
threshold — the same two gate numbers, applied engine against engine rather than engine
against the ink model (whose per-word ink calibration is paystub-derived and
under-counts dense prose on BOTH engines: the fallback's own page-level G2 read 0.45 on
that letter). Comparable yields, a fragmented or unconfident dense reading, or no G2 trip
on either pass leave the mean-confidence rule exactly as it was. The rule is symmetric:
a fallback that merged lines (its own G2 trips) loses to a primary that read words.
"""

from dataclasses import dataclass

from pragmaticds_docengine_worker.geometry import Box
from pragmaticds_docengine_worker.ocr.gates import OcrConfig

#: Default reconciliation grid when the request carries no regions: columns x rows.
GRID_COLS = 2
GRID_ROWS = 4

#: Slack (pt) allowed on page-bounds checks — canonical boxes round to 0.1pt.
_BOUNDS_SLACK_PT = 0.5


@dataclass(frozen=True)
class AttributedSpan:
    """A canonical-space span that knows which engine produced it."""

    text: str
    box: Box
    confidence: float
    engine: str


def _box_valid(box: Box, page_w_pt: float, page_h_pt: float) -> bool:
    if box.width <= 0 or box.height <= 0:
        return False
    return (
        box.x >= -_BOUNDS_SLACK_PT
        and box.y >= -_BOUNDS_SLACK_PT
        and box.x + box.width <= page_w_pt + _BOUNDS_SLACK_PT
        and box.y + box.height <= page_h_pt + _BOUNDS_SLACK_PT
    )


def _passes_geometry(spans: list[AttributedSpan], page_w_pt: float, page_h_pt: float,
                     config: OcrConfig) -> bool:
    if not spans:
        return False
    invalid = sum(1 for s in spans if not _box_valid(s.box, page_w_pt, page_h_pt))
    return invalid / len(spans) <= config.g6_invalid_fraction_threshold


def _region_index(span: AttributedSpan, regions: list[Box]) -> int | None:
    """Index of the region containing the span's centre, else None."""
    cx = span.box.x + span.box.width / 2
    cy = span.box.y + span.box.height / 2
    for index, region in enumerate(regions):
        if region.x <= cx < region.x + region.width and (
            region.y <= cy < region.y + region.height
        ):
            return index
    return None


def _grid_regions(page_w_pt: float, page_h_pt: float) -> list[Box]:
    cell_w = page_w_pt / GRID_COLS
    cell_h = page_h_pt / GRID_ROWS
    return [
        Box(col * cell_w, row * cell_h, cell_w, cell_h)
        for row in range(GRID_ROWS)
        for col in range(GRID_COLS)
    ]


#: Denser is not the same as "read the words". A merged line is LONGER THAN ANY WORD the
#: other engine read, and a merged-line reading keeps most of its characters in such
#: tokens; a fragmented reading (dotted leaders as '. . .', rules as '|', underlines as
#: '_', logo and signature scraps) still contains the same words, so nothing in the
#: sparse reading out-lengths it, however many fragments there are. So the denser
#: reading is served only when it looks like the sparse one's lines split into words:
#: at least this share of the sparse reading's characters sit in tokens longer than the
#: dense reading's longest token (SSAL's eight regions measure 0.76–1.0; four words
#: against the same four words plus any number of one-character fragments measure 0)...
SPLIT_LINES_LONG_MASS_MIN = 0.5
#: ...and the dense reading holds at least this fraction of the sparse reading's
#: character mass — three scraps read off a logo are not a line split into its words.
SPLIT_LINES_CHAR_MASS_MIN = 0.6


@dataclass(frozen=True)
class _Candidate:
    """One engine's geometry-valid spans inside one region."""

    spans: list[AttributedSpan]
    mean_confidence: float

    @property
    def char_mass(self) -> int:
        return sum(len(s.text) for s in self.spans)

    @property
    def longest_token(self) -> int:
        return max(len(s.text) for s in self.spans)

    def mass_in_tokens_longer_than(self, length: int) -> float:
        """Share of this reading's characters carried by tokens longer than `length`."""
        return sum(len(s.text) for s in self.spans if len(s.text) > length) / self.char_mass


def _looks_like_split_lines(sparse: _Candidate, dense: _Candidate) -> bool:
    """True when `dense` reads as `sparse`'s tokens broken into words (constants above)."""
    return (
        dense.char_mass >= SPLIT_LINES_CHAR_MASS_MIN * sparse.char_mass
        and sparse.mass_in_tokens_longer_than(dense.longest_token) >= SPLIT_LINES_LONG_MASS_MIN
    )


def _region_winner(
    candidates: list[_Candidate], config: OcrConfig, word_yield_tripped: bool
) -> _Candidate:
    """The mean-confidence rule, preceded by the relative-yield rule when G2 tripped.

    `candidates` is in engine order (primary first), so `max` on confidence hands ties
    to the primary — the pre-existing tie rule, unchanged."""
    if word_yield_tripped and len(candidates) == 2:
        sparse, dense = sorted(candidates, key=lambda c: len(c.spans))
        relative_yield = len(sparse.spans) / len(dense.spans)
        if (
            relative_yield < config.g2_yield_threshold
            and dense.mean_confidence >= config.g3_median_threshold
            and _looks_like_split_lines(sparse, dense)
        ):
            return dense
    return max(candidates, key=lambda c: c.mean_confidence)


def reconcile(
    primary_spans: list[AttributedSpan],
    fallback_spans: list[AttributedSpan],
    page_w_pt: float,
    page_h_pt: float,
    config: OcrConfig,
    regions: list[Box] | None = None,
    *,
    word_yield_tripped: bool = False,
) -> list[AttributedSpan]:
    """Merge two engines' canonical spans, choosing a winner per region.

    `word_yield_tripped` is EITHER pass's G2 verdict (module docstring): with it set, a
    region goes to the markedly denser confident reading — when that reading looks like
    the sparse one's lines split into words — before confidence is consulted."""
    partitions = list(regions) if regions else _grid_regions(page_w_pt, page_h_pt)

    survivors: list[AttributedSpan] = []
    for index in range(len(partitions)):
        candidates: list[_Candidate] = []
        for spans in (primary_spans, fallback_spans):  # primary first: wins ties
            in_region = [s for s in spans if _region_index(s, partitions) == index]
            if in_region and _passes_geometry(in_region, page_w_pt, page_h_pt, config):
                mean_conf = sum(s.confidence for s in in_region) / len(in_region)
                candidates.append(_Candidate(in_region, mean_conf))
        if candidates:
            survivors.extend(_region_winner(candidates, config, word_yield_tripped).spans)
    return survivors


def reading_order(spans: list[AttributedSpan]) -> list[AttributedSpan]:
    """Top-to-bottom, left-to-right within line bands — the /v1/text convention."""
    if not spans:
        return []
    heights = sorted(span.box.height for span in spans)
    band = max(heights[len(heights) // 2] * 0.6, 0.1)

    by_y = sorted(spans, key=lambda s: (s.box.y + s.box.height / 2, s.box.x))
    lines: list[list[AttributedSpan]] = []
    line_anchor: float | None = None
    for span in by_y:
        center_y = span.box.y + span.box.height / 2
        if line_anchor is None or center_y - line_anchor > band:
            lines.append([span])
            line_anchor = center_y
        else:
            lines[-1].append(span)
    ordered: list[AttributedSpan] = []
    for line in lines:
        ordered.extend(sorted(line, key=lambda s: s.box.x))
    return ordered
