-- V7: brain registry + per-brain isolation (co-resident multi-brain, spec §6).
-- Creates the brains table, seeds a well-known default brain, and stamps every
-- data + compliance row with brain_id. The column DEFAULT keeps pre-Phase-3
-- write paths (which don't yet set brain_id) working; Phase 3 drops the DEFAULT
-- once all writers pass brain_id explicitly.

CREATE TABLE brains (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug              VARCHAR(100)  NOT NULL UNIQUE,
    display_name      VARCHAR(200)  NOT NULL,
    pack_ref          VARCHAR(500),
    source_type       VARCHAR(20),                      -- s3 | local
    s3_bucket         VARCHAR(255),
    s3_prefix         VARCHAR(500),
    s3_region         VARCHAR(64),
    local_path        VARCHAR(1000),
    answer_provider   VARCHAR(50),
    answer_model      VARCHAR(100),
    utility_provider  VARCHAR(50),
    utility_model     VARCHAR(100),
    local_base_url    VARCHAR(500),
    local_api_key_ref VARCHAR(500),
    is_default        BOOLEAN       NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- At most one default brain.
CREATE UNIQUE INDEX ux_brains_single_default ON brains (is_default) WHERE is_default;

-- Well-known default brain. The fixed id lets the brain_id column DEFAULTs below
-- reference it. Descriptive fields are the app's static defaults; DefaultBrainSeeder
-- reconciles slug/pack/source/model to the live env at boot.
INSERT INTO brains (id, slug, display_name, pack_ref, is_default, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', 'mortgage', 'Example Mortgage',
        'packs/example-mortgage', TRUE, TRUE);

-- Per-brain isolation on data + compliance tables. NOT NULL + DEFAULT backfills
-- existing rows to the default brain and keeps unmodified inserts working.
ALTER TABLE brain_documents       ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE brain_document_chunks ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_conversations      ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_messages           ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_answer_sources     ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_audit_logs         ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;

CREATE INDEX idx_brain_documents_brain       ON brain_documents (brain_id);
CREATE INDEX idx_brain_document_chunks_brain ON brain_document_chunks (brain_id);
CREATE INDEX idx_ai_conversations_brain      ON ai_conversations (brain_id);
CREATE INDEX idx_ai_messages_brain           ON ai_messages (brain_id);
CREATE INDEX idx_ai_answer_sources_brain     ON ai_answer_sources (brain_id);
CREATE INDEX idx_ai_audit_logs_brain         ON ai_audit_logs (brain_id);
