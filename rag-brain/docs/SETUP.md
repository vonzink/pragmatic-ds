# rag-brain Setup And Local Testing Walkthrough

This is the practical setup checklist for getting `rag-brain` running locally,
then moving toward a production deployment.

## Current Deployment State

The source of truth is GitHub `main`:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
git fetch origin main
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

`HEAD` and `origin/main` should print the same commit SHA. If they differ, push:

```bash
git push origin main
```

There is no production host wired into this repo yet. "Deployed" currently means
one of two things:

- GitHub `main` is updated.
- The local API/dashboard are running from the current `main`.

Production deployment requires AWS/database/domain/proxy work described below.

## Prerequisites

- JDK 21
- Docker Desktop
- Node 20+
- Git
- API keys for embeddings and answers:
  - `OPENAI_API_KEY` is required for ingestion and vector retrieval.
  - One answer provider is required for generated answers, usually
    `ANTHROPIC_API_KEY`.

## Local `.env`

Create or edit local env:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
cp -n .env.example .env
nano .env
```

For normal local testing, use:

```bash
DB_URL=jdbc:postgresql://localhost:6435/pds_rag_brain
DB_USERNAME=pds_rag_brain
DB_PASSWORD=pds_local_dev_only

ADMIN_API_KEY=<your-local-admin-key>
OPENAI_API_KEY=<your-openai-key>
ANTHROPIC_API_KEY=<your-anthropic-key>

CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:6173,http://localhost:6174
RATE_LIMIT_TRUST_FORWARDED_FOR=false
RATE_LIMIT_TRUSTED_PROXY_COUNT=1
```

If you intentionally use a different local `DB_USERNAME` / `DB_PASSWORD`, create
that role in the local Postgres container:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
set -a && source .env && set +a
python3 - <<'PY' | docker exec -i pds-rag-brain-postgres psql -v ON_ERROR_STOP=1 -U pds_rag_brain -d pds_rag_brain
import os
u = os.environ["DB_USERNAME"]
p = os.environ["DB_PASSWORD"]
def sq(s): return "'" + s.replace("'", "''") + "'"
def ident(s): return '"' + s.replace('"', '""') + '"'
role = ident(u)
print("DO $$")
print("BEGIN")
print(f"  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = {sq(u)}) THEN")
print(f"    EXECUTE 'CREATE ROLE ' || {sq(role)} || ' LOGIN PASSWORD ' || {sq(sq(p))};")
print("  ELSE")
print(f"    EXECUTE 'ALTER ROLE ' || {sq(role)} || ' WITH LOGIN PASSWORD ' || {sq(sq(p))};")
print("  END IF;")
print("END $$;")
print(f"GRANT CONNECT ON DATABASE pds_rag_brain TO {role};")
print(f"GRANT USAGE, CREATE ON SCHEMA public TO {role};")
print(f"GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO {role};")
print(f"GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO {role};")
if u != "pds_rag_brain":
    print(f"GRANT pds_rag_brain TO {role};")
print(f"ALTER DEFAULT PRIVILEGES FOR ROLE pds_rag_brain IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO {role};")
print(f"ALTER DEFAULT PRIVILEGES FOR ROLE pds_rag_brain IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO {role};")
PY
```

## Start Local Services

Start PostgreSQL + pgvector:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
docker compose up -d
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

The default compose profile starts only `pds-rag-brain-postgres`. The app container
is opt-in because the normal local workflow runs the API from Gradle.

Start the API:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
set -a && source .env && set +a
./gradlew bootRun --args='--server.port=9093'
```

In a second terminal, start the dashboard:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain/dashboard
npm install
npm run dev -- --port 6174
```

Open:

```text
http://localhost:6174
```

Unlock with `ADMIN_API_KEY` from `.env`.

One-command local start is also available:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
./start.sh
```

## Local Smoke Tests

Run these with the API up:

```bash
curl -sf http://localhost:9093/actuator/health
curl -sf http://localhost:9093/.well-known/rag-brain.json
curl -sf http://localhost:9093/mcp/tools
```

Admin endpoint smoke test:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
set -a && source .env && set +a
curl -sf -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  http://localhost:9093/api/ai/admin/stats
```

Ingestion quality smoke test:

```bash
curl -sf -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  http://localhost:9093/api/ai/admin/ingestion-quality
```

Readiness check for the default generic brain:

```bash
curl -sf -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  http://localhost:9093/api/ai/admin/brains/00000000-0000-0000-0000-000000000001/readiness
```

Expected before ingestion: readiness is `false` because there are no documents,
no public token, and no allowlisted website domain.

## Dashboard Walkthrough

1. Open `http://localhost:6174`.
2. Enter the admin key from `.env`.
3. Go to **Brains**.
   - Confirm `Generic Brain` exists and is active.
4. Go to **Corpus**.
   - Upload a small PDF, Markdown, TXT, DOCX, or HTML file.
   - For public website testing, set `visibility=PUBLIC`.
   - For private/admin-only material, use `INTERNAL`.
   - Review the **Ingestion quality** panel for missing embeddings, citation
     metadata gaps, orphan child chunks, duplicate text, and empty chunks.
5. Return to **Brains** or **Corpus** and confirm document/chunk counts are no
   longer zero.
6. Go to **Test Console**.
   - Try retrieval-only first.
   - Then try a full ask.
   - Confirm answers include citations when sources are available.
7. Go to **Personality**.
   - Set purpose, audience, tone, disclaimer, and confidence target.
   - Add `localhost` as an allowed domain for local public-widget testing.
8. Go to **Connect**.
   - Walk through the readiness steps.
   - Generate a public token.
   - Use **Verify** with a live question.
9. Go to **Connectors**.
   - Create a connector client for server/agent use.
   - Rotate its token and copy the snippet.
   - Use only the scopes the client needs.
10. Go to **Tools**.
    - Select the target brain.
    - Review seeded example tools or create a new tool manifest.
    - Configure an adapter only for approved host API endpoints.
    - Use HTTPS in production.
    - For local testing, set `RAG_TOOL_SECRET_<SECRET_REF>` before starting the API.
    - Confirm write tools require confirmation.
    - Copy a connector test call for a selected tool.

## Manual API Ingestion Test

Use this if you want to bypass the dashboard:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
set -a && source .env && set +a
curl -X POST http://localhost:9093/api/ai/documents/upload \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -F "file=@/absolute/path/to/example.pdf" \
  -F "title=Example Source" \
  -F "sourceName=Example" \
  -F "sourceType=EDUCATIONAL" \
  -F "visibility=PUBLIC" \
  -F "trustLevel=APPROVED" \
  -F "brain=generic"
```

Then test retrieval:

```bash
curl -G http://localhost:9093/api/ai/documents/test-retrieval \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  --data-urlencode "brain=generic" \
  --data-urlencode "visibility=PUBLIC" \
  --data-urlencode "question=What does this document explain?"
```

## Public Website Test

After the dashboard has public access enabled, a public token generated, and
`localhost` allowlisted as a domain:

```bash
PUBLIC_BRAIN_TOKEN='<token-shown-once-by-dashboard>'
curl -X POST http://localhost:9093/api/ai/public/generic/ask \
  -H "Content-Type: application/json" \
  -H "Origin: http://localhost:6174" \
  -H "X-Public-Brain-Token: $PUBLIC_BRAIN_TOKEN" \
  -d '{
    "sessionId": "local-public-test",
    "message": "What can you help me with?",
    "pageRoute": "/",
    "surface": "PUBLIC",
    "facts": {}
  }'
```

Public requests retrieve only `PUBLIC` sources.

## Connector Test

Create and rotate a connector token in **Connectors**, then:

```bash
RAG_BRAIN_CONNECTOR_TOKEN='<rb_conn_token_shown_once>'
curl -sf http://localhost:9093/api/connect/v1/brains \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN"
```

Ask through the connector API:

```bash
curl -X POST http://localhost:9093/api/connect/v1/brains/generic/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \
  -d '{
    "sessionId": "connector-test",
    "message": "What can you help me with?",
    "pageRoute": "/",
    "facts": {}
  }'
```

For an internal dashboard brain, create a connector in **Connectors** with the
dashboard scopes (`dashboard:ask`, `dashboard:tools:list`, `dashboard:tools:read`,
and, only when needed, `dashboard:tools:write`). Tenant identity and permissions
are bound to the connector token, **not** trusted from the request body, so also
set on the connector:

- **Allowed tenants** — the connector may only act for these tenant IDs; a tool
  call asserting any other `tenantId` is rejected. Leave empty and every tool call
  asserting a tenant is rejected.
- **Granted dashboard permissions** — dashboard tools are authorized against this
  list (e.g. `dashboard.loans.read`), not against `permissions` sent in the body.

The Connectors screen sets all three (scopes, allowed tenants, granted
permissions) on create, and the **Edit** action updates them later. The
equivalent admin API:

```bash
curl -X POST http://localhost:9093/api/ai/admin/connectors \
  -H "Content-Type: application/json" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -d '{
    "name": "Dashboard app",
    "type": "INTERNAL_APP",
    "brainId": null,
    "scopes": ["dashboard:ask", "dashboard:tools:list", "dashboard:tools:read"],
    "allowedTenants": ["local-tenant"],
    "grantedPermissions": ["dashboard.loans.read"],
    "enabled": true
  }'
```

> **Upgrade note:** connectors created before tenant binding default to empty
> `allowedTenants`/`grantedPermissions` and will get 403s on tool calls until an
> admin sets these fields (Connectors → Edit, or `PUT /api/ai/admin/connectors/{id}`).

Dashboard tools are per-brain manifests. The default generic brain has seeded
examples, but a clone such as `dashboard-brain` should define only the tools the
host dashboard can safely support.

List configured tool definitions:

```bash
curl -sf http://localhost:9093/api/ai/admin/tool-definitions?brain=dashboard-brain \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"
```

Create a read tool definition:

```bash
curl -X POST http://localhost:9093/api/ai/admin/tool-definitions?brain=dashboard-brain \
  -H "Content-Type: application/json" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -d '{
    "name": "searchVisibleLoans",
    "description": "Search loans visible to the current dashboard user.",
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

List dashboard tools:

```bash
curl -sf http://localhost:9093/api/connect/v1/brains/dashboard-brain/dashboard/tools \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN"
```

Configure a direct HTTP adapter for approved host APIs only. The adapter stores
`secretRef` in PostgreSQL; the actual value comes from deployment env. For
example, `secretRef=dashboard_api` resolves `RAG_TOOL_SECRET_DASHBOARD_API`.

```bash
export RAG_TOOL_SECRET_DASHBOARD_API='<host-api-token>'
```

```bash
curl -X PUT http://localhost:9093/api/ai/admin/tool-adapters/searchLoans?brain=dashboard-brain \
  -H "Content-Type: application/json" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -d '{
    "enabled": true,
    "httpMethod": "POST",
    "urlTemplate": "https://dashboard.example.com/api/search",
    "authMode": "BEARER_TOKEN",
    "secretRef": "dashboard_api",
    "apiKeyHeader": "",
    "staticHeaders": { "X-App": "rag-brain" },
    "requestBodyTemplate": { "query": "{query}", "tenantId": "{user.tenantId}" },
    "allowedHosts": ["dashboard.example.com"],
    "timeoutMs": 5000
  }'
```

`allowedHosts` is required whenever `enabled` is `true` — the rendered URL's host
must be on this list, and in prod it must be https and must not resolve to a
private/loopback/metadata address.

Call a read tool. The asserted `tenantId` must be in the connector's
`allowedTenants`, and authorization uses the connector's `grantedPermissions` —
the `roles`/`permissions` in the body are informational only, not the authz
source:

```bash
curl -X POST http://localhost:9093/api/connect/v1/brains/dashboard-brain/dashboard/tools/searchLoans/call \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \
  -d '{
    "sessionId": "dashboard-test",
    "user": {
      "userId": "local-user",
      "tenantId": "local-tenant"
    },
    "arguments": { "query": "Smith" },
    "confirmed": false
  }'
```

Ask the internal dashboard brain. As with tool calls, an asserted `tenantId` must
be in the connector's `allowedTenants` (the `roles`/`permissions` here are
answer-framing context, not authorization):

```bash
curl -X POST http://localhost:9093/api/connect/v1/brains/dashboard-brain/dashboard/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $RAG_BRAIN_CONNECTOR_TOKEN" \
  -d '{
    "sessionId": "dashboard-test",
    "message": "What dashboard items need attention?",
    "pageRoute": "/dashboard",
    "visibility": "INTERNAL",
    "user": {
      "userId": "local-user",
      "tenantId": "local-tenant",
      "roles": ["loan-officer"],
      "permissions": ["dashboard.loans.read","dashboard.tasks.read"]
    },
    "facts": { "current_module": "dashboard" }
  }'
```

If a tool has no enabled adapter, rag-brain returns `STUBBED`. If an enabled
adapter exists, rag-brain calls the configured host endpoint. Write tools return
`CONFIRMATION_REQUIRED` unless the request includes `"confirmed": true`, and
rag-brain does not call the host endpoint until that confirmation is present.

## Full Verification Commands

Run before pushing changes:

```bash
cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain
./gradlew test

cd /Users/zacharyzink/zvzsolutions/pragmatic-ds/rag-brain/dashboard
npm run check
npm run test -- --run
npm run build
npm audit --omit=dev --audit-level=high
```

## Production Setup

Production requires real infrastructure. Do not use the local compose database
or local CORS values.

### 1. PostgreSQL + pgvector

Use AWS RDS PostgreSQL or Aurora PostgreSQL with pgvector support.

Recommended shape:

- PostgreSQL 16 compatible engine.
- Private subnet/security group.
- Only the application can connect to port `5432`.
- Dedicated app database, for example `pds_rag_brain`.
- Dedicated app user, for example `rag_brain_app`.
- Automated backups and point-in-time recovery enabled.
- Storage autoscaling or alerts before disk reaches 80%.

The app uses Flyway migrations and expects pgvector. Migration `V1` includes:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

For production, either:

- let the app/migration role create the extension on first startup, or
- create `vector` once as a DB admin before first app startup.

### 2. Production Environment

Set production env values through your host, container platform, or secrets
manager:

```bash
SPRING_PROFILES_ACTIVE=prod

DB_URL=jdbc:postgresql://<aws-db-endpoint>:5432/pds_rag_brain?sslmode=require
DB_USERNAME=rag_brain_app
DB_PASSWORD=<strong-managed-secret>

ADMIN_API_KEY=<32+ char random key>
OPENAI_API_KEY=<openai-key>
ANTHROPIC_API_KEY=<anthropic-key>

CORS_ALLOWED_ORIGINS=https://dashboard.yourdomain.com,https://www.yourdomain.com

RATE_LIMIT_REQUESTS_PER_MINUTE=10
CONNECTOR_RATE_LIMIT_REQUESTS_PER_MINUTE=60
ADMIN_RATE_LIMIT_REQUESTS_PER_MINUTE=120
RATE_LIMIT_TRUST_FORWARDED_FOR=true
RATE_LIMIT_TRUSTED_PROXY_COUNT=1
```

Under `prod`, startup fails if:

- `ADMIN_API_KEY` is blank or still the local default.
- `DB_PASSWORD` is blank or still the local default.
- `CORS_ALLOWED_ORIGINS` is empty or localhost-only.

### 3. TLS, Domain, And Proxy

The Spring app speaks HTTP on container port `8080`. Terminate TLS at a load
balancer, reverse proxy, or ingress.

Suggested domains:

- `https://brain.yourdomain.com` -> API container `:8080`
- `https://dashboard.yourdomain.com` -> dashboard/static host

Proxy requirements:

- Forward `Host`.
- Forward `X-Forwarded-Proto`.
- Forward `X-Forwarded-For`.
- Redirect HTTP to HTTPS.
- Do not allow public traffic to bypass the proxy if
  `RATE_LIMIT_TRUST_FORWARDED_FOR=true`.

Nginx example:

```nginx
location / {
  proxy_pass http://127.0.0.1:8080;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

### 4. CORS

Use full origins, not bare hosts:

```bash
CORS_ALLOWED_ORIGINS=https://dashboard.yourdomain.com,https://www.yourdomain.com
```

Rules:

- Do not use `*`.
- Do not keep localhost in production.
- Add public website hosts in the dashboard Connect/Personality settings for
  each brain.

### 5. Backups, Alerts, And Logs

Recommended defaults:

- DB backups: daily automated backups plus point-in-time recovery.
- Retention: at least 14 days before launch; increase for regulated use.
- Alerts:
  - API health down.
  - DB unavailable.
  - repeated `5xx`.
  - repeated `401/403`.
  - high `429`.
  - disk/storage over 80%.
  - failed backups.
- Logs:
  - `com.pragmaticds.rag=INFO`
  - `org.springframework=WARN`
  - never log raw API keys, public tokens, connector tokens, or customer secrets.

### 6. Token Rotation Policy

Use a 120-day rotation cycle:

- `ADMIN_API_KEY`: rotate every 120 days and redeploy/restart.
- Public brain tokens: rotate every 120 days from the dashboard. The old token
  is invalid immediately.
- Connector tokens: rotate every 120 days. For zero downtime, create a second
  connector, update clients, then disable the old connector.

Rotate immediately if a token is exposed, a contractor/vendor changes, or a
device/account is compromised.

## Production-Ready Definition

The project is production-ready when all are true:

- GitHub `main` is current and verified.
- Production database is managed PostgreSQL with pgvector.
- Production env has no local defaults.
- TLS/domain/proxy are configured.
- CORS uses real HTTPS origins only.
- Dashboard is deployed behind HTTPS.
- Documents are ingested and chunks are indexed.
- Ingestion quality is reviewed and missing embeddings/orphan chunks are resolved
  or explicitly accepted.
- Public token and allowed domains are configured for each public brain.
- Connect wizard readiness is green.
- Retrieval and answer quality have been tested with real documents.
- Backups and alerts are active.
- Token rotation owner/date is documented.
- Java dependency CVE scanning has been added to CI.
