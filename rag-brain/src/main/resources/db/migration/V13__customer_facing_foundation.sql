CREATE TABLE brain_profiles (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id              UUID NOT NULL UNIQUE REFERENCES brains (id) ON DELETE RESTRICT,
    mode                  VARCHAR(40) NOT NULL DEFAULT 'PUBLIC_SITE',
    purpose               TEXT NOT NULL DEFAULT 'Answer questions from approved sources.',
    audience              VARCHAR(120) NOT NULL DEFAULT 'public visitor',
    personality           TEXT NOT NULL DEFAULT 'Conversational, concise, source-grounded assistant.',
    tone                  VARCHAR(80) NOT NULL DEFAULT 'professional',
    expertise_level       VARCHAR(80) NOT NULL DEFAULT 'intermediate',
    answer_length         VARCHAR(40) NOT NULL DEFAULT 'balanced',
    confidence_target     DOUBLE PRECISION NOT NULL DEFAULT 0.90,
    clarification_policy  TEXT NOT NULL DEFAULT 'Ask one focused clarifying question when required facts are missing.',
    escalation_policy     TEXT NOT NULL DEFAULT 'Escalate personalized, unsupported, sensitive, or low-confidence requests.',
    citation_policy       VARCHAR(80) NOT NULL DEFAULT 'required_when_sources_used',
    cta_policy            TEXT NOT NULL DEFAULT 'Recommend relevant pages or a human handoff when useful.',
    disclaimer            TEXT NOT NULL DEFAULT 'This answer is generated from approved source context and may be incomplete.',
    public_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    public_token_hash     VARCHAR(128),
    allowed_domains       JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clarification_rules (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id              UUID NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    topic                 VARCHAR(120) NOT NULL,
    intent                VARCHAR(80) NOT NULL,
    required_facts        JSONB NOT NULL DEFAULT '[]'::jsonb,
    question              TEXT NOT NULL,
    priority              INTEGER NOT NULL DEFAULT 100,
    required_for_public   BOOLEAN NOT NULL DEFAULT TRUE,
    optional_for_general  BOOLEAN NOT NULL DEFAULT FALSE,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE brain_documents
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN trust_level VARCHAR(20) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE rag_traces
    ADD COLUMN response_type VARCHAR(40),
    ADD COLUMN clarification_decision JSONB,
    ADD COLUMN missing_facts JSONB,
    ADD COLUMN collected_facts JSONB,
    ADD COLUMN visibility_filter VARCHAR(20),
    ADD COLUMN confidence_reason JSONB,
    ADD COLUMN validation_outcome VARCHAR(120);

INSERT INTO brain_profiles (brain_id, mode)
SELECT id, 'PUBLIC_SITE'
FROM brains
ON CONFLICT (brain_id) DO NOTHING;

CREATE INDEX idx_brain_profiles_brain ON brain_profiles (brain_id);
CREATE INDEX idx_clarification_rules_brain_priority
    ON clarification_rules (brain_id, active, priority);
CREATE INDEX idx_brain_documents_visibility
    ON brain_documents (brain_id, visibility, trust_level);
