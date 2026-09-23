# Data Model — Pragmatic DS Document Engine

**Status:** As-built through V16 · 2026-08-15
**Companion:** [`ARCHITECTURE.md`](ARCHITECTURE.md) · [`superpowers/specs/2026-07-30-parse-and-evidence-core-design.md`](superpowers/specs/2026-07-30-parse-and-evidence-core-design.md)

---

## Conventions

Every rule below is invariant across the schema.

| Convention | Rule |
|---|---|
| **Tenancy** | Every domain table carries `org_id uuid not null`. Hibernate `@TenantId` filters reads and stamps writes; Postgres RLS (`FORCE` + `WITH CHECK`, fail-closed) is the backstop. Load by `findByIdAndOrgId`, **never** `findById` — `@TenantId` does not filter PK lookups |
| **Keys** | `uuid` primary keys, except high-volume `text_span` and `audit_event` which use `bigint` identity |
| **Coordinates** | `x`, `y`, `width`, `height` are **PDF points, top-left origin, rotation-0**, stored `numeric(10,2)`. Never pixels |
| **Timestamps** | `timestamptz`. `created_at` on every table; `updated_at` where mutable |
| **Soft delete** | `deleted_at timestamptz null`. All reads filter `deleted_at is null` |
| **Append-only** | `classification_result`, `ai_interpretation`, `review_decision`, `audit_event`, `parser_output` are never updated or deleted |
| **Supersession** | Mutable-looking rows (`extracted_field`, `validation_finding`) use `is_current boolean` rather than in-place overwrite |
| **Versioning** | Anything produced by an algorithm records the producing version: `parser_version`, `rule_pack_version`, `extractor_version`, `schema_version` |
| **PII** | Values that may contain SSN or account numbers are stored in `NpiCipher`-encrypted columns and masked at every read boundary |

## 1. Tenancy and identity

### `tenant`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `name` | text not null | |
| `status` | text not null | `ACTIVE` · `SUSPENDED` |
| `created_at`, `updated_at` | timestamptz | |

### `app_user`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null → `tenant` | |
| `external_subject` | text not null | OIDC `sub` |
| `email` | text not null | |
| `display_name` | text | |
| `role` | text not null | `ADMIN` · `PROCESSOR` · `REVIEWER` · `READONLY` |
| `status` | text not null | |
| `created_at`, `updated_at` | timestamptz | |

Unique: `(org_id, external_subject)`, `(org_id, lower(email))`

### `api_key`

Machine access for host-app, rag-brain, and other apps.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `name` | text not null | |
| `key_hash` | text not null | Argon2id. The plaintext key is shown once, never stored |
| `scopes` | text[] not null | e.g. `package:write`, `package:read`, `export:read`. **Scopes only — never roles** |
| `last_used_at`, `expires_at`, `revoked_at` | timestamptz | |

### `loan`

A lightweight reference. **The engine does not own loans** — host-app does. This exists only so
packages can be grouped and cross-document validation (Spec 4) has a subject.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `external_loan_id` | uuid null | host-app loan id |
| `loan_number` | text null | |
| `created_at` | timestamptz | |

Unique: `(org_id, external_loan_id)` where not null

## 2. Ingestion

### `document_package`

One upload session. The unit a user submits and reviews.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `loan_id` | uuid null → `loan` | |
| `name` | text | |
| `page_count` | int not null default 0 | Across all source files |
| `review_status` | text not null | `NOT_REVIEWED` · `IN_REVIEW` · `REVIEWED` |
| `created_by` | uuid → `app_user` | |
| `created_at`, `updated_at`, `deleted_at` | timestamptz | |
| `purge_after` | timestamptz null | Set on soft delete from the retention policy |

### `source_file`

One uploaded file.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `package_id` | uuid not null → `document_package` | |
| `ordinal` | int not null | Stable order within the package |
| `original_filename` | text not null | |
| `content_type` | text not null | **Sniffed from magic bytes, not the client's claim** |
| `declared_content_type` | text | What the client claimed — kept for audit |
| `size_bytes` | bigint not null | |
| `sha256` | char(64) not null | Duplicate detection |
| `storage_key_original` | text not null | |
| `storage_key_normalized` | text null | |
| `page_count` | int null | PDFs: `PdfProbe`. Images: `ImageProbe` — 1 for a JPEG/PNG, the frame count for a multi-page TIFF. Nullable only for rows written before images were probed |
| `is_encrypted` | boolean not null default false | Owner-password-only PDF: encrypted on disk, readable without a password. Provenance for the reviewer, never a rejection reason — a file needing a real *user* password is rejected at upload (`PASSWORD_PROTECTED`) and never gets a row |
| `malware_scan_status` | text not null | `PENDING` · `CLEAN` · `INFECTED` · `SKIPPED` |
| `uploaded_by` | uuid → `app_user` | |
| `created_at`, `deleted_at` | timestamptz | |

Unique: `(org_id, package_id, sha256)` — rejects an exact duplicate within a package
Index: `(org_id, sha256)` — surfaces a cross-package duplicate as a **warning**, not a rejection

## 3. Processing state

### `processing_job`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `package_id` | uuid not null → `document_package` | |
| `idempotency_key` | text not null | A replayed submit returns the existing job |
| `status` | text not null | See enum below |
| `current_stage` | text null | |
| `attempt` | int not null default 1 | |
| `parse_generation` | int not null default 1 | Stable machine-parse identity. Resume changes `attempt`; explicit regroup/re-extraction changes this value |
| `behavior_fingerprint` | char(64) null (V19) | SHA-256 of the canonical behavior document (engine release, parser adapter, worker `/version`, the org's winning packs and schemas, and the AI enrichment seam — its gates, budgets, `provider/model` and prompt content hashes) that this parse ACTUALLY EXECUTED under. Written once, at successful FINALIZING, by the run that produced the rows — never at job creation, because an admission-time value describes what the loaders would have said at upload while the pipeline runs asynchronously and from caches, and one such value can describe two different outputs. NULLED by the re-extract AND resume claims. NULL never matches a reuse probe, so pre-V19 jobs, stub parses, undescribable parses, and regrouped or resumed generations are never reuse sources |
| `created_by` | uuid → `app_user` | |
| `created_at`, `updated_at`, `started_at`, `finished_at` | timestamptz | |

Unique: `(org_id, idempotency_key)`

**`status`:** `UPLOADED` · `VALIDATING` · `NORMALIZING` · `RENDERING` · `TEXT_EXTRACTION` ·
`OCR_PROCESSING` · `PARSING` · `CLASSIFYING` · `SPLITTING` · `EXTRACTING` · `AI_EXTRACTION` · `FINALIZING` · `VALIDATING_DATA` ·
`AI_REVIEW` · `HUMAN_REVIEW_REQUIRED` · `COMPLETED` · `FAILED`

### `processing_stage`

The row that makes resume possible. Without it a retry reruns OCR on a 75-page package to fix a
classification bug.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `job_id` | uuid not null → `processing_job` | |
| `stage` | text not null | Same enum as `status` |
| `status` | text not null | `PENDING` · `RUNNING` · `SUCCEEDED` · `FAILED` · `SKIPPED` |
| `attempt` | int not null default 1 | |
| `skip_reason` | text null | e.g. `SPEC_4_NOT_IMPLEMENTED` for `VALIDATING_DATA` |
| `started_at`, `finished_at` | timestamptz | |
| `duration_ms` | bigint null | |
| `error_code` | text null | **From the PII-free taxonomy** |
| `error_detail` | jsonb null | **Non-sensitive parameters only. Never document content** |
| `worker_version` | text null | |
| `parser_versions` | jsonb null | Pinned library versions for this run |
| `output_digest` | char(64) null | |
| `created_at`, `updated_at` | timestamptz | |

Unique: `(job_id, stage, attempt)`
Index: `(org_id, job_id, stage)`

### `engine_result` — V15

An append-only descriptor for the exact canonical machine envelope produced by one successful
parse generation. The descriptor and successful `FINALIZING` stage commit in one database
transaction; the content-addressed envelope bytes live in blob storage.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | Tenant-stamped; ENABLE/FORCE RLS |
| `package_id` | uuid not null → `document_package` | The stored original's package |
| `processing_job_id` | uuid not null → `processing_job` | Must belong to the same org and package |
| `parse_generation` | int not null | Unique with `(org_id, processing_job_id)` |
| `materialized_job_attempt` | int not null | Attempt that committed the descriptor; not part of canonical envelope identity |
| `revision` | int not null | Package history ordinal, starting at 1 |
| `supersedes_result_id` | uuid null → `engine_result` | Null only for revision 1; otherwise the exact N−1 predecessor |
| `envelope_schema_version` | text not null | `1.0.0` |
| `canonicalization_version` | text not null | `DOCENGINE-C14N-1` |
| `canonical_media_type` | text not null | `application/vnd.pragmaticds.document-engine-result+json;version=1` |
| `source_set_sha256` | char(64) not null | Fingerprint of the ordered source set; no filenames |
| `provenance_sha256` | char(64) not null | Fingerprint of the materialized provenance object |
| `envelope_storage_key` | text not null unique | Internal only; V16 requires the exact tenant/digest-derived key and the API never exposes it |
| `envelope_sha256` | char(64) not null | SHA-256 of the exact canonical bytes; returned as the content ETag |
| `envelope_size_bytes` | bigint not null | Verified before every content response |
| `reuse_eligibility` | text not null | `PARSE_ONCE_CURRENT_PACKAGE`; never a cross-package cache claim. Upload-time parse-once reuse (V19) does NOT widen this: it is decided at upload by (org, source-set digest, behavior-fingerprint) equality against the package's CURRENT verified result, and serves the prior package itself |
| `created_at` | timestamptz | |

Unique: `(org_id, processing_job_id, parse_generation)`, `(org_id, package_id, revision)`, and
`envelope_storage_key`. Partial unique indexes enforce one root per package and one successor per
predecessor; a `SECURITY INVOKER` trigger enforces that each successor is exactly revision N−1.
The application role has SELECT, INSERT, and retention DELETE under tenant RLS, but no UPDATE.
V16 additionally binds every descriptor to
`org/{org_id}/engine-results/sha256/{envelope_sha256}.json`; the read path derives and verifies the
same key before touching common-root blob storage.

The current result is the descriptor for the package's one prototype processing job and its exact
`parse_generation`, backed by exactly one successful `FINALIZING` row whose `output_digest` matches
`envelope_sha256`. History is append-only. A GET never creates, repairs, or silently selects the
greatest revision.

## 4. Parsed content

### `page`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `source_file_id` | uuid not null → `source_file` | |
| `page_index` | int not null | 0-based within the source file |
| `package_page_index` | int not null | 0-based across the whole package — stable ordering for review |
| `width_pt`, `height_pt` | numeric(10,2) not null | Rotation-0 dimensions |
| `rotation` | int not null default 0 | Declared in the PDF: 0/90/180/270 |
| `detected_rotation` | int null | From Tesseract OSD |
| `osd_confidence` | numeric(5,4) null | |
| `text_layer` | text not null | `NATIVE` · `SCANNED` · `MIXED` · `NONE` |
| `is_blank` | boolean not null default false | |
| `blank_score` | numeric(5,4) null | Ink-density variance |
| `content_hash` | char(64) null | Normalized content hash — **duplicate-page detection** |
| `render_storage_key` | text null | Page image blob |
| `render_dpi` | int null | |
| `ocr_engine` | text null | Winning engine at page level |
| `ocr_fallback_reason` | text null | Which gate tripped: `G1_COVERAGE` … `G6_GEOMETRY` |
| `ocr_confidence_median` | numeric(5,4) null | |
| `extraction_confidence` | numeric(5,4) null | Page-level rollup |
| `duplicate_of_page_id` | uuid null → `page` | V5: set when another page in the package carries the same non-null `content_hash` (first occurrence wins; blank/no-span pages never match) |
| `layout_not_implemented` | jsonb null | V18: the worker's per-page `/v1/layout` `notImplemented` list, verbatim — the element types it did NOT look for. `[]` = full coverage; NULL = no declaration exists (parse predates the column, PARSING has not run or failed, or the worker omitted the key — a response without the key persists NULL, never an invented `[]`), coverage unknowable. Cleared and re-set by every PARSING attempt; a page absent from an attempt's layout responses stays NULL. Read by `GET /v1/pages/{id}/structure` `detectorCoverage` (`RAN` / `NOT_IMPLEMENTED` / `UNKNOWN`). Deliberately NOT backfilled: inventing `[]` would assert pixel coverage nobody declared. |
| `created_at` | timestamptz | |

| `package_id` | uuid not null → `document_package` | Denormalized from `source_file` so package-wide page ordering is enforceable by a constraint |

Unique: `(source_file_id, page_index)`, `(package_id, package_page_index)`
Index: `(org_id, content_hash)` for duplicate-page lookup, `(org_id, package_id, package_page_index)`

`package_id` is denormalized onto `page` deliberately. Without it, the uniqueness of
`package_page_index` across a package cannot be expressed as a database constraint — only as
application logic, which is exactly the kind of ordering invariant that silently breaks under
concurrent ingestion of multi-file packages.

### `text_span`

The atomic unit of traceable text. **The volume table** — roughly 30k–70k rows per 75-page package.

| Column | Type | Notes |
|---|---|---|
| `id` | bigint identity PK | |
| `org_id` | uuid not null | |
| `page_id` | uuid not null → `page` | |
| `ordinal` | int not null | Reading order within the page |
| `text` | text not null | |
| `x`, `y`, `width`, `height` | numeric(10,2) not null | Canonical space |
| `source` | text not null | `NATIVE` · `OCR` |
| `ocr_engine` | text null | `RAPIDOCR` · `TESSERACT`. **Per span, not per page** |
| `confidence` | numeric(5,4) not null | Per-word from OCR; `1.0` for native |
| `font_size` | numeric(6,2) null | |
| `font_name` | text null | |
| `created_at` | timestamptz | |

Index: `(page_id, ordinal)`, `(org_id, page_id)`
Partition-ready by `created_at` when volume warrants.

Recording `ocr_engine` per span is what keeps reconciliation honest. When a quality gate trips and
Tesseract output is merged region-by-region with RapidOCR output, every resulting span still names
the engine that produced it — so evidence never becomes untraceable.

**`(page_id, ordinal)` is deliberately not unique.** On a MIXED page the NATIVE block and the OCR
block each restart at ordinal 0. The stable read order is therefore `(source, ordinal)` — NATIVE
block then OCR block — and the total order, which the paginated L1 read
(`GET /v1/pages/{id}/spans`, ADMIN only) cursors over, is `(source, ordinal, id)`. `id` is the
tiebreak that makes the triple unique; ordinal alone cannot be a cursor, because resuming after it
either re-serves or skips the OCR block's first rows.

### `layout_element`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `page_id` | uuid not null → `page` | |
| `parent_element_id` | uuid null → `layout_element` | table → row → cell |
| `element_type` | text not null | See enum below |
| `ordinal` | int not null | Reading order |
| `x`, `y`, `width`, `height` | numeric(10,2) not null | |
| `text` | text null | Denormalized convenience |
| `confidence` | numeric(5,4) not null | |
| `detector` | text not null | `clustering` · `checkbox-cv` · `signature-cv` |
| `detector_version` | text not null | |
| `attributes` | jsonb | `{"row": 3, "col": 1}`, `{"checked": true, "fillRatio": 0.42}`, `{"inkFraction": 0.18}`, `{"level": 2}` |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | V5: added because `TenantScopedEntity` maps it uniformly (same trade as `source_file`) |

**`element_type`:** `PARAGRAPH` · `HEADER` · `TABLE` · `TABLE_ROW` · `TABLE_CELL` · `FORM_FIELD` ·
`CHECKBOX` · `SIGNATURE` · `IMAGE` · `LINE`

**As-built (Spec 3).** `CHECKBOX` and `SIGNATURE` are detected by the worker's CV detectors
(`detector` = `checkbox-cv` / `signature-cv`, `detector_version` = the worker semver). The engine
now ALWAYS attaches the original PDF to `/v1/layout`; the worker renders requested pages at
200 DPI internally (`DETECTOR_DPI`) and returns detection boxes through the canonical
`px_box_to_pt` / `unrotate_box` path. A checkbox element carries `attributes`
`{"checked": bool, "fillRatio": number}`, a signature element `{"inkFraction": number}`;
candidates below the 0.5 confidence floor are DROPPED, never guessed (design D6 — a bound field
then falls through its ladder and persists as missing). When no file part is attached, the page's
`notImplemented` still declares `["CHECKBOX", "SIGNATURE"]` — an empty result stays
distinguishable from "did not look"; with pixels available it is `[]`. Since V18 that declaration
is also persisted per page (`page.layout_not_implemented`) rather than surviving only in the raw
`parser_output` blob.

Index: `(org_id, page_id, ordinal)`; `(org_id, parent_element_id)` (V17 — child-of-element
resolution for the L2 read surface; V4 shipped the self-FK with no index behind it)

**Served (full capture, L2).** `GET /v1/pages/{id}/structure` (ADMIN only) is a read model over
this table exactly as persisted: tables as `(row, col)`-addressed cells (`rows`/`cols`/`ruled` from
the TABLE's own `attributes`), `PARAGRAPH`/`HEADER` as blocks, `CHECKBOX`/`SIGNATURE` as marks,
each with its member span ids. Nothing is re-detected at read time, so
`evidence[].layoutElementId` on `/fields` and in the canonical envelope resolves against these same
ids. `structureConfidence` is this table's `confidence` column; `textConfidence` is the minimum
over member spans' `text_span.confidence` and the two are never multiplied.

### `layout_element_span`

| Column | Type |
|---|---|
| `layout_element_id` | uuid → `layout_element` |
| `text_span_id` | bigint → `text_span` |
| `ordinal` | int |

PK: `(layout_element_id, text_span_id)`

### `parser_output`

Immutable raw stage output. Payloads are large, so they live in blob storage with only the digest in
the row.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `source_file_id` | uuid null → `source_file` | |
| `page_id` | uuid null → `page` | |
| `stage` | text not null | |
| `parser_name`, `parser_version` | text not null | |
| `payload_storage_key` | text not null | |
| `payload_sha256` | char(64) not null | |
| `created_at` | timestamptz | |

**Both** OCR engines' raw outputs persist here when fallback runs, regardless of which won
reconciliation. Layer 1 of the four value layers.

## 5. Classification and splitting

### `document_type`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid null | **null = global built-in** |
| `code` | text not null | `PAYSTUB` · `W2` · `BANK_STATEMENT` · `DRIVERS_LICENSE` · `MORTGAGE_STATEMENT` · `HOI_DECLARATION` · `PURCHASE_CONTRACT` · `TAX_RETURN` (V11) · `SCHEDULE_E` (V13) · `UNKNOWN` |
| `display_name` | text not null | |
| `category` | text | `INCOME` · `ASSET` · `IDENTITY` · `PROPERTY` · `LIABILITY` |
| `is_active` | boolean not null default true | |
| `created_at` | timestamptz | |

Unique: `(coalesce(org_id, '00000000-...'), code)`

Spec 1 populates all eight; only the first three plus `UNKNOWN` have classification rule packs.

**Shared-global RLS.** `document_type` and `classification_rule_pack` use per-command policies
instead of a single isolation policy: SELECT passes when `org_id IS NULL OR org_id =
current_org()` (every tenant reads the built-ins), but INSERT/UPDATE/DELETE require `org_id =
current_org()` — so no tenant can modify, delete, or hijack a global row (as-built in
`V6__classification.sql`, locked by `RlsCoverageIT`).

### `classification_rule_pack`

The versioned mortgage domain knowledge. **Loaded as data** — this is what stays private when the
engine is open-sourced.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid null | null = built-in |
| `document_type_code` | text not null | |
| `version` | text not null | semver |
| `definition` | jsonb not null | Weighted anchors: literal, regex, positional |
| `min_confidence` | numeric(5,4) not null | Below this → `UNKNOWN` |
| `is_active` | boolean not null | |
| `created_at` | timestamptz | |

Unique: `(coalesce(org_id,…), document_type_code, version)`

### `classification_result`

Append-only. One row per classification attempt, at page **or** logical-document level.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `subject_type` | text not null | `PAGE` · `LOGICAL_DOCUMENT` |
| `subject_id` | uuid not null | |
| `document_type_code` | text not null | |
| `confidence` | numeric(5,4) not null | |
| `method` | text not null | `RULE_ANCHOR` · `ML` · `LLM` · `HUMAN` |
| `rule_pack_version` | text null | |
| `evidence` | jsonb not null | **Matched anchors with their span ids and boxes** |
| `is_current` | boolean not null | |
| `created_by` | uuid null → `app_user` | null = system |
| `created_at` | timestamptz | |

Unique: `(org_id, subject_type, subject_id) where is_current` — append-only history, but at most
**one** current result per subject, enforced by the database rather than by application discipline.

Storing matched anchors as evidence is what lets a reviewer see *why* a page was called a paystub —
and fix the rule when it is wrong. A bare score would be unarguable.

### `logical_document`

The split result.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `package_id` | uuid not null → `document_package` | |
| `ordinal` | int not null | |
| `document_type_code` | text not null | `UNKNOWN` when below threshold |
| `classification_confidence` | numeric(5,4) null | **null on a human-shaped document** — a reviewer regroup (Spec 2) nulls it, because a human decided the grouping and a fabricated machine score would be a lie |
| `review_status` | text not null | `NOT_REVIEWED` · `IN_REVIEW` · `REVIEWED` |
| `reviewed_by` | uuid null → `app_user` | |
| `reviewed_at` | timestamptz null | |
| `created_at`, `updated_at` | timestamptz | |

### `logical_document_page`

A join table rather than a page range, so human regrouping can produce **non-contiguous** documents —
which real loan packages routinely need after a reviewer fixes a split.

| Column | Type |
|---|---|
| `org_id` | uuid not null → `tenant` |
| `logical_document_id` | uuid → `logical_document` |
| `page_id` | uuid → `page` |
| `ordinal` | int |

PK: `(logical_document_id, page_id)` · Unique: `(page_id)` — a page belongs to exactly one document

`org_id` is denormalised onto the join table so its RLS policy is self-contained — no join through
the parent inside a policy expression.

## 6. Extraction and evidence

### `extraction_schema`

Versioned per document type. Additive fields are a new row, not a code change.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid null | null = built-in |
| `document_type_code` | text not null | |
| `version` | text not null | semver — Spec 1 ships `paystub@1.0.0`, superseded by `1.1.0` (V35) then `1.2.0` (V36) |
| `definition` | jsonb not null | Field name, data type, required, normalizer, unit, anchor rules |
| `is_active` | boolean not null | |
| `created_at` | timestamptz | |

Unique: `(coalesce(org_id,…), document_type_code, version)`

Same shared-global RLS as `classification_rule_pack`: per-command policies — every tenant reads
the built-ins, writes touch only own-org rows, so a built-in schema is immutable from any tenant
session (as-built in `V7__extraction.sql`, locked by `RlsCoverageIT`).

**`paystub@1.0.0`** defines exactly ten fields: `borrowerName`, `employerName`, `payPeriodStart`,
`payPeriodEnd`, `payDate`, `payFrequency`, `currentGrossPay`, `ytdGrossPay`, `netPay`,
`federalWithholding`. Adding more is additive and needs no code (proven by test: an org-scoped
row adding a field extracts with zero code change). **`paystub@1.1.0`** (V35) supersedes it with
the SAME ten fields and six extra `LABEL_BELOW` rungs for the Oracle Cloud HCM layout, which
prints its captions above their values; the rungs are appended to each ladder, so the layouts
that already extracted keep winning on their own first rung. **`paystub@1.2.0`** (V36) repeats
the pattern for the payroll-bureau layout — a scanned stub whose spans arrive through OCR with
many captions FUSED into single tokens ("PayFrequency:", "TotalCurrentNet:") — appending seven
fused-tolerant rungs and leaving the gross-pay pair deliberately unpinned (the only captions over
those values repeat elsewhere on the page, and `LABEL_BELOW` binds a label's first page
occurrence). Each field
carries an ORDERED extractor ladder — the two grid fields try `TABLE_CLUSTER` first and fall back
to `ANCHOR_LABEL`, and each rung carries its own `strength` (the anchor-strength confidence
component is authored data, not code).

**Spec 3 (V11)** seeds seven more global schemas at `1.0.0` — `w2`, `bank_statement`,
`drivers_license`, `mortgage_statement`, `hoi_declaration`, `purchase_contract`, `tax_return` —
headline fields only (roughly 8–10 each, the paystub pattern; everything else is additive in later
versions). The set's seven `sensitive: true` fields (`employeeSsn`, `accountNumber`,
`licenseNumber`, `dateOfBirth`, `loanNumber`, `policyNumber`, `primarySsn`) are the first
production use of the masking boundary: the full value is extracted and stored, and the serializer
masks it on every read surface — no unmask path exists (design D3). Each of the seven is locked by
a masking IT asserting the mask on `/fields`, `/export` and `/history`.

**Spec 4 (V12)** ships `w2@1.1.0` and `tax_return@1.1.0` and retires their `1.0.0` predecessors.
A corpus sweep of thirteen filled official forms found the W-2 classifying at 1.00 and extracting
2 of 10 fields: every `w2@1.0.0` rung searched `LINE_RIGHT` while the form is a box grid. Nine W-2
rungs changed READING DIRECTION to `LABEL_BELOW` — same value patterns, same strengths, the real
printed caption as the label, and the previous rung retained beneath it so a flat W-2 still
extracts. `tax_return@1.1.0` moves `primarySsn` only: the 1040's numeric lines are genuinely flat,
and its name fields span two cells, where a one-cell read would return a confident partial rather
than a value. Retired versions are never deleted and never edited — `extracted_field.schema_id` is
a foreign key to the row that produced each stored field, so mutating a shipped version would
silently re-write the meaning of everything already extracted against it.

**Spec 5a (V13)** ships `schedule_e@1.0.0`, the first schema with a repeating-group dimension:
**twenty-five fields across all four parts** — eight ungrouped, five `COLUMN`-grouped on Part I's
`A`/`B`/`C`, and twelve `ROW`-grouped over Parts II, III and IV. Its money patterns admit a comma
group or cents and never a bare integer, because the form is dense with line numbers sitting on the
very lines the rungs read and an unbounded `\d+` would read a line number as an amount; the recorded
cost is that an amount under $1,000 printed without cents reports as missing rather than wrongly.
They also capture the **sign**, in every spelling a return prints it — accounting parentheses with
whitespace or a `$` inside them (`( 18,470 )`, `($18,470)`), and a leading minus adjacent or spaced —
because Schedule E line 21 is income *or (loss)* per property and the sign is the number's whole
meaning. The sign glyphs are part of the MATCH, so they are part of the displayed value and part of
its evidence box rather than cropped out beside it. A trailing minus is matched too but fails
normalization, so it reports as missing rather than as a positive number. Its entity-name pattern
(Parts II, III and IV) is whole-token bounded on both sides and admits leading digits, apostrophes
and mixed case, so a name it cannot fully cover comes back as nothing rather than as its tail; the
`entityName` normalizer scores what it captured instead of waving it through at certainty 1.
Line 22 is deliberately omitted: on a real form its value overlays a preprinted `( )` whose interior
is literal space glyphs, so the text layer yields interleaved characters no money pattern can match
in any direction — a tokenization defect in the parse layer, filed rather than papered over.

**Spec 5a (V13)** also re-authors the `TAX_RETURN` classification pack as `1.1.0` and retires
`1.0.0`. `schedule-form-1040` fired on a *lettered* schedule through the cross-reference a real
Schedule E prints in its own Part I instructions ("also enter this amount on Schedule 1 (Form 1040),
line 5"), scoring TAX_RETURN on a document that is not a 1040. That is the exclusivity lesson for
the third time: **a reference to a form is not evidence of being that form.** The `SCHEDULE_E` pack
at `1.0.0` is exclusive in both directions, and exactly one of its anchors declares
`startsDocument` — the page-1-only masthead subtitle — so a two-page form stays one document while
a second form starts a new one.

Because that one anchor carries the form boundary, **page 1 cannot qualify without it**: the rental
vocabulary (`Rents received`, `Fair Rental Days`) scores **zero** — it is salient on the form and
rules nothing out, which is V10's weight-is-exclusivity rule read strictly — so the remaining page-1
anchors sum to `3 + 2 = 5/10 = 0.50`, under the `0.60` bar. Without that, a page-1 rescan with a
cropped masthead classified `SCHEDULE_E` while `startsDocument` stayed false, and the splitter,
which cuts a same-type run only where a page claims a boundary, merged the borrower's second
Schedule E into the first — two forms' worth of properties in one `A`/`B`/`C` key set, silently. The
trade: such a page now reaches the reviewer unclassified instead. Page 2 is untouched at
`4 + 3 + 2 = 9/10 = 0.90`, which is what keeps a two-page form one run. Zero-weight anchors are
still matched and still recorded in the evidence a reviewer reads; they simply do not vote.

**Schedule E relative-line follow-up (V14)** leaves V13 immutable and supersedes only the global
extraction schema. `SCHEDULE_E@1.0.0` remains readable with its JSON unchanged but becomes inactive;
active `SCHEDULE_E@1.0.1` preserves every prior JSON element and the original co-linear
`incomeOrLoss` rung first, then appends one identical rung whose only addition is
`value.lineOffset = 2`. Omission and explicit zero mean the label's own line. A nonzero offset is
accepted only for `ANCHOR_LABEL` in a `COLUMN` group with `LINE` scope and occurrence `0`; the
engine selects that exact computed line and fails closed on an absent line, empty band, or anything
other than one match. Tenant schemas and the `SCHEDULE_E@1.0.0` classification pack are untouched.

### `extracted_field`

Layer 2 of the four value layers: the deterministic normalized result.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `logical_document_id` | uuid not null → `logical_document` | |
| `schema_id` | uuid not null → `extraction_schema` | |
| `field_name` | text not null | |
| `group_key` | text null | The repeating-group key **printed on the form** — `A`/`B`/`C` for a Schedule E property column, the row ordinal zero-padded to two digits for an entity table. Null for a field that does not repeat, which is every field before Spec 5a (V13) |
| `data_type` | text not null | `STRING` · `NUMBER` · `DATE` · `ENUM` · `MONEY` |
| `displayed_text` | text null | Exactly as it appears: `"$48,231.30"` |
| `raw_value` | text null | As parsed, before normalization |
| `normalized_text` | text null | Populated per `data_type` |
| `normalized_number` | numeric(18,4) null | |
| `normalized_date` | date null | |
| `normalized_json` | jsonb null | Composite values (addresses) |
| `extraction_method` | text not null | `ANCHOR_LABEL` · `TABLE_CLUSTER` · `REGEX` · `FORM_FIELD` · `OCR_LINE` · `LLM` · `HUMAN` · `NONE` · `CHECKBOX_STATE` · `SIGNATURE_PRESENCE` (CHECK widened in V11) · `LABEL_BELOW` (widened again in V12) · `ROW_CELL` (widened again in V13) · `LABEL_ABOVE` (widened again in V40) · `DERIVED` (widened again in V54) |
| `extractor_version` | text not null | |
| `confidence` | numeric(5,4) not null | |
| `confidence_components` | jsonb null | `{spanConfidence, anchorStrength, normalizerCertainty}` — the formula's inputs stored separately so the score is auditable and tunable |
| `validation_status` | text not null | `NOT_VALIDATED` · `VALID` · `WARNING` · `ERROR` · `UNABLE_TO_VALIDATE` · `MANUAL_REVIEW_REQUIRED` |
| `review_status` | text not null | `NOT_REVIEWED` · `CONFIRMED` · `CORRECTED` · `REJECTED` |
| `is_sensitive` | boolean not null default false | Drives masking at read boundaries |
| `is_current` | boolean not null default true | |
| `created_at`, `updated_at` | timestamptz | |

Unique: `(org_id, logical_document_id, field_name, coalesce(group_key, '')) where is_current` —
enforced by the database (`extracted_field_one_current`, rebuilt under the same name in V13), same
rule as `classification_result`. `coalesce(group_key, '')` preserves the old rule EXACTLY for every
ungrouped row, which is the migration's central claim and is tested rather than assumed
(`UngroupedFieldEquivalenceIT`, against two goldens recorded pre-migration).
`logical_document_id` cascades on delete: a re-split invalidates extraction wholesale (human
decisions are Phase 7 `review_decision` rows, which will not hang off this table). `NONE` is the
method recorded when no extractor rung fired — confidence = spanConfidence(min over value spans)
× anchorStrength(the winning rung's authored strength) × normalizerCertainty.

A field that could not be found still gets a row — with no evidence, `confidence = 0`, and
`validation_status = MANUAL_REVIEW_REQUIRED`. A missing field is a **result**, not an absence.

**A field may repeat within one document (Spec 5a).** `extracted_field` enforced one value per field
name per document, which was true and sufficient while a paystub had one net pay. Schedule E breaks
it three ways at once: Part I lists up to three rental properties in side-by-side columns, Parts
II–IV are open-ended entity tables, and a borrower with more than three properties files more than
one form. `group_key` is the dimension that makes repetition expressible, and it is the value
**printed on the form** — `A`/`B`/`C`, or the row's zero-padded ordinal where the form prints no key
— never a synthetic index, so a reviewer reading "property B, rents received" can find it on the
page. Two geometries share the one concept: a **COLUMN** group locates each key from the printed
column headers and reads the value inside that key's x-band; a **ROW** group walks the rows of a
region bounded by start and end anchors, one occurrence per row, with a required `maxRows` (1..99,
enforced by the loader) so an unbounded region cannot emit hundreds of occurrences and so the
zero-padded key never needs a third digit. Every occurrence writes its own VALUE and LABEL evidence
rows — `field_evidence` needed no change, since it hangs off `extracted_field_id` — and keeps the
same three `confidence_components` keys. **A missing occurrence is a missing field, per
occurrence**: an empty column C persists with confidence 0, method `NONE` and
`MANUAL_REVIEW_REQUIRED`, never absent and never a defaulted `0.00`, because a zeroed rental expense
silently changes a qualifying-income calculation. On the wire, `FieldView.groupKey`,
`ExportFieldView.groupKey` and `HistoryEntry.groupKey` carry the key, and both read paths sort by
field name then group key with a null key FIRST — written out explicitly rather than inherited from
Postgres' ASC NULLS LAST, because two read paths that disagree about the null position would
disagree silently. The export dimension is not decoration: an export field is identified by
`fieldName` alone, and three occurrences under one name would let a name-keyed consumer keep one of
three.

**`LABEL_BELOW` reads box grids (Spec 4).** `ANCHOR_LABEL` looks to the RIGHT of its label on the
same visual line. Real government and lender forms are box grids: the caption captions a cell and
the value sits on the next line inside that cell (measured on a filled IRS W-2: the value row is
+12.5pt below the caption row and x-overlaps it). `LABEL_BELOW` matches the label with the same
case-insensitive matcher `ANCHOR_LABEL` uses, then takes the value from spans on visual lines
below the label's bottom edge by at most `maxDropPt` (default 24.0) whose horizontal extent
overlaps the label's x-range by at least `cellOverlap` (default 0.5) of the narrower box, first
match in reading order winning. No match fails the RUNG — the ladder continues and the field ends
on the missing-field contract; the rung never guesses. `joinCells` (V47) names the ADJACENT captions
on the label's own row whose first value line is read together with the label's cell as one value —
a Form W-2's box e and a Form 1040's identity rows print a person's name split across "first name
and initial" | "Last name" — each looked for to the right of the label on its own row (never
page-wide, so a spouse row cannot borrow the taxpayer row's cell), derived with the same window,
drop and ownership test, and appended only when it is the same printed row; an empty joined cell
contributes nothing, and the value pattern still has to match the composed text whole. It writes VALUE and LABEL evidence exactly
as `ANCHOR_LABEL` does, so click-to-highlight needed no UI change, and it uses the same three
`confidence_components` keys. It deliberately does not depend on ruling detection: many lender
forms are boxes without drawn lines, and the worker skips rulings on rotated pages.

**`LABEL_ABOVE` reads tiles (V40).** `LABEL_BELOW`'s vertical mirror, for the layout a bank's
online activity print-out states its balances in: the amount in a larger face with its caption
directly BENEATH it, several such tiles across one row (measured on a real Chase checking
print-out: the amount's bottom edge 3.7pt above its caption's top edge, the next tile's caption
50pt to the right, that tile's own amount beginning 0.4pt inside this tile's window). The
horizontal cell is `LABEL_BELOW`'s exactly — from the caption's left edge to the next caption's
left edge on the caption row, a span admitted only when the majority of its OWN width lies inside
— which is what keeps the neighbouring tile's amount out. Two rules flip: a span is admitted when
its bottom edge lies at or above the caption's top edge by at most `maxRisePt` (default 24.0,
`maxDropPt`'s mirror and a separate schema key so an author never writes "drop" for a rung that
reads up), and the LAST of the cell's lines — the one nearest the caption — owns it; lines above
are never consulted, so an account caption printed over a tile can never answer for it. It is
deliberately not a negative `lineOffset` on `ANCHOR_LABEL`: that selector takes a whole page-global
visual line, and the line above a tile caption carries every tile's amount. Same evidence roles
and the same three `confidence_components` keys as `LABEL_BELOW`; `bank_statement@1.5.0` is its
first user (`endingBalance` from the "Present balance" tile), and `bank_statement@1.6.0` (V41) adds
the print-out's two month-to-date tiles as fields of their own — `monthToDateDeposits` and
`monthToDateWithdrawals`, never bound to the period totals, which they are not.

**`DERIVED` computes instead of reading (V54).** A field-level schema keyword,
`"derivation": {"plus": [...], "minus": [...]}`, names other ungrouped MONEY fields of the
same schema whose captured values are summed and subtracted once every rung on the field
itself has missed. It is computed from other captured fields; no evidence rows; the
arithmetic is in `raw_value`. `bank_statement@1.7.0`'s `totalWithdrawals` is its first user —
`beginningBalance + totalDeposits - endingBalance` — for the U.S. Bank and Chase dialects,
which print no withdrawals total at all, only categories (V21 forbids summing those).

**No BOOLEAN data type exists — signed-state rides `ENUM` (Spec 3).** The design (D5) sketched
signature presence as a boolean value; `data_type` has no `BOOLEAN` arm and Spec 3 chose not to add
one, because widening the type roster would touch every normalizer, serializer and UI branch to
express something the existing `ENUM` arm already expresses. So the detector-backed fields use the
arms that are there: `purchase_contract@1.0.0`'s `buyerSigned`/`sellerSigned` are `ENUM` with raw
value `SIGNED`/`UNSIGNED` via `SIGNATURE_PRESENCE` — `UNSIGNED` is a legitimate extracted value
whose evidence is LABEL-role only (the one sanctioned empty-VALUE-evidence case: the region anchor
was found, no signature element sat in the window), while a missing region anchor is the ordinary
missing-field contract, so "unsigned" and "could not find the signature block" stay distinct.
`tax_return@1.0.0`'s `filingStatus` is `ENUM` fed by whichever checkbox is checked via
`CHECKBOX_STATE` (`SINGLE`, `MARRIED_FILING_JOINTLY`, `MARRIED_FILING_SEPARATELY`,
`HEAD_OF_HOUSEHOLD`, `QUALIFYING_SURVIVING_SPOUSE`); zero or more-than-one checked boxes fail the
rung, so the field lands on the missing-field contract rather than a guess. A box counts as checked
when the detector read it so OR a printed single-character mark glyph (X, x, ✓, ✔, ✗, ✘, ☒) has its
center inside the box (V47: preparer software prints the mark as text, and the pixel fill of a thin
"X" sat under the detector's threshold on a real return). Detector confidence
occupies the `spanConfidence` slot of `confidence_components` — the JSON keys remain exactly the
V7 three.

### `field_evidence`

**The traceability spine.** The table the entire system exists to make possible.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `extracted_field_id` | uuid not null → `extracted_field` | ON DELETE CASCADE |
| `page_id` | uuid not null → `page` | ON DELETE CASCADE |
| `layout_element_id` | uuid null → `layout_element` | ON DELETE **SET NULL** — a reparse nulls the reference, never the box |
| `text_span_id` | bigint null → `text_span` | ON DELETE **SET NULL** — same |
| `x`, `y`, `width`, `height` | numeric(10,2) not null | **Denormalized deliberately** |
| `role` | text not null | `VALUE` · `LABEL` · `CONTEXT` |
| `ordinal` | int not null | |
| `created_at` | timestamptz | |

Two decisions worth stating plainly:

**Boxes are denormalized.** A reparse regenerates spans and layout elements. The box a human already
reviewed must not silently move underneath them, so the coordinates live here directly, not only by
reference.

**The label is evidence.** `$48,231.30` alone proves nothing. *"YTD Gross"* to its left is the entire
reason it was read as year-to-date gross pay. A `LABEL`-role row records that, and the reviewer sees
both highlights.

Full chain:
`extracted_field → field_evidence → page → source_file → document_package → original bytes`

## 7. Validation, AI, and review

### `validation_finding` — Spec 4

Schema exists now so Spec 4 adds rules, not migrations.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `package_id` | uuid not null | |
| `logical_document_id` | uuid null | |
| `rule_code`, `rule_version` | text not null | |
| `severity` | text not null | `VALID` · `WARNING` · `ERROR` · `UNABLE_TO_VALIDATE` · `MANUAL_REVIEW_REQUIRED` |
| `message_template` | text not null | **PII-free template** |
| `message_params` | jsonb | **Non-sensitive parameters only** |
| `subject_field_ids` | uuid[] | Fields the rule spans — cross-document rules cite several |
| `is_current` | boolean not null | |
| `created_at` | timestamptz | |

### `ai_interpretation` — Spec 5

Layer 3. Append-only, and **never auto-merged into `extracted_field`.**

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `subject_type`, `subject_id` | text, uuid | `PAGE` · `LOGICAL_DOCUMENT` · `EXTRACTED_FIELD` |
| `provider`, `model`, `prompt_version` | text not null | |
| `interpretation` | jsonb not null | Envelope v2 shaped |
| `confidence` | numeric(5,4) null | |
| `tokens_in`, `tokens_out` | int | |
| `cost_usd` | numeric(10,6) | |
| `created_at` | timestamptz | |

The LLM proposes. It never overwrites. A user promoting an interpretation writes a `review_decision`,
which keeps the human accountable for the change rather than the model.

### `review_decision`

Layer 4. Append-only human decisions — the audit trail's substance.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `subject_type` | text not null | `EXTRACTED_FIELD` · `LOGICAL_DOCUMENT` · `PAGE_ASSIGNMENT` · `CLASSIFICATION` · `PAGE` |
| `subject_id` | uuid not null | |
| `action` | text not null | `CONFIRM` · `CORRECT` · `REJECT` · `RECLASSIFY` · `REGROUP` · `MARK_REVIEWED` · `OVERRIDE_VERDICT` |
| `previous_value` | jsonb null | |
| `new_value` | jsonb null | |
| `reason` | text null | |
| `decided_by` | uuid not null | The principal's user id (a human is always accountable) |
| `decided_at` | timestamptz not null | |

Index: `(org_id, subject_type, subject_id, decided_at desc)`

This drives the UI's *"original value → corrected value → user → timestamp"* display directly. No
projection table, no derived history that can drift from the truth.

**As-built (V8):** `decided_by` is `NOT NULL` but carries **no foreign key** to `app_user`, matching
the existing `created_by`/`reviewed_by` convention — `app_user` is not populated at runtime until a
live identity provider is wired, so a FK would reject every correction by the dev principal. The
`NOT NULL` is the load-bearing invariant (a SYSTEM/API_KEY actor with no user id cannot author a
decision); the FK is added when real users exist. `subject_id` is likewise polymorphic with no FK,
so the retention purge deletes a package's `review_decision` rows explicitly by subject id — they
carry corrected values (potential PII) and would otherwise be orphaned.

**As-built (V9, Spec 2 — regroup & verdict).** The reviewer regroup path (§ [Spec 2 design](superpowers/specs/2026-08-04-classification-at-scale-design.md)) adds two decision shapes with **no new table**:

- A **`REGROUP`** decision records one package-wide membership delta. Its `subject_type` is
  `LOGICAL_DOCUMENT` but its `subject_id` is the **package id** (the delta spans documents, so no
  single document owns it); `previous_value` is the prior grouping snapshot. Because it is keyed on
  the package, the purge's subject-id sweep seeds the set with the package id (not just page ∪
  document ∪ field) so the grouping delta is deleted, not orphaned, and `GET
  /v1/documents/{id}/history` widens its subject set with the document's package id so the row
  surfaces under each affected document. The grouping JSON is ids/codes only — never PII — so it is
  rendered unmasked.
- An **`OVERRIDE_VERDICT`** decision (new `action`, new `PAGE` `subject_type`, `subject_id` = the
  page) records a `NOT_BLANK`/`NOT_DUPLICATE` override, preserving the prior `is_blank` /
  `duplicate_of_page_id`. The purge sweep already collects page ids; history widens its subject set
  with the document's current page ids.

**Corrections orphan-with-history on re-extraction (option a).** A regroup re-extracts affected
documents wholesale — the old `extracted_field` rows are deleted and recreated with fresh ids. Any
prior field `CORRECT`/`CONFIRM`/`REJECT` decision keeps pointing at the deleted field id: it stays in
the append-only trail (visible in the field's own history and never purged out) but is **not**
re-applied to the newly extracted field. The reviewer re-asserts a correction against the new value
if it recurs. This is a deliberate choice over async re-keying: the audit trail stays honest about
what was decided against which extraction, and re-extraction never silently mutates a human decision.

### `audit_event`

Everything that happened, including reads of sensitive data.

| Column | Type | Notes |
|---|---|---|
| `id` | bigint identity PK | |
| `org_id` | uuid not null | |
| `actor_type` | text not null | `USER` · `SYSTEM` · `API_KEY` |
| `actor_id` | uuid null | |
| `action` | text not null | `PACKAGE_UPLOADED` · `DOCUMENT_VIEWED` · `FIELD_CORRECTED` · `EXPORT_GENERATED` · `SIGNED_URL_ISSUED` · `ENGINE_RESULT_ACCESSED` · `PAGE_SPANS_ACCESSED` … |
| `subject_type`, `subject_id` | text, uuid | |
| `request_id` | text | Correlates to structured logs |
| `ip_hash` | text null | **Keyed HMAC-SHA256, never raw** — null when no server secret is set |
| `metadata` | jsonb | **PII-free** — ids, counts, codes only |
| `occurred_at` | timestamptz not null | |

Index: `(org_id, occurred_at desc)`, `(org_id, subject_type, subject_id)`

**As-built (V8):** `ip_hash` is a **keyed** HMAC (server secret), not a bare digest — an unsalted
SHA-256 over the 2^32 IPv4 space is reversible with a precomputed table, so it stores null rather
than a false privacy guarantee when no secret is configured. `audit_event` is append-only and
**survives a retention purge** (the immutable trail of what happened, including that a package was
purged); the value-bearing `review_decision` rows do not.

### `retention_policy`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `document_category` | text null | null = default for the org |
| `retain_days` | int not null | Soft delete after this |
| `purge_after_days` | int not null | Permanent deletion after soft delete |
| `created_at`, `updated_at` | timestamptz | |

Soft delete sets `deleted_at` and `purge_after`. A scheduled job permanently deletes rows and blobs
past `purge_after`, including engine-result descriptors and blobs, and writes a count-only
`audit_event` for each purge. The current prototype follows the existing non-atomic external-blob
deletion contract; a durable deletion outbox and reconciliation worker are required before
production borrower use.

## 8. Reserved for Spec 6 — RAG indexing

Not created in Spec 1. Recorded here so the pgvector extension is enabled in `V1` and Spec 6 needs no
extension migration.

### `document_chunk`

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid not null | |
| `logical_document_id` | uuid not null | |
| `ordinal` | int not null | |
| `text` | text not null | |
| `page_ids` | uuid[] not null | Pages the chunk spans |
| `bbox_refs` | jsonb not null | **Boxes per page — a RAG citation resolves to a highlight** |
| `embedding` | `vector(1536)` | |
| `embedding_model` | text not null | |
| `created_at` | timestamptz | |

Index: `hnsw (embedding vector_cosine_ops)`

Carrying `page_ids` and `bbox_refs` on the chunk is what extends the core principle into RAG: a
retrieved passage in rag-brain resolves back to the exact page and box it came from, rather than
becoming an untraceable quotation.

## 9. Migration plan (Spec 1)

| Migration | Contents |
|---|---|
| `V1__extensions_and_tenancy.sql` | `pgcrypto`, `vector`; `tenant`, `app_user`, `api_key`, `loan`; RLS policies and the `app.current_org` GUC |
| `V2__ingestion.sql` | `document_package`, `source_file`, `retention_policy` |
| `V3__processing.sql` | `processing_job`, `processing_stage` |
| `V4__parsed_content.sql` | `page`, `text_span`, `layout_element`, `layout_element_span`, `parser_output` |
| `V5__page_signals.sql` | `page.duplicate_of_page_id`, blank/ink signals, `layout_element.updated_at` |
| `V6__classification.sql` | `document_type`, `classification_rule_pack`, `classification_result`, `logical_document`, `logical_document_page` — **plus seeds**: eight `document_type` rows and three built-in rule packs, inserted before RLS is enabled (FORCE RLS binds the owner, so seeding after would be unapplyable by a non-superuser owner) |
| `V7__extraction.sql` | `extraction_schema`, `extracted_field`, `field_evidence` — **plus** the `paystub@1.0.0` seed (before RLS, same owner-binding rule as V6) and the `logical_document_page.page_id` ON DELETE CASCADE retrofit |
| `V8__review_and_audit.sql` | `validation_finding`, `ai_interpretation`, `review_decision`, `audit_event` — all append-only, org-scoped, RLS-forced; no seeds. `validation_finding`/`ai_interpretation` are table-only until Spec 4/5. Also a soft-delete/purge read-exclusion pass across the read paths. |
| `V9__regroup_verdict.sql` | **Spec 2.** Constraint-only — no new table. Widens the `review_decision` CHECKs to admit a `PAGE` `subject_type` and an `OVERRIDE_VERDICT` `action` (the page-verdict override); the regroup path reuses the existing `logical_document_page` table and `REGROUP` action. Seeds nothing; RLS and `MigrationOwnershipIT` seed counts unchanged. |
| `V10__w2_pack_exclusivity.sql` | Data-only. Retires the W2 rule pack `1.0.0` and seeds `1.1.0` with anchor weights reassigned by **exclusivity, not salience** — a real Form 1040 page scored exactly `0.60` against `1.0.0`, and `PageClassifier` qualifies on `score >= minConfidence`. The correction is a new pack VERSION, never an edit of `1.0.0`, because `classification_result.rule_pack_version` names the pack that decided each stored row. Establishes the **late-seed RLS dance** every later global seed reuses: `classification_rule_pack` was FORCEd by V6 and its only INSERT policy is `WITH CHECK (org_id = current_org())` — `NULL = <anything>` is never TRUE, so no GUC value admits a global row and FORCE binds the migration owner too. `NO FORCE ROW LEVEL SECURITY` … seed … `FORCE` restored, all in one transaction; a forgotten restore fails `RlsCoverageIT`'s `pg_class` sweep. |
| `V11__document_types_at_scale.sql` | **Spec 3.** Widens `extracted_field_method_check` with `CHECKBOX_STATE`/`SIGNATURE_PRESENCE`; seeds the `TAX_RETURN` document type (`INCOME`), five rule packs (`DRIVERS_LICENSE`, `MORTGAGE_STATEMENT`, `HOI_DECLARATION`, `PURCHASE_CONTRACT`, `TAX_RETURN`, all `1.0.0`, `min_confidence` 0.6) and seven extraction schemas (`w2`, `bank_statement`, `drivers_license`, `mortgage_statement`, `hoi_declaration`, `purchase_contract`, `tax_return`, all `1.0.0`) — every seed `org_id NULL`, each of the three FORCEd tables' inserts wrapped in one V10 late-seed dance. `MigrationOwnershipIT` seed counts updated to match. |
| `V12__box_grid_extraction.sql` | **Spec 4.** §1 widens `extracted_field_method_check` with `LABEL_BELOW`. §2 supersedes two schemas inside ONE V10 late-seed dance: `w2@1.0.0` and `tax_return@1.0.0` are set `is_active = false` (retired, never deleted — `extracted_field.schema_id` references them) and `w2@1.1.0` / `tax_return@1.1.0` are seeded in their place, reading box-grid cells via the new rung. Global schema ROWS go 8 → 10; `MigrationOwnershipIT` and `RlsCoverageIT` counts updated, and the `RlsCoverageIT` census is now keyed by `TYPE@VERSION` so a supersession cannot look like a no-op. |
| `V13__repeating_groups.sql` | **Spec 5a.** §1 adds `extracted_field.group_key` and rebuilds `extracted_field_one_current` — same NAME, now over `(org_id, logical_document_id, field_name, coalesce(group_key, ''))`, so an ungrouped row's uniqueness rule is unchanged and every existing row needs no backfill. §2 widens `extracted_field_method_check` with `ROW_CELL`. §3 re-authors the `TAX_RETURN` rule pack as `1.1.0` (retiring `1.0.0`) and seeds `SCHEDULE_E@1.0.0` beside it, both inside ONE V10 late-seed dance on `classification_rule_pack`. §4 seeds the `SCHEDULE_E` document type (`INCOME`) in one dance on `document_type`. §5 seeds `schedule_e@1.0.0` in one dance on `extraction_schema`. Global counts: packs 9 → 11, types 9 → 10, schemas 10 → 11; `MigrationOwnershipIT` and `RlsCoverageIT` censuses updated, and the rebuilt index keeps its name because `RlsCoverageIT` asserts on the constraint-violation message. |
| `V14__schedule_e_relative_value_line.sql` | Data-only Schedule E follow-up. In one transactional late-seed RLS dance, preserves and retires global `SCHEDULE_E@1.0.0`, then creates active `1.0.1` by appending one offset-2 `incomeOrLoss` fallback after the unchanged original rung. Tenant schemas, document types, and classification packs are untouched. Global extraction-schema rows go 11 → 12; ownership, RLS, seed-count, generated-fixture, and API-version assertions cover the successor and preserved predecessor. |
| `V15__engine_result.sql` | Seed-free immutable-result substrate. Adds `processing_job.parse_generation`, admits `FINALIZING`, creates append-only `engine_result` descriptors with composite tenant/package/job FKs, a linear revision chain, FORCE RLS, no application UPDATE path, and content/provenance/source fingerprints. V1–V14 remain byte-identical. |
| `V16__engine_result_storage_key_binding.sql` | Completion-review hardening. Adds a validated CHECK that binds each descriptor key to its exact `org_id` and `envelope_sha256`; Java finalization and reads share the same derivation, and malformed descriptors fail before blob access or content-access audit. V1–V15 remain byte-identical. |

**RLS deployment requirement.** RLS only engages when the application connects as a **non-owner**
role. Flyway must own the schema and the app must connect as a seeded `app_user` role with a
separately provisioned password — otherwise RLS is bypassed at runtime and only the Hibernate
`@TenantId` layer is protecting tenant isolation. This is the same requirement host-app documents,
and the same trap.

**Cluster-level provisioning.** The migration owner is a plain role — NOSUPERUSER, NOCREATEROLE.
Two things must therefore be provisioned by an admin (e.g. the RDS master user) before the first
Flyway run: the `pgcrypto` and `vector` extensions (`vector` is not a trusted extension) and the
`docengine_app` role (roles are cluster-level). V1 uses `IF NOT EXISTS` guards for both, so it
no-ops over the pre-provisioned objects. `MigrationOwnershipIT` runs the entire chain as exactly
this topology.
