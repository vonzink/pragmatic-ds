"""Punctuation-as-images repair: money whose comma/period are pictures.

The defect, measured on a real paystub: amounts are TEXT digit runs with the
comma and period drawn as ~3.8pt 1-bit images in ~5.1pt holes between them —
``$7,514.92`` reaches pdfplumber as words ``$7`` / ``514`` / ``92`` with
inked holes the text layer knows nothing about. ``SpanJoin`` correctly
refuses to bridge holes wider than 0.10 em (a dropped glyph's advance is the
same width as a printed space — joining there fabricates numbers), the money
pattern correctly declines ``$7 514 92``, and the field goes missing with the
amount printed on the page. OCR cannot rescue it either way: the punctuation
images sit under the 4pt uncovered-region sliver floor, and feeding RapidOCR
the row yields unstable reads at 9pt punctuation sizes (measured: commas as
periods, digits regrouped) — a text-equality reconcile would simply never
fire, and loosening it would trust hallucinations.

So the repair verifies PIXEL TRUTH directly and closes every other door.
A run of same-row digit words merges into one money token only when ALL of
these hold, each gate measured, none guessed:

  1. GRAMMAR — the first word is ``$``-optional 1-3 digits, every later word
     is 1-3 bare digits, and every seam between them is a sub-em hole. (A
     word containing real text punctuation is already whole and never enters.)
  2. DECLARED INK — every hole contains an IMAGE OBJECT of sub-glyph size:
     the page's own structure says something is drawn there. A hole with no
     image never merges — that is the two-adjacent-amounts case, and bridging
     it by geometry alone is the exact fabrication SpanJoin documents.
  3. RENDERED INK — the hole's rendered pixels contain exactly one compact
     mark: the LARGEST CONNECTED component must sit AT THE BASELINE, be
     punctuation-sized against the row's em, and be mostly FILLED (a hollow
     ring or a mid-height speck is not how any renderer draws a comma or a
     period), and every OTHER component must be halftone dust — the measured
     stub shades its money rows with a dither pattern whose isolated dark
     dots land inside the holes, and judging the raw ink bounding box against
     that background rejected every shaded row. A second mark-sized component
     (a quote, a second glyph) still refuses.
  4. CANONICAL RESULT — punctuation is never read out of the mark's shape
     (comma-vs-period at 4pt is exactly the unstable read OCR failed at).
     Instead the one assignment a money amount permits — ``.`` before a
     final 2-digit cents group, ``,`` in every earlier hole — must produce a
     string matching ``\\$?\\d{1,3}(,\\d{3})*\\.\\d{2}`` exactly. ``61``/
     ``400`` admits no such assignment (``61,400`` has no cents, ``61.400``
     has three) and stays two words no matter what ink sits between.

A merge failing ANY gate leaves the words exactly as extracted — and it
leaves its whole PRINTED ROW as extracted too: when a row carries several
canonical chains (a current amount beside its year-to-date amount) and any
one of them fails its ink gates, NONE of that row's chains merge. A partial
repair is worse than none, because downstream rungs bind values by OCCURRENCE
over the row's joined text — merge only the second amount and "occurrence 0"
quietly becomes the year-to-date value wearing the current-amount label (the
exact wrong capture measured on the real stub before this rule). The value
stays missing, which is the governing rule — a wrong amount is worse than a
missing one. The repaired word's box is the union of its parts, so evidence
drawn from it still covers the printed amount, holes included.

A DECORATED amount is the same partial-repair hazard from the other side, and
it takes both halves of the rule to close. A real deduction row prints its
current amount with a leading minus, and an excluded-from-taxable row adds a
trailing footnote asterisk — measured on the real stub, whose seven tax, 401k
and direct-deposit rows read ``-## <image> ##`` (one of them ``-## <image>
##*``) beside a positive year-to-date. But a minus and an asterisk are two
SPECIMENS, not the class, and the class cannot be closed by listing it: the
accounting negative is ``(1,234.56)``, many payroll systems print ``1,234.56-``,
a typesetter's negative is U+2212 rather than the hyphen a keyboard types, and
footnotes come as daggers as readily as asterisks. Enumerating those five is
the same bug with more entries, and the sixth dialect reopens it.

So the rule is stated over the ALPHABET instead, in two halves that partition
it. Gate 3 proves what sits in the seams BETWEEN digit runs and nothing else,
and the money grammar above produces exactly three kinds of character: an
optional leading ``$``, the digits, and the ``,``/``.`` gate 4 assigns by
position. Therefore:

  * CANDIDACY READS THE DIGITS ONLY. A chain's digit groups, under the one
    positional assignment, either spell canonical money or they do not —
    a question no decoration can answer, because the decoration is not looked
    at. So a run governs its row whatever is printed around it. Left outside
    the candidate set, a decorated run could neither merge nor veto while its
    positive neighbour merged alone, and "occurrence 0" over ``Federal
    Withholding -57 82 5,512.34`` is the year-to-date total wearing the
    per-period label: a confident wrong value where the unrepaired page had a
    missing one.
  * PUBLICATION REQUIRES THAT EVERY CHARACTER BE ONE THE GRAMMAR PRODUCED.
    The merged token, decorations and all, must BE canonical money. A leading
    hyphen is not proof of a minus SIGN — it is equally a dash, a leader or a
    bullet fused to the digits — and no gate here examined it; the same is
    true of a parenthesis, a dagger, a ``CR``, or whatever the next dialect
    prints. None of them is read, none of them is published.

Every character is a digit or it is not, so an unanticipated decoration is
invisible to the first half (the row still vetoes) and present in the second
(the amount is still refused). There is no third case for a new dialect to
land in, which is what makes this a rule rather than a longer list.

The same reasoning reaches one step PAST the chain's own characters. A chain
claims to be a whole printed amount, and a digit-bearing word close enough to
have been separated by punctuation — the same sub-em window gate 1 weighs —
can falsify that claim: when a document separates thousands with a SPACE, the
seam carries no image, gate 2 rightly refuses it, and the chain starts INSIDE
the number, where ``7 <space> 514 <image> 92`` publishes ``514.92`` for a
printed ``7 514.92``. Nothing about that token is decorated, so the character
rule cannot see it. Reading the neighbour in and re-running the assignment
can: if the longer reading is ALSO canonical money, the amount's extent is not
proven and it is not published. Two readings and no evidence between them is
exactly the case this module answers with silence.

One case the partition genuinely does NOT reach, stated rather than silently
omitted: a decoration made of DIGITS. A numeric footnote fused to the amount —
``1,438.041`` for note 1 — changes the digit skeleton itself, so candidacy
reads ``1,438.041``, which is not money, and the run neither publishes (right)
nor vetoes (wrong: a row-mate can still merge alone). No rule reading only the
text layer can close that, because the digits are genuinely ambiguous — the
same characters are a three-decimal rate — and inventing a "trailing digit is a
footnote" clause would be the enumeration this module just retired, guessing
wrong more often than right. It is the same shape as ``61``·``400``, whose
non-candidacy this module already chose deliberately, and it is no worse here
than before.

Missing over wrong, every time.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

import numpy as np

#: A hole wider than this fraction of the row's em is a printed gap between
#: two tokens, not a punctuation seam. (The measured holes are ~0.57 em; a
#: printed space is ~0.28 em — width alone separates NOTHING, which is why
#: the ink gates below exist.)
HOLE_MAX_EM = 0.9
#: An image taller than this fraction of the em is a glyph-scale graphic, not
#: an inline punctuation mark.
IMAGE_MAX_EM = 1.2
#: The rendered mark's bounding box must stay punctuation-sized...
MARK_MAX_EM = 0.62
#: ...reach at least this many rendered pixels (dust is not punctuation)...
MARK_MIN_PIXELS = 4
#: ...be mostly filled (a comma/period is solid ink; a ring or outline is not:
#: the measured bold comma fills 0.575 of its box, the hollow-ring decoy 0.47,
#: and the bound sits between them with margin on both sides)...
MARK_MIN_FILL = 0.52
#: ...and sit at the baseline: its lowest ink within this many ems of the
#: digit row's bottom edge. (The row bottom includes the font's descent, about
#: 0.2 em below the baseline; a comma/period's ink reaches within ~0.1 em of
#: it. A mid-height mark — a minus, a bullet — bottoms out ~0.4 em short and
#: must fail here: '12 − 34' is not '$12.34'.)
MARK_BASELINE_EM = 0.30
#: ...but never DEEPER below the row than a comma's descender reaches — ink
#: running well past the row bottom is a rule or a background band, not a mark.
MARK_DESCENT_EM = 0.25
#: An ink component of at most this many rendered pixels is halftone dust —
#: the row-shading dither the real stub prints measures 1-4 px per dot at the
#: fixed 300-dpi check render, and the smallest genuine mark (a 9pt period)
#: measures 16. Dust is skipped BEFORE the mark test, so this is also the
#: effective mark floor.
NOISE_MAX_PIXELS = 12
#: A component spanning at least this fraction of the hole's width while
#: staying RULE_MAX_EM thin is table/shading furniture (the band's horizontal
#: edges cross every hole on a shaded row) — ignorable, never a mark.
RULE_MIN_WIDTH_FRACTION = 0.9
RULE_MAX_EM = 0.18

#: What a merged token must be, entire: canonical US money — the ONLY
#: characters this module's grammar produces. Anything else — ``61.400``,
#: ``84,112``, a 4-digit group, a parenthesis, a dagger — refuses the merge
#: outright. This is the whole publication rule; there is no decorated variant
#: of it, because a decoration is by definition a character not in here.
CANONICAL_MONEY = re.compile(r"\$?\d{1,3}(?:,\d{3})*\.\d{2}")

#: One chain word: exactly one 1-3 digit group wearing whatever the document
#: prints around it. The decoration is not enumerated — it is simply everything
#: that is not a digit, which is what stops the next dialect from reopening the
#: veto. A word carrying its own SECOND digit group (``1,438.04``, ``2024``,
#: an account number) never matches: that token is already whole and the repair
#: has no business inside it.
_RUN = re.compile(r"[^\d\s]*(?P<digits>\d{1,3})[^\d\s]*")

#: Ink threshold on the 0-255 grayscale render.
_DARK = 128


@dataclass(frozen=True)
class _Hole:
    """The seam between two chained words, with the image declared inside it."""

    x0: float
    x1: float


@dataclass(frozen=True)
class Chain:
    """A maximal run of digit words whose every seam is an image-carrying hole.
    ``row`` is the index of the printed row the chain sits on — the unit the
    all-or-none merge rule is applied over. ``left``/``right`` are the words
    just outside it when they sit within a punctuation-seam's reach, which is
    how the chain learns it may not be the whole printed number."""

    words: tuple[dict, ...]
    holes: tuple[_Hole, ...]
    row: int
    left: dict | None = None
    right: dict | None = None


def repair_image_punctuation(raw_words: list[dict], image_boxes: list[tuple],
                             render_gray) -> None:
    """Merge image-punctuated money runs in place; leave everything else alone.

    ``image_boxes`` are (x0, top, x1, bottom) in the page's display space —
    rotation-0 pages only, where display and canonical agree. ``render_gray``
    is a lazy () -> (numpy grayscale array, scale px/pt) of the whole page; it
    is only invoked when at least one candidate chain exists, so pages without
    the defect never pay for a render.

    Row consistency (see the module docstring): candidates — chains whose
    DIGITS, under the one positional assignment, read as an amount — are judged
    per printed row, and a row merges all of its candidates or none of them.
    Candidacy ignores every character that is not a digit, so a decorated run
    is an amount like any other and vetoes like any other; publication requires
    the opposite, that no such character be present at all. Chains that were
    never money ('61'·'400' admits no assignment) neither merge nor veto.
    """
    chains = _chains(raw_words, image_boxes)
    candidates = [(chain, _merged_text(chain)) for chain in chains
                  if _is_amount(chain)]
    if not any(merged is not None for _, merged in candidates):
        return  # nothing on this page could be published: never pay for a render
    rendered = render_gray()
    rows: dict[int, list[tuple[Chain, str | None, bool]]] = {}
    for chain, merged in candidates:
        # An unpublishable candidate is judged without looking: no ink can earn
        # it a merge, so it fails here, and takes its row down with it.
        passed = merged is not None and all(
            _hole_ink_is_punctuation(hole, chain, *rendered) for hole in chain.holes)
        rows.setdefault(chain.row, []).append((chain, merged, passed))

    removed: set[int] = set()
    for row_chains in rows.values():
        if not all(passed for _, _, passed in row_chains):
            continue  # one failed candidate poisons its whole row: merge none
        for chain, merged, _ in row_chains:
            first = chain.words[0]
            first["text"] = merged
            first["x1"] = chain.words[-1]["x1"]
            first["top"] = min(float(w["top"]) for w in chain.words)
            first["bottom"] = max(float(w["bottom"]) for w in chain.words)
            removed.update(id(w) for w in chain.words[1:])
    if removed:
        raw_words[:] = [w for w in raw_words if id(w) not in removed]


# ── gate 1+2: grammar and declared ink ───────────────────────────────────────


def _chains(raw_words: list[dict], image_boxes: list[tuple]) -> list[Chain]:
    chains: list[Chain] = []
    for row_index, row in enumerate(_rows(raw_words)):
        start: int | None = None
        holes: list[_Hole] = []
        for position, word in enumerate(row):
            if start is not None:
                previous = row[position - 1]
                gap = float(word["x0"]) - float(previous["x1"])
                em = min(_height(previous), _height(word))
                linkable = (
                    _RUN.fullmatch(word["text"]) is not None
                    and em > 0
                    and 0 < gap <= HOLE_MAX_EM * em
                    and _hole_image(previous, word, em, image_boxes) is not None
                )
                if linkable:
                    holes.append(_Hole(float(previous["x1"]), float(word["x0"])))
                    continue
                if position - start >= 2:
                    chains.append(_chain(row, start, position - 1, holes, row_index))
            start = position if _RUN.fullmatch(word["text"]) else None
            holes = []
        if start is not None and len(row) - start >= 2:
            chains.append(_chain(row, start, len(row) - 1, holes, row_index))
    return chains


def _chain(row: list[dict], first: int, last: int, holes: list[_Hole],
           row_index: int) -> Chain:
    words = tuple(row[first:last + 1])
    return Chain(words, tuple(holes), row_index,
                 _neighbour(row, first - 1, words[0], before=True),
                 _neighbour(row, last + 1, words[-1], before=False))


def _neighbour(row: list[dict], index: int, anchor: dict, before: bool):
    """The word beside the chain, but only while it sits within the same sub-em
    reach gate 1 weighs as a possible punctuation seam. Further away than that
    and this module never had a reason to think the two are one token — a table
    column gap is not evidence of anything."""
    if not 0 <= index < len(row):
        return None
    other = row[index]
    em = min(_height(anchor), _height(other))
    gap = (float(anchor["x0"]) - float(other["x1"]) if before
           else float(other["x0"]) - float(anchor["x1"]))
    return other if em > 0 and gap <= HOLE_MAX_EM * em else None


def _rows(raw_words: list[dict]) -> list[list[dict]]:
    """Pairwise same-row grouping, centers within half the smaller height —
    the exact rule SpanJoin/VisualRows apply, so the worker and the engine
    cannot disagree about what a printed row is. Words in x order."""
    ordered = sorted(raw_words, key=lambda w: (_center(w), float(w["x0"])))
    rows: list[list[dict]] = []
    for word in ordered:
        if rows:
            previous = rows[-1][-1]
            threshold = 0.5 * min(_height(previous), _height(word))
            if abs(_center(word) - _center(previous)) <= threshold:
                rows[-1].append(word)
                continue
        rows.append([word])
    for row in rows:
        row.sort(key=lambda w: float(w["x0"]))
    return rows


def _height(word: dict) -> float:
    return float(word["bottom"]) - float(word["top"])


def _center(word: dict) -> float:
    return (float(word["top"]) + float(word["bottom"])) / 2.0


def _hole_image(a: dict, b: dict, em: float, image_boxes: list[tuple]):
    """The sub-glyph image object sitting inside the seam, or None."""
    top = min(float(a["top"]), float(b["top"])) - 0.2 * em
    bottom = max(float(a["bottom"]), float(b["bottom"])) + 0.35 * em
    for x0, im_top, x1, im_bottom in image_boxes:
        if x0 < float(a["x1"]) - 0.5 or x1 > float(b["x0"]) + 0.5:
            continue  # not confined to the hole
        if im_bottom <= top or im_top >= bottom:
            continue  # not on this row
        if (im_bottom - im_top) > IMAGE_MAX_EM * em:
            continue  # glyph-scale graphic, not a mark
        return (x0, im_top, x1, im_bottom)
    return None


# ── gate 4: the one canonical assignment ─────────────────────────────────────


def _assign(groups: list[str]) -> str:
    """Punctuation by POSITION, never by shape: ``.`` before the final group,
    ``,`` everywhere earlier. The one assignment a money amount permits."""
    marks = [","] * (len(groups) - 2) + ["."]
    return groups[0] + "".join(m + g for m, g in zip(marks, groups[1:]))


def _digits(word: dict) -> str:
    """The word's digit group, stripped of whatever is printed around it. Chain
    words are ``_RUN``-shaped by construction, so this always matches."""
    return _RUN.fullmatch(word["text"]).group("digits")


def _is_amount(chain: Chain) -> bool:
    """Does this run read as a money amount AT ALL — reading the digits and
    nothing else? This is the veto's question, and it is deliberately blind to
    decoration: an amount governs its row however the page dresses it, so no
    unlisted parenthesis, dagger or sign can buy a run its way out of the row
    rule. ``61``·``400`` admits no assignment and is genuinely not an amount;
    ``(7``·``514``·``92)`` is one, and vetoes."""
    return CANONICAL_MONEY.fullmatch(_assign([_digits(w) for w in chain.words])) \
        is not None


def _merged_text(chain: Chain) -> str | None:
    """What this chain may be PUBLISHED as, or None when it may not be.

    Two conditions, both about EVIDENCE rather than about any list of
    decorations. First, every character of the result must be one this module's
    own grammar produced — so the merged token must BE canonical money, and any
    character the page printed around the digits disqualifies it, whether or
    not anyone anticipated that character. Second, the chain must be the whole
    printed amount: see ``_reaches_further``."""
    text = _assign([w["text"] for w in chain.words])
    if CANONICAL_MONEY.fullmatch(text) is None:
        return None
    return None if _reaches_further(chain) else text


def _reaches_further(chain: Chain) -> bool:
    """Is there a SECOND money reading, one that swallows an adjacent run?

    A chain claims to be a whole printed amount, and its own characters cannot
    always falsify that: when a document separates thousands with a SPACE the
    seam holds no image, gate 2 refuses it, and the chain begins one group
    inside the number — ``7 <space> 514 <period image> 92`` reads ``514.92``,
    which is canonical, undecorated, and a hundredth of what the page prints.
    Reading the neighbour's digits in and re-running the assignment is what
    sees it: ``7,514.92`` is money too, so the extent is unproven and nothing
    is published. A neighbour that yields no money reading — a quantity column,
    where ``40``+``7,514.92`` gives the non-money ``40,7,514.92`` — leaves the
    amount's own reading the only one, and it publishes."""
    groups = [_digits(word) for word in chain.words]
    for neighbour, extended in ((chain.left, lambda d: [d] + groups),
                                (chain.right, lambda d: groups + [d])):
        if neighbour is None:
            continue
        match = _RUN.fullmatch(neighbour["text"])
        if match is None:
            continue
        if CANONICAL_MONEY.fullmatch(_assign(extended(match.group("digits")))):
            return True
    return False


# ── gate 3: rendered ink ─────────────────────────────────────────────────────


def _hole_ink_is_punctuation(hole: _Hole, chain: Chain,
                             gray: np.ndarray, scale: float) -> bool:
    """The hole's rendered pixels must contain EXACTLY ONE component that
    reads as a punctuation mark, and nothing else but known furniture.

    Every connected ink component is classified: halftone DUST (≤ a few px —
    the row-shading dither the real stub prints), a horizontal RULE (a thin
    edge crossing the hole's full width — the shading band's borders cross
    every hole on a shaded row), or a MARK (punctuation-sized, mostly filled,
    baseline-anchored). One mark among dust and rules merges; two marks (a
    quote), zero marks, or any component that is none of the three (a ring,
    a mid-height bar, a glyph fragment) refuses. Measured against the ROW's
    em so a scan at any resolution answers the same."""
    words = chain.words
    em = min(_height(w) for w in words)
    row_top = min(float(w["top"]) for w in words)
    row_bottom = max(float(w["bottom"]) for w in words)
    band_top = row_top - 0.2 * em
    band = _crop(gray, hole.x0 + 0.15, band_top,
                 hole.x1 - 0.15, row_bottom + 0.35 * em, scale)
    if band.size == 0:
        return False
    band_width_px = band.shape[1]
    marks = 0
    for component in _components(band < _DARK):
        if len(component) <= NOISE_MAX_PIXELS:
            continue  # dither dust
        ys = [y for y, _ in component]
        xs = [x for _, x in component]
        width_px = max(xs) - min(xs) + 1
        height_pt = (max(ys) - min(ys) + 1) / scale
        if (width_px >= RULE_MIN_WIDTH_FRACTION * band_width_px
                and height_pt <= RULE_MAX_EM * em):
            continue  # a shading/table rule crossing the hole
        if _is_mark(component, xs, ys, band_top, row_bottom, em, scale):
            marks += 1
            continue
        return False  # substantial ink that is neither dust, rule, nor mark
    return marks == 1


def _is_mark(component: list, xs: list, ys: list, band_top: float,
             row_bottom: float, em: float, scale: float) -> bool:
    if len(component) < MARK_MIN_PIXELS:
        return False
    width_pt = (max(xs) - min(xs) + 1) / scale
    height_pt = (max(ys) - min(ys) + 1) / scale
    if width_pt > MARK_MAX_EM * em or height_pt > MARK_MAX_EM * em:
        return False
    fill = len(component) / float((max(xs) - min(xs) + 1) * (max(ys) - min(ys) + 1))
    if fill < MARK_MIN_FILL:
        return False
    # Baseline anchoring, both directions: the mark's lowest ink reaches the
    # bottom part of the row band and never runs deep below it.
    ink_bottom_pt = band_top + (max(ys) + 1) / scale
    if ink_bottom_pt < row_bottom - MARK_BASELINE_EM * em:
        return False
    return ink_bottom_pt <= row_bottom + MARK_DESCENT_EM * em


def _components(ink: np.ndarray) -> list[list[tuple[int, int]]]:
    """4-connected components of a small boolean raster — the bands are tens
    of pixels a side, so a plain flood fill is plenty."""
    height, width = ink.shape
    seen = np.zeros_like(ink, dtype=bool)
    components: list[list[tuple[int, int]]] = []
    for start_y, start_x in zip(*np.nonzero(ink)):
        if seen[start_y, start_x]:
            continue
        stack = [(int(start_y), int(start_x))]
        seen[start_y, start_x] = True
        component: list[tuple[int, int]] = []
        while stack:
            y, x = stack.pop()
            component.append((y, x))
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < height and 0 <= nx < width \
                        and ink[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        components.append(component)
    return components


def _crop(gray: np.ndarray, x0: float, top: float, x1: float, bottom: float,
          scale: float) -> np.ndarray:
    height, width = gray.shape
    left = max(int(x0 * scale), 0)
    right = min(int(np.ceil(x1 * scale)), width)
    upper = max(int(top * scale), 0)
    lower = min(int(np.ceil(bottom * scale)), height)
    if right <= left or lower <= upper:
        return gray[0:0, 0:0]
    return gray[upper:lower, left:right]
