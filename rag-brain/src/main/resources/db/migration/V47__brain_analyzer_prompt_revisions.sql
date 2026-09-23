-- DEPLOY ORDER: main was at V45 when this was written and open PR #80 owns V46.
-- Flyway runs with outOfOrder=false (the default), so if this migration is applied
-- before V46 exists, the later arrival of V46 fails validation at startup
-- ("Detected resolved migration not applied to database: 46") and the app will
-- not boot. Merge and deploy #80 first, or renumber this file to V46 before merge.
-- V47: per-brain, per-analyzer base-prompt overrides with a draft state.
-- Effective prompt = newest PUBLISHED row with non-null content, else the pack default.
-- A PUBLISHED row with NULL content is the revert-to-pack marker (same convention as
-- brain_rule_revisions). At most one DRAFT per (brain, analyzer).
CREATE TABLE brain_analyzer_prompt_revisions (
    id             UUID         PRIMARY KEY,
    brain_id       UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    analyzer_slug  VARCHAR(64)  NOT NULL,
    state          VARCHAR(16)  NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED')),
    content        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(100) NOT NULL
);

CREATE UNIQUE INDEX uq_analyzer_prompt_draft
    ON brain_analyzer_prompt_revisions (brain_id, analyzer_slug)
    WHERE state = 'DRAFT';

CREATE INDEX idx_analyzer_prompt_published
    ON brain_analyzer_prompt_revisions (brain_id, analyzer_slug, created_at DESC)
    WHERE state = 'PUBLISHED';
