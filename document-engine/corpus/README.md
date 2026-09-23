# Local document corpus

Real (or realistically filled-in) loan documents used to validate classification rule packs
and extraction schemas against reality instead of against our own synthetic fixtures.

**Nothing in this directory except this README is ever committed.** `.gitignore` excludes
`corpus/*` and re-includes this file (`!corpus/README.md`) — the contents, not the directory,
because git cannot re-include a file whose parent directory is excluded. These files carry NPI
and must not enter git, CI, or any artifact.

## Why this exists

Rule packs and extraction schemas are authored against generated fixtures in `fixtures/`,
which are deterministic, provenance-tracked, and safe to commit — but they are also *our own
guess* at what a document looks like. A guess cannot find the defect where the guess is wrong.

On 2026-08-08 a single real scanned Form 1040 exposed a latent classification defect within
twenty minutes: the W2 rule pack's two heaviest anchors were not W-2-exclusive, and the 1040
scored exactly at the qualification threshold. It escaped misclassification only because OCR
dropped one word. That became V10 (`W2@1.1.0`, anchors reweighted by exclusivity). No synthetic
fixture would have found it, because we wrote the fixtures from the same assumption as the pack.

## What to put here

Filled-in documents — a blank form has nothing to extract. Both of these work:

- **Real documents.** Best signal: authentic layouts, real OCR noise, real scan skew.
- **Official blank forms filled with synthetic values.** Nearly as good, no privacy exposure,
  and you know the correct answers, which makes them assertable ground truth.

**Variety beats volume.** Four paystubs from four payroll providers teach the packs far more
than forty from one.

Rough priority:

| | Type | Why |
|---|---|---|
| 1 | Paystubs, several providers | The only type extracting today; provider format variance is the real test |
| 2 | Bank statements, several banks | Layouts differ wildly |
| 3 | W2s | Confirms the V10 exclusivity fix against reality |
| 4 | Tax returns (blank official + filled) | Classification against the authentic layout; extraction against known values |
| 5 | Scanned versions of any of the above | OCR quality is where synthetic fixtures mislead us most |

## How it is used

`tools/corpus_score.py` is not a printer that a human eyeballs — it is a verification harness.
It uploads a document to a running local stack, polls the job to completion, and reports per
page the classified type, confidence, and matched anchors; then per document, every extracted
field. When a `<basename>.answers.json` ground-truth key sits beside the PDF, each field line
carries a **verdict** instead of a bare value, and the run fails its exit code on the verdicts
that mean the extraction is wrong. Without a key present, the tool falls back to the old
capture-only report with a banner saying so — still useful for documents that don't have a key
yet, but no longer verified.

Start the stack from the repo root (`docker compose up -d --build`; API on 9090, dev auth, no
token needed), then:

    .venv/bin/python tools/corpus_score.py corpus/<file>.pdf [more.pdf ...] [--api URL] [--strict]

Several PDFs may be passed at once; each prints its own report, and a final roll-up line
summarizes verdict counts across every scored package.

- `--api` overrides the engine base URL (default `http://localhost:9090`).
- `--strict` also fails the exit code on `MISSING_EXPECTED` verdicts. Without `--strict`,
  `MISMATCH` and `UNMAPPED_CAPTURE` always fail the exit code (a wrong value or a phantom
  extraction is worse than a value the engine simply didn't produce yet); `MISSING_EXPECTED`
  only fails under `--strict`; `NOT_DECLARED` never fails the exit code, since it is a
  schema-coverage fact, not a defect.
- Matched anchors come from `GET /v1/packages/{id}/classification` as `packType:anchorId` —
  anchor IDs only, never matched text, because classification evidence never stores text. A
  pre-Spec-3 stack has no such route (404) and the report degrades to type+confidence per page.

**Verdict classes** (per field occurrence, against the answer key):

| Verdict | Meaning | Fails exit? |
|---|---|---|
| `MATCH` | Captured value equals its expectation under normalization (see below) | no |
| `MISMATCH` | Captured a wrong value | always |
| `UNMAPPED_CAPTURE` | A found value no key entry accounts for — the phantom-occurrence signature of the row-band defect | always |
| `MISSING_EXPECTED` | The key lists a value the engine did not return for a declared field | only under `--strict` |
| `NOT_DECLARED` | A key entry names a field the engine's extraction schema never declares — a schema-coverage fact, summarized in one line | never |
| `CAPTURED_UNEXPECTED` | The engine found a value where the key entry says blank | no |
| `NOT_IN_KEY` | A missing occurrence of a field the key does not cover at all — the only neutral class; it asserts nothing | no |
| `MASKED_SKIP` | The server masked a sensitive value (e.g. `•••-••-6789`); unverifiable, never counted as a mismatch | no |

**Field identity for mapped document types** (currently `SCHEDULE_E`, `W2`, `TAX_RETURN`) is an
explicit engine-field-name → answer-key-label table (`FIELD_LABEL_MAPS` in the script), not
fuzzy label matching — fuzzy bridging is exactly how a wrong value once sailed through exit 0.
Document types without a table (Schedules B/C/D/F and the K-1s, which have no extraction
schemas yet) fall back to normalized-name matching.

**Normalization rules** — deliberately in this order, and this is what decides what will and
will not compare equal:

1. **Money**, numerically with sign: `( 18,470 )` ≡ `-18470` ≡ `-18,470.00`. Parentheses are the
   accountant's negative; `$`, commas, and spaces are formatting.
2. **Dates**, ISO-normalized across common formats (`03/14/2016`, `March 14, 2016`, etc. all
   equal `2016-03-14`).
3. **Everything else**, whitespace-collapsed and case-**preserving** — a case difference in a
   name is a real difference until a human rules otherwise.
4. **Person names** (only on fields whose map entry is flagged `person=True`, never guessed from
   the value) additionally accept a spelled middle token for its initial — `Quinn` ≡ `Q.` ≡ `Q`,
   case-sensitive on the letter, applied per person on a joint name (`John Quinn Doe and Jane
   Marie Doe` ≡ `John Q. Doe and Jane M. Doe`). First and last tokens must still match exactly.
   Every relaxed match is annotated `middle-initial equivalence` in the report, never silent.

A map entry can also **shape the expectation itself** before comparison (`MapTarget` flags in
the script), annotated in the report so the shaping is never silent:

- **`composed_with`** — joins named sibling key entries into ONE expectation (e.g. the W-2 key
  splits box e into "Employee's first name and initial" + "Last name" while the engine reads
  the printed name as one field; the expectation becomes their space-joined value). Annotated
  `composed`; every consumed source entry leaves the missing/not-declared ledgers together.
- **`first_line`** — expects only the text before the first newline of a multi-line key value
  (e.g. the W-2 key holds box c, the employer's name/address/ZIP, as one multi-line string; the
  schema's `employerName` means just the company name on the first line). When that first line
  itself carries a comma-joined suffix (`Name, Inc.`-shaped), the text before the comma is
  admitted as an alternate rendering. Annotated `first-line`; the remainder is part of the
  consumed entry and never resurfaces as `MISSING_EXPECTED`.

**Privacy is unconditional** when a key is present: the displayed-text column is replaced by
the verdict; captured and expected values are never printed. A mismatch shows only value
lengths and a digits/letters shape hint (e.g. `999-99-9999` → `999-99-9999` shaped as
`999-99-9999`-style `9`s and punctuation) — never the value itself.

Before merging any pack or schema change, run every corpus document that has an answer key and
read the output.

## The `.answers.json` format

Every corpus PDF that participates in verification has a `<basename>.answers.json` sitting
beside it (e.g. `corpus/w2_2026_filled.pdf` → `corpus/w2_2026_filled.answers.json`). There is no
schema file for this format — it is derived from usage, and the script's `expected_entries()`
supports two layouts (see below). This section is that derivation, written down.

### Top-level metadata

A handful of top-level keys are bookkeeping about the key itself, not expectations, and the
script never treats them as fields to verify: `file`, `form`, `subject`, `extractionNotes`,
`verification`. Existing keys also carry ad hoc metadata beyond that set for human readers —
`formTitle`, `copy`, `sourceForm`, `derivations`, `blankByDesign`, `notes`, `variant`,
`employer`, and similar — which the script simply never reads. Add whatever documentation
metadata helps a future reader; only the reserved metadata names above are guaranteed to be
skipped as non-expectations, and any *other* top-level scalar (not an object or array) is
walked as an expectation (see `formYear`/`taxYear` below), so name free-form notes carefully or
nest them under an object.

### The `fields` layout (preferred, and required for mapped document types)

Every document type with a `FIELD_LABEL_MAPS` entry (`SCHEDULE_E`, `W2`, `TAX_RETURN`) is keyed
this way: a flat `fields` array, one entry per expected occurrence:

```json
{
  "file": "example_form.pdf",
  "form": "Example Form",
  "formYear": "2025",
  "subject": "Jordan A. Example",
  "fields": [
    {
      "label": "Wages, tips, other compensation",
      "line": "1",
      "acroField": "topmostSubform[0].f2_09[0]",
      "value": "104,288.80",
      "page": 0
    },
    {
      "label": "Rents received",
      "line": "3",
      "value": "31,800",
      "page": 0,
      "propertyColumn": "A"
    }
  ]
}
```

Per-entry shape:

- **`label`** (or `acroField` as a fallback name) — the field's identity. For a mapped document
  type this must equal, verbatim, a value on the right side of that type's `FIELD_LABEL_MAPS`
  table in `tools/corpus_score.py` — the IRS/form wording exactly as printed, not a
  paraphrase. A drifted label yields `UNMAPPED_CAPTURE`, loudly, never a silent pass.
  For an unmapped type it is matched by normalized name (case/punctuation-blind).
- **`value`** — the expectation, as a string. An empty string or `null` means "the key asserts
  this field is blank" (`CAPTURED_UNEXPECTED` if the engine finds anything there).
- **`line`**, **`acroField`**, **`page`** — documentation only (which printed line, which PDF
  AcroForm field, which physical page); the script does not use `line` for matching (a line
  number is the form's, not an occurrence key) and `acroField`/`page` are informational except
  that `page` shows up in `MISSING_EXPECTED` report lines.
- **An occurrence discriminator**, when the field repeats within the document — one of
  `propertyColumn`, `column`, `row`, `entityIndex`, `entity`, `occurrence`, `groupKey`. Most
  specific first; `propertyColumn` is what Schedule E's two-property layout uses (`"A"`/`"B"`).
  Zero-padded row ordinals (`"02"`) and column letters are normalized (`"02"` ≡ `"2"`,
  `"b"` ≡ `"B"`) so casing/padding in either the key or the capture doesn't matter.
- **`rendersAs`** — an accepted alternate rendering of `value` (e.g. a checkbox that extracts
  as a glyph rather than the word it means).

Top-level scalars that are not in the metadata set (e.g. `formYear`) are also expectations,
appended to the `fields` list — this is how `SCHEDULE_E`'s `taxYear` maps to the key's
`formYear` and `TAX_RETURN`'s `taxYear` maps to a bare top-level `taxYear` scalar.

### The generic (no `fields` list) layout

Document types with no extraction schema yet (Schedules B/C/D/F, the K-1s) can instead be keyed
by shape: the whole JSON object minus the metadata keys is walked leaf-by-leaf, and each leaf's
full dotted path becomes its expectation name (`borrower.name` matches an engine field named
`borrowerName` after normalization). A top-level array of objects is treated as a repeating
group: each element's own discriminator property (one of the `OCCURRENCE_KEYS` above) — or,
absent one, its 1-based index, matching a printed row ordinal — becomes the occurrence for
every leaf inside it. This is a fallback for schema-less types; a document type that gets a
`FIELD_LABEL_MAPS` table should use the `fields` layout so field identity is explicit rather
than name-normalized.

### Worked example

A minimal key for a fictional two-page form with one repeating table:

```json
{
  "file": "sample_form_filled.pdf",
  "form": "Sample Form",
  "formYear": "2025",
  "subject": "Taylor R. Example",
  "fields": [
    {
      "label": "Name(s) shown on return",
      "line": "",
      "value": "Taylor R. Example",
      "page": 0
    },
    {
      "label": "Total income",
      "line": "9",
      "value": "( 4,200 )",
      "page": 0
    },
    {
      "label": "Rents received",
      "line": "3",
      "value": "18,000",
      "page": 0,
      "propertyColumn": "A"
    },
    {
      "label": "Rents received",
      "line": "3",
      "value": "",
      "page": 0,
      "propertyColumn": "B"
    }
  ]
}
```

Here `Total income` will match a captured `-4200` or `-4,200.00` (money normalization), the
grouped `Rents received` entries are told apart by `propertyColumn`, and property B's blank
`value` means the key is asserting there is nothing to extract for that occurrence — any
captured value there is `CAPTURED_UNEXPECTED`.

## Adding a new corpus document

1. Obtain a filled document (real, with NPI, or an official blank form filled with synthetic
   values) per the priority table above.
2. Drop the PDF into `corpus/` — it is gitignored automatically; no action needed to keep it
   out of git.
3. Write `corpus/<basename>.answers.json` beside it:
   - If the document's type already has a `FIELD_LABEL_MAPS` entry (`SCHEDULE_E`, `W2`,
     `TAX_RETURN`), use the `fields` layout and copy each field's label verbatim from the
     table in `tools/corpus_score.py` (or from the form's own printed wording, then verify it
     matches the table) — do not paraphrase.
   - Otherwise use whichever layout is convenient (`fields` is usually still clearer); field
     identity will fall back to normalized-name matching.
   - Add an occurrence discriminator on any field that repeats.
   - Set metadata (`file`, `form`, `subject`, etc.) for your own and future readers' benefit.
4. Start the stack (`docker compose up -d --build`) and run:

       .venv/bin/python tools/corpus_score.py corpus/<basename>.pdf

   Read the report. A `MISMATCH` or `UNMAPPED_CAPTURE` means either the key or the engine is
   wrong — figure out which before treating the run as passing.
5. If the document surfaces a real defect, do not fix it by patching the corpus key to match
   bad output. Encode the finding as a **synthetic** regression fixture (via
   `fixtures/generate.py`) plus a pack or schema change, so the fix is provable in CI without
   the source document ever entering the repo.

## The `eval/` tree — the build's own harness, over real documents

`tools/corpus_score.py` verifies a document against a running stack. The build has a second
measuring stick, the deterministic extraction harness (`app/src/test/resources/extraction/eval/`),
which scores every synthetic fixture on every `./gradlew build` and publishes per-type
**completeness** (filled ÷ printed fields) and **accuracy** (correct ÷ filled) as
`app/build/reports/extraction-eval/summary.md`. Synthetic fixtures score ~100% by construction;
the number that matters is the same metric over real forms. `corpus/eval/` is where those cases
live, in the harness's own format, so the real number and the fixture number are one computation
on two inputs:

```
corpus/eval/
  W2/
    adp-2025-a/
      case.json     {"id": "adp-2025-a", "layout": "NONE", "synthetic": false,
                     "note": "...", "documents": [{"type": "W2"}]}
      truth.json    {"pages": [{"pageIndex": 0, "widthPt": 612, "heightPt": 792,
                                "contentRotation": 0, "words": [{"text","x","y","width","height"}, ...]}],
                     "expectedFields": [{"field": "wagesTipsOtherComp", "displayedText": "104,288.80",
                                         "normalized": {"number": "104288.80"}, "pageIndex": 0}, ...]}
  TAX_RETURN/
    ...
  baseline.json     optional — per-type floors in the committed baseline's shape; absent = report only
```

- The directory names the type; every document a case declares must be that type, or the loader
  refuses (a mislabelled directory would score one type under another's floor).
- `truth.json` carries the worker's words — what the engine would persist as `text_span` rows at
  upload — and the hand-labelled `expectedFields` using **engine field names** (the schema's, not
  the form's printed labels; `tools/corpus_score.py`'s `FIELD_LABEL_MAPS` is the bridge if you
  are converting an `.answers.json`). `displayedText: null` with `method: "NONE"` asserts the
  form prints no such field. Grouped fields add `"groupKey"`.
- `tools/corpus_truth.py corpus/<file>.pdf --type W2` writes `truth.json` (words from the running
  worker's `/v1/text`, `expectedFields` left for you) and a starter `case.json`. Re-running it
  keeps the labels you wrote.

Run it:

    ./gradlew corpusEval -PcorpusEval=true

To score a tree outside the repo — the gold-set mirror that `tools/gold.py` maintains — add
`-PcorpusDir=<path>`; `tools/gold.py score` does exactly this against `~/pds-gold-set/v1`.

The task is manual-only and mirrors `liveEval`: it never runs under `test` or `build`, refuses
without the property, and refuses outright while `git ls-files corpus` lists anything but this
README. It fails on zero cases rather than passing quietly. The report is
`app/build/reports/extraction-eval/corpus-summary.md`, beside the synthetic `summary.md`.
`-Ddocengine.eval.calibrate=true` writes a proposed `corpus-baseline.json` there; copy it to
`corpus/eval/baseline.json` to start gating the real numbers locally.

## Rules

- Never commit anything here but this file. `eval/` included — `case.json` and `truth.json` carry
  the borrower's words and values.
- Never copy a corpus document into `fixtures/` — that directory is generated by
  `fixtures/generate.py` and verified by a sha256 manifest in CI, and a real borrower document
  entering the repository is the one mistake this project has designed hardest against.
- Findings from corpus documents get encoded as **synthetic** regression fixtures plus a pack or
  schema change, so the fix is provable in CI without the source document.

## Gold set — real documents, labeled in the review UI

The field-accuracy gold set lives OUTSIDE this repo (`~/pds-gold-set`, mirrored to the private
bucket `pds-docengine-gold-set`) and is scored by the same harness as this corpus. It is managed
by `tools/gold.py`. The fast path is a chat session: `/gold-session W2` in Claude Code runs
`up`, `next`, `pages`, `fields`, shows one table per document, and after your one reply runs
`decide` and `done`. The slow path is the review UI: `add` a pool PDF, label it at the review link
it prints (Confirm / Correct / Reject each field, then Mark reviewed), then `export`. Either way,
`sync` and `score` finish the job. Design and rules:
`docs/superpowers/specs/2026-09-22-field-accuracy-gold-set-design.md` and
`docs/superpowers/specs/2026-09-22-gold-session-chat-loop-design.md`.
