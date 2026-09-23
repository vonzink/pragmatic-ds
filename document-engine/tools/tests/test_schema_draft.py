"""Tests for tools/schema_draft.py — canned spans, no PDF, no stack, no Docker.

The PDF half of the script is one function (``load_pages``) that hands the
worker's own extractor a file; everything that TURNS SPANS INTO A SCHEMA is pure
and pinned here, on hand-built pages whose coordinates are written out in the
test so a geometry change is visible in the diff.

Three properties are load-bearing and each has its own block below:

* **The engine ports agree with the engine.** ``VisualLines``' smaller-height row
  rule, ``SpanJoin``'s 0.10-em fuse, ``cellWindow``'s fail-closed widening and
  ``ownsSpan``'s span-own-width test are ported, not approximated — a drift makes
  the tool emit rungs the engine will not honour.
* **PII never reaches the draft.** Value patterns come from a fixed catalog and a
  candidate label that is, contains, or is contained by a document value is
  refused. The tests assert on the value's own characters being absent.
* **A gap stays a gap.** A value the page does not print, or one no rung
  round-trips, is parked in ``_draft.unresolved`` — never softened into a
  plausible-looking regex — and what IS emitted always validates.
"""

import json

import pytest

from tools import schema_draft as sd
from tools.schema_draft import (
    Draft,
    KeyEntry,
    Page,
    SchemaInvalid,
    Span,
    classify_value,
    compose,
    draft_schema,
    field_name_from,
    forbidden_values,
    in_row_order,
    is_usable_label,
    join_separator,
    locate_value,
    read_entries,
    report,
    simulate,
    validate_definition,
    visual_lines,
)

CHAR_PT = 4.0


def span(text: str, x: float, y: float, height: float = 8.0, ordinal: int = 0) -> Span:
    """A word box whose width is proportional to its text, like a real one."""
    return Span(text, x, y, len(text) * CHAR_PT, height, ordinal)


def words(text: str, x: float, y: float, height: float = 8.0, gap: float = 4.0) -> list[Span]:
    """One printed phrase, laid out left to right with a real printed gap."""
    out, cursor = [], x
    for token in text.split(" "):
        out.append(span(token, cursor, y, height))
        cursor += len(token) * CHAR_PT + gap
    return out


def page_of(*groups: list[Span]) -> Page:
    spans = [s for group in groups for s in group]
    return Page([Span(s.text, s.x, s.y, s.width, s.height, i) for i, s in enumerate(spans)])


# ── the engine ports ────────────────────────────────────────────────────────

def test_visual_lines_takes_the_row_threshold_from_the_smaller_span():
    # Centres 4 pt apart. Half the SMALLER height is 3 — a split. Half the LARGER
    # would be 10 — a merge, and that merge is the masthead bridging defect
    # VisualLines exists to prevent.
    small = Span("caption", 0.0, 1.0, 20.0, 6.0)
    tall = Span("1040", 40.0, 0.0, 30.0, 20.0)
    assert len(visual_lines([small, tall])) == 2


def test_visual_lines_keeps_differently_sized_text_of_one_printed_row_together():
    caption = Span("Wages", 0.0, 10.0, 20.0, 6.0)
    amount = Span("104,288.80", 40.0, 9.0, 40.0, 8.0)
    assert len(visual_lines([caption, amount])) == 1


def test_join_fuses_a_pair_the_page_printed_touching_and_spaces_a_real_gap():
    left = Span("1,321", 0.0, 0.0, 20.0, 10.0)
    touching = Span(".18", 20.5, 0.0, 12.0, 10.0)
    separated = Span(".18", 23.0, 0.0, 12.0, 10.0)
    # 0.5 pt on a 10 pt em is 0.05 em — inside SpanJoin's 0.10 em, so one token.
    assert join_separator(left, touching) == ""
    # 3 pt is 0.30 em — a printed space, and joining it would invent an amount.
    assert join_separator(left, separated) == " "


def test_in_row_order_resequences_rows_without_reordering_within_one():
    top = Span("Form", 0.0, 0.0, 20.0, 8.0, 0)
    bottom = Span("Department", 0.0, 20.0, 40.0, 8.0, 1)
    top_right = Span("1040", 60.0, 0.0, 20.0, 8.0, 2)
    assert [s.text for s in in_row_order([top, bottom, top_right])] == [
        "Form", "1040", "Department",
    ]


def test_a_label_below_cell_does_not_widen_past_the_next_caption():
    # Two captions on one row, each with an amount beneath it. The right amount is
    # inside the right caption's column; cellWindow must stop the left caption's
    # cell at the right caption's left edge, or box 1 would capture box 2's money.
    page = page_of(
        words("1 Wages tips", 20.0, 10.0, 6.0),
        words("2 Federal withheld", 200.0, 10.0, 6.0),
        [span("104,288.80", 22.0, 22.0)],
        [span("11,946.32", 202.0, 22.0)],
    )
    rung = {
        "method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Wages tips"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": sd.MONEY_CENTS, "occurrence": 0},
    }
    assert simulate(rung, page) == "104,288.80"


def test_a_label_below_cell_ignores_a_row_further_down_than_max_drop():
    page = page_of(
        words("1 Wages tips", 20.0, 10.0, 6.0),
        [span("104,288.80", 22.0, 60.0)],
    )
    rung = {
        "method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Wages tips"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": sd.MONEY_CENTS, "occurrence": 0},
    }
    assert simulate(rung, page) is None


def test_anchor_label_line_right_reads_only_past_the_label():
    page = page_of(
        words("Pay Date", 20.0, 10.0) + [span("01/15/2026", 100.0, 10.0)],
    )
    rung = {
        "method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Pay Date"},
        "value": {"pattern": r"(?<!\d)\d{1,2}/\d{1,2}/\d{4}(?!\d)", "occurrence": 0,
                  "scope": "LINE_RIGHT"},
    }
    assert simulate(rung, page) == "01/15/2026"


# ── locating the value ──────────────────────────────────────────────────────

def test_locate_value_finds_the_run_that_prints_the_value():
    page = page_of(words("Rents received", 20.0, 10.0) + [span("31,800", 200.0, 10.0)])
    found = locate_value(page, "31,800")
    assert len(found) == 1
    assert [s.text for s in found[0]] == ["31,800"]


def test_locate_value_reads_a_value_typed_over_the_forms_preprinted_parens():
    # Schedule E's own extraction note: lines 22 and 25 sit inside a preprinted
    # "( )" whose interior is literal space glyphs, so the typed value extracts
    # with the form's spaces interleaved.
    page = page_of([
        span("(", 20.0, 10.0), span("1", 26.0, 10.0), span("8", 32.0, 10.0),
        span(",", 38.0, 10.0), span("5", 44.0, 10.0), span("9", 50.0, 10.0),
        span("0", 56.0, 10.0), span(")", 62.0, 10.0),
    ])
    assert locate_value(page, "(18,590)")


def test_locate_value_returns_nothing_when_the_page_never_printed_it():
    page = page_of(words("Retirement plan", 20.0, 10.0))
    assert locate_value(page, "checked") == []


# ── the PII firewall ────────────────────────────────────────────────────────

FORBIDDEN = forbidden_values([
    {"value": "Cascade Ridge Logistics, Inc.\n1150 Winterhaven Parkway"},
    {"value": "DD"},
    {"value": "400-55-8172"},
])


@pytest.mark.parametrize("candidate", [
    "Employer Cascade Ridge Logistics, Inc.",   # a value INSIDE the caption
    "Inc.",                                     # a FRAGMENT of a value
    "1150 Winterhaven",                         # a fragment of the address line
    "Code DD",                                  # a short value printed as a word
    "400-55-8172",                              # the value itself
])
def test_a_caption_that_carries_a_document_value_is_never_an_anchor(candidate):
    assert not is_usable_label(candidate, FORBIDDEN)


@pytest.mark.parametrize("candidate", [
    "1 Wages, tips, other compensation",
    "12a See instructions for box 12",
    "b Employer identification number (EIN)",
])
def test_a_printed_form_caption_is_usable(candidate):
    assert is_usable_label(candidate, FORBIDDEN)


def test_a_caption_run_that_spilled_into_a_typed_entry_is_refused():
    # A Schedule E "Other (list)" caption shares its printed line with the typed
    # description, so a caption window can end three words INTO the value. It
    # contains neither the whole value nor is contained by it — only the n-gram
    # test catches this one.
    forbidden = forbidden_values([{"value": "HOA dues and pest control"}])
    assert not is_usable_label("Other (list) HOA dues and pest", forbidden)
    assert is_usable_label("Other (list)", forbidden)


def test_two_letter_debris_is_not_a_caption():
    # "de DD" is a split column head beside a box-12 code, not a printed caption.
    assert not is_usable_label("de DD", FORBIDDEN)


def test_the_value_pattern_is_a_catalog_constant_not_the_observed_value():
    family = classify_value("104,288.80")
    assert family.name == "money_cents"
    assert family.pattern == sd.MONEY_CENTS
    for fragment in ("104", "288", "80", "104,288.80"):
        assert fragment not in family.pattern


def test_a_field_name_that_would_quote_a_value_becomes_positional():
    # A key label is normally the form's printed caption, but nothing enforces
    # that — one corpus key spells an authoring note into a label. A name built
    # from a label that quotes the value is replaced by a positional one.
    entries = read_entries({"fields": [
        {"label": "Applicant name John Quinn Doe", "value": "John Quinn Doe", "page": 0},
    ]})
    assert entries[0].name.startswith("field")
    assert "john" not in entries[0].name.lower()


def test_field_names_come_from_the_caption_not_the_value():
    assert field_name_from("Wages, tips, other compensation", None) == "wagesTipsOtherCompensation"
    assert field_name_from("See instructions for box 12", "12a") == "seeInstructionsForBox12Line12a"


# ── drafting end to end ─────────────────────────────────────────────────────

def w2_like_page() -> Page:
    """A W-2 box grid: caption in the box, value beneath it — plus a labelled
    line, so both rungs the drafter can emit have a home."""
    return page_of(
        words("a Employees social security number", 20.0, 10.0, 6.0),
        [span("400-55-8172", 22.0, 22.0)],
        words("1 Wages, tips, other compensation", 200.0, 10.0, 6.0),
        [span("104,288.80", 202.0, 22.0)],
        words("Pay Date", 20.0, 60.0) + [span("01/15/2026", 120.0, 60.0)],
    )


def key_entry(name, label, value, page=0, **kw) -> KeyEntry:
    return KeyEntry(name=name, label=label, line=None, page=page, value=value,
                    occurrence_key=kw.get("occurrence_key"),
                    occurrence_value=kw.get("occurrence_value"),
                    multiline=False, raw_value=value)


def test_a_box_grid_value_drafts_a_label_below_rung_that_round_trips():
    entries = [key_entry("wagesTipsOtherCompensation",
                         "Wages, tips, other compensation", "104,288.80")]
    draft = draft_schema(entries, {0: w2_like_page()})
    assert draft.unresolved == []
    field = draft.fields[0]
    rung = field["extractors"][0]
    assert rung["method"] == "LABEL_BELOW"
    assert rung["label"] == {"kind": "literal",
                            "pattern": "1 Wages, tips, other compensation"}
    assert rung["maxDropPt"] == sd.DEFAULT_MAX_DROP_PT
    assert rung["cellOverlap"] == sd.DEFAULT_CELL_OVERLAP
    assert field["dataType"] == "MONEY"
    assert field["normalizer"] == "money"
    assert simulate(rung, w2_like_page()) == "104,288.80"


def test_a_labelled_line_drafts_an_anchor_label_rung():
    entries = [key_entry("payDate", "Pay Date", "01/15/2026")]
    draft = draft_schema(entries, {0: w2_like_page()})
    rung = draft.fields[0]["extractors"][0]
    assert rung["method"] == "ANCHOR_LABEL"
    assert rung["value"]["scope"] == "LINE_RIGHT"
    assert draft.fields[0]["dataType"] == "DATE"


def test_an_ssn_field_is_marked_sensitive_and_carries_no_digits_of_the_value():
    entries = [key_entry("employeeSsn", "Employee's social security number", "400-55-8172")]
    draft = draft_schema(entries, {0: w2_like_page()})
    field = draft.fields[0]
    assert field["sensitive"] is True
    assert "8172" not in json.dumps(field)


def test_a_value_the_page_never_printed_is_parked_not_guessed():
    entries = [
        key_entry("retirementPlan", "Retirement plan", "checked"),
        key_entry("payDate", "Pay Date", "01/15/2026"),
    ]
    draft = draft_schema(entries, {0: w2_like_page()})
    assert [gap["reason"] for gap in draft.unresolved] == ["value_not_in_text_layer"]
    assert [gap["field"] for gap in draft.unresolved] == ["retirementPlan"]
    # And what IS emitted still loads: a parked field never lands in `fields` as
    # an extractor-less shell, which the loader refuses outright.
    validate_definition(compose("W2", "0.1.0", draft))


def test_an_unresolved_entry_carries_a_name_and_a_reason_and_nothing_else():
    entries = [key_entry("retirementPlan", "Retirement plan", "checked")]
    draft = draft_schema(entries, {0: w2_like_page()})
    assert set(draft.unresolved[0]) == {"field", "reason"}


def test_a_value_with_no_shape_family_is_parked_when_open_text_is_refused():
    page = page_of(words("Present Position", 20.0, 10.0, 6.0),
                   [span("coordinator", 22.0, 22.0)])
    # Lower case, one word: no catalog family recognises it, which is exactly the
    # free-text case the open-ended cell pattern exists for.
    assert classify_value("coordinator") is None
    entries = [key_entry("presentPosition", "Present Position", "coordinator")]
    parked = draft_schema(entries, {0: page}, allow_open_text=False)
    assert parked.unresolved[0]["reason"] == "no_shape_family"
    opened = draft_schema(entries, {0: page}, allow_open_text=True)
    assert opened.fields
    assert {"field": "presentPosition", "note": "open_ended_cell_pattern"} in opened.review


def test_the_occurrence_index_is_pinned_when_the_first_match_is_the_wrong_one():
    # ONE indivisible caption with two amounts inside its cell: the second is
    # this field's, so the rung must carry the index that picks it. A caption a
    # window walk could split would let the drafter avoid the index instead, which
    # is why this page prints a single word.
    page = page_of(
        [span("Deductions", 20.0, 10.0, 6.0)],
        [span("104,288.80", 22.0, 22.0), span("4,588.71", 24.0, 34.0)],
    )
    entries = [key_entry("secondDeduction", "Deductions", "4,588.71")]
    draft = draft_schema(entries, {0: page})
    rung = draft.fields[0]["extractors"][0]
    assert rung["value"]["occurrence"] == 1
    assert simulate(rung, page) == "4,588.71"
    assert {"field": "secondDeduction",
            "note": "occurrence_1_on_label_below"} in draft.review


def test_a_ladder_is_at_most_one_rung_per_method_and_never_grows_stronger():
    entries = [key_entry("payDate", "Pay Date", "01/15/2026")]
    draft = draft_schema(entries, {0: w2_like_page()}, max_rungs=3)
    rungs = draft.fields[0]["extractors"]
    assert len({rung["method"] for rung in rungs}) == len(rungs)
    strengths = [rung["strength"] for rung in rungs]
    assert strengths == sorted(strengths, reverse=True)


def test_the_page_scoped_regex_fallback_is_opt_in():
    entries = [key_entry("employeeSsn", "Employee's social security number", "400-55-8172")]
    off = draft_schema(entries, {0: w2_like_page()}, max_rungs=3)
    on = draft_schema(entries, {0: w2_like_page()}, max_rungs=3, allow_regex_fallback=True)
    assert "REGEX" not in {r["method"] for r in off.fields[0]["extractors"]}
    assert "REGEX" in {r["method"] for r in on.fields[0]["extractors"]}


def test_a_rung_the_key_label_does_not_corroborate_is_flagged_for_review():
    entries = [key_entry("wages", "A caption the form does not print", "104,288.80")]
    draft = draft_schema(entries, {0: w2_like_page()})
    assert any(note["note"].startswith("label_does_not_echo_key_label")
               for note in draft.review)


# ── the emitted document ────────────────────────────────────────────────────

def test_compose_emits_a_loadable_definition_beside_an_honest_draft_block():
    entries = [
        key_entry("wagesTipsOtherCompensation", "Wages, tips, other compensation", "104,288.80"),
        key_entry("retirementPlan", "Retirement plan", "checked"),
    ]
    definition = compose("W2", "0.1.0", draft_schema(entries, {0: w2_like_page()}))
    validate_definition(definition)
    assert definition["documentTypeCode"] == "W2"
    assert definition["instanceKey"] == []
    assert definition["_draft"]["resolved"] == 1
    assert definition["_draft"]["unresolved"][0]["field"] == "retirementPlan"


def test_the_report_names_fields_and_reasons_and_never_a_captured_value(capsys):
    entries = [
        key_entry("employeeSsn", "Employee's social security number", "400-55-8172"),
        key_entry("retirementPlan", "Retirement plan", "checked"),
    ]
    report(draft_schema(entries, {0: w2_like_page()}), None)
    printed = capsys.readouterr().out
    assert "employeeSsn" in printed
    assert "value_not_in_text_layer" in printed
    for fragment in ("400-55-8172", "8172", "checked"):
        assert fragment not in printed


# ── validation: every refusal ExtractionSchemaLoader makes ──────────────────

def minimal_field(**overrides) -> dict:
    field = {
        "name": "payDate", "dataType": "DATE", "required": False,
        "normalizer": "date", "sensitive": False,
        "extractors": [{
            "method": "ANCHOR_LABEL", "strength": 0.9,
            "label": {"kind": "literal", "pattern": "Pay Date"},
            "value": {"pattern": r"\d{2}/\d{2}/\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"},
        }],
    }
    field.update(overrides)
    return field


def definition_of(*fields, **overrides) -> dict:
    body = {"documentTypeCode": "PAYSTUB", "version": "1.0.0", "fields": list(fields),
            "instanceKey": []}
    body.update(overrides)
    return body


def test_a_well_formed_definition_validates():
    validate_definition(definition_of(minimal_field()))


@pytest.mark.parametrize("definition, message", [
    (definition_of(), "no fields"),
    (definition_of(minimal_field(extractors=[])), "no extractors"),
    (definition_of(minimal_field(dataType="CURRENCY")), "unknown dataType"),
    (definition_of(minimal_field(), instanceKey=["nobody"]), "instanceKey names no such field"),
])
def test_the_loaders_schema_level_refusals(definition, message):
    with pytest.raises(SchemaInvalid) as raised:
        validate_definition(definition)
    assert message in str(raised.value)


@pytest.mark.parametrize("extractor, message", [
    ({"method": "ANCHOR_LABEL", "strength": 0.9,
      "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}},
     "ANCHOR_LABEL extractor has no label"),
    ({"method": "LABEL_BELOW", "strength": 0.9, "value": {"pattern": "x", "occurrence": 0}},
     "LABEL_BELOW extractor has no label"),
    ({"method": "LABEL_ABOVE", "strength": 0.9, "value": {"pattern": "x", "occurrence": 0}},
     "LABEL_ABOVE extractor has no label"),
    ({"method": "TABLE_CLUSTER", "strength": 1.0,
      "value": {"pattern": "x", "occurrence": 0, "scope": "LINE"}},
     "TABLE_CLUSTER extractor has no table"),
    ({"method": "ROW_CELL", "strength": 0.9, "value": {"pattern": "x", "occurrence": 0}},
     "ROW_CELL extractor has no columnHeader"),
    ({"method": "CHECKBOX_STATE", "strength": 0.9, "proximityPt": 12.0},
     "CHECKBOX_STATE extractor has no options"),
    ({"method": "CHECKBOX_STATE", "strength": 0.9,
      "options": [{"label": {"kind": "literal", "pattern": "Yes"}, "value": "YES"}]},
     "CHECKBOX_STATE extractor has no proximityPt"),
    ({"method": "SIGNATURE_PRESENCE", "strength": 0.9},
     "SIGNATURE_PRESENCE extractor has no region"),
    ({"method": "ANCHOR_LABEL", "strength": 0.9,
      "label": {"kind": "literal", "pattern": "Pay Date"}},
     "extractor has no value"),
    ({"method": "ANCHOR_LABEL", "strength": 0.9,
      "label": {"kind": "literal", "pattern": "Pay Date"},
      "value": {"pattern": "x", "occurrence": 0}},
     "value has no scope"),
    ({"method": "ANCHOR_LABEL", "strength": 0.9,
      "label": {"kind": "literal", "pattern": "Pay Date"},
      "value": {"pattern": "(unclosed", "occurrence": 0, "scope": "LINE"}},
     "does not compile"),
    ({"method": "SEMANTIC_GUESS", "strength": 0.9,
      "value": {"pattern": "x", "occurrence": 0, "scope": "PAGE"}},
     "unknown method"),
])
def test_the_loaders_extractor_level_refusals(extractor, message):
    with pytest.raises(SchemaInvalid) as raised:
        validate_definition(definition_of(minimal_field(extractors=[extractor])))
    assert message in str(raised.value)


def test_a_cell_rung_needs_no_scope_because_its_scope_is_the_cell():
    validate_definition(definition_of(minimal_field(extractors=[{
        "method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Wages"},
        "value": {"pattern": "x", "occurrence": 0},
    }])))


ROW_GROUP = {"kind": "ROW", "maxRows": 5,
             "region": {"start": {"kind": "literal", "pattern": "Income or Loss"},
                        "end": {"kind": "literal", "pattern": "Total partnership"}}}


@pytest.mark.parametrize("group, message", [
    ({"kind": "COLUMN", "keys": ["A", "B"]}, "COLUMN group has no header"),
    ({"kind": "COLUMN", "header": {"kind": "literal", "pattern": "Properties"}, "keys": []},
     "COLUMN group has no keys"),
    ({"kind": "COLUMN", "header": {"kind": "literal", "pattern": "Properties"},
      "keys": ["A", "A"]}, "distinct and non-blank"),
    ({"kind": "COLUMN", "header": {"kind": "literal", "pattern": "Properties"},
      "keys": ["A", "B"], "rowLabels": ["A"]}, "COLUMN group cannot declare rowLabels"),
    ({"kind": "ROW", "maxRows": 5}, "ROW group has no region"),
    ({**ROW_GROUP, "maxRows": None}, "ROW group has no maxRows"),
    ({**ROW_GROUP, "maxRows": 100}, "maxRows must be 1..99"),
    ({**ROW_GROUP, "maxRows": 0}, "maxRows must be 1..99"),
    ({**ROW_GROUP, "rowLabels": ["a"]}, "single letters A..Z"),
    ({**ROW_GROUP, "rowLabels": ["B", "A"]}, "strictly ascending"),
    ({**ROW_GROUP, "maxRows": 1, "rowLabels": ["A", "B"]}, "cannot exceed maxRows"),
    ({"kind": "DIAGONAL"}, "unknown group kind"),
])
def test_the_loaders_group_level_refusals(group, message):
    with pytest.raises(SchemaInvalid) as raised:
        validate_definition(definition_of(minimal_field(group=group)))
    assert message in str(raised.value)


LINE_OFFSET_RUNG = {
    "method": "ANCHOR_LABEL", "strength": 0.9,
    "label": {"kind": "literal", "pattern": "Rents received"},
    "value": {"pattern": "x", "occurrence": 0, "scope": "LINE", "lineOffset": 1},
}
COLUMN_GROUP = {"kind": "COLUMN", "header": {"kind": "literal", "pattern": "Properties"},
                "keys": ["A", "B"]}


def test_a_nonzero_line_offset_is_legal_only_in_the_one_shape_the_loader_allows():
    validate_definition(definition_of(
        minimal_field(group=COLUMN_GROUP, extractors=[LINE_OFFSET_RUNG])))


@pytest.mark.parametrize("field", [
    minimal_field(extractors=[LINE_OFFSET_RUNG]),                              # no group
    minimal_field(group=COLUMN_GROUP, extractors=[
        {**LINE_OFFSET_RUNG, "method": "LABEL_BELOW"}]),                       # wrong method
    minimal_field(group=COLUMN_GROUP, extractors=[
        {**LINE_OFFSET_RUNG,
         "value": {**LINE_OFFSET_RUNG["value"], "scope": "LINE_RIGHT"}}]),      # wrong scope
    minimal_field(group=COLUMN_GROUP, extractors=[
        {**LINE_OFFSET_RUNG,
         "value": {**LINE_OFFSET_RUNG["value"], "occurrence": 1}}]),            # wrong occurrence
])
def test_a_nonzero_line_offset_anywhere_else_is_refused(field):
    with pytest.raises(SchemaInvalid) as raised:
        validate_definition(definition_of(field))
    assert "nonzero lineOffset" in str(raised.value)


@pytest.mark.parametrize("offset, message", [
    (11, "lineOffset must be 0..10"),
    (-1, "lineOffset must be 0..10"),
    ("1", "lineOffset must be an integer"),
])
def test_the_line_offset_bounds(offset, message):
    rung = {**LINE_OFFSET_RUNG,
            "value": {**LINE_OFFSET_RUNG["value"], "lineOffset": offset}}
    with pytest.raises(SchemaInvalid) as raised:
        validate_definition(definition_of(minimal_field(group=COLUMN_GROUP, extractors=[rung])))
    assert message in str(raised.value)


def test_every_pattern_the_catalog_can_emit_compiles():
    for family in (*sd.FAMILIES, sd.OPEN_TEXT_FAMILY):
        validate_definition(definition_of(minimal_field(extractors=[{
            "method": "REGEX", "strength": 0.6,
            "value": {"pattern": family.pattern, "occurrence": 0, "scope": "PAGE"},
        }])))


# ── the answer-key reader ───────────────────────────────────────────────────

def test_one_caption_over_several_lines_keeps_the_line_in_the_name():
    entries = read_entries({"fields": [
        {"label": "See instructions for box 12", "line": "12a", "value": "D", "page": 0},
        {"label": "See instructions for box 12", "line": "12b", "value": "DD", "page": 0},
    ]})
    assert sorted(e.name for e in entries) == [
        "seeInstructionsForBox12Line12a", "seeInstructionsForBox12Line12b",
    ]


def test_an_occurrence_discriminator_is_read_off_the_entry():
    entries = read_entries({"fields": [
        {"label": "Rents received", "line": "3", "value": "31,800", "page": 0,
         "propertyColumn": "A"},
        {"label": "Rents received", "line": "3", "value": "26,400", "page": 0,
         "propertyColumn": "B"},
    ]})
    assert {(e.occurrence_key, e.occurrence_value) for e in entries} == {
        ("propertyColumn", "A"), ("propertyColumn", "B"),
    }
    assert len({e.name for e in entries}) == 1


def test_a_masthead_repeated_on_a_second_page_is_one_field_not_a_group():
    page = page_of(words("Your social security number", 20.0, 10.0, 6.0),
                   [span("400-55-8127", 22.0, 22.0)])
    entries = [
        key_entry("yourSocialSecurityNumber", "Your social security number", "400-55-8127", 0),
        key_entry("yourSocialSecurityNumber", "Your social security number", "400-55-8127", 1),
    ]
    draft = draft_schema(entries, {0: page, 1: page})
    assert draft.unresolved == []
    assert {"field": "yourSocialSecurityNumber",
            "note": "value_repeats_on_several_pages"} in draft.review


def test_the_same_caption_twice_on_one_page_with_no_discriminator_is_parked():
    entries = [
        key_entry("rentsReceived", "Rents received", "31,800", 0),
        key_entry("rentsReceived", "Rents received", "26,400", 0),
    ]
    draft = draft_schema(entries, {0: w2_like_page()})
    assert draft.unresolved[0]["reason"] == "repeats_with_no_occurrence_discriminator"


def test_blank_and_metadata_entries_are_not_expectations():
    assert read_entries({"file": "x.pdf", "form": "W-2"}) == []
    assert read_entries({"fields": [{"label": "Box 7", "value": "", "page": 0}]}) == []


# ── COLUMN groups ───────────────────────────────────────────────────────────

def schedule_e_like_page() -> Page:
    """A property table: the printed header row that names the key columns, then
    one captioned money row per line — the shape Schedule E Part I has."""
    return page_of(
        words("Properties:", 20.0, 10.0, 6.0),
        [span("A", 200.0, 10.0, 6.0), span("B", 320.0, 10.0, 6.0)],
        words("Rents received", 20.0, 40.0) + [
            span("31,800", 200.0, 40.0), span("26,400", 320.0, 40.0)],
    )


def test_a_column_group_drafts_its_printed_header_and_keys():
    entries = [
        key_entry("rentsReceived", "Rents received", "31,800",
                  occurrence_key="propertyColumn", occurrence_value="A"),
        key_entry("rentsReceived", "Rents received", "26,400",
                  occurrence_key="propertyColumn", occurrence_value="B"),
    ]
    draft = draft_schema(entries, {0: schedule_e_like_page()})
    assert draft.unresolved == []
    group = draft.fields[0]["group"]
    assert group["kind"] == "COLUMN"
    assert group["keys"] == ["A", "B"]
    assert group["header"]["pattern"] == "Properties:"
    validate_definition(compose("SCHEDULE_E", "0.1.0", draft))


def test_a_column_groups_rung_is_confined_to_each_key_band():
    entries = [
        key_entry("rentsReceived", "Rents received", "31,800",
                  occurrence_key="propertyColumn", occurrence_value="A"),
        key_entry("rentsReceived", "Rents received", "26,400",
                  occurrence_key="propertyColumn", occurrence_value="B"),
    ]
    page = schedule_e_like_page()
    draft = draft_schema(entries, {0: page})
    rung = draft.fields[0]["extractors"][0]
    bands = sd.resolve_column_bands("Properties:", ["A", "B"], page)
    captured = {band.key: simulate(rung, page, band) for band in bands}
    assert captured == {"A": "31,800", "B": "26,400"}


def test_a_column_group_whose_header_cannot_be_found_is_parked():
    # Same table, no printed key row: the engine would have no band to confine to,
    # so the drafter refuses rather than emitting a header that names nothing.
    page = page_of(words("Rents received", 20.0, 40.0) + [
        span("31,800", 200.0, 40.0), span("26,400", 320.0, 40.0)])
    entries = [
        key_entry("rentsReceived", "Rents received", "31,800",
                  occurrence_key="propertyColumn", occurrence_value="A"),
        key_entry("rentsReceived", "Rents received", "26,400",
                  occurrence_key="propertyColumn", occurrence_value="B"),
    ]
    draft = draft_schema(entries, {0: page})
    assert draft.fields == []
    assert draft.unresolved[0]["reason"] == "group_header_unresolved"


# ── the CLI edges ───────────────────────────────────────────────────────────

def test_main_refuses_a_pdf_with_no_answer_key(tmp_path, capsys):
    pdf = tmp_path / "orphan.pdf"
    pdf.write_bytes(b"%PDF-synthetic")
    assert sd.main([str(pdf)]) == 2
    assert "no answer key" in capsys.readouterr().err


def test_main_refuses_a_missing_pdf(tmp_path, capsys):
    assert sd.main([str(tmp_path / "absent.pdf")]) == 2
    assert "no such PDF" in capsys.readouterr().err


def test_main_writes_the_draft_and_reports_to_stderr(tmp_path, monkeypatch, capsys):
    pdf = tmp_path / "example.pdf"
    pdf.write_bytes(b"%PDF-synthetic")
    (tmp_path / "example.answers.json").write_text(json.dumps({
        "form": "Example Form",
        "fields": [{"label": "Wages, tips, other compensation", "value": "104,288.80",
                    "page": 0}],
    }))
    monkeypatch.setattr(sd, "load_pages", lambda _pdf, _pages: {0: w2_like_page()})
    out = tmp_path / "draft.json"

    assert sd.main([str(pdf), "--out", str(out)]) == 0

    definition = json.loads(out.read_text())
    validate_definition(definition)
    assert definition["documentTypeCode"] == "EXAMPLE_FORM"
    assert definition["version"] == "0.1.0"
    assert "104,288.80" not in capsys.readouterr().err


def test_strict_fails_the_exit_code_when_a_field_is_unresolved(tmp_path, monkeypatch):
    pdf = tmp_path / "example.pdf"
    pdf.write_bytes(b"%PDF-synthetic")
    (tmp_path / "example.answers.json").write_text(json.dumps({"fields": [
        {"label": "Wages, tips, other compensation", "value": "104,288.80", "page": 0},
        {"label": "Retirement plan", "value": "checked", "page": 0},
    ]}))
    monkeypatch.setattr(sd, "load_pages", lambda _pdf, _pages: {0: w2_like_page()})
    assert sd.main([str(pdf), "--out", str(tmp_path / "d.json")]) == 0
    assert sd.main([str(pdf), "--out", str(tmp_path / "d.json"), "--strict"]) == 1


def test_a_document_where_nothing_round_trips_emits_no_schema_at_all(
        tmp_path, monkeypatch, capsys):
    # The scanned-document case: no text layer, so no value can be located. An
    # empty `fields` array is what the loader refuses, so the tool writes nothing.
    pdf = tmp_path / "scanned.pdf"
    pdf.write_bytes(b"%PDF-synthetic")
    (tmp_path / "scanned.answers.json").write_text(json.dumps({"fields": [
        {"label": "Beneficiary name", "value": "Jane Q. Public", "page": 0}]}))
    monkeypatch.setattr(sd, "load_pages", lambda _pdf, _pages: {0: page_of([])})
    out = tmp_path / "draft.json"

    assert sd.main([str(pdf), "--out", str(out)]) == 1

    assert not out.exists()
    assert "no field round-tripped" in capsys.readouterr().err


def test_a_draft_object_composes_even_when_every_field_resolved():
    definition = compose("W2", "1.0.0", Draft(fields=[minimal_field()], unresolved=[], review=[]))
    validate_definition(definition)
    assert definition["_draft"]["unresolved"] == []


# ── document_type codes and the version grammar ─────────────────────────────
#
# Both of these were found by installing real drafts against a live engine: the
# generator emitted `0.1.0-draft` and slugs like `W_2`, and POST
# /v1/extraction-schemas refused both with a 400. Neither was reachable from a
# unit test, because neither tool knew the other's grammar.

def test_a_known_form_slug_resolves_to_the_engines_own_type_code():
    # "W-2" slugs to W_2; the engine calls it W2. No normalisation bridges that.
    assert sd.derive_type_code({"form": "W-2"}, sd.Path("w2.pdf")) == ("W2", "aliased")
    assert sd.derive_type_code({"form": "Form 1040"}, sd.Path("f.pdf")) == (
        "TAX_RETURN", "aliased")
    assert sd.derive_type_code({"form": "Schedule K-1 (Form 1065)"}, sd.Path("k.pdf")) == (
        "SCHEDULE_K1_1065", "aliased")


def test_a_form_the_engine_cannot_classify_is_flagged_not_silently_slugged():
    # Schedule D and F have no seeded document_type: they classify UNKNOWN and
    # extraction never runs, so a schema alone cannot reach them.
    code, provenance = sd.derive_type_code({"form": "Schedule D (Form 1040)"}, sd.Path("d.pdf"))
    assert (code, provenance) == ("SCHEDULE_D_FORM_1040", "no-engine-type")


def test_an_unrecognised_form_is_derived_and_says_so():
    code, provenance = sd.derive_type_code({"form": "Some New Form"}, sd.Path("x.pdf"))
    assert (code, provenance) == ("SOME_NEW_FORM", "derived")


def test_compose_records_how_the_type_code_was_arrived_at():
    draft = sd.Draft(fields=[], unresolved=[], review=[])
    assert compose("W2", "0.1.0", draft, "aliased")["_draft"][
        "documentTypeProvenance"] == "aliased"


def test_a_version_the_authoring_api_would_refuse_fails_before_any_work(tmp_path, capsys):
    # The API's grammar is strict MAJOR.MINOR.PATCH. Catching it here costs a
    # second; catching it at POST costs the whole drafting run.
    pdf = tmp_path / "example.pdf"
    pdf.write_bytes(b"%PDF-1.4\n")
    (tmp_path / "example.answers.json").write_text(json.dumps({
        "form": "W-2",
        "fields": [{"label": "Wages", "value": "1,000.00", "page": 0}],
    }))

    assert sd.main([str(pdf), "--version", "0.1.0-draft"]) == 2
    assert "MAJOR.MINOR.PATCH" in capsys.readouterr().err
