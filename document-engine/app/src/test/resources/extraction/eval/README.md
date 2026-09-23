# Deterministic extraction evaluation corpus

The measuring stick for the **deterministic** extraction path — rule packs and extraction schemas.
The gated AI dialects have their own harness at `app/src/test/resources/ai/eval/`; the two report
the same shape on purpose and are meant to converge once the AI path serves more than two dialects.

## Why this exists

Every document type's accuracy used to live in a bespoke IT — `W2ExtractionIT` 243 lines,
`TaxReturnExtractionIT` 532, `ScheduleEExtractionIT` 662. Those tests prove a type *works*. What
they cannot do is answer **"did adding the ninth document type make the third one worse"**, because
a pass/fail test publishes no number to compare against last week's.

This corpus publishes the numbers, per document type, on every build. A new document type costs one
small JSON file here instead of a new 300-line test class.

The per-type ITs stay. They assert *mechanism* — which rung fired, which evidence box, why the value
is trustworthy — far more deeply than a scorer should. This harness asserts *outcome*, across every
type at once.

## Ground truth is not authored here

A case file names a fixture and the document types it should split into. **The field expectations
come from that fixture's `fixtures/truth/<name>.json` `expectedFields`** — which `fixtures/generate.py`
emits by construction from its own draw calls, and which CI sha256-pins.

Copying those values into a second file would create a pair that can silently disagree, and the copy
would be the one nobody regenerates. So a case file carries only what truth cannot supply: which
fixture, which layout tree, and how the pages are expected to group into documents.

To pin a new field, add it to the fixture's `expectedFields` in `generate.py` and regenerate. Not here.

## Fixtures, never corpus

Cases name a fixture **name**. The format has no field for document bytes at all, so a real borrower
document has no route into this harness by construction rather than by reviewer vigilance. Findings
from a real document in `corpus/` become a synthetic fixture plus a pack or schema change — the same
rule `corpus/README.md` states, enforced here by the shape of the file.

## Case format

```json
{
  "id": "w2",
  "fixture": "w2_form",
  "layout": "NONE",
  "synthetic": true,
  "coverage": ["label-below", "box-grid"],
  "note": "why this case earns its place",
  "documents": [{"type": "W2"}]
}
```

- `layout` — `NONE`, `GRID`, or `GRID_RULED`. The grid rungs read the TABLE → ROW → CELL tree the
  worker emits; a fixture whose truth words carry no `"cell"` marker needs `NONE`. Today only the
  `paystub_complete` family carries cells.
- `documents` — expected logical documents in ordinal order. Add `"pages": [0, 1]` to a document in a
  multi-document fixture; omitted means every page, which is every case here today.

A field whose truth entry has `displayedText: null` and `method: "NONE"` inverts the expectation: the
fixture deliberately draws no such field, and capturing anything is a rung reaching past its anchor
and inventing a value (`paystub_missing_field`'s `payDate`).

## Metrics

Two headline numbers, defined the way issue #59 defines them so the build's figure and a `psql`
figure agree, and the finer metrics beneath them:

| Metric | What a drop means |
|---|---|
| `completeness` | filled ÷ expected-present fields. The document prints it and the engine captured nothing — the 1040 disease, per type, as a number that moves on every build. Fields the fixture deliberately omits are not in the denominator |
| `accuracy` | correct ÷ filled fields. Of what the engine did capture, less of it is right. An engine that captures nothing scores 100% here and 0% above, which is why the two are always printed side by side |
| `classificationAccuracy` | A rule pack stopped qualifying, or another pack started outscoring it |
| `fieldCaptureRate` | Fields went quiet — the Spec 4 W-2 failure, where 9 of 10 rungs searched the wrong direction |
| `valuePrecision` | Of the values produced, fewer are right |
| `valueRecall` | Of the values expected, fewer arrived. Diverges from precision exactly when the engine goes *quiet* rather than *wrong* |
| `normalizationAccuracy` | A normalizer regressed — money, date, or text |
| `methodAccuracy` | A different rung fired than the one that should have. Often the first sign of a value that is right today by luck |
| `evidenceCoverage` | A captured value carried no evidence row. This is a breach of the engine's one guiding principle, not a near miss |
| `schemaCoverage` | Reported, never gated: how much of what the engine produced this corpus actually checks. A schema field the fixture does not draw is a fixture gap to close deliberately |

## Running

Normal `test` runs it and writes two build artifacts under `app/build/reports/extraction-eval/`:
`summary.json` (every metric, overall and per type, with the raw counts and the names of every
missing, wrong and phantom field) and `summary.md` (one row per document type — cases, fields,
filled, correct, completeness, accuracy, the committed floors, and a verdict).

A regression fails the build naming the type, the metric, and the fields:

```
Extraction evaluation gate: FAIL (1 violation)
  - W2.completeness regressed below its committed floor: measured 0.6000, floor 0.99
  W2:
    missing  w2/document[0].employerEin#
    missing  w2/document[0].wagesTipsOtherComp#
    ...
```

The gate itself (`ExtractionEvalGate`) is pure and unit-tested (`ExtractionEvalGateTest`): raising
a floor above a measurement fails with exactly that text, so the failure shape cannot drift into a
bare "assertion failed".

Calibrate the floors — required once, and again whenever a genuine improvement lands:

```
./gradlew :app:test --tests '*ExtractionEvalIT*' -Ddocengine.eval.calibrate=true
cp app/build/reports/extraction-eval/baseline.json app/src/test/resources/extraction/eval/
```

Read the diff before committing. A floor that came out lower than expected is a defect the harness
just found, not a number to bless.

**The ratchet cuts both ways.** A metric that beats its floor by more than 5 points — `completeness`
and `accuracy` included — fails the build as STALE, because a floor that far below the measurement
would let the next regression hide inside the gap. So a PR that genuinely improves a type must
re-run with `-Ddocengine.eval.calibrate=true` and commit the raised `baseline.json` in the same PR.
That is not a chore to work around; it is the improvement being recorded.

## Real documents — the same harness, by hand

Synthetic fixtures are drawn to match the packs, so this corpus scores ~100% by construction. The
number that hurt on 2026-09-10 was 25–33% on real W-2s, 1099s and 1040s, and it was found with
`psql`. The same loader, scorer and gate run over real documents from the gitignored `corpus/eval/`
tree — manual-only, never in CI:

```
./gradlew corpusEval -PcorpusEval=true
```

To score a tree outside the repo — the gold-set mirror that `tools/gold.py` maintains — add
`-PcorpusDir=<path>`; `tools/gold.py score` does exactly this against `~/pds-gold-set/v1`.

Cases live at `corpus/eval/<TYPE>/<case>/case.json` + `truth.json` (the case shape above, with the
truth beside it instead of in `fixtures/truth`); `corpus/README.md` documents the tree and
`tools/corpus_truth.py` produces `truth.json` from the running worker. The report lands beside the
synthetic one as `corpus-summary.json` / `corpus-summary.md`, gated only when a
`corpus/eval/baseline.json` exists. The task refuses to run without the property, and refuses to run
at all while git tracks anything under `corpus/`.

## Adding a document type

1. Seed the type, rule pack and extraction schema in a Flyway migration.
2. Draw a synthetic fixture in `fixtures/generate.py`, including its `expectedFields`. Regenerate in
   the worker's test image (`fixtures/generate.py` header explains why).
3. Add a case file here and list it in `index.json`.
4. Calibrate and commit the new floors.

`ExtractionEvalIT.corpus_covers_every_type_with_a_seeded_extraction_schema` fails the build if step 3
is forgotten, so a type cannot land unmeasured.
