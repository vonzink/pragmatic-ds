"""THE coordinate contract test (IMPLEMENTATION_PLAN.md Phase 2, risk R1).

fixtures/truth/native_paystub.json records the box of every word AS DRAWN —
x/width from reportlab stringWidth, y/height from font ascent/descent. Every one
must match an extracted span:

    x within 0.5pt · x-extent (x+width) within 1.0pt · vertical ranges overlap
    by >= 80% of the shorter · whole-box IoU >= 0.9 MEAN across all words.

The plan's aspirational 0.98 IoU is not reachable here and that is a measured
fact, not a shortcut: pdfminer word heights use the font's glyph bbox while the
truth uses ascent-descent (e.g. ACME: 14.0pt vs 13.0pt tall at size 14), so
vertical extents systematically differ by ~1pt. Measured mean IoU on this
fixture: ~0.921, minimum ~0.905. The x-axis — where field VALUES live — is
asserted to the tight tolerances above.

A coordinate bug produces plausible-looking boxes with no exception raised
anywhere; this file is the tripwire.
"""

X_TOLERANCE_PT = 0.5
X_EXTENT_TOLERANCE_PT = 1.0
MIN_Y_OVERLAP_RATIO = 0.8
MIN_MEAN_IOU = 0.9


def iou(a: dict, b: dict) -> float:
    ax2, ay2 = a["x"] + a["width"], a["y"] + a["height"]
    bx2, by2 = b["x"] + b["width"], b["y"] + b["height"]
    overlap_w = max(0.0, min(ax2, bx2) - max(a["x"], b["x"]))
    overlap_h = max(0.0, min(ay2, by2) - max(a["y"], b["y"]))
    intersection = overlap_w * overlap_h
    union = a["width"] * a["height"] + b["width"] * b["height"] - intersection
    return intersection / union if union > 0 else 0.0


def y_overlap_ratio(a: dict, b: dict) -> float:
    overlap = min(a["y"] + a["height"], b["y"] + b["height"]) - max(a["y"], b["y"])
    shorter = min(a["height"], b["height"])
    return overlap / shorter if shorter > 0 else 0.0


def test_every_truth_word_matches_an_extracted_span(client, fixture_bytes, fixture_truth, text_of):
    truth = fixture_truth("native_paystub.json")["pages"][0]

    body = text_of(client, fixture_bytes("native_paystub.pdf")).json()

    spans = body["pages"][0]["spans"]
    assert len(spans) == len(truth["words"]), "span count diverged from words drawn"

    ious = []
    for word in truth["words"]:
        candidates = [span for span in spans if span["text"] == word["text"]]
        assert candidates, f"no span extracted for drawn word {word['text']!r}"
        best = max(candidates, key=lambda span: iou(word, span))

        assert abs(best["x"] - word["x"]) <= X_TOLERANCE_PT, (
            f"{word['text']!r}: x {best['x']} vs truth {word['x']}"
        )
        truth_extent = word["x"] + word["width"]
        span_extent = best["x"] + best["width"]
        assert abs(span_extent - truth_extent) <= X_EXTENT_TOLERANCE_PT, (
            f"{word['text']!r}: x-extent {span_extent} vs truth {truth_extent}"
        )
        assert y_overlap_ratio(word, best) >= MIN_Y_OVERLAP_RATIO, (
            f"{word['text']!r}: vertical overlap "
            f"{y_overlap_ratio(word, best):.3f} < {MIN_Y_OVERLAP_RATIO}"
        )
        ious.append(iou(word, best))

    mean_iou = sum(ious) / len(ious)
    assert mean_iou >= MIN_MEAN_IOU, f"mean IoU {mean_iou:.4f} < {MIN_MEAN_IOU}"


def test_contract_holds_on_every_page_of_the_multipage_fixture(
    client, fixture_bytes, fixture_truth, text_of
):
    truth = fixture_truth("native_multipage.json")

    body = text_of(client, fixture_bytes("native_multipage.pdf")).json()

    for truth_page, got_page in zip(truth["pages"], body["pages"]):
        spans = got_page["spans"]
        assert len(spans) == len(truth_page["words"])
        for word in truth_page["words"]:
            candidates = [span for span in spans if span["text"] == word["text"]]
            best = max(candidates, key=lambda span: iou(word, span))
            assert abs(best["x"] - word["x"]) <= X_TOLERANCE_PT
            assert y_overlap_ratio(word, best) >= MIN_Y_OVERLAP_RATIO
