-- V19 — parse-once reuse: the behavior fingerprint and the reuse-probe index.
-- Additive only: V1 through V18 remain immutable migration history.
--
-- behavior_fingerprint is the SHA-256 (lowercase hex) of the canonical
-- (DOCENGINE-C14N-1) behavior document describing what this job's parse
-- ACTUALLY EXECUTED under: engine release, worker /version, and the org's
-- post-shadowing winning classification packs and extraction schemas. It is
-- composed at FINALIZING from the views the run RECORDED as it ran (not at job
-- creation, and never from "what the loaders would say now"), and written in
-- the SAME transaction as the engine_result row it describes. A run that cannot
-- be fully accounted for is left NULL rather than described wrongly: a loader it
-- never consulted, a view replaced under it before finalization, or a worker
-- version its stages straddled all withhold the stamp; a resumed/continuation
-- generation never even asks, because its rows blend two behaviors. NULL never
-- matches a reuse probe, so every pre-V19 job and every undescribable parse is
-- permanently non-reusable — the deliberate over-trigger direction: when in
-- doubt, parse again.
ALTER TABLE processing_job
    ADD COLUMN behavior_fingerprint char(64),
    ADD CONSTRAINT processing_job_behavior_fingerprint_format_check
        CHECK (behavior_fingerprint IS NULL OR behavior_fingerprint ~ '^[0-9a-f]{64}$');

-- The reuse probe's candidate lookup: same org, same source-set digest,
-- newest first. Runs under engine_result's FORCE-RLS tenant SELECT policy.
CREATE INDEX engine_result_org_source_set_idx
    ON engine_result (org_id, source_set_sha256);
