# RAG Brain — Architecture & Capabilities Reference

How the platform works, end to end, so you can build against it as you add
features, brains, and instances. Grounded in the source at HEAD.

> Companion docs: **[TRAINING-PLAYBOOK.md](TRAINING-PLAYBOOK.md)** (the operational
> how-to for training a brain). This doc is the *architecture* reference.
> **`rag-brain` is a private repo** — it holds infra detail and internal corpus.

---

## 1. The model in one paragraph

One Spring Boot engine (Java 21) + one Postgres/pgvector database host **many
independent "brains."** A **brain** is a row in the `brains` table with its own
corpus, model config, personality, and feature flags. A **scope**
(`income · assets · title · credit · reo · insurance · submission`) is a folder-tag on documents *within* a
brain, not a separate brain. A request selects a brain by `?brain=<slug>` (admin) or
by URL path + token (public); everything is isolated by a `brain_id` FK. The engine
turns a question into a **grounded, cited, compliance-validated answer** through a
fixed pipeline, and refuses rather than guesses when evidence is thin.

**Prod today:** three brains — `mortgage` (S3 corpus, mortgage pack, used by the LOS
folder analyzers), `suite` (local pack corpus, used by the console Ask-AI), and
`generic` (the default/fallback brain).

---

## 2. How a question becomes an answer (the pipeline)

Everything converges on `AskService.ask(...)` — a deliberately non-transactional
orchestrator. Stages run in order; **any failure short-circuits to a canned refusal**,
and failed answers are never shown.

| # | Stage | Service | What it does |
|---|---|---|---|
| — | Persist turn | Conversation/Message repos | Saves the user turn; `sessionId` is bound to one `brainId` (no cross-brain/visitor reuse). |
| 0 | **Guardrail classify** | `QuestionClassifierService` | Regex rules from the pack's `classifier.yaml`. Non-educational categories **refuse before any spend** → canned answer (ELIGIBILITY→escalate, LEGAL/TAX/LIVE_RATES/FRAUD→their canned reply). |
| 0a | Plan | `AgenticRetrievalService.plan` | Builds Intent, RetrievalPlan, a rewritten query (vocabulary expansion), and a `calculationRequest` flag. |
| 0b | **Calc guardrail** | `IntentRouterService` | "calculate my payment / DTI / rate" → refuse (numeric-hallucination guard). Definitional "what is DTI?" still answers. |
| 0c | **Spend cap** | `SpendGuardService` | Refuse once the per-brain/global daily budget is met (opt-in; OFF by default). |
| 1 | **Retrieve (hybrid)** | `RetrievalService` | pgvector cosine + Postgres full-text, merged **0.65 / 0.35**, optional LLM rerank. Bounded agentic loop: initial → rewrite-retry (if insufficient) → gap-fill (if all hits share one doc). |
| 2 | **Sufficiency gate** | `RetrievalResult.sufficientEvidence()` | If evidence is insufficient → refuse with pack `no-source`. **This is the hard anti-hallucination gate.** |
| 3 | Prompt + generate | `PromptBuilderService` → `ModelRouterService` | Locked compliance template + rules + facts. Anthropic default, OpenAI fallback. |
| 4 | Parse | `ModelAnswerParser` | Extracts `{answer, citations, confidence, human_escalation_required, disclaimer}`; null → escalate. |
| 4b | **Content gate** | `AnswerValidationService.validateContent` | Raw model text checked for prohibited phrases / unquoted "you are eligible" → escalate. |
| 4c | **Citation integrity** | `AnswerCitationService` | Drops any citation not matching a retrieved source; salvages citations for a grounded-but-uncited answer. |
| 5 | **Final backstop** | `AnswerValidationService.validate` | Content re-check + require citations when evidence was sufficient. Fail → escalate. |
| 6 | Persist + spend | repos + `SpendGuardService` | Save assistant turn + `AnswerSource` rows; record spend **only on a validated answer** (refusals aren't charged). |
| 7 | Audit | `AuditLogService` + `RagTraceService` | Full trace (own `REQUIRES_NEW` tx), PII-aware. Returns `AskResponse` with `traceId`. |

**Retrieval SQL filters:** `is_active`, effective/expiration date window,
`visibility = :visibility`, non-admin requires `trust_level <> 'BLOCKED'`, `brain_id`,
child chunks only, embedding present, and analyzer-scope match
(`:scope IS NULL OR analyzer_scope IS NULL OR analyzer_scope = :scope`).

**Embeddings:** OpenAI `text-embedding-3-small` @ **1536 dims**. **Answer model:**
`claude-haiku-4-5` (Anthropic) default; **utility/fallback:** `gpt-4.1-nano` (OpenAI).
Per-brain overrides live on the `brains` row.

**Tools are NOT called during answering.** Tool execution is a separate,
permission/tenant/confirmation/SSRF-gated gateway (see §5).

---

## 3. Data model (table → purpose)

Every data + compliance table is stamped with `brain_id` for isolation.

**Core**
- **`brains`** — the registry. Key columns: `slug` (unique), `display_name`,
  `pack_ref`, `source_type` (`s3`|`local`), `s3_bucket/prefix/region`, `local_path`,
  `answer_provider/model`, `utility_provider/model`, `is_default`, `is_active`,
  `learning_enabled` (default OFF), `daily_cost_budget_usd` (null=global, 0=unlimited),
  `env_config_fingerprint`. One movable `is_default` row (partial unique index);
  seeded default id `00000000-…-01`.
- **`brain_documents`** — source docs. `title`, `source_name`, `source_type`,
  `visibility`, `trust_level`, `analyzer_scope` (null = shared), effective/expiration
  dates, `content_sha256`, `is_active`.
- **`brain_document_chunks`** — searchable chunks. `VECTOR(1536)` (HNSW cosine) +
  `content_tsv` (GIN FTS) + `metadata` jsonb + parent/child hierarchy (`chunk_type`,
  `parent_chunk_id`). Retrieval matches CHILD chunks.
- **`brain_profiles`** (1:1 with brains) — the public-assistant personality + gating:
  `mode`, `purpose`, `audience`, `tone`, `expertise_level`, `answer_length`,
  `clarification/escalation/citation/cta_policy`, `disclaimer`, `confidence_target`
  (0.90), `retrieval_confidence_threshold`, `public_enabled`, `allowed_domains`,
  `public_token_hash`.

**Per-brain config**
- `brain_rule_revisions` — owner-editable prompt rule blocks (`rules.hard`, `rules.guidance`), append-only, newest wins, null = revert to pack default.
- `brain_vocabulary_revisions` — search-time synonym/glossary expansion.
- `brain_source_links` — approved external links the assistant may cite.
- `brain_page_guides` — per-route/topic answering + navigation guidance.
- `brain_tool_definitions` / `brain_tool_adapters` / `brain_tool_adapter_runs` — agent tool catalog, outbound-HTTP binding, invocation audit.
- `brain_connector_clients` / `brain_connector_events` — connector tokens (scope/origin/peer/tenant/permission bound) + activity log.
- `clarification_rules` — "ask a clarifying question" rules.
- `brain_daily_usage` — per-brain per-day spend accounting for the budget breaker.

**Learning loop** — `rag_answer_feedback` (👍/👎) → `brain_source_weights` (per-document
retrieval multiplier) → `brain_source_weight_events` (review queue). Gated by
`brains.learning_enabled`.

**Global (not brain-scoped):** `brain_settings` — global key/value runtime knobs
(despite the name). `rag_traces` — per-request trace.

**Enums:** `SourceType` = AGENCY_GUIDELINE·INTERNAL_POLICY·INVESTOR_OVERLAY·EDUCATIONAL ·
`SourceVisibility` = PUBLIC·INTERNAL·SECURE · `SourceTrustLevel` =
AUTHORITATIVE·APPROVED·REFERENCE·EXPERIMENTAL·BLOCKED · `BrainMode` =
PUBLIC_SITE·PRIVATE_SITE·SECURE_DEPLOYMENT.

---

## 4. Access & security (four auth mechanisms)

All key comparisons are constant-time; every filter short-circuits `OPTIONS`; gating
uses the decoded within-app path (no percent-encoding bypass).

| Mechanism | Header | Gates | Can do |
|---|---|---|---|
| **Admin** | `X-Admin-Api-Key` | `/api/ai/admin/**`, `/api/ai/documents/**` | Everything: brains, docs/sync, connectors, rules, vocab, source-links, page-guides, settings. One key per engine (covers all brains). |
| **Analyze** (suite S2S) | `X-Analyze-Api-Key` | `/api/ai/{brain}/analyze/{slug}` | Run folder analyzers. Fail-closed: unset → whole surface 503 `BRAIN_DISABLED`. |
| **Public widget** | `X-Public-Brain-Token` (+ `X-Session-Id`) | `/api/ai/public/{slug}/ask`,`/feedback` | Ask as a website. Requires `public_enabled` + `mode=PUBLIC_SITE` + `Origin` in `allowed_domains` + token hash match. |
| **Connector / MCP** | `Authorization: Bearer` | `/api/connect/**`, `/mcp/tools/*` | Scoped calls. Checks brain binding, scope, allowed peer-host/origin, tenant, permission. |

- **CORS:** static env origins (`CORS_ALLOWED_ORIGINS` = `CONSOLE_ORIGIN`) for
  admin/console; dynamic per-brain `allowed_domains` for public widgets. *(This is what
  broke Sync/learning until `CONSOLE_ORIGIN` was set — see playbook.)*
- **Rate limits** (in-memory per JVM): public 10/min, connector/MCP 60/min, admin
  120/min; keyed by remote addr (`X-Forwarded-For` ignored unless trusted-proxy set).
- **`ProductionConfigGuard`** refuses to boot in prod if the admin key is blank/default,
  DB password is dev, or CORS is empty/localhost-only.
- **Connector allow-lists are asymmetric:** empty peer-host/origin list = *allow all*;
  empty tenant list = *act for no tenant* (fail-closed). Tool permissions are fail-closed.
- **LOS/suite wiring** (how the suite holds these): analyzers use
  `LOS_BRAIN_API_KEY`(=analyze key)/`LOS_BRAIN_NAME=mortgage`/`LOS_BRAIN_BASE_URL`;
  console Ask-AI uses `LOS_SUITEBRAIN_PUBLIC_TOKEN`/`LOS_SUITEBRAIN_ORIGIN`.

> The static keys are documented as an interim measure ("replace with Cognito JWT at
> deployment") — that JWT path is not implemented, so **the static keys are prod auth today.**

---

## 5. The admin dashboard (13 screens)

React/Vite SPA under HashRouter. Auth = admin key in `sessionStorage` → `X-Admin-Api-Key`.
`?brain`/`?scope` live in the hash; `api.ts::withBrainFromUrl` injects `?brain` into
admin/documents calls and NavLinks preserve the query (the routing fix). **Per-brain
selection is not uniform** — some screens use the URL brain, others carry their own picker.

| Screen | Scope | Purpose |
|---|---|---|
| **Corpus** | URL `?brain`+`?scope` | Upload/edit/delete docs, run S3/folder **Sync**, ingestion-quality panel. |
| **Brains** | global | Create a brain, point at folder/S3, sync, set active, toggle learning. |
| **Connect** | own picker (by id) | 6-step wizard to attach a brain to a website (Choose→Knowledge→Voice→Publish→Embed→Verify) — issues the public token + embed snippet. |
| **Connectors** | global | Issue scoped tokens for MCP_AGENT / PEER_BRAIN / SERVER_API / INTERNAL_APP callers. |
| **Tools** | own picker (by slug) | Define the agent tool manifest + bind each tool to an outbound HTTP adapter (auth, allowed-hosts, confirmation). |
| **Personality** | URL brain | Full public-assistant profile (mode, tone, policies, disclaimer, confidence, public token, allowed domains). |
| **Settings** | **global** | Runtime model/retrieval config: answer/utility provider+model, `retrieval.confidence-threshold`, `top-k`, `rerank.enabled`. Live within ~10 s. |
| **Rules** | own picker (by slug) | Edit the two prompt tiers (`rules.hard` must / `rules.guidance` should), with revisions + "Preview full prompt". |
| **Vocabulary** | URL brain | Search-time synonym expansion (borrower words → guideline words). Never changes what the model may say. |
| **Source links** | URL brain | Registry of external URLs the assistant may cite (authority, surface, topics). |
| **Page guides** | URL brain | Per-route guidance (intents, allowed guidance, internal links, bound source links). |
| **Test console** | brain slug | Exercise the brain: Full ask (admin) / Public ask (as a site) / Retrieval-only; thumbs up/down a trace. |
| **Audit** | URL brain + own picker | Q&A audit trail + the learning review queue / source weights. |

**Extending the UI:** add `screens/X.tsx` + a `<Route>` and `<NavLink to={`/x${suffix}`}>`
(keep `suffix` so brain/scope propagate) + an api group in `api.ts`. Option lists to
extend are centralized per screen (e.g. Connectors `TYPES`/`PUBLIC_SCOPES`, Tools
`MODES`/`AUTH_MODES`, Settings `FIELDS`); type unions live in `types.ts`. Every screen
has a colocated Vitest.

---

## 6. Corpus & ingestion

**Flow:** `SyncService.sync(dryRun, brainId, scope, force)` → `CorpusSourceFactory`
picks `S3CorpusSource` or `LocalFolderCorpusSource` by `brain.source_type` → read
`_manifest.json` → list files → plan → ingest.

- **Source of truth:** S3 `s3://example-bucket/rag-brain/<scope>/`; `_manifest.json` at the
  prefix root. `analyzer_scope` = per-file manifest → **subfolder name** → default → null.
- **Supported extensions:** `pdf, docx, txt, md, markdown, html, htm` — **YAML/JSON are
  NOT ingestible** (skipped). Author retrieval docs as `.md`.
- **Plan rules:** new→UPLOAD, changed sha256/scope→UPDATE, unchanged→SKIP,
  inactive→REACTIVATE, no-longer-present→DEACTIVATE. **Mass-deactivation guard** refuses
  if the listing is empty or >30% would deactivate (protects against wrong bucket);
  `force=true` overrides (audited).
- **Ingest:** Tika text extract (blank → "scanned PDF? OCR unsupported") →
  `ChunkingService.chunkHierarchical` (parent+child) → embed CHILD chunks (1536-dim).
- **CLI:** `scripts/s3-ingest/corpus-onboard.mjs` `add → validate → plan → apply`.
  `apply --all` walks scopes `shared→income→assets→title→credit→reo→insurance→submission` one at a time,
  health-probing between (a full sync is the OOM trigger on the 4 GB box).

**Packs** — a domain pack (`packs/<slug>/`) is the brain's *behavior* config, bound via
`brains.pack_ref` (enforced: `pack.slug == brain.slug`). Required: `pack.yaml`,
`prompt.yaml` (5 `%s` slots + hard/guidance rules), `guardrails.yaml`
(prohibited-phrases + canned answers), `classifier.yaml` (category regex),
`retrieval.yaml`. Optional: `source-links.yaml`, `page-guides.yaml`, `analyzers.yaml`
(the mortgage pack defines the income/assets/title/credit/reo/insurance/submission/documents analyzers).
Dashboard-generated packs live in the persistent `pds_rag_brain_packs` volume — **must
persist or dashboard-created brains 500 after redeploy.**

---

## 7. Deployment & ops

To be defined for Pragmatic DS infrastructure.

## 8. Adding features / brains / instances — quick map

- **New brain:** Brains screen (or `POST /api/ai/admin/brains`) → set source (S3
  prefix or local pack) + pack (`pack.slug` must match brain slug) → sync → set active
  if it should be the default. Same-engine, so it shares `CONSOLE_ORIGIN`.
- **New scope in a brain:** just a new S3 subfolder + `.md` files → sync that scope.
- **New instance (separate engine):** stand up another box/container with its own
  `engine.env` + `BRAIN_SLUG`/`BRAIN_PACK`; brains/rate-limits/CORS caches are
  per-JVM, so instances are independent.
- **New agent/app integration:** internal server→server = Analyze or a `SERVER_API`
  connector token; AI agent = `MCP_AGENT` connector + the MCP snippet; public website =
  Connect wizard → public token + embed snippet.
- **New answer behavior:** pack files (`prompt.yaml`, `guardrails.yaml`,
  `classifier.yaml`) for defaults; per-brain overrides via Rules / Vocabulary /
  Personality / Source-links / Page-guides screens (live within ~10 s).
- **New dashboard capability:** new screen + api group (§5), mirror the colocated tests.

---

*Generated from a source-grounded audit of the rag-brain platform. Verify specifics
against the code when a detail is load-bearing; migration/column details were read from
migrations + JPA entities, endpoints from the TS client + controllers.*
