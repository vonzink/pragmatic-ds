# Pragmatic DS Document Engine

Mortgage document parsing with **page-and-coordinate-level source evidence**.

> Every extracted value must be traceable back to the exact source page and location it came from.

This is not a general-purpose PDF parser. It ingests mortgage loan packages, splits them into logical
documents, extracts structured fields, and attaches a bounding box on a page to every value it
produces — so a reviewer, an underwriter, or a downstream analyzer can always ask *"where did that
number come from?"* and get an answer.

Output feeds three consumers: **Host App** (LOS/DMS), **RAG Brain** (pgvector), and other apps.

## Status

**Spec 1 complete (all 8 phases); Spec 2 complete; Spec 3 complete.** See [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

| Spec 1 — Parse & Evidence Core | | |
|---|---|---|
| 0 | Foundation, contracts, licence gate | ✅ |
| 1 | Ingestion and job orchestration | ✅ |
| 2 | Render, text, OCR, coordinate contract | ✅ |
| 3 | Page signals and layout | ✅ |
| 4 | Classification and package splitting | ✅ |
| 5 | Paystub extraction with evidence | ✅ |
| 6 | Review UI | ✅ |
| 7 | Correction, audit, hardening | ✅ |

| Spec 2 — Classification at Scale | | |
|---|---|---|
| — | Human regroup, page-move, verdict override, re-extraction | ✅ |

| Spec 3 — Document Types at Scale | | |
|---|---|---|
| — | Eight classifying types, seven new schemas, checkbox/signature detection | ✅ |

The pipeline renders, extracts, OCRs, parses layout, classifies, splits, and extracts fields with
evidence; a reviewer sees the document beside its data and clicks any value to highlight the box it
came from. **Phase 7 closed the loop and hardened it:** a reviewer can correct a value and the
original machine value stays permanently readable (corrections are append-only, the effective value
derived at read time), every change is audited, sensitive values are masked at the serializer
boundary, and the security posture is *proven* rather than asserted — real authentication and RBAC,
Postgres RLS engaged at runtime under a non-owner role (the app refuses to boot otherwise),
short-TTL signed URLs, and a retention purge that removes rows, blobs, and residual PII while
keeping the audit trail. Every claim in [Security](#security) is backed by a test.

**Spec 2 delivers the reviewer-facing override half.** Spec 1 refuses to guess — below-threshold
pages land `UNKNOWN`, blanks and duplicates land unassigned — which is correct but, until now, a
dead end when the machine grouped wrong. A reviewer can now move pages between documents, split,
merge, assign an unassigned page, create a document, and override a blank/duplicate verdict, through
one atomic regroup primitive (`POST /v1/packages/{id}/regroup`) plus a verdict endpoint (`POST
/v1/pages/{id}/verdict`), with a select-and-act UI over the existing document list. A human-shaped
document keeps the reviewer's declared type and its machine confidence goes **null** — the machine
never re-guesses what a human decided. Every regroup and verdict is an append-only `review_decision`
preserving the prior state, and affected documents re-extract wholesale through the existing stage
machine. Confidence / rule-pack tuning is explicitly deferred to a later spec (it needs a real-document
corpus to tune against). See [`docs/superpowers/specs/2026-08-04-classification-at-scale-design.md`](docs/superpowers/specs/2026-08-04-classification-at-scale-design.md).

**Spec 3 makes every seeded type real.** Five new rule packs (driver's license, mortgage statement,
HOI declaration, purchase contract, tax return — joining paystub, W-2, bank statement) and seven new
extraction schemas, plus a new `TAX_RETURN` type covering Form 1040 pages and schedules as one
document. The worker now detects checkboxes and signatures (OpenCV, persisted as the
`CHECKBOX`/`SIGNATURE` layout elements Spec 1 reserved) and two new extractor rungs read them by
anchor proximity: `filingStatus` comes from which box is checked, `buyerSigned`/`sellerSigned` from
whether ink sits in the signature window — `UNSIGNED` is a real answer, distinct from "signature
block not found", and a detection below the confidence floor is dropped rather than guessed, so the
field lands as missing. Sensitive fields (SSNs, license/account/loan/policy numbers, date of birth)
ship at full value and are masked unconditionally on every read surface; no unmask endpoint exists.
Pack separation is CI-enforced: every type's fixture pages score against every other type's pack
under the production qualification predicate, and any cross-qualification fails the build. See
[`docs/superpowers/specs/2026-08-08-document-types-design.md`](docs/superpowers/specs/2026-08-08-document-types-design.md).

The extraction underneath it: versioned extraction schemas (data, not code — the engine ships a
generic `paystub@1.0.0`; richer schemas load as org-scoped rows) define per-field extractor
ladders (TABLE_CLUSTER over recovered grids, ANCHOR_LABEL fallback, bare REGEX), every extracted
value carries VALUE-role evidence boxes back to the exact spans it came from plus the LABEL-role
boxes that justified reading it that way, and a field that cannot be found is persisted as a
result (confidence 0, `MANUAL_REVIEW_REQUIRED`) rather than silently absent. Confidence is a
product of three separately-stored components — span OCR confidence, authored anchor strength,
normalizer certainty — so a score is always auditable. Adding a field is a schema-version bump
with zero code change, proven by test. `GET /v1/documents/{id}/fields` serves the evidence chain;
`GET /v1/packages/{id}/export` serves the brief's export shape.
Benchmarks: [`docs/PARSER_EVALUATION.md`](docs/PARSER_EVALUATION.md) §10.

## Architecture in one picture

```
Browser ──OIDC JWT──▶ ┌─────────────┐ ──bytes only──▶ ┌───────────────┐
                      │  engine-api │                 │ parser-worker │
Apps ──API key───────▶│  Java 21    │ ◀──JSON────────  │  Python 3.12  │
                      └──────┬──────┘                 │  no DB        │
                             ▼                        │  no creds     │
                   Postgres 16 + pgvector             │  no persist   │
                     (org_id + RLS)                   └───────────────┘
```

The worker has no database access, no storage credentials, and persists nothing. `engine-api` owns
every row of processing state, which is what makes retry and resume possible and keeps the worker a
replaceable pure function.

Full detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Prerequisites

JDK 21 · Python 3.12 · Docker Desktop · Node 22 (pinned in `.nvmrc`, for the review UI)

## Run locally

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| API | http://localhost:9090 |
| Swagger UI | http://localhost:9090/swagger-ui.html |
| Worker | http://localhost:9091/health |
| Worker versions | http://localhost:9091/version |
| Postgres | `localhost:6433` (db/user `docengine`) |

The review UI runs separately (it is a dev server, not a compose service):

```bash
cd ui && nvm use && npm ci && npm run dev
```

| Review UI | http://localhost:6173 |
|---|---|

Ports are offset so this stack coexists with host-app (5432) and rag-brain (6435).

### Backend only

```bash
docker compose up -d postgres
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

The `local` profile binds a fixed dev principal (dev org, ADMIN role) with no token, so you can
drive the API without an identity provider. It is allowlisted per profile — a profile that is not
`local`/`test` requires a real OIDC JWT and refuses to start as a database owner. See
[Security](#security).

## Quickstart: from clone to a parsed document

Everything below assumes `docker compose up --build` is running (API on 9090, worker on 9091,
Postgres on 6433). Under the `local` profile no auth header is needed. The fixtures are synthetic —
generated, never collected.

```bash
# 1. Upload a synthetic paystub. Returns {"packageId": "...", "jobId": "..."}.
curl -s -F "files=@fixtures/paystub_complete.pdf" http://localhost:9090/v1/packages

# 2. Poll the job until status is HUMAN_REVIEW_REQUIRED (render → OCR → classify → split → extract).
curl -s http://localhost:9090/v1/jobs/<jobId>

# 3. List the split logical documents (here, one PAYSTUB).
curl -s http://localhost:9090/v1/packages/<packageId>/documents

# 4. The extracted fields, each with a bounding box back to the source page.
curl -s http://localhost:9090/v1/documents/<documentId>/fields

# 4b. The same fields as deterministic Markdown — group tables, missing cells shown,
#     sensitive values masked, one flat appendix carrying the confidence components.
curl -s http://localhost:9090/v1/documents/<documentId>/fields.md

# 5. Correct a value — append-only. The original machine value stays readable; the change is audited.
curl -s -X PATCH http://localhost:9090/v1/documents/../fields/<fieldId> \
  -H 'Content-Type: application/json' \
  -d '{"action":"CORRECT","value":"8,888.88","reason":"typo in source"}'

# 6. The correction history: original value → corrected value → who → when.
curl -s http://localhost:9090/v1/documents/<documentId>/history

# 7. The consumer export (what an LOS ingests): fields, typed values, evidence boxes.
curl -s http://localhost:9090/v1/packages/<packageId>/export
```

Or open the review UI at http://localhost:6173, upload the same fixture, and click any field to see
its evidence box highlighted on the page.

### Scoring real documents (local corpus)

Synthetic fixtures gate CI; REAL documents gate pack merges. Drop local PDFs into `corpus/`
(gitignored — only its README is tracked; nothing there ever enters git or CI) and score them
against the running stack:

```bash
.venv/bin/python tools/corpus_score.py corpus/<file>.pdf
```

Per page: classified type, confidence, matched anchor ids. Per document: every extracted field
exactly as the API serves it (sensitive values arrive masked). Run every corpus document before
merging a pack or schema change — see [`corpus/README.md`](corpus/README.md).

### Worker only

```bash
python -m venv .venv && .venv/bin/pip install -r worker/requirements-dev.txt
.venv/bin/uvicorn pragmaticds_docengine_worker.api:app --port 9091 --app-dir worker/src
```

## Test

```bash
./gradlew build
```

```bash
.venv/bin/python -m pytest -q
```

That is the fast loop and it is fine for everything except the golden files, which pin a PDFium
raster's sha256 and the words an OCR engine returned. Those describe an *environment* as much as
they describe the code, so CI runs the worker suite inside the worker's own image and goldens are
refreshed there and nowhere else — `worker/tests/conftest.py` refuses `--update-goldens` anywhere
else rather than let a laptop's renderer be committed as the reference.

```bash
docker build --platform linux/amd64 --target test -t pds-worker-test worker/
```

```bash
docker run --rm --platform linux/amd64 -v "$PWD:/repo" pds-worker-test pytest -q
```

Java integration tests use Testcontainers and need Docker running. The build configures the Docker
socket and API version for Docker Desktop on macOS automatically — see the comments in
[`build.gradle.kts`](build.gradle.kts) if your setup differs.

### Licence gate

```bash
.venv/bin/python -m tools.license_gate_cli --format pip --report /tmp/pip-licences.json
```

```bash
./gradlew generateLicenseReport && .venv/bin/python -m tools.license_gate_cli --format gradle --report build/reports/dependency-license/index.json
```

### Fixture provenance

```bash
.venv/bin/python -c "from pathlib import Path; from tools.fixture_provenance import check_fixture_provenance; r = check_fixture_provenance(Path('fixtures')); print(r.ok, r.problems)"
```

## Two rules that shape everything

**Coordinates are PDF points, top-left origin, at rotation-0 — never pixels.** Pixel coordinates
become silently wrong the moment render DPI changes, and every stored evidence box is corrupt with no
error raised anywhere. The worker normalises pdfplumber, pypdfium2, and OCR output into this one
space at its boundary.

**Corrections are append-only.** Four value layers are stored separately and never overwritten: raw
parser output, deterministic normalisation, LLM interpretation, human decision. The effective value
is derived at read time. This is what keeps *"what did the parser originally say?"* permanently
answerable, which is the difference between an audit trail and decoration.

## Parser stack

| Role | Library | Licence |
|---|---|---|
| Render | pypdfium2 | Apache-2.0 / BSD-3 |
| Native text + boxes | pdfplumber | MIT |
| PDF operations | pypdf | BSD-3 |
| OCR primary | RapidOCR (PP-OCR on ONNX Runtime) | Apache-2.0 |
| OCR fallback + orientation | Tesseract 5 | Apache-2.0 |
| Image ops | opencv-python-headless, Pillow, NumPy | Apache-2.0 / MIT-CMU / BSD-3 |

RapidOCR is primary; Tesseract runs only when a deterministic quality gate trips, and supplies the
orientation detection RapidOCR lacks. PyMuPDF, poppler, Surya, and Marker are excluded on licence.
PaddleOCR is deferred on packaging (no linux/aarch64 wheels) and retained as a GPU adapter.

Reasoning and the benchmark protocol: [`docs/PARSER_EVALUATION.md`](docs/PARSER_EVALUATION.md).

## Security

Each item below is shipped and covered by a test. `docs/ARCHITECTURE.md` §14 is the authoritative
list and separates what is code from what is deployment configuration.

- **Tenant isolation, two layers.** `org_id` on every row with Hibernate `@TenantId`, plus Postgres
  RLS engaged at runtime — the datasource is wrapped so every pooled connection is stamped with the
  caller's org, proven to block cross-tenant reads as a non-owner role and proven not to leak across
  the pool. Structurally enforced (`RlsCoverageIT`) and proven to migrate as a non-owner
  (`MigrationOwnershipIT`).
- **Authentication + RBAC.** An OIDC JWT resource-server chain resolves the `org_id` claim and the
  `app_user` row to a principal outside `local`/`test`; a dev principal inside them. A central role
  matrix (READONLY ⊂ PROCESSOR ⊂ REVIEWER ⊂ ADMIN). Cross-tenant ids answer 404, never 403.
- **Append-only corrections + audit.** A correction never overwrites the machine value; it appends a
  `review_decision` and the effective value is derived at read time, so *"what did the parser
  originally say?"* stays answerable. Every mutation writes an append-only `audit_event` (actor,
  PII-free metadata, keyed-HMAC IP hash).
- **PII masking at the serializer layer**, with an ArchUnit rule that makes a response DTO
  structurally unable to emit an unmasked sensitive value; a log-capture test proves no borrower
  value reaches the logs.
- **Lifecycle.** Short-TTL signed download URLs (HMAC, org-bound, expiry-checked); soft delete then a
  scheduled purge that removes rows, blobs, *and* PII-bearing correction rows while keeping the audit
  trail; a tombstoned package is 404 everywhere.
- **LLM egress off by default** (no adapter wired) · CI secret scanning, licence gating across all
  three ecosystems, fixture provenance.

The boot-time assertion below is the enforcement half of the deployment requirement.

> ⚠️ **Deployment requirement.** Outside `local`/`test` the application **refuses to start** unless
> it connects as a **non-owner, non-superuser, non-BYPASSRLS** database role, with Flyway as the
> owner. If it connected as the schema owner, RLS would be silently bypassed and only the
> application layer would protect tenant isolation — a failure that returns correct-looking results.
> `V1__extensions_and_tenancy.sql` creates `docengine_app` (NOLOGIN) for this purpose; local dev runs
> as the owner deliberately (RLS dormant, `@TenantId` isolating, assertion warn-only).
>
> The Flyway owner is a plain role, so an admin (e.g. the RDS master user) must pre-create the
> `pgcrypto` and `vector` extensions and the `docengine_app` role before the first migration —
> V1's `IF NOT EXISTS` guards then no-op. `MigrationOwnershipIT` proves the whole chain applies
> under exactly this topology. A deployment must also set per-environment secrets
> (`DOCENGINE_WORKER_SECRET`, `DOCENGINE_SIGNED_URL_SECRET`, `DOCENGINE_AUDIT_IP_HASH_SECRET`) — each
> fails closed when unset rather than degrading to a weak default.

**This system is not certified or compliant with GLBA, SOC 2, HIPAA, or any other standard.**
Security features are not compliance. `docs/ARCHITECTURE.md` §14 lists the controls a formal program
would additionally require.

## Test data

Fixtures are **generated, never collected** — synthetic names, employers, and amounts, produced by a
committed script and tracked in `fixtures/MANIFEST.json`. CI fails if any file under `fixtures/` is
not accounted for. No real borrower document can enter this repository, enforced mechanically rather
than by policy.

## Documentation

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System overview, diagrams, trust boundaries, scaling, failure/retry |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | 24 entities, migrations, the evidence chain |
| [`docs/PARSER_EVALUATION.md`](docs/PARSER_EVALUATION.md) | Library evaluation, MVP and production stacks, benchmark protocol |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Eight phases with acceptance criteria and risks |
| [`docs/LICENSING.md`](docs/LICENSING.md) | Allowlist, denylist, licence elections, CI enforcement |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | The source design spec |

## Licence

Intended for release under **Apache-2.0**, with domain-specific classification rule packs and
extraction schemas remaining private — the architecture already loads those as versioned data rather
than code. *This is not yet confirmed; nothing is published until it is.* See
[`docs/LICENSING.md`](docs/LICENSING.md).
