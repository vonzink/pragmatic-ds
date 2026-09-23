-- Immutable per-run manifest for the analyze pipeline (spec
-- docs/superpowers/specs/2026-08-01-income-deterministic-slice-design.md).
-- METADATA ONLY by default: ids, hashes, method names, counts, and tokens are
-- always persisted. findings, calculation inputs/values, calculation display
-- names (calc_audit[].name/inputs/value), and document filenames (docs[]/
-- skipped[].fileName) are persisted only when
-- ragbrain.rag.analyze.persist-findings=true. Document bytes are NEVER stored.
CREATE TABLE analysis_runs (
    id                  UUID PRIMARY KEY,
    brain_id            UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    analyzer_slug       VARCHAR(64) NOT NULL,
    envelope_version    VARCHAR(8)  NOT NULL,
    status              VARCHAR(16) NOT NULL,
    error_reason        TEXT,
    provider            VARCHAR(40),
    model               VARCHAR(80),
    prompt_sha256       VARCHAR(64),
    attempts            INT         NOT NULL DEFAULT 1,
    input_tokens        INT         NOT NULL DEFAULT 0,
    output_tokens       INT         NOT NULL DEFAULT 0,
    cost_usd            DOUBLE PRECISION NOT NULL DEFAULT 0,
    doc_count           INT         NOT NULL DEFAULT 0,
    page_count          INT         NOT NULL DEFAULT 0,
    docs                JSONB,
    skipped             JSONB,
    filtered            JSONB,
    retrieved_chunk_ids JSONB,
    calc_audit          JSONB,
    findings            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_runs_brain_created ON analysis_runs (brain_id, created_at DESC);
