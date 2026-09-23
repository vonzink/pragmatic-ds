"""Label/value pairing (issue #63 defect 2b).

A form prints "Gross Pay        1,234.56": a short label and, on the SAME
baseline, one numeric run a tab stop or two to its right. lines.py already split
them into two fragments at the wide gap, so by the time they reach the paragraph
stage they are siblings that nothing relates — the label became a HEADER (bold,
isolated) and the figure a PARAGRAPH, and a reader of the body saw two blocks.

A pair is exactly:

  LABEL  — a fragment of 1..LABEL_MAX_SPANS spans carrying letters (not itself a value);
  VALUE  — the NEXT fragment on the band, a run of 1..VALUE_MAX_SPANS spans that is an
           amount / count / percentage / date (values.py), at most PAIR_GAP_EM ems
           right of the label's end, with no fragment on another band starting in
           the gap between them (text living there makes the gap a page column);

and the value must be the ONLY value that follows: a label followed by two
figures (this period AND year-to-date) is a row whose owner is undecidable
here, and a guess would carry a real-looking citation. It stays ungrouped —
the same conservatism as the header-row rule in tables.py.

The result is one FORM_FIELD element (the enum's existing pair type; the
composer renders it as prose, the structure view skips it until it earns a
shape). Its spans are label then value in page order, and the attributes say
which ordinals are which — ordinals only, never text, so nothing in a jsonb
column repeats page content.
"""

from pragmaticds_docengine_worker.layout.model import Element, Line, union_box
from pragmaticds_docengine_worker.layout.values import is_value_run

#: Same honest fixed prior PARAGRAPH/HEADER carry — pairing is a rule, not a model.
PAIR_CONFIDENCE = 0.9
#: A label is a few words; a sentence that happens to end before a figure is prose.
LABEL_MAX_SPANS = 5
#: "$" + "3,105.87" is the widest value run the parsers emit; three spans is a list.
VALUE_MAX_SPANS = 2
#: The value sits within this many ems of the label's end. A leader or a tab stop
#: on a form is a few ems; 8 keeps "Pay Date   01/15/2026" and "Hours   40" while
#: refusing a figure that sits in a column of its own — a paystub's Gross Pay with
#: its amount right-aligned ~20 ems away under the Current column is a column
#: relationship the table detector and the reading order own, and a two-column
#: page's right column is further still. A wrong pair carries a citation (#61);
#: a missed one costs nothing the paragraph stage does not still serve.
PAIR_GAP_EM = 8.0


def detect_pairs(lines: list[Line]) -> tuple[list[Element], list[Line]]:
    """(FORM_FIELD elements top-to-bottom, the lines not consumed, in input order)."""
    by_band: dict[int, list[Line]] = {}
    for line in lines:
        by_band.setdefault(line.band, []).append(line)

    pairs: list[Element] = []
    consumed: set[int] = set()
    for band, members in sorted(by_band.items(), key=lambda item: min(l.top for l in item[1])):
        fragments = sorted(members, key=lambda l: l.left)
        others = [line for line in lines if line.band != band]
        index = 0
        while index < len(fragments) - 1:
            label, value = fragments[index], fragments[index + 1]
            next_is_value = index + 2 < len(fragments) and _is_value(fragments[index + 2])
            if (
                _is_label(label)
                and _is_value(value)
                and not next_is_value
                and _close(label, value)
                and not _text_in_gap(label, value, others)
            ):
                pairs.append(_pair(label, value))
                consumed.update((id(label), id(value)))
                index += 2
            else:
                index += 1

    remaining = [line for line in lines if id(line) not in consumed]
    return pairs, remaining


def _is_value(fragment: Line) -> bool:
    return len(fragment.spans) <= VALUE_MAX_SPANS and is_value_run(
        [span.text for span in fragment.spans]
    )


def _is_label(fragment: Line) -> bool:
    if len(fragment.spans) > LABEL_MAX_SPANS or _is_value(fragment):
        return False
    return any(char.isalpha() for span in fragment.spans for char in span.text)


def _close(label: Line, value: Line) -> bool:
    gap = value.left - label.right
    return 0 <= gap <= PAIR_GAP_EM * max(label.size, value.size)


def _text_in_gap(label: Line, value: Line, others: list[Line]) -> bool:
    """Prose on ANOTHER band that starts strictly inside the gap between the
    label and the figure means the gap is a column of the page, not the leader
    space of a form — the figure belongs to that column, not to this label. A
    neighbouring VALUE starting there is the form's own value column, ragged
    because figures are right-aligned or of different lengths, and does not
    count: "Gross Pay  1,234.56" over "Net Pay  1,000.00" stays two pairs."""
    return any(
        label.right < other.left < value.left and not _is_value(other) for other in others
    )


def _pair(label: Line, value: Line) -> Element:
    spans = (*label.spans, *value.spans)
    return Element(
        element_type="FORM_FIELD",
        box=union_box([span.box for span in spans]),
        spans=spans,
        confidence=PAIR_CONFIDENCE,
        attributes={
            "labelSpanOrdinals": [span.ordinal for span in label.spans],
            "valueSpanOrdinals": [span.ordinal for span in value.spans],
        },
    )
