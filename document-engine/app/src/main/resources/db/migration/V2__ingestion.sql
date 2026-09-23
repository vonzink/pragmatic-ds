-- V2 — ingestion: document packages, source files, retention policy.
-- Column definitions: docs/DATA_MODEL.md section 2. Every table is tenant-
-- isolated (org_id + FORCE RLS) — RlsCoverageIT enforces this structurally.

-- loan predates TenantScopedEntity (V1); the base class maps updated_at on
-- every tenant entity. Added here rather than editing V1 — V1 is applied in
-- real environments and an edited migration breaks Flyway checksums.
ALTER TABLE loan ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- One upload session; the unit a user submits and reviews.
CREATE TABLE document_package (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid        NOT NULL REFERENCES tenant (id),
    loan_id        uuid        REFERENCES loan (id),
    name           text,
    page_count     int         NOT NULL DEFAULT 0,
    review_status  text        NOT NULL DEFAULT 'NOT_REVIEWED',
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    purge_after    timestamptz,
    CONSTRAINT document_package_review_status_check
        CHECK (review_status IN ('NOT_REVIEWED', 'IN_REVIEW', 'REVIEWED'))
);

CREATE INDEX document_package_org_created_idx
    ON document_package (org_id, created_at DESC) WHERE deleted_at IS NULL;

-- One uploaded file within a package.
CREATE TABLE source_file (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 uuid        NOT NULL REFERENCES tenant (id),
    package_id             uuid        NOT NULL REFERENCES document_package (id),
    ordinal                int         NOT NULL,
    original_filename      text        NOT NULL,
    -- Sniffed from magic bytes, never trusted from the client.
    content_type           text        NOT NULL,
    -- What the client claimed — kept for audit, never for decisions.
    declared_content_type  text,
    size_bytes             bigint      NOT NULL,
    sha256                 char(64)    NOT NULL,
    storage_key_original   text        NOT NULL,
    storage_key_normalized text,
    page_count             int,
    is_encrypted           boolean     NOT NULL DEFAULT false,
    malware_scan_status    text        NOT NULL DEFAULT 'PENDING',
    uploaded_by            uuid,
    created_at             timestamptz NOT NULL DEFAULT now(),
    -- TenantScopedEntity maps updated_at on every tenant entity; source_file
    -- rows are effectively immutable, but carrying the column beats a
    -- read-only remapping hack in the entity.
    updated_at             timestamptz NOT NULL DEFAULT now(),
    deleted_at             timestamptz,
    CONSTRAINT source_file_malware_status_check
        CHECK (malware_scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'SKIPPED')),
    -- Rejects an exact duplicate within one package.
    CONSTRAINT source_file_package_sha_key UNIQUE (package_id, sha256),
    CONSTRAINT source_file_package_ordinal_key UNIQUE (package_id, ordinal)
);

-- Surfaces a cross-package duplicate as a WARNING, not a rejection: the same
-- paystub legitimately appears in multiple loan files.
CREATE INDEX source_file_org_sha_idx ON source_file (org_id, sha256);

-- Soft delete then scheduled permanent purge. document_category NULL = the
-- org-wide default policy.
CREATE TABLE retention_policy (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid        NOT NULL REFERENCES tenant (id),
    document_category  text,
    retain_days        int         NOT NULL,
    purge_after_days   int         NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT retention_policy_org_category_key UNIQUE NULLS NOT DISTINCT (org_id, document_category)
);

-- ── RLS ─────────────────────────────────────────────────────────────────────
ALTER TABLE document_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_package FORCE  ROW LEVEL SECURITY;
ALTER TABLE source_file      ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_file      FORCE  ROW LEVEL SECURITY;
ALTER TABLE retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_policy FORCE  ROW LEVEL SECURITY;

CREATE POLICY document_package_isolation ON document_package
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY source_file_isolation ON source_file
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY retention_policy_isolation ON retention_policy
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
