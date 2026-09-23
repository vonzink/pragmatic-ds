---
title: Consumer integration guide
derived-from: docs/consumer/income-extraction-contract.md
contract-sha256: aa1999ed6c61a2c14bd9a709abb7a26919abc937796840b9b2213522b7cd5ad4
---

<!--
  THIS FILE IS A SUMMARY, NOT THE CONTRACT.

  `docs/consumer/income-extraction-contract.md` is the source of truth. This file
  exists so someone integrating a new consumer can learn the rules in one page
  without reading 1,600 lines — which means it can go stale, silently, while a
  consumer keeps following rules the engine no longer honours.

  The `contract-sha256` above pins the contract revision this summary was written
  against. CI recomputes it (`tools/consumer_guide_gate.py`) and fails the build
  when the two disagree. If that gate fails: re-read what changed in the contract,
  update the prose below to match, and only then re-record the hash. Do NOT
  re-record the hash to make the build green — the whole point is that a contract
  change forces a human to look at what consumers are being told.
-->

# Consumer integration guide

How to read Pragmatic DS Document Engine output without getting a wrong answer that looks
like a right one.

For a consumer that reads the **canonical envelope only**, most of this is better
enforced in a typed parser than in prose — see rag-brain's
`com.pragmaticds.rag.lab.engine` package for a worked example that pins the envelope
version, refuses review members, and fails closed. This guide matters most for a
consumer reading the **read-model** surfaces, where corrections, masking and review
state are live and there is no schema to lean on.

## Which surface to read

| Surface | Corrections | Sensitive values | Citable |
|---|---|---|---|
| `GET /v1/packages/{id}/engine-result` | never | **unmasked** (ADMIN only) | yes — SHA-256, byte-frozen |
| `GET /v1/documents/{id}/fields` | overlaid at read time | masked | no |
| `GET /v1/packages/{id}/export` | overlaid at read time | masked | no |
| `GET /v1/documents/{id}/fields.md` | overlaid at read time | masked | no |
| `GET /v1/documents/{id}/body.md` | n/a (page text, no fields) | **unmasked** (ADMIN or `ENGINE_RESULT_READ` scope) | no |

**Choose deliberately, because the two halves disagree on purpose.**

- The **envelope** is what the machine said. Immutable, hashed, servable as a
  citation. It carries NO review concepts at all — no `reviewStatus`, no
  `effectiveStatus`, no corrections. A human fix made after the parse is invisible
  here, by design. Pin `envelopeVersion` and `canonicalizationVersion` and fail
  closed on an unrecognised value; the bytes are frozen and a bump is deliberate.
  **Fail closed on the right shapes, though — some grew without a bump.** A page's classification
  `evidence` is keyed on its `method`: `RULE_ANCHOR` carries `anchors` + `scores` and, only when
  non-empty, `coQualifyingTypes`; `LLM` carries `source`, `model`, `promptVersion`,
  `matchedSpanIds`, `offsets` and optional `deterministicRunnerUp`. A parser pinned to exactly
  `{anchors, scores}` refuses real 1040-plus-schedule packages. Admit both shapes by `method`,
  refuse anything else (contract §1.8.1).
- The **read model** (`/fields`, `/export`, `.md`) is the current reviewed state.
  It carries the review keys, and it is masked.

Read the envelope alone and you serve values a human has since corrected or
rejected. Read the read model alone and you cannot cite an immutable parse. A
consumer that needs both should read the read model for values and carry the
pinned engine-result revision as metadata.

The Markdown is a projection of `/fields` — same values, same masking, same
ordering. It carries no envelope hash and makes no integrity claim. Never treat it
as a citable parse.

`body.md` is a different kind of surface: the document's captured text in reading order, needing no
schema, so it is the only rendering that survives a document classified `UNKNOWN`. It is **not
masked** and sits behind the same gate as the envelope — rule 10 applies to it in full, so never
embed, index or cache its text anywhere the masked surfaces would not have put it. Treat it as
context, never as a source of amounts: compute on typed fields.

Beyond these, the engine serves only source visuals: `image/png` page renders and
`application/pdf` document pages. There is no other output format.

## Rules you must honor

### 1. A missing field is a RESULT, not an absence

Missing occurrences are always present in `fields[]`, never omitted:

| | envelope | read model |
|---|---|---|
| marker | `"status": "MISSING"` | `"extractionMethod": "NONE"` |
| normalized | `null` (the whole object) | all three arms null |
| confidence | `0` | `0` |
| validationStatus | `"MANUAL_REVIEW_REQUIRED"` | `"MANUAL_REVIEW_REQUIRED"` |
| confidenceComponents | `null` | `null` |
| evidence | `[]` | `[]` |

- Treat this as **UNKNOWN**. Never coerce to 0, 0.00, "", or "not applicable".
  A defaulted 0.00 in a rental expense silently changes a qualifying-income
  calculation.
- Do NOT infer "the document does not contain X" from a missing X. The engine's
  only claim is that its extractors did not find X.
- Do NOT count rows to measure coverage — ten rows can be ten misses. Count rows
  with a non-null `displayedText`.

### 2. Occurrence identity is (fieldName, groupKey), never fieldName alone

Repeating fields appear once per occurrence. `groupKey` is present-and-null for
ungrouped fields, never omitted. Three key alphabets, all printed on the form:

- `null` — the field does not repeat
- printed letters (`A`, `B`, `C`) — COLUMN groups and labeled ROW groups
- zero-padded ordinals (`01`..`99`) — unlabeled counted ROW groups

Reassemble a repeating record (one rental property, one partnership) by joining
every field that shares a group key. Never key a row, an index, or a cache entry by
bare field name — you will collapse occurrences into each other.

`groupKind` (`NONE` / `ROW` / `COLUMN`) travels beside `groupKey` on every
read-model occurrence and says how the form lays the occurrences out. Use it rather
than inferring shape from the key alphabet.

One exception to watch: a grouped field whose region could not be located at all
persists a single MISSING occurrence with a **null** key. `groupKind` still reports
its real kind. It is a GROUPED field — do not promote it to a document-level field.

### 3. Ordering is guaranteed — rely on it

Field name ascending, then group key ascending, **nulls first**. Identical on every
read path. Two surfaces walked in parallel stay in lockstep.

### 4. Confidence is a product of exactly three components

`spanConfidence × anchorStrength × normalizerCertainty`. Never a fourth.
`confidenceComponents` is null exactly when the occurrence is missing. If you
threshold, threshold on the product and keep the components for explanation.

### 5. `textProvenance` says where the characters came from — and is NOT a component

Present on every read-model occurrence: `{ "source": ..., "ocrEngine": ... }`.

- `source` is `NATIVE` (the PDF's own text layer), `OCR` (recognised from pixels),
  `MIXED` (both), or `UNKNOWN` (no text span backs the value — every MISSING
  occurrence reads UNKNOWN). Never null.
- `ocrEngine` is `RAPIDOCR`, `TESSERACT`, or the two joined with `+`. Null exactly
  when no OCR span contributed.

An OCR'd value and a native-text value render identically and do not deserve
identical trust. Weight them differently if you like — but do not fold provenance
into confidence. It travels beside the three components, never inside them.

### 6. Read `effectiveStatus` before using any read-model value

Read-model surfaces only (`/fields`, `/export`, `.md`). The envelope has no such key.

- `MACHINE` — the untouched machine parse, including one a human CONFIRMED.
- `CORRECTED` — the served `displayedText`/`normalized` is a human's correction;
  `rawValue` stays machine. Usable. **This breaks the rule-1 equivalences on
  purpose:** method `NONE` with a non-null `displayedText` is a human filling a
  machine miss, and that value IS usable.
- `REJECTED` — a named human examined the value and refused it. The value channel
  still carries it so review surfaces can show what was rejected. **You must not
  use it.** Treat as missing-for-use — never a current value, never a defaulted zero.

The rule-1 shortcuts (`confidence == 0` means unknown, etc.) hold only on rows where
`effectiveStatus == "MACHINE"`. Check the status first, then apply rule 1.

### 7. Values: raw vs normalized

`rawValue`/`displayedText` is what the page says; `normalized` is machine-usable and
polymorphic — `4670.69` (JSON number) for money, `"2026-01-17"` for a date,
`"BIWEEKLY"` for an enum. Accounting parens become real negatives: `(5,610)` → `-5610`.
Compute on normalized; quote raw back to humans.

### 8. Evidence, if you cite locations

VALUE and LABEL boxes in PDF points, top-left origin, rotation-0 frame, **one box per
text span** — a multi-word value yields several boxes, and `( 18,470 )` is three (the
parenthesis glyphs are their own spans). LABEL boxes are why the engine read the value
that way; keep them if you want to explain a reading. A found `DERIVED` value (arithmetic over other
fields, e.g. a bank statement's `totalWithdrawals`) has no boxes at all — cite its inputs instead.

Box shape differs by surface: flat `x`/`y`/`width`/`height` on read-model evidence,
nested under `"box"` in the envelope.

### 9. Page numbering differs by surface

Markdown is 1-based. Evidence JSON `packagePageIndex` is 0-based. Convert explicitly.

### 10. Masking

`/fields`, `/export` and the Markdown serve sensitive values already masked, and there
is no unmask endpoint. Only the envelope carries full values, and only for ADMIN.
Never persist an unmasked value anywhere the masked surfaces would not have put it —
no logs, no caches, no derived stores, no third-party service.

### 11. The DECLARED field set is per-org, and it can change without a release

Which fields a document type declares is not a property of the engine. It is a
property of the extraction schema that wins for **your organization**, and since
`POST /v1/extraction-schemas` an org can author its own version at runtime.

An org-authored schema **shadows the global built-in wholesale — it does not merge
with it.** So a schema declaring 12 fields replaces one declaring 30, and the
missing 18 stop appearing. Nothing is broken and nothing errors; those fields are
simply not declared any more.

Read this off the surfaces rather than assuming a stable field list:

- **`NOT_DECLARED` is the signal.** A field absent because no schema names it is a
  schema-coverage fact, and is materially different from Rule 1's *missing* — which
  means "we looked and the document did not carry it". Do not collapse the two.
- **`schemaVersion` on the read model names the version that DECIDED.** If it moved
  and your field set shrank, an authoring write is why.
- `GET /v1/extraction-schemas` (ADMIN) reports which schema decides each type for
  your org and whether your org authored it. `fieldCount` there is the fastest way
  to see a shadow that dropped fields.
- **Some types declare nothing at all.** The triage-only types (`FAX_COVER_SHEET`,
  `EFILE_AUTHORIZATION`, `TAX_PREPARER_LETTER`, `LOAN_DISCLOSURE_PACKAGE`,
  `CLOSING_PACKAGE`; contract §5, marked `—`) have a rule pack and no schema. A
  document of one of them carries no fields by design — it is named and sorted, never
  extracted — so do not read its empty field list as a failed extraction.

A consumer that hard-codes an expected field list will read this as data loss. The
engine's answer is that the field list was always a per-org configuration; it merely
used to be one only a migration could change.

### 12. A document's page range is inferred — the export says how far to trust it

The splitter decides where one logical document ends and the next begins, and a page it could not
type joins whatever typed document precedes it. Two members of each `/export` document tell you
how much of that range was proven:

- **`boundaryProvenance`** — how the document's start was decided: `HUMAN`, `RULE`,
  `PACKAGE_START`, `TYPE_CHANGE` or `AI`. `null` for documents split before the engine recorded it.
- **`absorbedUntypedPages`** — pages that had no type and joined only as continuations. `null`
  where the engine did not count; `0` on an `UNKNOWN` document.

A document with absorbed pages is plausibly more than one document. Do not total, reconcile or
cite across its whole page range as though it were one form; surface it for review instead.

A tax package's document list also changed shape with `V46`: Schedules 1 and 2, Form 8962 and state
returns are now separate documents rather than pages inside `TAX_RETURN` or `SCHEDULE_B`. Anything
you captured or cached from a tax package before that release classifies those pages differently.
