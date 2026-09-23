"""The generator's own guarantees: determinism, provenance, and truthful ground truth.

Determinism is load-bearing — the manifest pins sha256s, so a generator that embeds
timestamps would make every regeneration look like tampering.
"""

import importlib.util
import json
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "worker" / "src"))

spec = importlib.util.spec_from_file_location("fixture_generator", REPO / "fixtures" / "generate.py")
fixture_generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_generator)


@pytest.fixture(scope="module")
def generated():
    return fixture_generator.generate()


def test_generation_is_deterministic(generated):
    again = fixture_generator.generate()

    assert generated.keys() == again.keys()
    for path in generated:
        assert generated[path] == again[path], f"{path} differs between runs — timestamps leaking?"


def test_every_fixture_class_phase2_needs_is_present(generated):
    names = set(generated)
    for required in [
        "native_paystub.pdf",
        "native_multipage.pdf",
        "scanned_paystub.pdf",
        "scanned_rot90.pdf",
        "scanned_rot180.pdf",
        "scanned_rot270.pdf",
        "degraded_paystub.pdf",
        "blank_page.pdf",
        "mixed_page.pdf",
    ]:
        assert required in names, f"missing fixture {required}"
        truth = f"truth/{required.replace('.pdf', '.json')}"
        assert truth in names, f"missing ground truth {truth}"


def test_ground_truth_boxes_are_canonical_and_sane(generated):
    truth = json.loads(generated["truth/native_paystub.json"])
    page = truth["pages"][0]

    assert page["widthPt"] == 612.0
    assert page["heightPt"] == 792.0
    words = page["words"]
    assert len(words) > 30  # a paystub-shaped page, not a token gesture
    for word in words:
        assert 0 <= word["x"] <= 612 and 0 <= word["y"] <= 792, word
        assert word["width"] > 0 and word["height"] > 0
        # Canonical space is top-left: the header drawn near the TOP of the page
        # must have a SMALL y.
    header = next(word for word in words if word["text"] == "ACME")
    assert header["y"] < 100, "top-left origin violated — header should be near y=0"

    net_pay = next(word for word in words if word["text"] == "$3,105.87")
    assert net_pay["y"] > header["y"], "net pay line is below the header on the page"


def test_rotated_truth_speaks_the_real_page_frame(generated):
    """Phase 2 review finding: rot90/270 truth declared portrait dims for LANDSCAPE
    pages — truth contradicting its own fixture's geometry, which is exactly how the
    rotation-frame defect stayed invisible. Truth must carry the real page dims and
    boxes in that page's frame, verified via the geometry module's tested inverse."""
    import sys
    from pathlib import Path as P

    sys.path.insert(0, str(P(__file__).resolve().parents[2] / "worker" / "src"))
    from pragmaticds_docengine_worker.geometry import Box, unrotate_box

    portrait = json.loads(generated["truth/native_paystub.json"])["pages"][0]["words"]

    for rotation in (90, 180, 270):
        page = json.loads(generated[f"truth/scanned_rot{rotation}.json"])["pages"][0]
        expected_w, expected_h = (792.0, 612.0) if rotation in (90, 270) else (612.0, 792.0)
        assert page["widthPt"] == expected_w and page["heightPt"] == expected_h, rotation
        assert len(page["words"]) == len(portrait)
        # Word order is generator-preserved: compare by index (texts repeat, e.g. "Pay").
        for word, source in zip(page["words"], portrait):
            assert word["text"] == source["text"]
            assert 0 <= word["x"] <= expected_w and 0 <= word["y"] <= expected_h, (rotation, word)
            # Un-rotating a page-frame box must recover the portrait-frame truth box.
            back = unrotate_box(
                Box(word["x"], word["y"], word["width"], word["height"]),
                rotation=rotation, page_w_pt=612.0, page_h_pt=792.0,
            )
            assert abs(back.x - source["x"]) < 0.15 and abs(back.y - source["y"]) < 0.15, (rotation, word)


def test_table_fixtures_carry_cell_truth(generated):
    """M4 (table alignment) ground truth by construction: every grid word knows its
    (row, col); the ruled variant differs from the unruled ONLY by drawn lines."""
    for name in ("ruled_table", "unruled_table"):
        page = json.loads(generated[f"truth/{name}.json"])["pages"][0]
        cells = [w for w in page["words"] if "cell" in w]
        assert len(cells) == 25, (name, len(cells))  # 5 rows x 5 cols
        rows = {c["cell"]["row"] for c in cells}
        cols = {c["cell"]["col"] for c in cells}
        assert rows == {0, 1, 2, 3, 4} and cols == {0, 1, 2, 3, 4}, name
        # Column truth is geometric: same col => same x by construction.
        by_col = {}
        for c in cells:
            by_col.setdefault(c["cell"]["col"], set()).add(c["x"])
        for col, xs in by_col.items():
            assert len(xs) == 1, f"{name} col {col} not x-aligned: {xs}"

    ruled = json.loads(generated["truth/ruled_table.json"])["pages"][0]["words"]
    unruled = json.loads(generated["truth/unruled_table.json"])["pages"][0]["words"]
    assert ruled == unruled, "the two table variants must differ only by drawn lines"


def test_ruled_table_actually_has_rulings_and_unruled_does_not(generated):
    import io

    import pdfplumber

    with pdfplumber.open(io.BytesIO(generated["ruled_table.pdf"])) as pdf:
        assert len(pdf.pages[0].lines) >= 10, "ruled fixture must carry drawn grid lines"
    with pdfplumber.open(io.BytesIO(generated["unruled_table.pdf"])) as pdf:
        assert len(pdf.pages[0].lines) == 0, "unruled fixture must carry none"


def test_two_column_truth_order_is_reading_order(generated):
    """The left column's words all precede the right column's in truth order —
    that ordering IS the reading-order ground truth (Kendall tau target)."""
    words = json.loads(generated["truth/two_column.json"])["pages"][0]["words"]
    left_indices = [i for i, w in enumerate(words) if w["x"] < 300]
    right_indices = [i for i, w in enumerate(words) if w["x"] >= 300]
    assert left_indices and right_indices
    assert max(left_indices) < min(right_indices), "truth order must be column-major"


def test_combined_package_duplicates_are_byte_level_span_identical(generated):
    """Pages 10-11 must be EXACT redraws of pages 0-1 — that is what makes them
    content-hash duplicates downstream. Word lists must match to the 0.1pt."""
    truth = json.loads(generated["truth/combined_package.json"])
    pages = truth["pages"]
    for original, duplicate in ((0, 10), (1, 11)):
        assert pages[original]["words"] == pages[duplicate]["words"], (original, duplicate)
    assert truth["unassignedPages"] == [9, 10, 11]
    assert pages[9]["words"] == [], "page 9 must be blank"


def test_combined_package_document_truth_covers_every_typed_page(generated):
    truth = json.loads(generated["truth/combined_package.json"])
    grouped = {p for doc in truth["expectedDocuments"] for p in doc["pages"]}
    unassigned = set(truth["unassignedPages"])
    assert grouped | unassigned == set(range(20))
    assert grouped & unassigned == set()
    types = [p["expectedType"] for p in truth["pages"]]
    assert types.count("BANK_STATEMENT") == 6 and types.count("W2") == 1


def test_ambiguous_page_carries_exactly_the_two_planted_weak_anchors(generated):
    """The ambiguous fixture's design: one mid-weight anchor from TWO packs and no
    other anchor phrases. If someone edits the page text and accidentally adds a
    third anchor, the UNKNOWN-below-threshold test upstream stops meaning anything."""
    words = " ".join(
        w["text"] for w in json.loads(generated["truth/ambiguous.json"])["pages"][0]["words"]
    )
    assert "Net Pay" in words and "Ending Balance" in words
    for strong_anchor in ("Pay Period", "Gross Pay", "Statement Period", "Beginning Balance",
                          "W-2", "Wage and Tax Statement", "Earnings"):
        assert strong_anchor not in words, f"ambiguous fixture gained a strong anchor: {strong_anchor}"


def test_scanned_fixture_has_no_text_layer(generated):
    import pdfplumber

    import io
    with pdfplumber.open(io.BytesIO(generated["scanned_paystub.pdf"])) as pdf:
        assert pdf.pages[0].extract_words() == []


def test_native_fixture_text_layer_matches_truth_words(generated):
    import io

    import pdfplumber

    truth = json.loads(generated["truth/native_paystub.json"])
    expected = {word["text"] for word in truth["pages"][0]["words"]}
    with pdfplumber.open(io.BytesIO(generated["native_paystub.pdf"])) as pdf:
        extracted = {word["text"] for word in pdf.pages[0].extract_words()}

    # Every drawn word is recoverable (modulo pdfplumber merging: allow supersets).
    missing = {word for word in expected if word not in extracted}
    assert not missing, f"drawn words not found in the text layer: {missing}"


def test_drivers_license_fixture_carries_its_pack_anchor_phrases(generated):
    """Spec 3 T8: the DL card fixture and the V11 DRIVERS_LICENSE pack are co-authored —
    every anchor phrase the pack scores must be drawn on the card, verbatim."""
    assert "drivers_license.pdf" in generated
    truth = json.loads(generated["truth/drivers_license.json"])
    assert truth["expectedDocuments"] == [{"type": "DRIVERS_LICENSE", "pages": [0]}]
    assert truth["unassignedPages"] == []
    page = truth["pages"][0]
    assert page["expectedType"] == "DRIVERS_LICENSE"
    words = " ".join(w["text"] for w in page["words"])
    for anchor in ("DRIVER LICENSE", "DL No", "DOB", "EXP", "CLASS", "ISS"):
        assert anchor in words, f"missing DRIVERS_LICENSE anchor phrase: {anchor}"
    assert "COLORADO" in words  # the state-issuer header the card layout requires
    # And none of another pack's strong anchors — separation starts at authoring time.
    for foreign in ("Pay Period", "Gross Pay", "Wage and Tax Statement", "Statement Period",
                    "Mortgage Statement", "Principal Balance"):
        assert foreign not in words, f"DL fixture gained a foreign anchor: {foreign}"


def test_mortgage_statement_fixture_carries_ms_anchors_and_no_bank_anchors(generated):
    """Spec 3 T8: the servicing-statement fixture must carry every MORTGAGE_STATEMENT
    anchor and NONE of the BANK_STATEMENT pack's phrases — the confusable-neighbor
    rule (design section 4), enforced from the authoring side before cross-confusion CI."""
    assert "mortgage_statement.pdf" in generated
    truth = json.loads(generated["truth/mortgage_statement.json"])
    assert truth["expectedDocuments"] == [{"type": "MORTGAGE_STATEMENT", "pages": [0]}]
    page = truth["pages"][0]
    assert page["expectedType"] == "MORTGAGE_STATEMENT"
    words = " ".join(w["text"] for w in page["words"])
    for anchor in ("Mortgage Statement", "Loan Number", "Payment Due Date",
                   "Principal Balance", "Escrow Balance", "Interest Rate"):
        assert anchor in words, f"missing MORTGAGE_STATEMENT anchor phrase: {anchor}"
    for bank in ("Statement Period", "Beginning Balance", "Ending Balance",
                 "Account Statement", "Deposits and Credits", "Withdrawals"):
        assert bank not in words, f"MS fixture gained a BANK_STATEMENT anchor: {bank}"
    for other in ("Pay Period", "Gross Pay", "Net Pay", "Pay Date",
                  "W-2", "Wage and Tax Statement"):
        assert other not in words, f"MS fixture gained a foreign anchor: {other}"


# ── Spec 3 (T9): HOI declaration, purchase contract, tax return ──────────────


def test_t9_fixtures_are_present_with_truth(generated):
    names = set(generated)
    for required in [
        "hoi_declaration.pdf",
        "purchase_contract_signed.pdf",
        "purchase_contract_unsigned.pdf",
        "tax_return.pdf",
        "tax_return_single.pdf",
        "tax_return_mfs.pdf",
        "tax_return_hoh.pdf",
        "tax_return_qss.pdf",
        "degraded_tax_return.pdf",
    ]:
        assert required in names, f"missing fixture {required}"
        truth = f"truth/{required.replace('.pdf', '.json')}"
        assert truth in names, f"missing ground truth {truth}"
    assert "degraded_tax_return_page0.png" in names


def test_tax_return_page1_has_five_status_boxes_exactly_one_checked(generated):
    """Truth-by-construction for the checkbox page: five 10 pt squares, ONE pair of
    crossing lines, and that pair sits inside the box on the 'Married filing jointly'
    row. If someone checks a second box, the CHECKBOX_STATE zero-or-multi rule (T13)
    upstream stops meaning anything — this pins the fixture side of that contract."""
    import io

    import pdfplumber

    truth = json.loads(generated["truth/tax_return.json"])
    jointly = next(w for w in truth["pages"][0]["words"] if w["text"] == "jointly")

    with pdfplumber.open(io.BytesIO(generated["tax_return.pdf"])) as pdf:
        page = pdf.pages[0]
        boxes = [
            r for r in page.rects
            if 9.0 <= r["width"] <= 11.0 and 9.0 <= r["height"] <= 11.0
        ]
        assert len(boxes) == 5, f"expected five 10pt checkboxes, found {len(boxes)}"
        diagonals = [
            l for l in page.lines
            if abs(l["x0"] - l["x1"]) > 1.0 and abs(l["top"] - l["bottom"]) > 1.0
        ]
        assert len(diagonals) == 2, "exactly one checked box = exactly two crossing lines"
        containing = [
            b for b in boxes
            if all(
                b["x0"] - 0.5 <= d["x0"] and d["x1"] <= b["x1"] + 0.5
                and b["top"] - 0.5 <= d["top"] and d["bottom"] <= b["bottom"] + 0.5
                for d in diagonals
            )
        ]
        assert len(containing) == 1, "both crossing lines must sit inside one single box"
        box_center_y = (containing[0]["top"] + containing[0]["bottom"]) / 2
        label_center_y = jointly["y"] + jointly["height"] / 2
        assert abs(box_center_y - label_center_y) < 6, "the checked box is not on the MFJ row"


def test_checkbox_helper_records_label_truth(generated):
    """PageBuilder.checkbox must record truth for its label words — they are what
    CHECKBOX_STATE anchors on (T13)."""
    words = [w["text"] for w in json.loads(generated["truth/tax_return.json"])["pages"][0]["words"]]
    for label_word in ("Single", "Married", "filing", "jointly", "separately",
                       "Head", "household", "Qualifying", "surviving", "spouse"):
        assert label_word in words, f"checkbox label word missing from truth: {label_word}"


def test_purchase_contract_variants_differ_only_by_ink(generated):
    """Same words, same truth boxes; the signed page 2 carries six bezier strokes
    (two squiggles x three curves each), the unsigned page carries none. That ink
    difference IS the fixture pair's entire reason to exist (design D5: absence is
    an answer for SIGNATURE_PRESENCE)."""
    import io

    import pdfplumber

    signed = json.loads(generated["truth/purchase_contract_signed.json"])
    unsigned = json.loads(generated["truth/purchase_contract_unsigned.json"])
    for page_index in (0, 1):
        assert signed["pages"][page_index]["words"] == unsigned["pages"][page_index]["words"]

    with pdfplumber.open(io.BytesIO(generated["purchase_contract_signed.pdf"])) as pdf:
        assert len(pdf.pages[0].curves) == 0
        assert len(pdf.pages[1].curves) == 6, "signed variant must carry two 3-stroke squiggles"
    with pdfplumber.open(io.BytesIO(generated["purchase_contract_unsigned.pdf"])) as pdf:
        assert len(pdf.pages[1].curves) == 0, "unsigned variant must carry no ink at all"


def test_hoi_mortgagee_clause_present_without_mortgage_statement_vocabulary(generated):
    """The HOI page deliberately names a mortgage lender (real dec pages almost always
    do) — and must still carry NONE of the MORTGAGE_STATEMENT pack's anchor phrases.
    Same guard shape as the ambiguous-fixture test above: if someone edits the page
    and accidentally adds a foreign anchor, the no-trip IT stops meaning anything.
    Case-insensitive, mirroring the literal matcher."""
    joined = " ".join(
        w["text"]
        for w in json.loads(generated["truth/hoi_declaration.json"])["pages"][0]["words"]
    )
    assert "Mortgagee:" in joined and "MORTGAGE" in joined
    lowered = joined.lower()
    for foreign_anchor in ("Mortgage Statement", "Principal Balance", "Escrow",
                           "Loan Number", "Payment Due", "Statement Period",
                           "Beginning Balance"):
        assert foreign_anchor.lower() not in lowered, (
            f"HOI fixture gained a foreign anchor: {foreign_anchor}"
        )


def test_tax_return_truth_and_degraded_variant(generated):
    truth = json.loads(generated["truth/tax_return.json"])
    # Two pages since issue #60: the Schedule 2 header that used to ride as page 3 is
    # its own type (schedule_2.pdf) and would split into a second document here.
    assert [p["expectedType"] for p in truth["pages"]] == ["TAX_RETURN"] * 2
    assert truth["expectedDocuments"] == [{"pages": [0, 1], "type": "TAX_RETURN"}]

    degraded = json.loads(generated["truth/degraded_tax_return.json"])
    assert len(degraded["pages"]) == 1
    assert degraded["pages"][0]["expectedVerdict"] == "SCANNED"
    # Degraded truth words are page 1's words verbatim — same boxes, worse pixels.
    assert degraded["pages"][0]["words"] == truth["pages"][0]["words"]


@pytest.mark.parametrize(
    ("fixture", "row_word"),
    [
        ("tax_return_single", "Single"),
        ("tax_return_mfs", "separately"),
        ("tax_return_hoh", "household"),
        ("tax_return_qss", "spouse"),
    ],
)
def test_each_filing_status_variant_checks_exactly_its_own_box(generated, fixture, row_word):
    """Spec §7: the generator produces EACH filing status. Same truth-by-construction
    pin as the canonical MFJ test above, once per single-page variant: five 10 pt
    boxes, ONE pair of crossing lines, and that pair sits inside the box on the row
    whose label ends with `row_word`. T13's parameterized extraction IT trusts this
    drawn ink when it maps each fixture to its enum code — the Java IT inserts its own
    detections, so THIS test is the only guard on the variants' actual ink."""
    import io

    import pdfplumber

    truth = json.loads(generated[f"truth/{fixture}.json"])
    assert len(truth["pages"]) == 1
    assert truth["pages"][0]["expectedType"] == "TAX_RETURN"
    row = next(w for w in truth["pages"][0]["words"] if w["text"] == row_word)

    with pdfplumber.open(io.BytesIO(generated[f"{fixture}.pdf"])) as pdf:
        page = pdf.pages[0]
        boxes = [
            r for r in page.rects
            if 9.0 <= r["width"] <= 11.0 and 9.0 <= r["height"] <= 11.0
        ]
        assert len(boxes) == 5, f"{fixture}: expected five 10pt checkboxes, found {len(boxes)}"
        diagonals = [
            l for l in page.lines
            if abs(l["x0"] - l["x1"]) > 1.0 and abs(l["top"] - l["bottom"]) > 1.0
        ]
        assert len(diagonals) == 2, "exactly one checked box = exactly two crossing lines"
        containing = [
            b for b in boxes
            if all(
                b["x0"] - 0.5 <= d["x0"] and d["x1"] <= b["x1"] + 0.5
                and b["top"] - 0.5 <= d["top"] and d["bottom"] <= b["bottom"] + 0.5
                for d in diagonals
            )
        ]
        assert len(containing) == 1, "both crossing lines must sit inside one single box"
        box_center_y = (containing[0]["top"] + containing[0]["bottom"]) / 2
        label_center_y = row["y"] + row["height"] / 2
        assert abs(box_center_y - label_center_y) < 6, (
            f"{fixture}: the checked box is not on the '{row_word}' row"
        )


def _union(boxes):
    """The union box of a list of truth words: (x, y, width, height)."""
    x0 = min(b["x"] for b in boxes)
    y0 = min(b["y"] for b in boxes)
    x1 = max(b["x"] + b["width"] for b in boxes)
    y1 = max(b["y"] + b["height"] for b in boxes)
    return x0, y0, x1 - x0, y1 - y0


def _caption_on_row(words, y, phrase):
    """The union box of the caption `phrase` — consecutive words — printed on the row
    whose top is `y`. Fails loudly when the row does not print it."""
    row = sorted((w for w in words if abs(w["y"] - y) < 0.5), key=lambda w: w["x"])
    for i in range(len(row) - len(phrase) + 1):
        if [w["text"] for w in row[i:i + len(phrase)]] == phrase:
            return _union(row[i:i + len(phrase)])
    raise AssertionError(f"caption {' '.join(phrase)!r} is not printed on the row at y={y}")


def test_w2_is_a_box_grid_with_every_value_below_its_caption(generated):
    """Spec 4 T2: the W-2 fixture is a BOX GRID — the layout real IRS forms use.

    Until 2026-08-10 this fixture drew every value to the RIGHT of its label on the
    same line. No real W-2 is laid out that way. The w2@1.0.0 schema was authored
    against that drawing, so ten of ten fields extracted here while two of ten
    extracted from a real filled W-2 — and NOTHING in this suite contradicted the
    fiction, which is precisely why it shipped. This test is that contradiction,
    made permanent: it fails the moment anyone flattens the page again.

    The numbers are the ones measured on the real form: a value's top sits 12.5pt
    below its caption's top, i.e. 6pt below its caption's bottom edge, well inside
    the LABEL_BELOW rung's 24pt maxDropPt, and EVERY value word shares at least half
    of the narrower box's width with the caption cell that OWNS it (the rung's
    cellOverlap default).

    Since V47 (2026-09-14, measured on a filled W-2) one field spans TWO cells: box e is
    `first name and initial` | `Last name` | `Suff.` on one caption row, a filed form
    fills them as captioned, and the employeeName rung reads the first cell and the
    `Last name` cell together (joinCells). So the first name and the initial are owned
    by the field's own caption and the surname by the `Last name` caption — each value
    word is tested against the cell that owns it, exactly as the rung admits spans.
    """
    truth = json.loads(generated["truth/w2_form.json"])
    by_name = {f["field"]: f for f in truth["expectedFields"]}
    page_words = truth["pages"][0]["words"]

    box_grid = [f for f in truth["expectedFields"] if f["method"] == "LABEL_BELOW"]
    assert len(box_grid) == 9, "nine of the ten W2 fields read a box-grid cell"

    for field in box_grid:
        name = field["field"]
        lx, ly, lw, lh = _union(field["labelWords"])
        vx, vy, vw, vh = _union(field["valueWords"])

        assert abs((vy - ly) - 12.5) < 0.2, (
            f"{name}: value top is {vy - ly:.2f}pt below its caption's top, "
            f"not the 12.5pt measured on a real W-2"
        )
        drop = vy - (ly + lh)
        assert 0.0 <= drop <= 24.0, (
            f"{name}: drop {drop:.2f}pt is outside the rung's maxDropPt window"
        )
        # The rung applies cellOverlap PER SPAN — ownsSpan runs once per span inside its
        # per-line loop — so the bound has to hold for EVERY value word, not for their
        # union. Asserting the union would hide a leading word that falls out of the
        # cell, and a value whose first word is dropped comes back as a SILENT PARTIAL
        # ("WIDGETS LLC" for "ACME WIDGETS LLC"), which is a confident wrong value, not
        # the missing field a reviewer can act on. The multi-word values clear 0.5 on
        # their leading word by ~2pt of x; the union figure looks comfortable and is not
        # the gate.
        #
        # Each word is tested against the cell that OWNS it. For every field that is the
        # field's own caption; employeeName (V47) also owns the `Last name` cell on the
        # same caption row — the rung's joinCells — and the surname must sit THERE, not
        # under the first-name caption, or the fixture would no longer draw the split
        # name the real form fills and the join would be exercised by nothing.
        cells = {"its caption": (lx, ly, lw, lh)}
        if name == "employeeName":
            cells["Last name"] = _caption_on_row(page_words, ly, ["Last", "name"])
        owned = {cell: [] for cell in cells}
        for word in field["valueWords"]:
            owner = None
            for cell, (cx, cy, cw, ch) in cells.items():
                shared = min(cx + cw, word["x"] + word["width"]) - max(cx, word["x"])
                narrower = min(cw, word["width"])
                if shared / narrower >= 0.5:
                    owner = cell
            assert owner is not None, (
                f"{name}: value word {word['text']!r} shares less than 0.5 of the "
                f"narrower box with every cell of its field — the rung would drop it "
                f"and return a partial value"
            )
            owned[owner].append(word["text"])
        leading = field["valueWords"][0]["text"]
        assert leading in owned["its caption"], (
            f"{name}: the leading value word {leading!r} is not in the anchoring cell"
        )
        if name == "employeeName":
            surname = field["valueWords"][-1]["text"]
            assert owned["Last name"] == [surname], (
                f"employeeName: the surname {surname!r} must be the one word under "
                f"`Last name` (the joined cell), found {owned['Last name']}"
            )
            assert len(owned["its caption"]) == 2, (
                "employeeName: the first name and the initial sit under the first-name "
                f"caption, found {owned['its caption']}"
            )
        # The old fiction, stated as a negative so it can never come back: the value
        # is NOT on the caption's own line, and NOT to its right.
        assert vy > ly + lh, f"{name}: value is on the caption's line, not below it"

    # taxYear is the one genuinely FLAT field on a real W-2 — the year is printed
    # beside the form title, not in a box — so it keeps its ANCHOR_LABEL rung. Its
    # presence keeps this fixture honest about both layouts existing.
    flat = [f["field"] for f in truth["expectedFields"] if f["method"] == "ANCHOR_LABEL"]
    assert flat == ["taxYear"]
    lx, ly, lw, lh = _union(by_name["taxYear"]["labelWords"])
    vx, vy, vw, vh = _union(by_name["taxYear"]["valueWords"])
    assert vx > lx + lw, "taxYear's value is to the RIGHT of its label"
    assert abs((vy + vh / 2) - (ly + lh / 2)) < 6.0, "taxYear's value is on the label's line"


def test_w2_box_grid_still_carries_every_w2_pack_anchor(generated):
    """Redrawing the page must not cost classification. The W2 pack (V10, 1.1.0)
    weights by exclusivity, and the fixture's recall depends on every one of its six
    anchor phrases surviving as a contiguous run in reading order."""
    words = " ".join(
        w["text"] for w in json.loads(generated["truth/w2_form.json"])["pages"][0]["words"]
    )
    for anchor in (
        "W-2",
        "Wage and Tax Statement",
        "Employer identification number",
        "Federal income tax withheld",
        "Social security wages",
        "Copy B",
    ):
        assert anchor in words, f"the box-grid redraw lost W2 pack anchor: {anchor}"
    # And it must not have GAINED a rival pack's anchor on the way in (CrossConfusionIT
    # asserts the same thing end to end; catching it here is cheaper).
    for rival in (
        "Department of the Treasury—Internal Revenue Service",
        "Form 1040",
        "Pay Period",
        "Gross Pay",
        "Statement Period",
        "Beginning Balance",
        "Mortgage Statement",
        "Principal Balance",
    ):
        assert rival not in words, f"the box-grid redraw gained a rival anchor: {rival}"


def test_w2_box_one_and_box_three_carry_different_amounts(generated):
    """The old fixture drew the SAME amount in box 1 and box 3, so a rung that read
    the wrong cell would have produced the right number by accident — the fixture was
    structurally incapable of detecting the defect this spec exists to fix. Box 3 sits
    directly beneath box 1 on a real W-2; the two amounts must differ."""
    by_name = {
        f["field"]: f
        for f in json.loads(generated["truth/w2_form.json"])["expectedFields"]
    }
    wages = by_name["wagesTipsOtherComp"]["displayedText"]
    social = by_name["socialSecurityWages"]["displayedText"]
    assert wages == "61,538.72"
    assert social == "62,538.72"
    assert wages != social, "box 1 and box 3 must not be mutually confusable"


def test_w2_truth_carries_extraction_expectations(generated):
    """Spec 3 T10: w2_form graduates from classification truth to extraction truth.
    Spec 4 T3: the corrected box-grid page is read by w2@1.3.0 (V43 appended ADP's
    caption alternates to w2@1.1.0's ladders; V47 reads the employee's name across the
    split first-name / `Last name` cells the real form fills), whose nine box rungs
    are LABEL_BELOW — the value sits in the cell UNDER its caption, not to its right.

    T2 writes those method strings and pins how MANY of them there are (nine). This
    test pins WHICH nine, by name, plus taxYear as the one flat field. A count alone
    survives a rename or two fields swapping methods; a named set does not. It is
    green the moment T2 lands, which is the point — it exists to fail later."""
    truth = json.loads(generated["truth/w2_form.json"])
    assert truth["schema"] == "w2@1.3.0"
    by_name = {f["field"]: f for f in truth["expectedFields"]}
    assert set(by_name) == {
        "employeeName", "employeeSsn", "employerName", "employerEin", "taxYear",
        "wagesTipsOtherComp", "federalIncomeTaxWithheld", "socialSecurityWages",
        "medicareWages", "stateWages",
    }
    # The nine box-grid fields read DOWNWARD. taxYear is the one field a real W-2 prints
    # flat (the year sits right of "Wage and Tax Statement"), so it keeps ANCHOR_LABEL.
    assert {f for f, spec in by_name.items() if spec["method"] == "LABEL_BELOW"} == {
        "employeeName", "employeeSsn", "employerName", "employerEin",
        "wagesTipsOtherComp", "federalIncomeTaxWithheld", "socialSecurityWages",
        "medicareWages", "stateWages",
    }
    assert by_name["taxYear"]["method"] == "ANCHOR_LABEL"
    # Drawn XXX-XX-NNNN-shaped so the server's SSN mask branch triggers (9 digits).
    assert by_name["employeeSsn"]["displayedText"] == "123-45-6789"
    assert all(f["valueWords"] for f in by_name.values()), "every W2 field resolves value boxes"


def test_bank_statement_truth_carries_extraction_expectations(generated):
    """Spec 3 T10: bank_statement graduates to extraction truth; closing figures on the LAST page."""
    truth = json.loads(generated["truth/bank_statement.json"])
    assert truth["schema"] == "bank_statement@1.0.0"
    by_name = {f["field"]: f for f in truth["expectedFields"]}
    assert set(by_name) == {
        "accountHolderName", "bankName", "accountNumber", "statementPeriodStart",
        "statementPeriodEnd", "beginningBalance", "endingBalance", "totalDeposits",
        "totalWithdrawals",
        # V41: the online print-out's month-to-date tiles are fields of their own. A
        # mailed statement prints no such tiles, so the truth pins both MISSING here —
        # the two LABEL_ABOVE ladders must never fire on a statement's summary block.
        "monthToDateDeposits", "monthToDateWithdrawals",
    }
    assert by_name["monthToDateDeposits"]["method"] == "NONE"
    assert by_name["monthToDateWithdrawals"]["method"] == "NONE"
    # Page attribution by construction: the closing figures exist only on page index 2.
    assert by_name["endingBalance"]["pageIndex"] == 2
    assert by_name["totalDeposits"]["pageIndex"] == 2
    assert by_name["totalWithdrawals"]["pageIndex"] == 2
    # 12 digits, NOT 9: nine digits would take the SSN mask branch; last-4 mask expected.
    assert by_name["accountNumber"]["displayedText"] == "482199021177"


def test_drivers_license_truth_carries_extraction_expectations(generated):
    """Spec 3 T11: drivers_license graduates from classification truth to extraction truth."""
    truth = json.loads(generated["truth/drivers_license.json"])
    assert truth["schema"] == "drivers_license@1.0.0"
    by_name = {f["field"]: f for f in truth["expectedFields"]}
    assert set(by_name) == {
        "fullName", "licenseNumber", "dateOfBirth", "address", "issueDate",
        "expirationDate", "issuingState", "licenseClass",
    }
    # 10 digits, NOT 9: T8 drew 94-123-4567, which is nine digits ignoring punctuation
    # and would take the serializer's SSN mask branch; the licenseNumber contract is the
    # generic last-4 mask, so the drawn value must not be SSN-shaped.
    assert by_name["licenseNumber"]["displayedText"] == "941-234-5678"
    assert by_name["dateOfBirth"]["displayedText"] == "01/15/1988"
    # The name is now ONE surname-comma-given run so fullName has contiguous value words.
    assert by_name["fullName"]["displayedText"] == "FIXTURE, JORDAN QUINN"
    assert all(f["valueWords"] for f in by_name.values()), "every DL field resolves value boxes"


def test_mortgage_statement_truth_carries_extraction_expectations(generated):
    """Spec 3 T11: mortgage_statement graduates to extraction truth. NO builder change:
    every value word the schema reads was already drawn by T8, so the PDF bytes must
    not move — only the truth JSON does."""
    truth = json.loads(generated["truth/mortgage_statement.json"])
    assert truth["schema"] == "mortgage_statement@1.0.0"
    by_name = {f["field"]: f for f in truth["expectedFields"]}
    assert set(by_name) == {
        "borrowerName", "lenderName", "loanNumber", "statementDate", "paymentDueDate",
        "totalAmountDue", "principalBalance", "escrowBalance", "interestRate",
    }
    # 10 digits (not 9, so no SSN branch). The server mask reveals the last four
    # CHARACTERS — hyphen included: ••••-921. The masking IT pins that deliberately.
    assert by_name["loanNumber"]["displayedText"] == "0087-445-921"
    assert all(f["valueWords"] for f in by_name.values()), "every MS field resolves value boxes"


def test_combined_v2_covers_all_seven_types_plus_blank_and_duplicate(generated):
    """Spec 3's split-e2e fixture (plan root, Fixture contracts): one page per
    schema-bearing type in a fixed order, a blank page, and an exact duplicate
    of page 0 — the two unassigned pages."""
    truth = json.loads(generated["truth/combined_package_v2.json"])
    types = [p["expectedType"] for p in truth["pages"]]
    assert types == [
        "W2", "BANK_STATEMENT", "DRIVERS_LICENSE", "MORTGAGE_STATEMENT",
        "HOI_DECLARATION", "PURCHASE_CONTRACT", "TAX_RETURN", "BLANK", "DUPLICATE",
    ]
    assert [d["type"] for d in truth["expectedDocuments"]] == [
        "W2", "BANK_STATEMENT", "DRIVERS_LICENSE", "MORTGAGE_STATEMENT",
        "HOI_DECLARATION", "PURCHASE_CONTRACT", "TAX_RETURN",
    ]
    assert [d["pages"] for d in truth["expectedDocuments"]] == [
        [0], [1], [2], [3], [4], [5], [6],
    ]
    assert truth["unassignedPages"] == [7, 8]
    assert truth["pages"][7]["words"] == [], "page 7 must be blank"
    # The duplicate must be an EXACT redraw of page 0 — that is what makes it a
    # content-hash duplicate downstream (same rule as combined_package pages 10-11).
    assert truth["pages"][8]["words"] == truth["pages"][0]["words"]
    assert "combined_package_v2.pdf" in generated


@pytest.mark.parametrize("name, holder", [
    ("bank_statement_usbank", "MARIA T LOPEZ OR DIEGO R LOPEZ"),
    ("bank_statement_anb", "NORTHGATE PLUMBING SUPPLY LLC"),
    ("bank_statement_chase_combined", "PRIYA S NARAYAN OR ARJUN K NARAYAN"),
])
def test_real_layout_bank_fixtures_pin_every_headline_field(generated, name, holder):
    """V54's three real-layout fixtures (2026-09-23): every headline field the
    bank_statement@1.7.0 schema declares must resolve to something other than MISSING,
    except the two month-to-date fields, which no mailed statement prints."""
    t = json.loads(generated[f"truth/{name}.json"])
    fields = {f["field"]: f for f in t["expectedFields"]}
    assert fields["accountHolderName"]["displayedText"] == holder
    for required in ("bankName", "accountNumber", "statementPeriodStart", "statementPeriodEnd",
                     "beginningBalance", "endingBalance", "totalDeposits", "totalWithdrawals"):
        assert fields[required]["method"] != "NONE", (name, required)
    assert fields["monthToDateDeposits"]["method"] == "NONE"
