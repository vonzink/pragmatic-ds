-- V3 — processing state: jobs and stages.
-- The stage row is what makes resume possible: without it, a retry reruns OCR
-- on a 75-page package to fix a classification bug. docs/DATA_MODEL.md 3.

CREATE TABLE processing_job (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid        NOT NULL REFERENCES tenant (id),
    package_id       uuid        NOT NULL REFERENCES document_package (id),
    -- A replayed submit returns the existing job instead of duplicating work.
    idempotency_key  text        NOT NULL,
    status           text        NOT NULL DEFAULT 'UPLOADED',
    current_stage    text,
    attempt          int         NOT NULL DEFAULT 1,
    created_by       uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT processing_job_org_idempotency_key UNIQUE (org_id, idempotency_key),
    CONSTRAINT processing_job_status_check CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'NORMALIZING', 'RENDERING', 'TEXT_EXTRACTION',
        'OCR_PROCESSING', 'PARSING', 'CLASSIFYING', 'SPLITTING', 'EXTRACTING',
        'VALIDATING_DATA', 'AI_REVIEW', 'HUMAN_REVIEW_REQUIRED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX processing_job_org_package_idx ON processing_job (org_id, package_id);

CREATE TABLE processing_stage (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid        NOT NULL REFERENCES tenant (id),
    job_id           uuid        NOT NULL REFERENCES processing_job (id),
    stage            text        NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING',
    attempt          int         NOT NULL DEFAULT 1,
    -- e.g. SPEC_4_NOT_IMPLEMENTED for VALIDATING_DATA in Spec 1. A skipped
    -- stage says so explicitly; it never pretends to have run.
    skip_reason      text,
    started_at       timestamptz,
    finished_at      timestamptz,
    duration_ms      bigint,
    -- Stable code from the PII-free taxonomy. NEVER document content.
    error_code       text,
    -- Non-sensitive parameters only, enforced at the write seam.
    error_detail     jsonb,
    worker_version   text,
    -- Pinned library versions for this run — what makes a parse reproducible.
    parser_versions  jsonb,
    output_digest    char(64),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT processing_stage_job_stage_attempt_key UNIQUE (job_id, stage, attempt),
    CONSTRAINT processing_stage_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX processing_stage_org_job_idx ON processing_stage (org_id, job_id, stage);

-- ── RLS ─────────────────────────────────────────────────────────────────────
ALTER TABLE processing_job   ENABLE ROW LEVEL SECURITY;
ALTER TABLE processing_job   FORCE  ROW LEVEL SECURITY;
ALTER TABLE processing_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE processing_stage FORCE  ROW LEVEL SECURITY;

CREATE POLICY processing_job_isolation ON processing_job
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY processing_stage_isolation ON processing_stage
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
