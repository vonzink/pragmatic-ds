-- V26: the feedback-driven adaptive-retrieval learning loop (design 2026-07-06).
-- Adds the per-brain on/off switch plus three tables: captured answer feedback,
-- the learned per-(brain,document) score weights, and an audit/review-queue of
-- every weight change. All bounded, revertible, and OFF by default.

-- Per-brain master switch. A brain column (not a global brain_settings key) so
-- each brain learns in isolation, mirroring V23's env_config_fingerprint.
ALTER TABLE brains ADD COLUMN learning_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- One rating per answer per rater. End-user rows carry session_id; admin rows
-- carry session_id = NULL (Postgres treats NULLs as distinct, so the UNIQUE does
-- not constrain admin re-rating).
CREATE TABLE rag_answer_feedback (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id     UUID NOT NULL REFERENCES rag_traces (id) ON DELETE CASCADE,
    brain_id     UUID NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    rating       VARCHAR(8)  NOT NULL,             -- UP | DOWN
    source       VARCHAR(16) NOT NULL,             -- END_USER | ADMIN
    reason       TEXT,
    session_id   VARCHAR(255),
    created_by   VARCHAR(100),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trace_id, session_id)
);
CREATE INDEX idx_answer_feedback_brain_created ON rag_answer_feedback (brain_id, created_at);

-- The learned state: per (brain, document) retrieval-score multiplier.
CREATE TABLE brain_source_weights (
    brain_id       UUID NOT NULL REFERENCES brains (id) ON DELETE CASCADE,
    document_id    UUID NOT NULL REFERENCES brain_documents (id) ON DELETE CASCADE,
    weight         DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    feedback_count INTEGER NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     VARCHAR(100) NOT NULL,
    PRIMARY KEY (brain_id, document_id)
);

-- Audit of every weight change + the review queue.
CREATE TABLE brain_source_weight_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id        UUID NOT NULL REFERENCES brains (id) ON DELETE CASCADE,
    document_id     UUID NOT NULL,
    old_weight      DOUBLE PRECISION,
    new_weight      DOUBLE PRECISION,
    proposed_weight DOUBLE PRECISION,
    evidence_count  INTEGER NOT NULL,
    status          VARCHAR(16) NOT NULL,          -- APPLIED | PENDING | APPROVED | REJECTED | REVERTED
    reason          TEXT,
    actor           VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_source_weight_events_brain ON brain_source_weight_events (brain_id, created_at DESC);
CREATE INDEX idx_source_weight_events_status ON brain_source_weight_events (status) WHERE status = 'PENDING';
