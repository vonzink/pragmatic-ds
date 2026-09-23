"""Punctuation-as-images: money whose comma/period are pictures, not text.

A real paystub prints ``$7,514.92`` as text digit runs ``$7``/``514``/``92``
with the comma and period drawn as ~3.8pt 1-bit images in ~5.1pt holes.
``punctuation.repair_image_punctuation`` merges such a run back into one
token — but ONLY behind four gates (grammar, declared image, rendered
baseline ink, canonical-money result), because the neighbouring failure modes
all fabricate numbers: two separate amounts bridge into one, a speck buys a
comma, a minus becomes a decimal point. Each of those decoys is pinned here
with real geometry; the PDFs are built in memory (fixtures/ is owned by the
provenance manifest).

The bitmaps here are deliberately SIMPLE ink grids (solid marks, a hollow
ring, a mid-height bar): the gates judge rendered pixels, not typography, and
plain grids make each test's ink self-evident. The corpus fixture
(tools/gen_realworld_fixtures.py) carries the real-typography version of the
same geometry through the full stack.
"""

import zlib

import pytest

from pragmaticds_docengine_worker.text import extract_text

PAGE_H = 792.0
SIZE = 9.0
#: Helvetica advances for the digits/symbols drawn (per 1000 em).
W = {c: 556 for c in "0123456789$"}
#: ...and the decorations real payroll and bank documents print around an
#: amount. The list is the TEST's, not the rule's: the rule never enumerates a
#: decoration, so these are only the specimens the measurement table used.
W.update({"-": 333, "*": 389, "(": 333, ")": 333, "+": 584, "−": 584,
          "†": 556, "‡": 556, "¶": 537, "U": 722, "S": 667, "%": 889})

#: Characters outside latin-1 that WinAnsiEncoding can still draw, as the
#: octal escapes a PDF content stream wants.
_WINANSI = {"†": r"\206", "‡": r"\207", "¶": r"\266", "–": r"\226"}
#: WinAnsi has no true MINUS SIGN (U+2212) — the character a document actually
#: prints for a negative, as distinct from the hyphen a keyboard types. Mapping
#: one spare code to Helvetica's /minus glyph puts a real one on the page.
_MINUS_CODE = r"\310"
_MINUS_DIFFERENCES = ("<< /Type /Encoding /BaseEncoding /WinAnsiEncoding "
                      "/Differences [200 /minus] >>")


def _literal(text):
    """One PDF string literal: WinAnsi escapes for the non-latin-1 marks, and
    the delimiters escaped so a parenthesised amount does not end the string."""
    out = []
    for character in text:
        if character == "−":
            out.append(_MINUS_CODE)
        elif character in _WINANSI:
            out.append(_WINANSI[character])
        elif character in "()\\":
            out.append("\\" + character)
        else:
            out.append(character)
    return "".join(out)

#: Solid marks (fill 1.0 / ~0.7): how a renderer actually inks a period/comma.
PERIOD = ["####", "####", "####", "####"]
COMMA = ["####", "####", "####", ".###", ".##.", "##.."]
#: A hollow ring: ink in the hole that no renderer would use for punctuation.
RING = ["..####..", ".##..##.", "##....##", "##....##", ".##..##.", "..####.."]
#: A solid mid-height bar: a minus. Baseline-anchored it is NOT.
BAR = ["####", "####"]


def _bitmap(rows):
    width, height = len(rows[0]), len(rows)
    data = bytearray()
    for row in rows:
        byte, bits, line = 0, 0, bytearray()
        for cell in row:
            byte = (byte << 1) | (1 if cell == "#" else 0)
            bits += 1
            if bits == 8:
                line.append(byte)
                byte, bits = 0, 0
        if bits:
            line.append(byte << (8 - bits))
        data += line
    return width, height, zlib.compress(bytes(data))


def _pdf(runs, marks):
    """One page: ``runs`` = [(text, x, baseline_top)], ``marks`` =
    [(rows, x, top, w_pt, h_pt)] drawn as 1-bit image XObjects."""
    objects = []

    def add(body):
        objects.append(body.encode("latin-1") if isinstance(body, str) else body)
        return len(objects)

    encoding = "/WinAnsiEncoding"
    if any("−" in text for text, *_rest in runs):
        encoding = f"{add(_MINUS_DIFFERENCES)} 0 R"
    helv = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
               f"/Encoding {encoding} >>")
    xrefs = {}
    for index, (rows, *_rest) in enumerate(marks):
        width, height, data = _bitmap(rows)
        head = (f"<< /Type /XObject /Subtype /Image /Width {width} /Height {height} "
                "/BitsPerComponent 1 /ImageMask true /Decode [1 0] /Filter /FlateDecode "
                f"/Length {len(data)} >>\nstream\n").encode("latin-1")
        xrefs[f"Im{index}"] = add(head + data + b"\nendstream")

    ops = []
    for text, x, baseline_top in runs:
        ops.append(f"BT /F1 {SIZE:g} Tf 1 0 0 1 {x:.2f} {PAGE_H - baseline_top:.2f} "
                   f"Tm ({_literal(text)}) Tj ET")
    for index, (_rows, x, top, w_pt, h_pt) in enumerate(marks):
        ops.append(f"q 0 g {w_pt:.2f} 0 0 {h_pt:.2f} {x:.2f} "
                   f"{PAGE_H - top - h_pt:.2f} cm /Im{index} Do Q")
    stream = "\n".join(ops).encode("latin-1")
    contents = add(b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream))
    pages, page = len(objects) + 1, len(objects) + 2
    xobjects = " ".join(f"/{n} {r} 0 R" for n, r in xrefs.items())
    resources = f"/Font << /F1 {helv} 0 R >>"
    if xrefs:
        resources += f" /XObject << {xobjects} >>"
    add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    add(f"<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 612 {PAGE_H:g}] "
        f"/Resources << {resources} >> /Contents {contents} 0 R >>")
    catalog = add(f"<< /Type /Catalog /Pages {pages} 0 R >>")

    out = bytearray(b"%PDF-1.5\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    start = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objects) + 1, catalog, start)
    return bytes(out)


def _advance(text):
    return sum(W[c] for c in text) * SIZE / 1000.0


BASELINE = 254.6


def _row(texts, x0=210.5, hole=5.1):
    """Digit runs left to right with fixed holes; returns (runs, hole x-spans)."""
    runs, holes = [], []
    cursor = x0
    for index, text in enumerate(texts):
        runs.append((text, cursor, BASELINE))
        end = cursor + _advance(text)
        if index < len(texts) - 1:
            holes.append((end, end + hole))
        cursor = end + hole
    return runs, holes


def _texts(pdf_bytes):
    return [span.text for span in extract_text(pdf_bytes, [0])[0].spans]


def _span(pdf_bytes, text):
    for span in extract_text(pdf_bytes, [0])[0].spans:
        if span.text == text:
            return span
    return None


class TestTheRepair:
    def test_comma_and_period_images_merge_the_amount(self):
        runs, holes = _row(["$7", "514", "92"])
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(runs, marks))
        assert "$7,514.92" in texts
        for fragment in ("$7", "514", "92"):
            assert fragment not in texts

    def test_the_merged_box_is_the_union_of_its_parts(self):
        runs, holes = _row(["$7", "514", "92"])
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        span = _span(_pdf(runs, marks), "$7,514.92")
        assert span is not None
        assert span.box.x == pytest.approx(210.5, abs=0.15)
        expected_end = 210.5 + _advance("$7") + 5.1 + _advance("514") + 5.1 + _advance("92")
        assert span.box.x + span.box.width == pytest.approx(expected_end, abs=0.2)

    def test_a_cents_only_pair_merges_too(self):
        runs, holes = _row(["18", "203", "44"], x0=291.1)
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        assert "18,203.44" in _texts(_pdf(runs, marks))


class TestTheRefusals:
    def test_no_image_in_the_hole_no_merge(self):
        """DECOY 3: two SEPARATE amounts at the same hole geometry — bridging
        them by geometry alone is the fabrication SpanJoin documents."""
        runs, _ = _row(["84", "112"])
        texts = _texts(_pdf(runs, []))
        assert "84" in texts and "112" in texts
        for fabricated in ("84,112", "84.112", "84112"):
            assert fabricated not in texts

    def test_a_hollow_ring_is_not_punctuation(self):
        """DECOY 4: ink in the hole that is not SHAPED like a mark. (The
        canonical gate would refuse '61'/'400' anyway; the fill gate must
        refuse the ring on its own so the walls stay independent.)"""
        runs, holes = _row(["61", "400"])
        marks = [(RING, holes[0][0] + 0.1, BASELINE - 2.2, 2.6, 2.2)]
        texts = _texts(_pdf(runs, marks))
        assert "61" in texts and "400" in texts
        for fabricated in ("61,400", "61.400", "61400"):
            assert fabricated not in texts

    def test_even_a_perfect_period_cannot_buy_a_non_money_shape(self):
        """The canonical wall alone: real punctuation ink between '61' and
        '400' still refuses, because neither 61,400 nor 61.400 is money."""
        runs, holes = _row(["61", "400"])
        marks = [(PERIOD, holes[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1)]
        texts = _texts(_pdf(runs, marks))
        assert "61" in texts and "400" in texts
        for fabricated in ("61,400", "61.400", "61400"):
            assert fabricated not in texts

    def test_a_mid_height_bar_is_not_a_decimal_point(self):
        """A minus drawn as an image between '12' and '34' must not turn into
        12.34: baseline anchoring is what separates a point from a dash."""
        runs, holes = _row(["12", "34"])
        marks = [(BAR, holes[0][0] + 0.1, BASELINE - 5.4, 3.8, 1.4)]
        texts = _texts(_pdf(runs, marks))
        assert "12" in texts and "34" in texts
        for fabricated in ("12.34", "12,34", "1234"):
            assert fabricated not in texts

    def test_a_baseline_period_between_two_digit_pairs_does_merge(self):
        """The positive counterpart of the two tests above: the same geometry
        WITH baseline punctuation ink and a canonical result is exactly the
        defect class, and it merges."""
        runs, holes = _row(["12", "34"])
        marks = [(PERIOD, holes[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1)]
        assert "12.34" in _texts(_pdf(runs, marks))

    def test_glyph_scale_graphics_never_link_a_chain(self):
        """A tall image between digit runs (a logo, a separator graphic) is
        not an inline mark: the declared-image gate bounds its height."""
        runs, holes = _row(["12", "34"])
        tall = ["##"] * 12
        marks = [(BAR and tall, holes[0][0] + 0.5, BASELINE - 11.0, 2.0, 12.0)]
        texts = _texts(_pdf(runs, marks))
        assert "12" in texts and "34" in texts
        assert "12.34" not in texts


class TestShadedRows:
    """The real stub SHADES its money rows: the band's thin horizontal edges
    cross every hole and its halftone dither drops 1-4px dots everywhere.
    Both are known furniture — a comma among them still merges — but the
    all-or-none row rule keeps a partly-repairable row honest."""

    RULE = ["#" * 48]  # a hole-crossing thin edge, drawn wider than the hole
    DOT = ["##", "##"]

    def _furniture(self, holes, index):
        """A rule above, a rule below, and a dust dot beside hole #index."""
        x0, _ = holes[index]
        return [
            (self.RULE, x0 - 3.0, BASELINE - 9.4, 11.0, 0.9),
            (self.RULE, x0 - 3.0, BASELINE + 1.2, 11.0, 0.9),
            (self.DOT, x0 + 0.2, BASELINE - 7.6, 0.5, 0.5),
        ]

    def test_a_mark_among_rules_and_dither_still_merges(self):
        runs, holes = _row(["$7", "514", "92"])
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
            *self._furniture(holes, 0),
            *self._furniture(holes, 1),
        ]
        assert "$7,514.92" in _texts(_pdf(runs, marks))

    def test_one_failed_chain_keeps_its_whole_row_unmerged(self):
        """The wrong-capture the row rule exists for, measured live: merge
        only the SECOND amount on a row and 'occurrence 0' of the row's text
        becomes the year-to-date value wearing the current-amount label. A
        row merges all of its canonical chains or none."""
        current, holes_current = _row(["$7", "514", "92"], x0=210.5)
        ytd, holes_ytd = _row(["18", "203", "44"], x0=291.1)
        marks = [
            # the current amount's comma is a hollow ring: its chain fails...
            (RING, holes_current[0][0] + 0.6, BASELINE - 2.2, 2.6, 2.2),
            (PERIOD, holes_current[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
            # ...while the YTD amount's marks are real punctuation.
            (COMMA, holes_ytd[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes_ytd[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(current + ytd, marks))
        assert "18,203.44" not in texts, (
            "a row with a failed canonical chain must merge NOTHING")
        for fragment in ("$7", "514", "92", "18", "203", "44"):
            assert fragment in texts


class TestDecoratedAmounts:
    """A deduction row prints its CURRENT amount with a leading minus, and an
    excluded-from-taxable row adds a trailing footnote asterisk. Such a run is
    an amount — it must not be repaired (neither decoration is ink this repair
    verified), and it must not be ignored either: leaving it unmerged beside a
    merged YEAR-TO-DATE amount is what hands 'occurrence 0' the YTD total
    wearing the per-period label."""

    def test_a_minus_led_current_amount_vetoes_its_whole_row(self):
        """The measured Colman dialect: 'Federal Withholding' with a minus-led
        current amount ('-57' · period image · '82') beside a positive YTD
        ('5' · comma · '512' · period · '34'). Merging the YTD ALONE leaves the
        row reading '... -57 82 5,512.34', whose first money-shaped run is the
        YTD — a confident wrong value where HEAD had a missing one."""
        label = [("Federal", 39.6, BASELINE), ("Withholding", 80.0, BASELINE)]
        current, holes_current = _row(["-57", "82"], x0=210.5)
        ytd, holes_ytd = _row(["5", "512", "34"], x0=291.1)
        marks = [
            (PERIOD, holes_current[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
            (COMMA, holes_ytd[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes_ytd[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(label + current + ytd, marks))

        assert "5,512.34" not in texts, (
            "the YTD must not merge alone: it becomes occurrence 0 of the row")
        assert "-57.82" not in texts, (
            "a leading hyphen is text this repair never proved to be a SIGN")
        for fragment in ("-57", "82", "5", "512", "34"):
            assert fragment in texts

    def test_a_minus_led_amount_alone_on_its_row_still_never_merges(self):
        """No row-mate to protect, and it still stays two words: the refusal is
        about what the gates verified, not about who else is on the line."""
        runs, holes = _row(["-57", "82"])
        marks = [(PERIOD, holes[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1)]
        texts = _texts(_pdf(runs, marks))
        assert "-57" in texts and "82" in texts
        for fabricated in ("-57.82", "-57,82", "57.82"):
            assert fabricated not in texts

    def test_a_footnote_asterisk_vetoes_its_row_too(self):
        """The stub's 401k row: '-57' · period image · '82*'. The asterisk is a
        footnote reference, no part of the number — but the run is still an
        amount, so it governs its row exactly as the bare minus-led one does."""
        current, holes_current = _row(["-57", "82*"], x0=210.5)
        ytd, holes_ytd = _row(["5", "512", "34"], x0=291.1)
        marks = [
            (PERIOD, holes_current[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
            (COMMA, holes_ytd[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes_ytd[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(current + ytd, marks))

        assert "5,512.34" not in texts
        for fabricated in ("-57.82*", "-57.82", "57.82"):
            assert fabricated not in texts

    def test_a_positive_row_beside_no_decoration_still_merges(self):
        """The veto is the decoration, not the presence of a second column: the
        same two-amount row without one merges both, as it must."""
        current, holes_current = _row(["57", "82"], x0=210.5)
        ytd, holes_ytd = _row(["5", "512", "34"], x0=291.1)
        marks = [
            (PERIOD, holes_current[0][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
            (COMMA, holes_ytd[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes_ytd[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(current + ytd, marks))
        assert "57.82" in texts and "5,512.34" in texts


class TestDecorationsNobodyEnumerated:
    """The minus and the asterisk above are two specimens of ONE class, and a
    class cannot be closed by listing its members: a payroll or bank document
    prints its amounts wearing whatever its dialect prefers, and the next
    dialect wears something this file has never seen.

    The rule these pin is not a longer list. CANDIDACY reads the DIGITS ONLY —
    so a run governs its row no matter what is printed around it — and
    PUBLICATION requires that every character of the merged token be one the
    money grammar itself produced. Every character is a digit or it is not, so
    an unanticipated decoration lands on the veto side by construction.

    Each case is measured twice, because a decoration has two ways to serve a
    wrong number: TRUNCATION (the decorated run is skipped, the rest merges,
    and a fraction of the printed amount is published all by itself) and
    OCCURRENCE SHIFT (the decorated run neither merges nor vetoes, its
    undecorated row-mate merges alone, and 'occurrence 0' becomes the wrong
    column). Both must end in nothing merging.
    """

    def _row_with(self, current_runs, marked_seams=None):
        """A decorated CURRENT amount beside an undecorated YEAR-TO-DATE one —
        the two-column shape every real earnings row has. ``marked_seams``
        selects which of the current amount's seams carry a punctuation image;
        by default all of them do."""
        current, holes_current = _row(current_runs, x0=210.5)
        ytd, holes_ytd = _row(["5", "512", "34"], x0=291.1)
        if marked_seams is None:
            marked_seams = range(len(holes_current))
        marks = [(COMMA if index < len(holes_current) - 1 else PERIOD,
                  holes_current[index][0] + 0.1,
                  BASELINE - (4.0 if index < len(holes_current) - 1 else 3.5),
                  3.8, 4.6 if index < len(holes_current) - 1 else 4.1)
                 for index in marked_seams]
        marks += [
            (COMMA, holes_ytd[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes_ytd[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        return _texts(_pdf(current + ytd, marks))

    def _assert_nothing_merged(self, texts, decorated):
        assert "5,512.34" not in texts, (
            "the undecorated row-mate merged alone: 'occurrence 0' of this row "
            "is now the year-to-date column wearing the current-amount label")
        assert "514.92" not in texts, (
            "a TRUNCATED amount was published: the decorated group was skipped "
            "and the remainder merged into a number the page never printed")
        for fabricated in ("7,514.92", f"{decorated}7,514.92", "7,514.92*"):
            assert fabricated not in texts, f"published {fabricated!r}"

    def test_a_parenthesised_negative_vetoes_its_row(self):
        """The accounting negative, and the commonest decoration in finance:
        '(7,514.92)'. Both parentheses are outside every seam gate 3 read."""
        self._assert_nothing_merged(
            self._row_with(["(7", "514", "92)"]), decorated="(")

    def test_a_trailing_minus_vetoes_its_row(self):
        """Many payroll systems print the sign BEHIND the amount: '7,514.92-'."""
        self._assert_nothing_merged(
            self._row_with(["7", "514", "92-"]), decorated="-")

    def test_a_true_minus_sign_vetoes_its_row(self):
        """U+2212, the character a typesetter actually uses for a negative —
        not the hyphen the existing rule happens to name."""
        self._assert_nothing_merged(
            self._row_with(["−7", "514", "92"]), decorated="−")

    def test_a_currency_symbol_out_of_position_vetoes_its_row(self):
        """'US$7,514.92'. The grammar produces a bare leading '$' and nothing
        else, so the 'US' in front of it is unproven text like any other."""
        self._assert_nothing_merged(
            self._row_with(["US$7", "514", "92"]), decorated="US$")

    def test_a_footnote_dagger_vetoes_its_row(self):
        """The asterisk's sibling: a dagger is the same footnote reference and
        the same non-number."""
        self._assert_nothing_merged(
            self._row_with(["7", "514", "92†"]), decorated="†")

    def test_a_decoration_this_module_has_never_seen_vetoes_too(self):
        """THE POINT OF THE CLASS. A pilcrow in front and a double dagger
        behind is not a rendering anyone expects — which is exactly why the
        rule may not be a list. Nothing here was anticipated, and nothing
        merges."""
        self._assert_nothing_merged(
            self._row_with(["¶7", "514", "92‡"]), decorated="¶")

    def test_a_space_thousands_separator_publishes_nothing(self):
        """The separator itself can be the decoration: '7 514.92' prints its
        thousands seam as a SPACE, so that seam carries no image and gate 2
        rightly refuses it — but the chain then starts INSIDE the number and
        '514.92' is a hundredfold-wrong value the page never printed."""
        self._assert_nothing_merged(
            self._row_with(["7", "514", "92"], marked_seams=[1]), decorated="")

    def test_a_decorated_amount_alone_on_its_row_publishes_nothing_either(self):
        """TRUNCATION needs no second column to serve a wrong number, which
        makes it the worse half. A decoration only at the FRONT leaves the tail
        of the number linkable on its own: skip '(7' and '514.92' merges and
        publishes by itself — a hundredth of the printed amount, alone on a row
        that has no other candidate to be confused with."""
        runs, holes = _row(["(7", "514", "92"])
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        texts = _texts(_pdf(runs, marks))
        assert "(7" in texts and "514" in texts and "92" in texts
        for fabricated in ("514.92", "7,514.92", "(7,514.92"):
            assert fabricated not in texts, f"published {fabricated!r}"

    def test_an_undecorated_amount_beside_a_quantity_column_still_merges(self):
        """The control that keeps the widening honest. A timecard prints hours
        in the column before the money, and those digits are NOT part of the
        amount: reading them in gives '40,7,514.92', which is not money, so the
        amount's own reading is the only one and it publishes."""
        hours = [("40", 180.0, BASELINE)]
        runs, holes = _row(["7", "514", "92"], x0=210.5)
        marks = [
            (COMMA, holes[0][0] + 0.1, BASELINE - 4.0, 3.8, 4.6),
            (PERIOD, holes[1][0] + 0.1, BASELINE - 3.5, 3.8, 4.1),
        ]
        assert "7,514.92" in _texts(_pdf(hours + runs, marks))
