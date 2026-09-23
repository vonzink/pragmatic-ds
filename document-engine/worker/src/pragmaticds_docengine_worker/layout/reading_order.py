"""Column-major reading order over a page's top-level elements.

Column detection is an x-histogram of element boxes: the page is cut into
elementary x-strips at element edges, and a strip with ZERO element coverage —
no element crosses it anywhere on the page, so the free channel spans the full
page height (trivially >= the contract's 60% bar) — is a valley. Adjacent
valley strips merge.

The zero-coverage reading is deliberate. The softer alternative ("crossing
elements still leave a 60% contiguous free run") shears sparse pages apart:
on the two-column fixture the columns themselves cover barely 10% of the page
height, so every strip INSIDE a column also leaves a 90% free run, the valleys
merge into one interval swallowing the whole page, and no split survives. A
consequence worth naming: an element physically spanning the gutter (a
full-width title over a two-column body) glues the columns together and the
page falls back to top-to-bottom order — acceptable for Phase 3 and covered by
a test documenting exactly that.

A valley only splits columns when at least one element lies WHOLLY on each
side. Without that guard the page margins count as valleys, and a lone wide
table low on the page fabricates a phantom right-hand column out of the strip
beyond the text above it (found the hard way against the ruled-table fixture).

Order: columns left-to-right by position, elements within a column
top-to-bottom (ties left-to-right). Single-column pages are plain top-to-bottom.
"""

from pragmaticds_docengine_worker.layout.model import Element


#: A "column" below this share of total element area is a POCKET — a page number,
#: a margin tab, gutter line numbers — and joins its nearest real column instead of
#: reading first (Phase 3 review, confirmed: a bottom-LEFT page number became
#: column 0 and was ordered before the entire page body).
MIN_COLUMN_MASS_RATIO = 0.05


def order_elements(
    elements: list[Element], page_width_pt: float, page_height_pt: float
) -> list[Element]:
    if len(elements) < 2:
        return sorted(elements, key=lambda e: (e.box.y, e.box.x))

    valleys = _valleys(elements)
    boundaries = [(lo + hi) / 2.0 for lo, hi in valleys]

    def raw_column_of(element: Element) -> int:
        center = element.box.x + element.box.width / 2.0
        return sum(1 for boundary in boundaries if center > boundary)

    mass: dict[int, float] = {}
    for element in elements:
        mass[raw_column_of(element)] = mass.get(raw_column_of(element), 0.0) + (
            element.box.width * element.box.height
        )
    total_mass = sum(mass.values()) or 1.0
    real_columns = sorted(c for c, m in mass.items() if m / total_mass >= MIN_COLUMN_MASS_RATIO)
    if not real_columns:
        real_columns = sorted(mass)

    def column_of(element: Element) -> int:
        raw = raw_column_of(element)
        if raw in real_columns:
            return raw
        # Pockets adopt the nearest real column and take their place by y.
        return min(real_columns, key=lambda c: abs(c - raw))

    return sorted(elements, key=lambda e: (column_of(e), e.box.y, e.box.x))


def _valleys(elements: list[Element]) -> list[tuple[float, float]]:
    edges = sorted({edge for e in elements for edge in (e.box.x, e.box.x + e.box.width)})
    candidate_strips = [
        (lo, hi)
        for lo, hi in zip(edges, edges[1:])
        if hi > lo
        and not any(e.box.x < hi and e.box.x + e.box.width > lo for e in elements)
    ]

    merged: list[list[float]] = []
    for lo, hi in candidate_strips:
        if merged and abs(merged[-1][1] - lo) < 1e-9:
            merged[-1][1] = hi
        else:
            merged.append([lo, hi])

    return [
        (lo, hi)
        for lo, hi in merged
        if any(e.box.x + e.box.width <= lo + 0.1 for e in elements)
        and any(e.box.x >= hi - 0.1 for e in elements)
    ]
