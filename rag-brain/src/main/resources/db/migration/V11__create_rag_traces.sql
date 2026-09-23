CREATE TABLE rag_traces (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id                   UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    conversation_id            UUID,
    user_question              TEXT        NOT NULL,
    rewritten_question         TEXT,
    intent                     VARCHAR(80),
    retrieval_plan             JSONB,
    retrieved_context          JSONB,
    side_evidence              JSONB,
    citations                  JSONB,
    final_answer               TEXT,
    confidence_score           DOUBLE PRECISION,
    human_escalation_required  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rag_traces_brain_created ON rag_traces (brain_id, created_at DESC);
CREATE INDEX idx_rag_traces_conversation ON rag_traces (conversation_id);
