# Package fixtures

Synthetic Pragmatic DS Document Engine parses, allowlisted in `InstancePackageFixtureRegistry`. Nothing
here is a real borrower package: every name, amount, and identifier is invented.

The *values* are invented. The **vocabulary is not** — a fixture stands in for a genuine parse, so
every name it uses must be a name the engine can actually emit. Those names live in the engine's
seeded `extraction_schema` and `classification_rule_pack` rows, not in this repo, and they are the
only authority:

| Fixture content | Engine source (`pds-document-engine`) |
| --- | --- |
| PAYSTUB field names, dataTypes, `sensitive` | `V35__paystub_oracle_extraction.sql` — schema `1.1.0` |
| W2 field names, dataTypes, `sensitive` | `V12__box_grid_extraction.sql` — schema `1.1.0` |
| HOI_DECLARATION field names | `V11__document_types_at_scale.sql` — schema `1.0.0` |
| BANK_STATEMENT field names, dataTypes, `sensitive` | schema `1.6.0` (`V41__bank_statement_month_to_date.sql` adds the month-to-date fields). `fixture-bank-statement-single` is the BANK_STATEMENT document, page and source of rag-brain's engine-captured golden `combined-package.json` (engine `5ce1aae`), re-identified under the `5f1a0004` prefix with a synthetic source hash |
| `classification.evidence` anchor ids, weights, `targetScore` | `V34` (PAYSTUB `1.1.0`), `V10` (W2 `1.1.0`), `V11` (HOI_DECLARATION `1.0.0`), BANK_STATEMENT pack `1.1.0` (as captured in the golden) |
| `dataType` vocabulary — `STRING NUMBER DATE ENUM MONEY` | `V7__extraction.sql`, `extracted_field_data_type_check` |
| `method`, `validationStatus`, evidence `role` vocabularies | `V7__extraction.sql`, the matching CHECK constraints |
| `extractorVersion` — `engine/1.0.0` | `DefaultFieldExtractionEngine.VERSION` |
| `normalized` arm per normalizer (no normalizer ⇒ `text`) | `Normalizers.normalize` |
| Envelope member names and ordering | `EngineResultEnvelopeAssembler` |

Rules the engine's own assembler enforces, which a fixture must satisfy too:

- **A row per schema field.** A field the extractors did not find is persisted as a result, not an
  absence: `method` `NONE`, `status` `MISSING`, `normalized` null, no evidence. Omitting it
  describes a parse the engine cannot produce.
- **Fields sort by name** (code-point) within a document; evidence sorts `VALUE`, `LABEL`,
  `CONTEXT`, then ordinal.
- **Classification `score` is a ratio**, `min(1, matched weights / targetScore)`, and the page's
  `classification.confidence` is the winning pack's score.
- `generation.sourceSetSha256` must equal `InstancePackageFixtureRegistry.sourceSetDigest`, which
  derives it from the fixture's own source content hashes. Changing field content does not change
  it; changing a source's `contentSha256` or ordinal does.

Nothing in this repo looks a field up by name — the compatibility policy keys on document type and
field *status*, and `ParsedDocumentPromptRenderer` prints `name` verbatim. So a wrong name here
fails nothing locally; it misleads whoever reads a fixture to learn what the engine emits, and it
breaks any consumer that does key on names (host-app-web's Document Review parsing templates do).
That is why the table above is the check, not the test suite.
