"""Span ORDER on /Rotate pages — the assertion whose absence let a reversal ship.

test_coordinate_contract.py matches every truth word to an extracted span BY BOX
(`max(candidates, key=iou)`), so it is blind to the sequence those spans arrive
in. The Java integration tests (RotatedPageFieldExtractionIT) replay pages from
these same truth files rather than from the worker, so they never see the
worker's ordering either. Between the two, a /Rotate page could come back with
perfect boxes, perfect characters, and its words in reverse — and did: sorting
in DISPLAY space walks a 180-turned page from the last logical line to the
first, right-to-left within each line, which drops PAYSTUB from 1.00 to 0.30
because "Net Pay" arrives as "Pay Net".

So this file asserts the pairing the others each half-assert: span i is truth
word i, in text AND in box. Order and geometry cannot drift apart while it
holds.

fixtures/truth/paystub_complete_rot{90,180,270}.json record the SAME canonical
word list as the unrotated fixture — same order, same rotation-0 boxes — because
rotation is a property of the page, not of what the page says. Rotation 0 is
parametrised in alongside them as the control: it passed before this test
existed, and proves the expectation is the reading order, not the fix.
"""

import pytest

from pragmaticds_docengine_worker.text import _reading_order

#: A rotated box is unrotated through a page dimension and re-rounded, so it can
#: land 0.1pt off the box the generator drew. Tighter than a character is width.
BOX_TOLERANCE_PT = 0.6


def y_overlap_ratio(a: dict, b: dict) -> float:
    overlap = min(a["y"] + a["height"], b["y"] + b["height"]) - max(a["y"], b["y"])
    shorter = min(a["height"], b["height"])
    return overlap / shorter if shorter > 0 else 0.0


@pytest.mark.parametrize("rotation", [0, 90, 180, 270])
def test_spans_arrive_in_canonical_reading_order(
    client, fixture_bytes, fixture_truth, text_of, rotation
):
    stem = "paystub_complete" if rotation == 0 else f"paystub_complete_rot{rotation}"
    truth = fixture_truth(f"{stem}.json")["pages"][0]

    page = text_of(client, fixture_bytes(f"{stem}.pdf")).json()["pages"][0]

    assert page["rotation"] == rotation
    spans = page["spans"]
    assert [span["text"] for span in spans] == [word["text"] for word in truth["words"]], (
        f"/Rotate {rotation} reading order diverged from the canonical word sequence"
    )


@pytest.mark.parametrize("rotation", [0, 90, 180, 270])
def test_the_span_at_each_ordinal_carries_that_words_box(
    client, fixture_bytes, fixture_truth, text_of, rotation
):
    """Order and geometry welded: the nth span is the nth word, box included.

    Matching pairwise rather than by best-IoU is the whole point — a reversed
    page satisfies a by-box match and fails this.
    """
    stem = "paystub_complete" if rotation == 0 else f"paystub_complete_rot{rotation}"
    truth = fixture_truth(f"{stem}.json")["pages"][0]

    spans = text_of(client, fixture_bytes(f"{stem}.pdf")).json()["pages"][0]["spans"]

    assert len(spans) == len(truth["words"])
    for ordinal, (span, word) in enumerate(zip(spans, truth["words"])):
        assert span["ordinal"] == ordinal, "ordinals must number the payload order"
        assert span["text"] == word["text"], f"ordinal {ordinal}: {span['text']!r} != {word['text']!r}"
        assert abs(span["x"] - word["x"]) <= BOX_TOLERANCE_PT, (
            f"ordinal {ordinal} {word['text']!r}: x {span['x']} vs truth {word['x']}"
        )
        assert y_overlap_ratio(word, span) >= 0.8, (
            f"ordinal {ordinal} {word['text']!r}: vertical overlap "
            f"{y_overlap_ratio(word, span):.3f} — right text, wrong line"
        )


class TestOrderingReadsTheExactExtent:
    """_reading_order sorts EXACT canonical extents, never the emitted 0.1pt box.

    Rounding to a tenth is monotonic, so it cannot reverse a comparison — but it
    is not injective, and the ties it invents are decided by whatever pdfplumber
    happened to emit first. Measured, not theorised: ordering the rounded boxes
    moved two words on page 1 of a real filled 1040, where a band-join sat within
    0.05pt of the half-height threshold.

    Extents are (x0, top, x1, bottom); the payload rides along untouched.
    """

    def test_a_rounded_tie_does_not_merge_two_lines(self):
        # first: top 100.0, bottom 110.0.  second: top 105.04, bottom 115.04.
        # Exact overlap 110.0 - 105.04 = 4.96 < half of the 10.0-tall shorter box,
        # so these are two lines. Round both to a tenth and the overlap reads 5.0,
        # which clears the >= half test and welds them into one — at which point
        # the band sorts by x and hands back the second word first.
        first = ((200.0, 100.0, 260.0, 110.0), {"text": "first"})
        second = ((100.0, 105.04, 160.0, 115.04), {"text": "second"})

        ordered = _reading_order([first, second])

        assert [word["text"] for _, word in ordered] == ["first", "second"]

    def test_a_genuine_overlap_still_makes_one_line(self):
        """The mirror case, so the test above cannot pass by never banding."""
        # 110.0 - 104.96 = 5.04 >= 5.0: one line, and a line reads left to right.
        first = ((200.0, 100.0, 260.0, 110.0), {"text": "first"})
        second = ((100.0, 104.96, 160.0, 114.96), {"text": "second"})

        ordered = _reading_order([first, second])

        assert [word["text"] for _, word in ordered] == ["second", "first"]
