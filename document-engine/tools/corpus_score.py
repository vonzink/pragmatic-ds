#!/usr/bin/env python3
"""Score local corpus documents against the running stack — and VERIFY them.

    .venv/bin/python tools/corpus_score.py corpus/<file>.pdf [more.pdf ...] \
        [--api http://localhost:9090] [--strict]

The manual sanity gate before a rule-pack merge (Spec 3 design §7, D8): synthetic
fixtures gate CI, but only real documents catch anchor wordings the generator
never drew. Documents live in the gitignored corpus/ (see corpus/README.md) and
NEVER enter git or CI; this script talks to a locally running stack
(`docker compose up -d --build`, API on 9090, dev auth — no token needed) and
prints, per uploaded PDF:

  per page:      package page index, classified type, confidence, matched anchors
  per document:  type, then every extracted field — VERIFIED against the
                 ``<basename>.answers.json`` sitting beside the PDF when one
                 exists, or reported capture-only (with a banner) when not

Verification (per field OCCURRENCE, against the answer key):

  MATCH               captured value ≡ its EXPECTATION under deliberate
                      normalization: money numerically with sign (``( 18,470 )``
                      ≡ ``-18470`` ≡ ``-18,470.00``), dates ISO-normalized,
                      everything else whitespace-collapsed and case-PRESERVING.
                      A PERSON-NAME field (flagged on its map entry, never
                      guessed from the value) additionally accepts a spelled
                      middle token for its initial (``Quinn`` ≡ ``Q.`` ≡ ``Q``,
                      per person on a joint name) — annotated in the report,
                      never silent. The expectation is the key value verbatim
                      unless the field's map entry declares otherwise: a
                      COMPOSED entry joins named sibling key entries into ONE
                      expectation (annotated ``composed``; the consumed source
                      entries leave the missing/not-declared ledgers together)
                      and a FIRST-LINE entry expects only the text before the
                      first newline of a multi-line key value, admitting the
                      text before that line's first comma as an alternate
                      rendering when the line carries a comma-joined suffix
                      (annotated ``first-line``; the remainder is part of the
                      consumed entry and never resurfaces as
                      MISSING_EXPECTED). Every summary line carries a legend
                      counting its annotated matches
  MISMATCH            captured a WRONG value — fails the exit code
  UNMAPPED_CAPTURE    a FOUND value no key entry accounts for — the phantom-
                      occurrence signature of the row-band defect; fails the
                      exit code, same as MISMATCH
  MISSING_EXPECTED    the key lists a value the engine did not return for a
                      DECLARED field; reported prominently, fails the exit only
                      under ``--strict``
  NOT_DECLARED        a key entry naming a field the engine schema never
                      declares — a schema-coverage fact, summarized in one
                      line, never counted as missing-expected
  CAPTURED_UNEXPECTED the engine found a value where the key entry says blank
  NOT_IN_KEY          a MISSING occurrence of a field the key does not cover —
                      the only neutral class; it asserts nothing
  MASKED_SKIP         the server masked a sensitive value; unverifiable, never
                      a MISMATCH

Field identity is an EXPLICIT per-document-type mapping (``FIELD_LABEL_MAPS``):
engine field name → the answer key's IRS label (SCHEDULE_E all 29 declared
schema fields; W2 and TAX_RETURN all 10 each). A mapped type never falls back
to label normalization; normalization remains only as the bridge for document
types without a table (Schedules B/C/D/F and the K-1s, which have no
extraction schemas yet). The mapping table is also the "declared" set that
separates MISSING_EXPECTED from NOT_DECLARED.

The answer-key walk keys every leaf by its FULL path (arrays by index): a
prior ad-hoc checker keyed leaves by bare name, same-named leaves overwrote
each other, and a clean merge was mis-called a REGRESSION. Occurrences map to
key entries by (mapped name, occurrence key): a grouped capture like
``rentsReceived[B]`` matches the key entry whose ``propertyColumn``/``column``/
``row`` discriminator says B (zero-padded row ordinals equal their numeric
rows), never a sibling column's entry.

PRIVACY IS UNCONDITIONAL: when a key is present the displayed-text column is
replaced by the verdict; captured and expected VALUES are never printed — a
mismatch shows value lengths and a digits/letters shape hint only. The corpus
holds real borrower documents.

Matched anchors come from ``GET /v1/packages/{id}/classification`` — the read
endpoint this task ships — as anchor ids and pack types only; classification
evidence never carries matched text, and this report keeps that property. A
pre-Spec-3 stack has no such route (404) and the report degrades to
type+confidence per page.

stdlib only (urllib), on the Spec 2 smoke pattern — no requests, no new deps.
Everything below the "report building" marker is pure and unit-tested from
canned JSON in tools/tests/test_corpus_score.py.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from pathlib import Path

TERMINAL = ("COMPLETED", "HUMAN_REVIEW_REQUIRED", "FAILED")


# ── HTTP (thin, stdlib — the Spec 2 smoke pattern) ──────────────────────────

def req(api: str, method: str, path: str, data: bytes | None = None,
        ctype: str | None = None) -> dict:
    request = urllib.request.Request(api + path, data=data, method=method)
    if ctype:
        request.add_header("Content-Type", ctype)
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
        return json.loads(body) if body else {}


def upload(api: str, pdf: Path) -> dict:
    boundary = "corpusboundary"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="files"; filename="{pdf.name}"\r\n'
        f"Content-Type: application/pdf\r\n\r\n"
    ).encode() + pdf.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return req(api, "POST", "/v1/packages", body,
               f"multipart/form-data; boundary={boundary}")


def wait(api: str, job_id: str, timeout: float = 300.0) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = req(api, "GET", f"/v1/jobs/{job_id}")
        if job["status"] in TERMINAL:
            return job["status"]
        time.sleep(1.5)
    raise TimeoutError(f"job {job_id} did not settle within {timeout:.0f}s")


def classification_of(api: str, package_id: str) -> dict | None:
    """The package's per-page classification evidence, or None where absent.

    ``GET /v1/packages/{id}/classification``: one entry per classified page —
    pageId, packagePageIndex, documentTypeCode, confidence, rulePackVersion,
    and the stored classification_result.evidence JSON verbatim. A pre-Spec-3
    stack has no such route and answers 404; the caller degrades gracefully.
    """
    try:
        return req(api, "GET", f"/v1/packages/{package_id}/classification")
    except urllib.error.HTTPError as error:
        if error.code in (404, 405):
            return None
        raise


# ── report building (pure — unit-tested from canned JSON, no network) ───────

def anchor_ids(evidence: dict) -> list[str]:
    """``packType:anchorId`` per matched anchor, first-occurrence order, de-duplicated.

    Ids and pack types only: classification evidence carries no matched text by
    design (Phase 4 rule 3), and this report keeps that property.
    """
    seen: list[str] = []
    for anchor in evidence.get("anchors", []):
        label = f"{anchor.get('packType', '?')}:{anchor.get('anchorId', '?')}"
        if label not in seen:
            seen.append(label)
    return seen


def evidence_by_page(classification: dict | None) -> dict[int, dict]:
    """Evidence keyed by package page index.

    Reads the endpoint shape — ``{"packageId": ..., "pages":
    [{"packagePageIndex": n, "evidence": {...}}, ...]}``. ``None``/empty input
    (endpoint absent on an older stack) yields an empty mapping.
    """
    if not classification or not isinstance(classification.get("pages"), list):
        return {}
    return {
        page.get("packagePageIndex"): page.get("evidence") or {}
        for page in classification["pages"]
    }


def page_line(index: int, page_type: str, confidence: float | None,
              anchors: list[str]) -> str:
    shown = ", ".join(anchors) if anchors else "-"
    conf = "?" if confidence is None else f"{float(confidence):.2f}"
    return f"  page {index:>3}  {page_type:<20} {conf:>5}  [{shown}]"


# ── answer-key verification (pure — the part that makes this a VERIFIER) ────

MATCH = "MATCH"
MISMATCH = "MISMATCH"
MISSING_EXPECTED = "MISSING_EXPECTED"
CAPTURED_UNEXPECTED = "CAPTURED_UNEXPECTED"
NOT_IN_KEY = "NOT_IN_KEY"
MASKED_SKIP = "MASKED_SKIP"
UNMAPPED_CAPTURE = "UNMAPPED_CAPTURE"
NOT_DECLARED = "NOT_DECLARED"

NO_KEY_BANNER = "no answer key — capture-only report"

# MATCH annotations — the summary-line legend's closed vocabulary. The two
# POLICY annotations mark an expectation a map entry SHAPED before comparison
# (per-entry declarations, never heuristics); the RELAXATION annotation marks
# a comparison the person-name flag admitted. Every annotated match is visible
# twice: on its own report line and counted in its summary line's legend.
COMPOSED = "composed"
FIRST_LINE = "first-line"
MIDDLE_INITIAL_EQUIVALENCE = "middle-initial equivalence"

# Answer-key bookkeeping properties that describe the KEY, not the document.
# formYear is deliberately NOT here: it is an expectation (the year printed on
# the form) that a mapped field (taxYear) verifies against.
METADATA_KEYS = frozenset(
    {"file", "form", "subject", "extractionNotes", "verification"})

# ── explicit field mapping: engine field name → answer-key label ─────────────
#
# Label normalization cannot bridge "Mortgage interest paid to banks, etc." to
# mortgageInterest, and fuzzy bridging is exactly how wrong values sailed
# through exit-0. A mapped document type resolves field identity ONLY through
# this table; a field absent from its type's table is unmapped, and a FOUND
# value for it is UNMAPPED_CAPTURE. The table doubles as the declared-field
# set: key entries whose label is nobody's target are NOT_DECLARED
# (schema-coverage facts), never MISSING_EXPECTED. A target may be a
# ``MapTarget`` wrapper carrying per-entry verification flags (person /
# composed_with / first_line — see the class docstring); a plain-str target
# keeps full-string equality.
#
# SCHEDULE_E: all 29 fields of the V13 extraction schema. Labels for Part I
# and the masthead are the answer keys' exact IRS wordings; Part II–V labels
# (no corpus key covers those parts yet — the corpus form fills Part I only)
# are part-qualified IRS column captions, the tool's canonical forward targets.
# A future key covering those parts adopts these labels or this table changes
# with it — loudly, since a drifted label yields UNMAPPED_CAPTURE, never a
# silent pass.
#
# W2 and TAX_RETURN: all 10 fields of each V12 §2 schema (w2@1.1.0,
# tax_return@1.1.0), added after the first full-corpus sweep false-alarmed
# seven CORRECT captures as UNMAPPED_CAPTURE — the fuzzy bridge cannot map an
# IRS box caption ("Wages, tips, other compensation") to an engine field name
# (wagesTipsOtherComp). Labels are the corpus keys' exact wordings.
#
# Schedules B/C/D/F and the K-1s (1065/1120-S) have NO tables BY DESIGN: the
# engine declares no extraction schema for them yet (schedules get their own
# types in Spec 5), so there is no declared-field set to map — their keys ride
# the fuzzy fallback until a schema exists, and adding a table here without
# one would invent a declared set the engine does not honor.


class MapTarget(str):
    """A map target carrying per-entry verification flags.

    ONE wrapper class with orthogonal flags, deliberately — not a subclass per
    policy, because policies stack (a composed W-2 employee name is also a
    person name). A ``str`` subclass, so every existing consumer of the map
    (norm_name targets, declared-label sets, collision checks) sees a plain
    label. Flags are per-entry DECLARATIONS, never value heuristics:

    * ``person`` — the field holds a PERSON's name; middle-initial equivalence
      (``person_names_equivalent``) is admitted when strict comparison fails
      (the real form may spell a middle name the answer key holds as an
      initial, and the page is truth), annotated ``middle-initial
      equivalence`` when it fires.
    * ``composed_with`` — labels of sibling key entries whose values join the
      primary entry's value (space-separated, declaration order) into ONE
      expectation at hand-out time; annotated ``composed`` on every match,
      and every consumed source entry leaves the MISSING_EXPECTED /
      NOT_DECLARED ledgers together.
    * ``first_line`` — the field means only the leading NAME line of a
      multi-line key value: the expectation is the text before the first
      newline, and when that line itself carries a comma-joined suffix
      (``Name, Inc.``-shaped — the documented prefix-to-comma rule the key's
      structure requires), the text before the line's first comma is an
      accepted ALTERNATE rendering. Annotated ``first-line`` on every match;
      the remainder, part of the consumed entry, never resurfaces as
      MISSING_EXPECTED.
    """

    __slots__ = ("person", "composed_with", "first_line")

    def __new__(cls, label: str, *, person: bool = False,
                composed_with: tuple[str, ...] = (),
                first_line: bool = False) -> "MapTarget":
        target = super().__new__(cls, label)
        target.person = person
        target.composed_with = tuple(composed_with)
        target.first_line = first_line
        return target


FIELD_LABEL_MAPS: dict[str, dict[str, str]] = {
    "SCHEDULE_E": {
        "taxpayerName": MapTarget("Name(s) shown on return", person=True),
        "taxpayerSsn": "Your social security number",
        "taxYear": "formYear",
        "propertyAddress":
            "Physical address of each property (street, city, state, ZIP code)",
        "rentsReceived": "Rents received",
        "mortgageInterest": "Mortgage interest paid to banks, etc.",
        "depreciationExpense": "Depreciation expense or depletion",
        "totalExpenses": "Total expenses. Add lines 5 through 19",
        "incomeOrLoss": "Subtract line 20 from line 3 (rents) and/or 4 (royalties)",
        "totalRentalRealEstateIncomeOrLoss":
            "Total rental real estate and royalty income or (loss). "
            "Combine lines 24 and 25",
        "partnershipName": "Part II (a) Name",
        "partnershipEin": "Part II (d) Employer identification number",
        "partnershipPassiveLossAllowed": "Part II (g) Passive loss allowed",
        "partnershipPassiveIncome": "Part II (h) Passive income from Schedule K-1",
        "partnershipNonpassiveLossAllowed": "Part II (i) Nonpassive loss allowed",
        "partnershipSection179Expense":
            "Part II (j) Section 179 expense deduction",
        "partnershipNonpassiveIncome":
            "Part II (k) Nonpassive income from Schedule K-1",
        "partnershipAndSCorpTotal":
            "Total partnership and S corporation income or (loss)",
        "estateOrTrustName": "Part III (a) Name",
        "estateOrTrustPassiveDeductionOrLoss":
            "Part III (c) Passive deduction or loss allowed",
        "estateOrTrustPassiveIncome": "Part III (d) Passive income from Schedule K-1",
        "estateOrTrustDeductionOrLoss":
            "Part III (e) Deduction or loss from Schedule K-1",
        "estateOrTrustOtherIncome": "Part III (f) Other income from Schedule K-1",
        "estateAndTrustTotal": "Total estate and trust income or (loss)",
        "remicName": "Part IV (a) Name",
        "remicExcessInclusion": "Part IV (c) Excess inclusion from Schedules Q",
        "remicIncome": "Part IV (e) Income from Schedules Q",
        "remicTotal": "Combine columns (d) and (e) only",
        "totalIncomeOrLoss":
            "Total income or (loss). Combine lines 26, 32, 37, 39, and 40",
    },
    "W2": {
        # employeeName: the IRS W-2 prints box e as TWO sub-boxes and the key
        # holds them as two entries — "Employee's first name and initial" and
        # "Last name" — while the engine's schema reads the whole printed name
        # as ONE field (V12's one-cell rule, deliberate). The VERIFIER's
        # expectation is therefore COMPOSED: primary + " " + Last name; both
        # consumed entries leave the missing/not-declared ledgers, and the
        # person flag rides on top because the composed expectation is a
        # person's name. The engine still composes nothing — only the
        # expectation does.
        "employeeName": MapTarget("Employee's first name and initial",
                                  person=True, composed_with=("Last name",)),
        "employeeSsn": "Employee's social security number",
        # employerName: the key holds box c as ONE multi-line string — the
        # printed lines newline-joined — while the schema's employerName
        # means the company NAME, the first printed line. The key's actual
        # structure (read, not assumed) prints that name line
        # ``Name, Suffix``-shaped — a comma-joined corporate suffix on the
        # name line itself — so the FIRST-LINE expectation is the full first
        # line with its pre-comma segment as a declared alternate rendering:
        # match iff captured ≡ the text before the first newline, or ≡ the
        # text before that line's first comma. The address/ZIP remainder is
        # part of the consumed entry and never a phantom MISSING_EXPECTED.
        "employerName": MapTarget("Employer's name, address, and ZIP code",
                                  first_line=True),
        "employerEin": "Employer identification number (EIN)",
        # The key carries the year twice — a "Tax year" fields entry and a
        # top-level taxYear scalar; both normalize to the same bucket.
        "taxYear": "Tax year",
        "wagesTipsOtherComp": "Wages, tips, other compensation",
        "federalIncomeTaxWithheld": "Federal income tax withheld",
        "socialSecurityWages": "Social security wages",
        "medicareWages": "Medicare wages and tips",
        "stateWages": "State wages, tips, etc.",
    },
    "TAX_RETURN": {
        # Name/SSN labels caption the identity-block cells. Like the W-2's
        # box e, the key holds each name as TWO entries — the first-name cell
        # and its "Last name" sibling — and since V47 the engine reads the
        # name ACROSS both cells as one field, so the expectation is COMPOSED.
        # The key labels the two surname cells distinctly — "Last name" on
        # the taxpayer row, "Last name (spouse)" on the spouse row — so each
        # composition names its own row's cell and neither can claim the
        # other's.
        "primaryTaxpayerName":
            MapTarget("Your first name and middle initial", person=True,
                      composed_with=("Last name",)),
        "spouseName":
            MapTarget("If joint return, spouse's first name and middle initial",
                      person=True, composed_with=("Last name (spouse)",)),
        "primarySsn": "Your social security number",
        # The 1040 key has no fields[] year entry — only the top-level scalar,
        # the SCHEDULE_E formYear precedent.
        "taxYear": "taxYear",
        # The key labels the CHECKED option, so this target names the corpus
        # form's filing status. A key for a differently-filed 1040 prints a
        # different caption and false-alarms loudly (UNMAPPED_CAPTURE) until
        # the map and key reconcile — never a silent pass.
        "filingStatus":
            "Filing Status — Married filing jointly (even if only one had income)",
        "totalIncome":
            "Add lines 1z, 2b, 3b, 4b, 5b, 6b, 7, and 8. "
            "This is your total income",
        "adjustedGrossIncome":
            "Subtract line 10 from line 9. This is your adjusted gross income",
        "taxableIncome":
            "Subtract line 14 from line 11. If zero or less, enter -0-. "
            "This is your taxable income",
        "totalTax": "Add lines 22 and 23. This is your total tax",
        "refundAmount": "Amount of line 34 you want refunded to you",
    },
}

# Occurrence discriminators observed/supported in answer-key entries, most
# specific first: a Schedule-E property column letter, a generic column, an
# entity-table row, and friends. "line" is deliberately NOT here — it is the
# printed form line number, not an occurrence.
OCCURRENCE_KEYS = ("propertyColumn", "column", "row", "entityIndex", "entity",
                   "occurrence", "groupKey")

_MASKED_TEXT = re.compile(r"[•●]|\*{2,}|X{3,}")
_MONEY_DIGITS = re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?")
_DATE_FORMATS = ("%Y-%m-%d", "%m/%d/%Y", "%m-%d-%Y", "%m/%d/%y",
                 "%B %d, %Y", "%b %d, %Y", "%d %B %Y", "%d %b %Y")


def collapse_ws(text) -> str:
    return re.sub(r"\s+", " ", str(text)).strip()


def looks_masked(text: str) -> bool:
    return bool(_MASKED_TEXT.search(text))


def parse_money(text) -> Decimal | None:
    """Numeric money with sign, or None where the text is not money.

    ``( 18,470 )`` ≡ ``-18470`` ≡ ``-18,470.00``: parentheses are the
    accountant's negative; ``$``, commas, and spaces are formatting. Comma
    grouping must be valid — ``1,23`` is not money and falls back to string
    comparison rather than silently becoming 123.
    """
    s = collapse_ws(text)
    if not s:
        return None
    negative = False
    if s.startswith("(") and s.endswith(")"):
        negative = True
        s = s[1:-1].strip()
    for _ in range(2):  # sign and currency symbol arrive in either order
        if s[:1] == "-":
            negative = True
            s = s[1:].lstrip()
        elif s[:1] == "+":
            s = s[1:].lstrip()
        if s[:1] == "$":
            s = s[1:].lstrip()
    if not _MONEY_DIGITS.fullmatch(s):
        return None
    amount = Decimal(s.replace(",", ""))
    return -amount if negative else amount


def parse_date(text) -> str | None:
    """The ISO form of a recognizable date, or None."""
    s = collapse_ws(text)
    if not s or not any(ch.isdigit() for ch in s):
        return None
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            continue
    return None


def values_equivalent(captured, expected) -> bool:
    """Normalization-aware equality — deliberately, in this order.

    Money first (numeric, sign-aware), dates second (ISO-normalized), and
    everything else as whitespace-collapsed, case-PRESERVING text: a case
    difference in a name is a real difference until a human rules otherwise.
    """
    captured_money, expected_money = parse_money(captured), parse_money(expected)
    if captured_money is not None and expected_money is not None:
        return captured_money == expected_money
    captured_date, expected_date = parse_date(captured), parse_date(expected)
    if captured_date is not None and expected_date is not None:
        return captured_date == expected_date
    return collapse_ws(captured) == collapse_ws(expected)


_NAME_CONJUNCTIONS = frozenset({"and", "&"})


def _middle_token_equivalent(a: str, b: str) -> bool:
    """``Quinn`` ≡ ``Q.`` ≡ ``Q`` — case-sensitive on the letter; else exact."""
    if a == b:
        return True
    return b in (a[:1], a[:1] + ".") or a in (b[:1], b[:1] + ".")


def _person_tokens_equivalent(captured: list[str], expected: list[str]) -> bool:
    """One person: first and last tokens exact; middles pairwise by form."""
    if len(captured) != len(expected) or not captured:
        return False
    if captured[0] != expected[0] or captured[-1] != expected[-1]:
        return False
    return all(_middle_token_equivalent(c, e)
               for c, e in zip(captured[1:-1], expected[1:-1]))


def _split_joint_name(tokens: list[str]) -> tuple[list[list[str]], list[str]]:
    persons: list[list[str]] = []
    conjunctions: list[str] = []
    current: list[str] = []
    for token in tokens:
        if token in _NAME_CONJUNCTIONS:
            conjunctions.append(token)
            persons.append(current)
            current = []
        else:
            current.append(token)
    persons.append(current)
    return persons, conjunctions


def person_names_equivalent(captured, expected) -> bool:
    """Middle-initial equivalence, PERSON-NAME fields only (per-entry flag).

    The real form may spell a middle name the answer key holds as an initial,
    and the page is truth — so for a field whose map entry is person-flagged,
    a spelled middle token and its initial are the same person: ``Quinn`` ≡
    ``Q.`` ≡ ``Q``, case-sensitive on the letter. EVERYTHING else stays exact:
    first and last tokens, token counts, and the conjunction joining a joint
    return's filers (``and``/``&``), with the rule applied per person. A
    middle-token disagreement on the LETTER is a real mismatch.
    """
    captured_persons, captured_joins = _split_joint_name(
        collapse_ws(captured).split())
    expected_persons, expected_joins = _split_joint_name(
        collapse_ws(expected).split())
    if captured_joins != expected_joins:
        return False
    return all(_person_tokens_equivalent(c, e)
               for c, e in zip(captured_persons, expected_persons))


def shape_hint(text) -> str:
    """Digits→9, letters→A, punctuation kept: the SHAPE of a value, never the value."""
    shaped = "".join("9" if ch.isdigit() else "A" if ch.isalpha() else ch
                     for ch in collapse_ws(text))
    return shaped if len(shaped) <= 24 else shaped[:24] + "…"


def norm_name(name) -> str:
    """Case/punctuation-blind field identity: ``rentsReceived`` ≡ ``Rents received``."""
    return re.sub(r"[^a-z0-9]", "", str(name).lower())


def norm_occurrence(value) -> str:
    """``02`` ≡ ``2`` (zero-padded row ordinals), ``b`` ≡ ``B`` (column letters)."""
    text = collapse_ws(value)
    return str(int(text)) if text.isdigit() else text.upper()


def flatten_key(node, path: tuple = ()) -> dict[tuple, object]:
    """Every leaf of an answer key, keyed by its FULL path — arrays by index.

    The whole point: a walk keyed by bare leaf name lets same-named leaves in
    different scopes overwrite each other, which once erased most of a key and
    produced a false REGRESSION verdict. Paths never collide.
    """
    leaves: dict[tuple, object] = {}
    if isinstance(node, dict):
        for name, value in node.items():
            leaves.update(flatten_key(value, path + (name,)))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            leaves.update(flatten_key(value, path + (index,)))
    else:
        leaves[path] = node
    return leaves


def path_text(path: tuple) -> str:
    text = ""
    for part in path:
        if isinstance(part, int):
            text += f"[{part}]"
        else:
            text += f".{part}" if text else str(part)
    return text


@dataclass
class Expected:
    """One expected field-occurrence from the answer key."""
    name: str                       # semantic name used for matching
    occurrence: str | None          # normalized occurrence key (column/row), or None
    value: str                      # expected value ("" = key says blank)
    path: str                       # printable full path into the key file
    page: int | None = None
    alternates: tuple[str, ...] = ()  # e.g. a checkbox's rendersAs
    notes: tuple[str, ...] = ()     # policy annotations that shaped the value
    consumed: bool = False


def occurrence_of(mapping: dict) -> tuple[str | None, str | None]:
    """(discriminator property, normalized occurrence) of a key entry, or (None, None)."""
    for name in OCCURRENCE_KEYS:
        value = mapping.get(name)
        if value is not None and collapse_ws(value):
            return name, norm_occurrence(value)
    return None, None


def expected_entries(key: dict) -> list[Expected]:
    """The answer key as a flat list of expected field-occurrences.

    Two key layouts exist and both are supported:

    * ``fields`` list (authoritative when present): one dict per expected
      occurrence — ``label`` names the field, ``value`` is the expectation,
      ``propertyColumn``/``row`` is the occurrence key, ``rendersAs`` (when
      present) is an accepted alternate rendering. Top-level non-metadata
      SCALARS (``formYear``) are expectations too and are appended; top-level
      group-summary arrays are redundant with ``fields`` and are NOT
      double-counted.
    * generic layout (no ``fields`` list): the full-path walk. A top-level
      array of dicts is a repeating group — each element's discriminator
      property (or its 1-based index, matching printed row ordinals) is the
      occurrence, and every other leaf is an expectation named by its dotted
      path (``borrower.name`` matches an engine ``borrowerName``).
    """
    fields = key.get("fields")
    if isinstance(fields, list) and any(isinstance(entry, dict) for entry in fields):
        entries = []
        for index, entry in enumerate(fields):
            if not isinstance(entry, dict):
                continue
            _, occurrence = occurrence_of(entry)
            value = entry.get("value")
            renders = entry.get("rendersAs")
            entries.append(Expected(
                name=str(entry.get("label") or entry.get("acroField")
                         or f"fields[{index}]"),
                occurrence=occurrence,
                value="" if value is None else str(value),
                path=f"fields[{index}]",
                page=entry.get("page"),
                alternates=() if renders is None else (str(renders),),
            ))
        for top_name, top_value in key.items():
            if (top_name in METADATA_KEYS or top_name == "fields"
                    or isinstance(top_value, (dict, list))):
                continue
            entries.append(Expected(
                name=top_name,
                occurrence=None,
                value="" if top_value is None else str(top_value),
                path=top_name,
            ))
        return entries
    entries = []
    for top_name, top_value in key.items():
        if top_name in METADATA_KEYS or top_name == "fields":
            continue
        if (isinstance(top_value, list) and top_value
                and all(isinstance(element, dict) for element in top_value)):
            for index, element in enumerate(top_value):
                discriminator, occurrence = occurrence_of(element)
                if occurrence is None:
                    occurrence = str(index + 1)  # 1-based, like printed row ordinals
                for subpath, leaf in flatten_key(element).items():
                    if subpath == (discriminator,):
                        continue  # the discriminator names the occurrence, it is not a value
                    dotted = ".".join(
                        [top_name, *(p for p in subpath if isinstance(p, str))])
                    entries.append(Expected(
                        name=dotted,
                        occurrence=occurrence,
                        value="" if leaf is None else str(leaf),
                        path=f"{top_name}[{index}]." + path_text(subpath),
                    ))
        else:
            for subpath, leaf in flatten_key(top_value, (top_name,)).items():
                dotted = ".".join(str(p) for p in subpath if isinstance(p, str))
                entries.append(Expected(
                    name=dotted,
                    occurrence=None,
                    value="" if leaf is None else str(leaf),
                    path=path_text(subpath),
                ))
    return entries


class KeyMatcher:
    """Hands out answer-key entries as captured occurrences claim them.

    Field identity resolves through the document type's explicit mapping when
    one exists (``label_map``): engine field name → key label, no fuzzy
    fallback for mapped types, ever. Types without a table fall back to
    normalized-name matching. Lookup within a name is by normalized
    occurrence; each entry is consumed at most once, so a second capture of
    the same occurrence cannot re-match it and an entry consumed by a capture
    never resurfaces as MISSING_EXPECTED. When every entry under a name agrees
    on its occurrence (including "none"), a capture that disagrees only about
    WHETHER the field is grouped still matches — ambiguity between occurrences
    never does.

    A ``MapTarget``'s policy flags shape the EXPECTATION at hand-out time
    (``_apply_policies``): the comparison downstream never composes or trims,
    it only sees the entry the map entry declared.
    """

    def __init__(self, key: dict):
        self.entries = expected_entries(key)
        self._by_name: dict[str, list[Expected]] = defaultdict(list)
        for entry in self.entries:
            self._by_name[norm_name(entry.name)].append(entry)

    def _candidates(self, field_name: str,
                    label_map: dict[str, str] | None) -> tuple[str, list[Expected]]:
        if label_map is not None:
            target = label_map.get(field_name)
            if target is None:
                return "UNMAPPED", []       # mapped type: no fuzzy bridge, ever
            candidates = self._by_name.get(norm_name(target), [])
        else:
            candidates = self._by_name.get(norm_name(field_name), [])
        if not candidates:
            return "NO_ENTRY", []
        return "OK", candidates

    def take(self, field_name: str, group_key,
             label_map: dict[str, str] | None = None) -> tuple[str, Expected | None]:
        status, candidates = self._candidates(field_name, label_map)
        if status != "OK":
            return status, None
        wanted = None if group_key is None else norm_occurrence(group_key)
        entry = self._claim(candidates, wanted)
        if entry is None:
            return "NO_OCCURRENCE", None
        target = None if label_map is None else label_map.get(field_name)
        if isinstance(target, MapTarget):
            entry = self._apply_policies(entry, target, wanted)
        return "OK", entry

    @staticmethod
    def _claim(candidates: list[Expected], wanted: str | None) -> Expected | None:
        for entry in candidates:
            if not entry.consumed and entry.occurrence == wanted:
                entry.consumed = True
                return entry
        if len({entry.occurrence for entry in candidates}) == 1:
            for entry in candidates:
                if not entry.consumed:
                    entry.consumed = True
                    return entry
        return None

    def _apply_policies(self, entry: Expected, target: MapTarget,
                        wanted: str | None) -> Expected:
        """The entry a policy-flagged map target declares, built at hand-out.

        The comparison downstream never composes or trims: the flags shape the
        EXPECTATION here, once, and ``verdict_of`` compares against it like
        any other entry. Every source entry folded in is consumed, so none of
        them can resurface as MISSING_EXPECTED or NOT_DECLARED. Policy notes
        annotate every verdict the shaped entry produces.
        """
        value, path, alternates = entry.value, entry.path, entry.alternates
        notes: list[str] = []
        if target.first_line:
            value = str(value).split("\n", 1)[0]
            before_comma = value.split(",", 1)[0]
            if before_comma != value:   # a comma-joined suffix on the name line
                alternates = (*alternates, before_comma)
            notes.append(FIRST_LINE)
        if target.composed_with:
            parts = [value]
            for label in target.composed_with:
                part = self._claim(self._by_name.get(norm_name(label), []),
                                   wanted)
                if part is not None:
                    parts.append(part.value)
                    path += f"+{part.path}"
            value = " ".join(str(part) for part in parts)
            notes.append(COMPOSED)
        if not notes:
            return entry
        return Expected(name=entry.name, occurrence=entry.occurrence,
                        value=value, path=path, page=entry.page,
                        alternates=alternates, notes=tuple(notes),
                        consumed=True)

    def suppress(self, field_name: str,
                 label_map: dict[str, str] | None = None) -> None:
        """A masked capture makes its field unverifiable EVERYWHERE it appears.

        Name/SSN repeat on every key page; without this, the page-2 copy of a
        server-masked field would resurface as a false MISSING_EXPECTED. A
        composed target's part entries are the same field's other cells, so
        the suppression reaches them too.
        """
        _, candidates = self._candidates(field_name, label_map)
        target = None if label_map is None else label_map.get(field_name)
        if isinstance(target, MapTarget):
            for label in target.composed_with:
                candidates = candidates + self._by_name.get(norm_name(label), [])
        for entry in candidates:
            entry.consumed = True

    def leftovers(self) -> list[Expected]:
        """Valued key entries no capture claimed — missing or undeclared."""
        return [entry for entry in self.entries
                if not entry.consumed and collapse_ws(entry.value)]


_VERDICT_ATTR = {MATCH: "match", MISMATCH: "mismatch",
                 MISSING_EXPECTED: "missing_expected", MASKED_SKIP: "masked",
                 CAPTURED_UNEXPECTED: "captured_unexpected",
                 NOT_IN_KEY: "not_in_key", UNMAPPED_CAPTURE: "unmapped_capture",
                 NOT_DECLARED: "not_declared"}

_COUNT_FIELDS = ("match", "mismatch", "unmapped_capture", "missing_expected",
                 "masked", "captured_unexpected", "not_in_key", "not_declared",
                 "composed", "first_line", "middle_initial",
                 "documents", "packages")

# annotation → VerdictCounts attribute, in the legend's fixed display order
_ANNOTATION_ATTR = {COMPOSED: "composed", FIRST_LINE: "first_line",
                    MIDDLE_INITIAL_EQUIVALENCE: "middle_initial"}


@dataclass
class VerdictCounts:
    match: int = 0
    mismatch: int = 0
    unmapped_capture: int = 0
    missing_expected: int = 0
    masked: int = 0
    captured_unexpected: int = 0
    not_in_key: int = 0
    not_declared: int = 0
    composed: int = 0
    first_line: int = 0
    middle_initial: int = 0
    documents: int = 0
    packages: int = 0

    def count(self, verdict: str) -> None:
        attr = _VERDICT_ATTR[verdict]
        setattr(self, attr, getattr(self, attr) + 1)

    def count_annotations(self, verdict: str, detail: str | None) -> None:
        """Tally a MATCH's annotations into the summary-line legend.

        The vocabulary is closed (``_ANNOTATION_ATTR``); a MISMATCH detail is
        a length/shape hint, never an annotation, and is ignored.
        """
        if verdict != MATCH or not detail:
            return
        for annotation in detail.split(", "):
            attr = _ANNOTATION_ATTR.get(annotation)
            if attr is not None:
                setattr(self, attr, getattr(self, attr) + 1)

    def merge(self, other: "VerdictCounts") -> None:
        for attr in _COUNT_FIELDS:
            setattr(self, attr, getattr(self, attr) + getattr(other, attr))

    def summary(self) -> str:
        text = (f"{self.match} match / {self.mismatch} mismatch / "
                f"{self.unmapped_capture} unmapped-capture / "
                f"{self.missing_expected} missing-expected / {self.masked} masked "
                f"({self.captured_unexpected} captured-unexpected, "
                f"{self.not_in_key} not-in-key, {self.not_declared} not-declared)")
        legend = [f"{getattr(self, attr)} {annotation}"
                  for annotation, attr in _ANNOTATION_ATTR.items()
                  if getattr(self, attr)]
        if legend:
            text += f" [annotated: {', '.join(legend)}]"
        return text


_UNACCOUNTED_DETAIL = {
    "UNMAPPED": "field not in the document type's mapping",
    "NO_ENTRY": "no key entry for this field",
    "NO_OCCURRENCE": "occurrence not listed in key",
}


def verdict_of(field: dict, matcher: KeyMatcher,
               label_map: dict[str, str] | None = None) -> tuple[str, str | None]:
    """(verdict, detail) for one captured field-occurrence.

    The detail never contains a captured or expected VALUE — lengths and
    digits/letters shape hints only. MASKED values (server-masked sensitive
    fields) are unverifiable and skip, never mismatch — and suppress every key
    entry of their field, which repeats on later pages. A FOUND value no key
    entry accounts for is UNMAPPED_CAPTURE (loud, exit-failing): that is the
    phantom-occurrence signature of the row-band defect. NOT_IN_KEY, the
    neutral class, is only for MISSING occurrences of unaccounted fields.
    A PERSON-NAME field (its map entry carries the ``person`` flag) that fails
    strict comparison gets one relaxation — middle-initial equivalence — and
    such a MATCH carries the detail "middle-initial equivalence", never
    silence. An expectation the map entry SHAPED (composed / first-line)
    arrives here already built by ``KeyMatcher``; its match carries the
    shaping annotation(s) the same way.
    """
    captured = field.get("displayedText")
    masked = captured is not None and (bool(field.get("sensitive"))
                                       or looks_masked(str(captured)))
    name = field.get("fieldName", "?")
    status, entry = matcher.take(name, field.get("groupKey"), label_map)
    if masked:
        matcher.suppress(name, label_map)
        return MASKED_SKIP, None
    if status != "OK":
        if captured is None:
            return NOT_IN_KEY, None
        return UNMAPPED_CAPTURE, _UNACCOUNTED_DETAIL[status]
    expected = collapse_ws(entry.value)
    if captured is None:
        if not expected:
            return MATCH, None
        return MISSING_EXPECTED, f"expected len={len(expected)} shape={shape_hint(expected)}"
    if not expected:
        return CAPTURED_UNEXPECTED, "key lists no value for this field"
    notes = ", ".join(entry.notes) or None
    for alternative in (entry.value, *entry.alternates):
        if values_equivalent(captured, alternative):
            return MATCH, notes
    target = None if label_map is None else label_map.get(name)
    if isinstance(target, MapTarget) and target.person:
        for alternative in (entry.value, *entry.alternates):
            if person_names_equivalent(captured, alternative):
                return MATCH, ", ".join(
                    (*entry.notes, MIDDLE_INITIAL_EQUIVALENCE))
    shown = collapse_ws(captured)
    return MISMATCH, (f"captured len={len(shown)} shape={shape_hint(shown)}; "
                      f"expected len={len(expected)} shape={shape_hint(expected)}")


def verdict_line(field: dict, verdict: str, detail: str | None = None) -> str:
    """The keyed-mode field line: the VERDICT stands where displayed text stood."""
    confidence = field.get("confidence")
    conf = "?" if confidence is None else f"{float(confidence):.2f}"
    group = field.get("groupKey")
    name = field.get("fieldName", "?")
    named = name if group is None else f"{name}[{group}]"
    line = (f"    {named:<24} {verdict:<20} "
            f"{field.get('extractionMethod', '?'):<18} conf={conf}")
    return f"{line}  ({detail})" if detail else line


def missing_expected_line(entry: Expected) -> str:
    named = entry.name if entry.occurrence is None else f"{entry.name}[{entry.occurrence}]"
    where = entry.path if entry.page is None else f"{entry.path} page={entry.page}"
    expected = collapse_ws(entry.value)
    return (f"    {named:<24} {MISSING_EXPECTED:<20} key={where}  "
            f"(expected len={len(expected)} shape={shape_hint(expected)})")


def field_line(field: dict) -> str:
    """One report line per OCCURRENCE.

    A repeating field (Spec 5a) returns one entry per occurrence under one name, so the
    name alone stops identifying the value: three rents on a Schedule E would print as
    three indistinguishable lines. The key printed on the form — A/B/C for a property
    column, the zero-padded row ordinal for an entity table — goes in brackets after the
    name, so a reviewer reads "property B" and finds column B on the page. An ungrouped
    field reports a null key and its line is exactly what it was; so is a response from a
    stack older than Spec 5a, which serves no groupKey property at all.
    """
    display = field.get("displayedText")
    shown = "(missing)" if display is None else str(display)
    confidence = field.get("confidence")
    conf = "?" if confidence is None else f"{float(confidence):.2f}"
    sensitive = "  (sensitive, masked by server)" if field.get("sensitive") else ""
    group = field.get("groupKey")
    name = field.get("fieldName", "?")
    named = name if group is None else f"{name}[{group}]"
    return (f"    {named:<24} {shown:<28} "
            f"{field.get('extractionMethod', '?'):<18} conf={conf}{sensitive}")


def document_report(document: dict, classification: dict | None,
                    fields_view: dict | None,
                    matcher: KeyMatcher | None = None,
                    counts: VerdictCounts | None = None) -> list[str]:
    lines: list[str] = []
    confidence = document.get("classificationConfidence")
    conf = "human" if confidence is None else f"{float(confidence):.2f}"
    lines.append(f"document {document.get('ordinal', 0) + 1}: "
                 f"{document.get('documentTypeCode', '?')} ({conf})")
    per_page = evidence_by_page(classification)
    for page in document.get("pages", []):
        index = page.get("packagePageIndex", -1)
        cls = page.get("classification") or {}
        anchors = anchor_ids(per_page.get(index, {}))
        lines.append(page_line(index, cls.get("type", "?"), cls.get("confidence"), anchors))
    label_map = (None if matcher is None
                 else FIELD_LABEL_MAPS.get(document.get("documentTypeCode")))
    for field in (fields_view or {}).get("fields", []):
        if matcher is None:
            lines.append(field_line(field))
            continue
        verdict, detail = verdict_of(field, matcher, label_map)
        if counts is not None:
            counts.count(verdict)
            counts.count_annotations(verdict, detail)
        lines.append(verdict_line(field, verdict, detail))
    return lines


def declared_labels(documents: list[dict]) -> set[str] | None:
    """The union of mapped labels across the package's document types.

    None when no document type has a mapping table — then there is no
    declared/undeclared distinction and every leftover is MISSING_EXPECTED,
    the pre-mapping behavior.
    """
    labels: set[str] = set()
    mapped = False
    for document in documents:
        label_map = FIELD_LABEL_MAPS.get(document.get("documentTypeCode"))
        if label_map:
            mapped = True
            labels.update(norm_name(target) for target in label_map.values())
    return labels if mapped else None


def leftover_lines(matcher: KeyMatcher, declared: set[str] | None,
                   counts: VerdictCounts) -> list[str]:
    """Unclaimed valued key entries: MISSING_EXPECTED lines for DECLARED
    fields, one schema-coverage line for the rest."""
    lines: list[str] = []
    leftovers = matcher.leftovers()
    if declared is None:
        declared_missing, undeclared = leftovers, []
    else:
        declared_missing = [entry for entry in leftovers
                            if norm_name(entry.name) in declared]
        undeclared = [entry for entry in leftovers
                      if norm_name(entry.name) not in declared]
    for entry in declared_missing:
        lines.append(missing_expected_line(entry))
        counts.count(MISSING_EXPECTED)
    if undeclared:
        labels: list[str] = []
        for entry in undeclared:
            counts.count(NOT_DECLARED)
            if entry.name not in labels:
                labels.append(entry.name)
        lines.append(f"  schema-coverage: {len(undeclared)} key entries name "
                     f"{len(labels)} fields the schema does not declare: "
                     f"{', '.join(labels)}")
    return lines


def package_report(name: str, status: str, documents_view: dict,
                   classification: dict | None,
                   fields_by_document: dict[str, dict | None],
                   answer_key: dict | None = None,
                   totals: VerdictCounts | None = None) -> str:
    """The per-package report; with an answer key, the per-package VERDICT.

    Without a key this is exactly the historical capture report plus a banner
    saying so. With one, each field line carries its verdict instead of its
    value, each document gets a ``summary:`` count line, and valued key entries
    no capture claimed print as MISSING_EXPECTED after the final document (the
    key is package-scoped, so that is where the ledger closes — for the
    corpus's one-form-per-PDF files it is simply the document's own summary).
    """
    lines = [f"== {name} -> {status}"]
    matcher = None if answer_key is None else KeyMatcher(answer_key)
    package_counts = VerdictCounts()
    documents = documents_view.get("documents", [])
    declared = declared_labels(documents) if matcher is not None else None
    for position, document in enumerate(documents):
        doc_counts = VerdictCounts()
        lines.extend(document_report(
            document,
            classification,
            fields_by_document.get(document.get("id", "")),
            matcher=matcher,
            counts=None if matcher is None else doc_counts,
        ))
        if matcher is not None:
            if position == len(documents) - 1:
                lines.extend(leftover_lines(matcher, declared, doc_counts))
            lines.append(f"  summary: {doc_counts.summary()}")
            doc_counts.documents = 1
            package_counts.merge(doc_counts)
    if matcher is not None and not documents:
        lines.extend(leftover_lines(matcher, declared, package_counts))
        lines.append(f"  summary: {package_counts.summary()}")
    for page in documents_view.get("unassignedPages", []):
        lines.append(f"  page {page.get('packagePageIndex', '?'):>3}  "
                     f"unassigned ({page.get('reason', '?')})")
    if matcher is None:
        lines.append(f"  ({NO_KEY_BANNER})")
    else:
        package_counts.packages = 1
        if totals is not None:
            totals.merge(package_counts)
    return "\n".join(lines)


# ── driver ──────────────────────────────────────────────────────────────────

def load_answer_key(pdf: Path) -> dict | None:
    """``<basename>.answers.json`` beside the PDF, or None when it has none.

    A key that exists but cannot be parsed (or is not a JSON object) raises:
    a corrupt answer key must be fixed, never silently degraded to a
    capture-only report that looks like success.
    """
    path = pdf.with_suffix(".answers.json")
    if not path.is_file():
        return None
    with path.open(encoding="utf-8") as handle:
        key = json.load(handle)
    if not isinstance(key, dict):
        raise ValueError(f"answer key {path.name} is not a JSON object")
    return key


def score(api: str, pdf: Path, timeout: float = 300.0,
          totals: VerdictCounts | None = None) -> str:
    up = upload(api, pdf)
    status = wait(api, up["jobId"], timeout)
    package_id = up["packageId"]
    documents_view = req(api, "GET", f"/v1/packages/{package_id}/documents")
    classification = classification_of(api, package_id)
    fields: dict[str, dict | None] = {}
    for document in documents_view.get("documents", []):
        document_id = document["id"]
        try:
            fields[document_id] = req(api, "GET", f"/v1/documents/{document_id}/fields")
        except urllib.error.HTTPError:
            fields[document_id] = None
    return package_report(pdf.name, status, documents_view, classification, fields,
                          answer_key=load_answer_key(pdf), totals=totals)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Score local corpus PDFs against the running stack.")
    parser.add_argument("pdfs", nargs="+", type=Path, help="local PDFs to score")
    parser.add_argument("--api", default="http://localhost:9090",
                        help="engine API base URL (default: %(default)s)")
    parser.add_argument(
        "--timeout",
        type=float,
        default=300.0,
        help="seconds to wait for each document job (default: %(default)s)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="MISSING_EXPECTED verdicts also fail the exit code "
             "(MISMATCH and UNMAPPED_CAPTURE always do — wrong and phantom "
             "are worse than missing; NOT_DECLARED never fails)",
    )
    args = parser.parse_args(argv)
    exit_code = 0
    totals = VerdictCounts()
    for pdf in args.pdfs:
        if not pdf.is_file():
            print(f"== {pdf}: not a file, skipping", file=sys.stderr)
            exit_code = 1
            continue
        try:
            print(score(args.api, pdf, args.timeout, totals))
        except (TimeoutError, urllib.error.URLError) as error:
            message = f"== {pdf.name} -> NOT SCORED ({type(error).__name__}: {error})"
            print(message, file=sys.stderr)
            print(message)
            exit_code = 1
    if totals.packages:
        print(f"== roll-up ({totals.packages} scored package(s), "
              f"{totals.documents} document(s)): {totals.summary()}")
    if (totals.mismatch or totals.unmapped_capture
            or (args.strict and totals.missing_expected)):
        exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
