-- V1 — extensions, tenancy spine, and row-level security.
--
-- Two layers of tenant isolation, mirroring the pattern proven in host-app:
--   1. Application layer — Hibernate @TenantId filters reads and stamps writes.
--   2. Postgres RLS — FORCE + WITH CHECK, fail-closed. This backstop only
--      engages when the application connects as a NON-OWNER role.
--
-- ⚠️ DEPLOYMENT REQUIREMENT: Flyway must own the schema and the application must
-- connect as `docengine_app` (or a login role granted it). If the app connects as
-- the schema owner, RLS is silently bypassed and only the application layer is
-- protecting tenant isolation. That failure is invisible — every query still
-- returns correct-looking results. Risk R2 in docs/IMPLEMENTATION_PLAN.md.

-- ⚠️ DEPLOYMENT: `vector` is not a trusted extension — a plain schema owner cannot
-- CREATE it. Production provisioning (e.g. the RDS master user) creates both
-- extensions BEFORE the first migration run; IF NOT EXISTS then no-ops here.
-- MigrationOwnershipIT mirrors exactly this topology.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Enabled in V1 rather than when RAG indexing lands, so Spec 6 adds a table
-- instead of an extension migration.
CREATE EXTENSION IF NOT EXISTS vector;


-- ── Application role ────────────────────────────────────────────────────────
-- NOLOGIN by design: deployment creates a login role granted this one, with its
-- password supplied out-of-band. A migration must never contain a credential.
-- Like extensions, roles are cluster-level: production provisioning creates
-- docengine_app up front (a NOCREATEROLE migration owner cannot), and this
-- guard block then no-ops.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'docengine_app') THEN
        CREATE ROLE docengine_app NOLOGIN;
    END IF;
END
$$;


-- ── Tenancy ─────────────────────────────────────────────────────────────────

CREATE TABLE tenant (
    id          uuid        PRIMARY KEY,
    name        text        NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE app_user (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid        NOT NULL REFERENCES tenant (id),
    external_subject  text        NOT NULL,
    email             text        NOT NULL,
    display_name      text,
    role              text        NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_check
        CHECK (role IN ('ADMIN', 'PROCESSOR', 'REVIEWER', 'READONLY'))
);

CREATE UNIQUE INDEX app_user_org_subject_key ON app_user (org_id, external_subject);
CREATE UNIQUE INDEX app_user_org_email_key   ON app_user (org_id, lower(email));

-- Machine access for host-app, rag-brain, and other consumers. Scopes only,
-- never roles — a machine principal must not inherit a human's authority.
CREATE TABLE api_key (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL REFERENCES tenant (id),
    name          text        NOT NULL,
    key_hash      text        NOT NULL,
    scopes        text[]      NOT NULL DEFAULT '{}',
    last_used_at  timestamptz,
    expires_at    timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX api_key_hash_key ON api_key (key_hash);

-- A lightweight reference only. The engine does not own loans — host-app does.
-- This exists so packages can be grouped and Spec 4 cross-document validation
-- has a subject.
CREATE TABLE loan (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid        NOT NULL REFERENCES tenant (id),
    external_loan_id  uuid,
    loan_number       text,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX loan_org_external_key
    ON loan (org_id, external_loan_id) WHERE external_loan_id IS NOT NULL;


-- ── Row-level security ──────────────────────────────────────────────────────
-- The current org comes from the `app.current_org` GUC, stamped at connection
-- acquisition. `current_setting(..., true)` yields NULL when unset and NULLIF
-- collapses the empty string, so an unstamped connection compares against NULL
-- and sees nothing. Fail-closed is the invariant.

CREATE OR REPLACE FUNCTION current_org() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.current_org', true), '')::uuid $$;

ALTER TABLE tenant   ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant   FORCE  ROW LEVEL SECURITY;
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE  ROW LEVEL SECURITY;
ALTER TABLE api_key  ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key  FORCE  ROW LEVEL SECURITY;
ALTER TABLE loan     ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan     FORCE  ROW LEVEL SECURITY;

-- tenant is keyed by its own id; every other table carries org_id.
CREATE POLICY tenant_isolation ON tenant
    USING (id = current_org()) WITH CHECK (id = current_org());

CREATE POLICY app_user_isolation ON app_user
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());

CREATE POLICY api_key_isolation ON api_key
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());

CREATE POLICY loan_isolation ON loan
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());


-- ── Grants ──────────────────────────────────────────────────────────────────
-- No DDL for the application role: schema changes belong to Flyway.
GRANT USAGE ON SCHEMA public TO docengine_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO docengine_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO docengine_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO docengine_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO docengine_app;
