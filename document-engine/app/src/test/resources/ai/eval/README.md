# Synthetic bank-statement evaluation corpus

Every case in this directory is deliberately fictional. Names use `CASE ...`, banks use
`SYNTHETIC`, addresses use the invalid state `ZZ`, and dates are in 2099. Do not place borrower
documents or copied production text here.

Each case contains:

- `input.json`: page-ordered text spans, table text, page classifications, and coverage tags;
- `expected.json`: the hand-authored correct summary, complete transaction ledger, and checks;
- `outcomes.json`: expected evidence-anchor, reconciliation, review, and forbidden-value outcomes.

`goldenResponseFile` identifies the deterministic mocked provider response. Most cases intentionally
use `expected.json` as the perfect golden response. The prompt-injection case points to a separate
adversarial response so CI proves that a model-followed injected value is not auto-accepted.

Normal `test` runs the golden evaluation without network access and writes
`app/build/reports/ai-eval/summary.json`. A real-provider run is manual-only:

```text
./gradlew liveEval -PliveEval=true
```

The command additionally requires `DOCENGINE_AI_PROVIDER`, `DOCENGINE_AI_MODEL`, and that provider's
development credential/configuration. It never reads a corpus outside this synthetic resource tree.
