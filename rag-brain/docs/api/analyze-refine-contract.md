# Income Analyze / Refine — Suite Contract

Both endpoints are S2S, gated by `X-Analyze-Api-Key` (the `AnalyzeApiKeyFilter` regex `/api/ai/[^/]+/(analyze|chat)(/.*)?` covers the `/refine` sub-path).

## 1. Draft — `POST /api/ai/{brain}/analyze/{slug}` (multipart)
Unchanged, except the response now enriches skips. Parts: `context` (JSON `AnalysisContext`) + repeated `docs` files.

`AnalysisContext.analystNotes[]` (optional) folds human input into the first pass:
`{ "docRef": string|null, "text": string, "source": string|null }`

Response `skippedDocs[]` is now: `{ "id", "fileName", "category", "reason" }` where
`category ∈ { OVER_DOC_CAP, OVER_SIZE_CAP, UNSUPPORTED_TYPE, UNREADABLE_PDF, OVER_PAGE_CAP }`.
Surface each with an "enter this document's figures" affordance → store as an `analystNote`.
When a document trips more than one condition, the reported category is the first one hit
(doc cap → unsupported type → unreadable → size cap → page cap), so don't switch on it for
anything beyond the message you show.

### Page selection (large tax returns)
Large PDFs are trimmed to whitelisted federal pages **before** the vision pass, so state returns,
worksheets and instructions never cost vision tokens. Each trimmed document is reported in
`filtered[]`:
`{ "id", "fileName", "pagesTotal", "pagesKept", "matchedForms": [string] }`
Render it as "2024_1040.pdf — kept 9 of 62 pages (1040, Schedule C)". A document absent from
`filtered[]` was either sent whole or never sent at all — check `skippedDocs[]` to tell the two
apart. `filtered[]` is populated on ERROR runs too, so the re-run affordance still works when a
run trims pages and then fails.

PDFs of **8 pages or fewer are never trimmed** (`min-pages`), so a standalone W-2 or paystub always
goes through whole — the W-2/paystub whitelist rules exist for pages inside a larger merged PDF.

`AnalysisContext.disablePageFilter` (optional boolean, default false) sends every page of every
PDF, bypassing selection — wire the "re-run without filtering" control to it.

Guarantees worth knowing: a page is dropped only when it has extractable text that matches no
whitelist pattern and is not a continuation of a kept page, so **scanned PDFs (no text layer) are
never trimmed**, a document matching nothing is sent whole, and selection never yields zero pages.
Because trimming runs before the caps, it also frees doc/page/byte-cap headroom. Which pages count
as federal lives in the pack's `page-selection.yaml` (profile `federal-income`) and is
editable without a code change.

## 2. Refine — `POST /api/ai/{brain}/analyze/{slug}/refine` (JSON)
Text-only regenerate (no document bytes; cheap — no vision pass). Body:
```json
{
  "context": {
    "loan": { "urlaIncome": { } },
    "analystNotes": [ { "docRef": "", "text": "", "source": "" } ]
  },
  "priorFindings": { },
  "priorReportMarkdown": "…",
  "transcript": [ { "role": "user", "content": "…" } ]
}
```
Response: same shape as analyze (`reportMarkdown` + `findings`), with `skippedDocs` empty and `pageCount` 0. On model/parse failure it returns `status: "ERROR"` with a `reason` (still HTTP 200, mirroring analyze).

## Suite flow
Analyze (vision, once) → chat → capture corrections and any skipped-doc figures as `analystNotes` →
"Regenerate report" calls **/refine** with prior findings + notes + transcript. Re-call **/analyze**
only when a document is added or replaced. "Finalize" is a suite-side status flag on the latest
refine output — same document at draft vs finalized maturity, no separate engine artifact.

## Model knob (ops)
Analyze and refine share one routing lane, defaulting to the answer-lane model (Haiku today). To
switch only this lane — e.g. to Sonnet for analyze/refine while chat and public Ask stay on Haiku —
set the brain_settings keys `analyze.provider` + `analyze.model` via the admin settings API. Unset =
unchanged (behaves exactly like today). A separate `refine.model` split is not implemented.

## 3. `income-v2` — deterministic calculations (envelope v2)

`income-v2` is a second, opt-in analyzer slug alongside the unchanged `income` (v1). Call it exactly
like any other analyzer — `POST /api/ai/{brain}/analyze/income-v2`, same multipart shape, same
`AnalyzeResponse` envelope. The difference is what `findings` contains: for `income-v2`, `findings`
is not a per-analyzer summary object — it **is the whole validated Analyzer Envelope v2**
(`envelopeVersion`, `analyzer`, `reportMarkdown`, `facts`, `assumptions`, `warnings`,
`recommendations`, `calculations[]`, `missingItems`, `citations`, `confidence`, `domain`), the same
shape `ai/analyzer-envelope-v2.schema.json` defines. `domain` is analyzer-specific and, for
`income-v2`, references ids rather than restating numbers: `domain.borrowers[].sources[].calcIds`,
`domain.totalCalcId`, `domain.reconciliationCalcId`, `domain.opportunities[]`, `domain.gaps[]`. To
get a headline number, resolve the id through `calculations[]` — never read a number the model wrote
directly into `domain` or `reportMarkdown` prose, because there isn't one; every dollar figure comes
from a calculation's `result`.

### `calculations[].result` — engine-populated, never model-authored

The model's job is to *request* a calculation (`id`, `name`, `method`, `inputs`); it must never emit
a `result` — if it does, the engine strips and overwrites it and logs a warning. After schema
validation, the engine runs every request through its own deterministic calc services (currently
`IncomeCalcService`: `income.monthly_from_annual.v1`, `income.monthly_from_rate.v1`,
`income.ytd_monthly_average.v1`, `income.total_monthly.v1`, `income.variance.v1` — the full v2
vocabulary, also documented in the `income-v2` prompt) and writes the outcome into `result`,
discriminated on `status`:
- **`COMPUTED`** — `{ "status": "COMPUTED", "value": number, "intermediates": {...}, "rounding": "HALF_UP,2dp" }`.
  `value` and every money-valued intermediate are `BigDecimal`, `RoundingMode.HALF_UP`, scale 2 —
  the same arithmetic contract throughout, computed server-side, never by the LLM.
- **`ERROR`** — `{ "status": "ERROR", "value": null, "error": "<message>" }`. Missing, invalid,
  negative, or out-of-range inputs fail the request this way; the engine never defaults a missing
  input to zero or silently drops it.

A calculation request whose `method` isn't one of the supported ids fails the same way an ordinary
schema violation does: the model gets one corrective retry with the validation errors (including
"unsupported method \<id\>; supported: ...") fed back into the prompt, and if the retry still fails
validation the whole run returns `status: "ERROR"` — an unsupported method id can never survive into
a response with a silently-failed calculation sitting in `calculations[]`.

### Chaining — `amountRefs` / `computedRef`

`income.total_monthly.v1` and `income.variance.v1` can take another calculation's output as an
input instead of (or alongside) a literal number:
- `income.total_monthly.v1` accepts `inputs.amountRefs: ["<id>", ...]` — each id resolves to that
  calculation's computed value and is summed together with any literal `inputs.amounts`.
- `income.variance.v1` accepts `inputs.computedRef: "<id>"` as an alternative to a literal
  `inputs.computed` (`inputs.stated` is never a ref target — it's always the claimed URLA figure).

Rules worth relying on:
- **Backward-only.** A ref may only point to a calculation earlier in `calculations[]`; a forward or
  self reference fails closed with a distinct error message rather than being silently ignored.
- **Fail-closed propagation.** A ref to a calculation that itself errored (or to an unknown id) fails
  the *dependent* calculation too — never a defaulted zero, so a failed source can never silently
  vanish from a total or a reconciliation.
- **Resolved inputs are visible.** The enriched `calculations[].inputs` you get back shows both the
  ref field (`amountRefs` / `computedRef`, unchanged) and the literal field it resolved into
  (`amounts` / `computed`) — the dependency is never erased in favor of just the number.
- On a **duplicate `id`** in the model's output (the schema doesn't enforce uniqueness), a ref
  resolves to the *earliest* occurrence.

### `{{calc:<id>}}` substitution in `reportMarkdown`

Wherever the model wants a computed number to appear in the report prose, it writes the literal
placeholder `{{calc:<id>}}` instead of a number. After calculations execute, the engine substitutes
every placeholder with its calculation's value (`result.value.toPlainString()` on `COMPUTED`, or
`[calculation failed: <id>]` on `ERROR`) in both the top-level `reportMarkdown` and the copy nested
inside `findings`. **A literal `{{calc:<id>}}` still present in the text you receive means the model
referenced a calculation `id` that doesn't exist anywhere in `calculations[]`** — treat any surviving
placeholder as a defect worth surfacing, not a formatting quirk; it means a number the loan officer
is reading is not backed by anything the engine computed.

## 4. Run manifest — `runId` and `analysis_runs`

Every analyze response, on **every analyzer slug** (v1 and v2 alike), now carries a `runId` —
`AnalyzeResponse.runId`, the primary key of the immutable `analysis_runs` manifest row the engine
writes for that run. `refine` does **not** produce a manifest row (no `runId` is written for refine
calls; refine remains a text-only regenerate with no document bytes, no vision pass, and no
calculation execution — see §2, unchanged) — the response DTO still has a `runId` field, it's just
absent/null there.

`analysis_runs` exists for audit/reproducibility: ids, hashes, calculation *method names*, counts,
tokens, cost, and status are always written, regardless of configuration. **Privacy default:
metadata only.** Findings content (the borrower-identifying part) is redacted unless the engine is
explicitly configured otherwise:
- `findings` (the full envelope/summary JSON) is stored only when
  `ragbrain.rag.analyze.persist-findings=true`.
- With that flag off, each `calc_audit[]` row is trimmed to `id`/`method`/`status`/`error` only —
  `name` (often model-authored free text that can carry a borrower name) and `inputs`/`value` (the
  borrower's actual income figures) are dropped.
- With the flag off, `docs[]`/`skipped[]` rows drop `fileName` (mortgage filenames routinely embed
  surnames, e.g. `Smith_John_W2_2025.pdf`); `id` (+ `sha256` for `docs[]`) still identifies the
  document without naming the borrower.
- Document bytes are never stored in either mode.

Don't build suite-side tooling that assumes `findings`, calculation `name`/`inputs`/`value`, or
document filenames are present in `analysis_runs` — they're opt-in, off by default, and the default
is what runs in production today.

## ⚠ Breaking change for the suite team — action required before this deploys

**If the suite keeps its own copy of `analyzer-envelope-v2.schema.json` and validates incoming
envelopes with `additionalProperties: false`, that copy MUST be updated to the version in this repo
(`src/main/resources/ai/analyzer-envelope-v2.schema.json`) BEFORE this change deploys.** The engine
now emits a `result` object on every `calculations[]` entry (§3 above). A stale suite-side schema
copy that doesn't know about `calculationRequest.result` will reject **every** `income-v2` envelope
outright under `additionalProperties: false`, not just fail to read the new field. Pull the current
schema file into the suite repo and re-validate against it before this ships.

Also note: the schema's top-level `description` field changed wording. It used to describe
calculations as executed by "the suite calc engine"; it now says calculations are "REQUESTS executed
by the ENGINE's deterministic calc services after validation (the model never computes)" — i.e. this
repo's `IncomeCalcService`/`CalculationExecutor`, not a suite-side calculator. If the suite has (or
was planning) its own calculation engine consuming these `calculations[]` requests, that ownership
question needs to be resolved with this team — the numbers in `result` are already final and
suite-side recomputation would be redundant at best and a second source of truth at worst.

## 5. `submission` — purchase contract review (envelope v2, `submission-domain-v1`)

`POST /api/ai/{brain}/analyze/submission` (multipart, same parts as §1). Reads the Submission
folder (contract, counters, addendums), resolves the effective terms, compares them to the
application, extracts key dates, and grounds guideline findings. **Compare-only:** nothing is
written back to the loan.

### New `context.loan` keys (all optional, all nullable)

| Key | Type | Meaning |
|---|---|---|
| `salesPrice` | number | Application sales price |
| `downPayment` | number | Application down payment |
| `propertyAddress` | string | One formatted line: street, city, state, postal code |
| `consummationDate` | string | ISO date; compared to the contract closing date |
| `sellerCredits` | number | Sum of seller concessions across the fees ledger; null when the ledger is empty |

A null renders as `not on application` and the matching conflict row comes back `NOT_COMPARABLE`.
Earnest money has no application field in v1 and is always `NOT_COMPARABLE`.

### `domain` shape

`domain.schemaVersion` is the literal `submission-domain-v1`; the engine validates the object
against `ai/submission-domain-v1.schema.json` (copy it into the suite beside the envelope schema).
Top-level members: `documents[]` (documentId, kind CONTRACT|COUNTER|ADDENDUM|OTHER, date, title,
signedBy), `effectiveTerms` (the resolved deal plus `setBy` term→documentId), `conflicts[]` (one
row per field in borrowers|propertyAddress|salesPrice|closingDate|loanAmount|downPayment|earnestMoney|sellerCredits;
status MATCH|CONFLICT|NOT_COMPARABLE; `severity` HIGH|MEDIUM|LOW only on CONFLICT; `factId` points
at the fact carrying the document citation), `keyDates[]` (one row per kind; a superseding
document replaces the row). Duplicate `conflicts[].field` or `keyDates[].kind` is a validation
error that routes into the normal retry-once-then-fail-closed flow.
