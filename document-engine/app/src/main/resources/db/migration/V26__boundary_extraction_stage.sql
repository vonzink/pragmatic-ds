-- V26 — Phase D of the Reducto-parity roadmap: the gated BOUNDARY_EXTRACTION stage and its
-- append-only proposal ledger (docs/superpowers/specs/2026-08-22-ai-document-splitting-design.md).
--
-- Two changes: the job-state vocabulary learns the new stage (V23's DROP-then-ADD pattern), and
-- boundary_proposal is created — one row per model-proposed cut, VERDICT INCLUDED. The table is
-- what makes the stage idempotent (roadmap R2: SPLITTING deletes and recreates documents on every
-- run, so an accepted cut that lived only in memory would silently revert on replay — instead the
-- re-split READS accepted rows) and what makes the refusals auditable (a reviewer investigating a
-- bad split can see what the model suggested and why the engine refused it).

ALTER TABLE processing_job DROP CONSTRAINT processing_job_status_check;
ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_status_check CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'NORMALIZING', 'RENDERING', 'TEXT_EXTRACTION',
        'OCR_PROCESSING', 'PARSING', 'CLASSIFYING', 'SPLITTING', 'BOUNDARY_EXTRACTION',
        'EXTRACTING', 'AI_EXTRACTION', 'FINALIZING', 'VALIDATING_DATA', 'AI_REVIEW',
        'HUMAN_REVIEW_REQUIRED', 'COMPLETED', 'FAILED'));

-- APPEND-ONLY, like classification_result: a proposal is a recorded fact about what the model
-- said and what the engine decided — it is never updated and never deleted except by the
-- retention purge. No updated_at, same as every other append-only table.
--
-- page_id and job_id are PLAIN uuids, deliberately not FKs: RENDERING deletes and recreates a
-- package's page rows on retry (and a reprocess rotates every page id), and the audit trail of
-- what the model once proposed must survive that — a stale page_id simply matches no current
-- page, so a replayed split reads it as a no-op rather than a boundary. package_id keeps its FK:
-- when the package goes, the purge takes the ledger with it (PackagePurger).
CREATE TABLE boundary_proposal (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        NOT NULL REFERENCES tenant (id),
    package_id          uuid        NOT NULL REFERENCES document_package (id),
    job_id              uuid        NOT NULL,
    -- The page the model claims STARTS a document. package_page_index is always recorded (it is
    -- what the model actually said); page_id resolves only when that index names a real page.
    package_page_index  int         NOT NULL,
    page_id             uuid,
    proposed_type_code  text,
    confidence          numeric(5,4),
    -- The anchoring key: the header text the model claims it READ, verbatim. Persisted because it
    -- is the evidence a verdict was decided on; span text for these pages is already persisted, so
    -- this introduces no new sensitivity class.
    quoted_header_text  text,
    partition_value     text,
    -- The ambiguity window this proposal answered (package page indexes, inclusive). NULL when
    -- the proposal named a page no window contains — that is REJECTED_OUT_OF_WINDOW's evidence.
    window_start_index  int,
    window_end_index    int,
    -- ACCEPTED, or the FIRST gate that refused it (design §6). A dropped proposal is recorded,
    -- never discarded silently.
    verdict             text        NOT NULL,
    -- The window call's token spend (per CALL, so proposals from one window repeat it; the stage
    -- detail carries the package totals).
    input_tokens        bigint,
    output_tokens       bigint,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT boundary_proposal_verdict_check
        CHECK (verdict IN ('ACCEPTED', 'REJECTED_QUOTE_MATCH', 'REJECTED_OUT_OF_WINDOW',
                           'REJECTED_OVERRIDE', 'REJECTED_TRANSPARENT',
                           'REJECTED_CONFIDENCE_FLOOR'))
);

CREATE INDEX boundary_proposal_org_package_idx ON boundary_proposal (org_id, package_id, verdict);

ALTER TABLE boundary_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE boundary_proposal FORCE  ROW LEVEL SECURITY;
CREATE POLICY boundary_proposal_isolation ON boundary_proposal
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());

COMMENT ON TABLE boundary_proposal IS
    'Append-only ledger of model-proposed document boundaries (Phase D). One row per proposal '
    'with the verdict the anchoring gates reached; accepted rows are what a replayed SPLITTING '
    'reads, so the split converges without re-calling the model.';
