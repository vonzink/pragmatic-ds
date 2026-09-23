# rag-brain

Reusable RAG brain platform with a Spring Boot API, React admin dashboard, and PostgreSQL + pgvector vector store.

The default brain is intentionally generic and starts with no business-specific corpus. New brains can use generated starter packs or their own pack directory without changing backend code.

## Stack

Java 21, Spring Boot 3.5, Spring AI 1.1, PostgreSQL 16, pgvector, Flyway, Gradle, React/Vite/TypeScript.

## Local Start

Prereqs: JDK 21, Docker Desktop, Node 20+.

```bash
cp .env.example .env
# optional for dashboard-only boot:
# fill OPENAI_API_KEY for embeddings/retrieval, and an answer provider key such as ANTHROPIC_API_KEY for generated answers

docker compose up -d
set -a && source .env && set +a
./gradlew bootRun --args="--server.port=9091"
```

Dashboard:

```bash
cd dashboard
npm install
npm run dev -- --port 6174
```

One-command local start is also available:

```bash
./start.sh
```

For the full local testing walkthrough and production checklist, see
[docs/SETUP.md](docs/SETUP.md).

## Database And pgvector

`docker-compose.yml` runs `pgvector/pgvector:pg16` on host port `6435`.

Flyway owns schema creation. `V1__init_schema.sql` enables:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Document child chunks use `VECTOR(1536)` by default for `text-embedding-3-small`. The vector index is HNSW cosine:

```sql
CREATE INDEX idx_chunks_embedding ON brain_document_chunks
  USING hnsw (embedding vector_cosine_ops);
```

Run migrations by starting the app:

```bash
./gradlew bootRun
```

## Ingest Documents

Use the dashboard Corpus screen or the admin API:

```bash
curl -X POST http://localhost:9091/api/ai/documents/upload \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -F "file=@example.pdf" \
  -F "title=Example Source" \
  -F "sourceName=Example" \
  -F "sourceType=EDUCATIONAL"
```

Supported source types: `AGENCY_GUIDELINE`, `INTERNAL_POLICY`, `INVESTOR_OVERLAY`, `EDUCATIONAL`.

For a registered brain, pass `?brain=<slug>` to admin document endpoints.

Check ingestion quality after upload or sync:

```bash
curl -sf -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  http://localhost:9091/api/ai/admin/ingestion-quality?brain=generic
```

## Query Flow

```bash
curl -X POST http://localhost:9091/api/ai/generic/ask \
  -H "Content-Type: application/json" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -d '{
    "sessionId": "local-test",
    "question": "What does the uploaded source say about renewals?",
    "pageRoute": "/knowledge/base",
    "surface": "PUBLIC"
  }'
```

The response includes `answer`, `citations`, confidence/escalation flags, optional `recommendedPage`, `links`, `nextAction`, and `traceId`. Query and ingestion flows require `OPENAI_API_KEY` because embeddings are generated with OpenAI by default. The API and dashboard can still boot without AI keys so admin setup is possible first.

The dashboard Test Console uses `VITE_LEGACY_ASK_SLUG` for its full ask route and sends the selected brain as `?brain=<slug>`. New deployments can leave it as `generic`; set it only when an existing website still calls a legacy route slug.

### Dashboard build flags

The dashboard has two independent build-time flags. Both default off, both are read at build time
rather than at runtime, and neither knows about the other — any of the four combinations is a
supported deployment.

| Flag | Off (default) | On |
| --- | --- | --- |
| `VITE_INSTANCE_CONTROL_ENABLED` | The dashboard is exactly what shipped before the instance control plane: same navigation, same routes, and an unknown path still lands on Corpus. | Navigation leads with Instances, Run queue, Corpus library, Models and providers; the technical screens are demoted under **More** rather than removed, and an unknown path lands on Instances. |
| `VITE_FOLDER_AI_LAB_ENABLED` | No Income Lab entry and no `/lab/income` route. | The Income Lab prototype is reachable — in the legacy navigation directly, and under **More** when instance control is also on. |

Because the flags are read at build time, "off" means the code is **absent from the bundle**, not
hidden behind a condition. With `VITE_INSTANCE_CONTROL_ENABLED` unset the entire instance-control
tree is eliminated by the bundler — roughly 107 kB of the production bundle — so an unknown path
cannot fall through into a screen that was never meant to ship. Verify with:

```bash
cd dashboard
npm run build                                    # instance control absent
VITE_INSTANCE_CONTROL_ENABLED=true npm run build # instance control present
```

Neither flag puts a credential in the bundle. The dashboard holds the admin key in `sessionStorage`
and sends it as `X-Admin-Api-Key`; every provider credential lives on the backend, and the
dashboard never carries a hardcoded provider or model id — the model list comes from
`GET /api/ai/admin/instances/model-catalog` and a model absent from it is one this deployment
cannot run.

## Public Website Assistant

Public website calls use a per-brain public token, not `ADMIN_API_KEY`.

1. Open the dashboard.
2. Go to Personality.
3. Add allowed domains for the active brain.
4. Rotate a public token and store it in the website environment.
5. Test with:

```bash
curl -X POST http://localhost:9091/api/ai/public/generic/ask \
  -H "Content-Type: application/json" \
  -H "Origin: http://localhost:6174" \
  -H "X-Public-Brain-Token: $PUBLIC_BRAIN_TOKEN" \
  -d '{
    "conversationId": null,
    "sessionId": "hero-test",
    "message": "What can you help me with?",
    "pageRoute": "/",
    "surface": "PUBLIC",
    "facts": {}
  }'
```

Public requests only retrieve `PUBLIC` sources. Internal or secure sources are filtered before prompt assembly.
See [docs/public-assistant.md](docs/public-assistant.md) for the full request/response contract.

## Document Manager Instance Runs

Server-to-server integrations (Document Manager) can launch a run of an instance's **current live
release** over an already-parsed Document Engine package and poll the result — never naming a
release, model, prompt, or corpus, and never uploading a document. The full contract, including
idempotent replay semantics and the tenant rules, is in
[`docs/integrations/document-manager-instance-runs.md`](docs/integrations/document-manager-instance-runs.md).
The surface requires both `ragbrain.instances.enabled` and `ragbrain.instances.connector-enabled`
(both default false), plus a connector token carrying the `instances:run` / `instances:runs:read`
scopes and the matching `INSTANCE_RUN_*` permissions and an allowed tenant.

### Instance control plane — the reading map

Everything instance-control ships dark (all flags default false) and activates through
documented, independent gates:

- Design: [`docs/superpowers/specs/2026-08-20-unified-instance-control-plane-design.md`](docs/superpowers/specs/2026-08-20-unified-instance-control-plane-design.md)
- The seven phase plans: [`docs/superpowers/plans/`](docs/superpowers/plans/) —
  `2026-08-20-instance-control-phase1…phase7-*.md`, each carrying a status note saying what its
  ticked boxes rest on; the living state is
  [`docs/superpowers/plans/2026-08-26-instance-control-handoff.md`](docs/superpowers/plans/2026-08-26-instance-control-handoff.md)
- Connector contract: [`docs/integrations/document-manager-instance-runs.md`](docs/integrations/document-manager-instance-runs.md)
- Rollout gates and stop conditions: [`docs/runbooks/instance-control-plane-rollout.md`](docs/runbooks/instance-control-plane-rollout.md)
- Rollback, gate by gate in reverse: [`docs/runbooks/instance-control-plane-rollback.md`](docs/runbooks/instance-control-plane-rollback.md)
- Human acceptance script: [`docs/runbooks/instance-control-plane-uat.md`](docs/runbooks/instance-control-plane-uat.md)

## Connector Clients

Connector clients are for server apps, agent tools, and peer `rag-brain` instances. They use `rb_conn_...` bearer tokens and are intentionally separate from website public tokens (`rb_pub_...`) and `ADMIN_API_KEY`.

Create and rotate connector clients from the dashboard **Connectors** screen. Choose only the scopes the client needs:

- `brains:list`
- `brain:read`
- `readiness:read`
- `ask:public`
- `retrieve:public`
- `citations:read`

Useful endpoints:

```bash
curl http://localhost:9091/.well-known/rag-brain.json
```

```bash
curl -X POST http://localhost:9091/api/connect/v1/brains/generic/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \
  -d '{
    "sessionId": "connector-test",
    "message": "What can you help me with?",
    "pageRoute": "/",
    "facts": {}
  }'
```

```bash
curl http://localhost:9091/mcp/tools \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN"
```

The MCP surface is a lightweight HTTP facade for tool-style callers:

- `GET /mcp/tools` lists available tool definitions.
- `POST /mcp/tools/rag_brain_list_brains`
- `POST /mcp/tools/rag_brain_readiness`
- `POST /mcp/tools/rag_brain_ask`
- `POST /mcp/tools/rag_brain_retrieve`
- `POST /mcp/tools/rag_brain_dashboard_tools`
- `POST /mcp/tools/rag_brain_dashboard_tool_call`
- `POST /mcp/tools/rag_brain_dashboard_ask`

Federation endpoints under `/api/connect/v1` let another `rag-brain` discover active brains, inspect readiness, ask public-safe questions, and retrieve public evidence chunks. Connector calls never expose admin settings, private source paths, token hashes, trace internals, or non-public source content.

## Internal Dashboard Brain

This does not create or assume a separate dashboard application inside this
repo. It adds reusable internal-app/live-tool capability to `rag-brain` so a
future clone such as `dashboard-brain`, `dashboard-main`, or another secure
site-specific brain can use the same pattern.

For an internal dashboard assistant, create a separate brain slug such as
`dashboard-brain` and use connector tokens from that site's backend only.
Browser clients should not receive connector tokens.

Tool definitions are per-brain manifests, not hardcoded dashboard behavior.
Manage them with the admin API:

```bash
curl http://localhost:9091/api/ai/admin/tool-definitions?brain=dashboard-brain \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"
```

```bash
curl -X POST http://localhost:9091/api/ai/admin/tool-definitions?brain=dashboard-brain \
  -H "Content-Type: application/json" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -d '{
    "name": "searchLoans",
    "description": "Search loans visible to the current user.",
    "mode": "READ",
    "confirmationRequired": false,
    "requiredPermissions": ["dashboard.loans.read"],
    "inputSchema": {
      "type": "object",
      "properties": {
        "query": { "type": "string" }
      }
    }
  }'
```

In the **Connectors** screen, grant only the scopes the dashboard integration
needs (`dashboard:ask`, `dashboard:tools:list`, `dashboard:tools:read`,
`dashboard:tools:write`) plus the connector's **allowed tenants** and **granted
permissions**. Tenant identity and permissions are bound to the token: a tool
call may only assert a `tenantId` in the connector's allowed tenants, and
dashboard tools are authorized against the connector's granted permissions — not
against `permissions` sent in the request body. Use **Edit** to change them later.

Internal endpoints:

```bash
curl http://localhost:9091/api/connect/v1/brains/dashboard-brain/dashboard/tools \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN"
```

```bash
curl -X POST http://localhost:9091/api/connect/v1/brains/dashboard-brain/dashboard/tools/searchLoans/call \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \
  -d '{
    "sessionId": "dashboard-session",
    "user": {
      "userId": "user-1",
      "tenantId": "tenant-1"
    },
    "arguments": { "query": "Smith" },
    "confirmed": false
  }'
```

The connector token must be created with `allowedTenants` including `tenant-1`
and `grantedPermissions` including `dashboard.loans.read`, or this call is
rejected with 403.

Tools can execute configured HTTP APIs directly through per-brain adapters.
Store only `secretRef` in the dashboard; provide the actual secret through
deployment env such as `RAG_TOOL_SECRET_DASHBOARD_API`. If a tool has no enabled
adapter, read and write tools return stable `STUBBED` responses. Write tools are
confirmation-gated before any outbound call.

Pipeline:

```text
question
  -> safety classifier
  -> intent router
  -> vocabulary/query rewrite preview
  -> retrieval planner
  -> child-chunk vector + keyword retrieval
  -> bounded rewrite/gap-fill retrieval loop
  -> parent-section context assembly
  -> answer model
  -> citation/guardrail validation
  -> audit log + RAG trace
```

## Dashboard Workflows

The dashboard supports:

- Brains: create, activate, sync local/S3 source bindings.
- Corpus: upload, edit metadata, delete, activate/deactivate, reindex, sync, and review ingestion quality.
- Vocabulary: edit retrieval synonyms.
- Source Links: manage approved external citation/source links.
- Page Guides: manage recommended pages and internal links.
- Test Console: run full ask or retrieval-only tests.
- Audit: inspect answers and retrieved sources.
- Connect: publish a website widget and verify public access.
- Connectors: create scoped agent/server/peer-brain connectors, rotate tokens, copy snippets, and inspect recent connector events.
- Tools: manage per-brain internal tool manifests, configure direct HTTP adapters, and copy connector test calls.


## Tests

```bash
./gradlew test
cd dashboard
npm run check
npm run test -- --run
npm run build
```

Integration tests use Testcontainers and require Docker.
