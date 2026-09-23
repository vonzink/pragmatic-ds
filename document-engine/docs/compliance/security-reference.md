# Security Reference — Pragmatic DS Document Engine

**Status:** Living document. Last updated 2026-08-20.
**Purpose:** One place to point auditors, partners, and future contributors at the engine's security
controls for handling borrower **NPI**. This summarizes and links the authoritative sources; it does
not replace them. Companion: [`data-processing-and-vendors.md`](./data-processing-and-vendors.md).

Authoritative sources in-repo:
- Architecture: [`../ARCHITECTURE.md`](../ARCHITECTURE.md)
- Security code: `app/src/main/java/com/pragmaticds/docengine/security/**` and
  `platform/src/main/java/com/pragmaticds/docengine/platform/security/**`
- Deployment: `deploy/DEPLOY-RUNBOOK.md`, `deploy/compose.prod.yml`

---

## 1. Data classification
- Borrower documents = **GLBA NPI** (names, addresses, account numbers, balances, SSNs where present).
- Treated as sensitive by default: **masked on output**, **never logged as content**, **US-resident only**.

## 2. Tenant isolation (multi-tenant, defense in depth)
- **Every domain row carries a tenant/org id.** Access is tenant- and document-scoped on every path.
- **Two enforcement layers:**
  1. **App layer** — JWT `org` scoping on reads/writes.
  2. **Postgres Row-Level Security** — `FORCE` + `WITH CHECK`, **fail-closed**. Engages because the app
     connects as a **non-owner DB role** while Flyway migrates as the owner
     (`RlsDataSourceConfig`, `DatasourceOwnershipCheck`, `DatasourceRoleAssertionRunner`).
- ⚠️ **Deployment invariant:** the app datasource MUST run as the **non-owner** role or RLS is bypassed
  at runtime. Verified by IT (`RlsRuntimeIT`, `RbacAndCrossTenantIT`).

## 3. Authentication & authorization
- **Human / suite → engine:** OIDC **JWT** (resource server; issuer = the suite's Cognito).
  `SecurityConfig`, `JwtAuthPrincipalConverter`.
- **Service → engine (suite calling the engine):** **API key** in header `X-DocEngine-Api-Key`,
  stored only as an **HMAC-SHA256 hash** (`ApiKeyHasher`, `ApiKeyAuthFilter`, `ApiKeyRepository`);
  the raw key is shown once at mint time and never persisted. Salt from env (`DOCENGINE_APIKEY_SALT`),
  fail-closed on empty.
- **Authorization guards:** `DocumentAccessGuard`, role model (`Role`, `ActorType`), request-context
  clearing (`ContextClearingFilter`). Admin raw-content access is boundary-tested
  (`RawContentAdminBoundaryIT`).

## 4. Data-at-rest & data-in-transit
- **Masking:** sensitive values masked by default on the read model / `fields.md`; full value only via
  an audited path.
- **Blob storage:** local-disk volume behind `BlobStoragePort` (S3 adapter deferred). Back up per
  runbook.
- **Worker rasters in RAM:** page images staged in **tmpfs**, never written to disk; a crash leaves
  nothing behind.
- **Network posture:** engine API is **not internet-exposed** — bound to `127.0.0.1` on the box and
  reachable only by the suite over a private Docker network; Postgres is **not published** to the host.
- **Secrets:** `deploy/engine.env` on the box only (gitignored, `chmod 600`); never committed, never
  pasted in chat. Signed-URL secret fail-closed on empty.

## 5. Logging & observability (NPI hygiene)
- **No document content in logs, traces, exceptions, or CI artifacts.** Logs carry ids, stage names,
  counters, and stable error codes only.
- **AI adapter specifically** logs only ids / state / token counts — never prompts, responses, or
  document text.

## 6. AI extraction controls
- **Off by default:** `DOCENGINE_AI_ENABLED=false`. The `AI_EXTRACTION` stage records `SKIPPED`
  (`AI_DISABLED`) until deliberately enabled per org.
- **Provider-agnostic seam** (`AiExtractionPort`): provider/model/key configurable; adapter selection
  fails closed to the stub when disabled or unconfigured.
- **Never-throws boundary:** an AI failure cannot fail the deterministic pipeline or corrupt
  deterministic output; exceptions are sanitized (no content in error detail).
- **Compliance gate:** real borrower documents to a provider only after that provider's DPA +
  no-training + retention terms are cleared — see
  [`data-processing-and-vendors.md`](./data-processing-and-vendors.md). Synthetic-only until then.
- **Prompt-injection posture:** document text is treated as untrusted data; the arithmetic
  reconciliation trust-gate (Phase 4) means a value the model was tricked into emitting still fails
  math and is routed to review rather than asserted.

## 7. Known exposures / open items
- **AI provider egress not yet cleared** — Vertex/Anthropic gated off pending DPA (tracked in the
  vendor register).
- **S3 blob adapter deferred** — durable store is currently the on-box disk volume; ensure it's in the
  backup routine.
- Add findings here as they arise so this stays the single security-status reference.

## 8. Change log
| Date | Change |
|---|---|
| 2026-08-20 | File created. Catalogued tenant isolation (RLS), auth (JWT + hashed API key), masking, RAM-only rasters, no-content logging, AI-off-by-default gate, US-only residency. |
