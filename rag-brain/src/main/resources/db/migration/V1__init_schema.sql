-- ============================================================
-- RAG Brain — initial schema
-- PostgreSQL 16 + pgvector
-- Embedding dimension 1536 = OpenAI text-embedding-3-small
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- Guideline documents (Fannie/Freddie guides, internal overlays, etc.)
-- ------------------------------------------------------------
CREATE TABLE mortgage_documents (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(500)  NOT NULL,
    source_name      VARCHAR(255)  NOT NULL,            -- e.g. "Fannie Mae Selling Guide"
    source_type      VARCHAR(50)   NOT NULL,            -- agency_guideline | internal_policy | investor_overlay | educational
    file_name        VARCHAR(500)  NOT NULL,
    s3_key           VARCHAR(1000),                     -- null while storage is local
    document_version VARCHAR(50),
    effective_date   DATE,
    expiration_date  DATE,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_active ON mortgage_documents (is_active);
CREATE INDEX idx_documents_source_type ON mortgage_documents (source_type);

-- ------------------------------------------------------------
-- Document chunks with embeddings + full-text search
-- ------------------------------------------------------------
CREATE TABLE mortgage_document_chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID          NOT NULL REFERENCES mortgage_documents (id) ON DELETE CASCADE,
    chunk_index  INTEGER       NOT NULL,
    content      TEXT          NOT NULL,
    token_count  INTEGER       NOT NULL,
    metadata     JSONB         NOT NULL DEFAULT '{}'::jsonb,  -- section, page_number, headings, etc.
    embedding    VECTOR(1536),
    -- generated tsvector column keeps keyword search always in sync with content
    content_tsv  TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

-- HNSW index for fast cosine similarity search
CREATE INDEX idx_chunks_embedding ON mortgage_document_chunks
    USING hnsw (embedding vector_cosine_ops);

-- GIN index for full-text keyword search
CREATE INDEX idx_chunks_content_tsv ON mortgage_document_chunks USING gin (content_tsv);

CREATE INDEX idx_chunks_document_id ON mortgage_document_chunks (document_id);

-- ------------------------------------------------------------
-- Website chat sessions
-- ------------------------------------------------------------
CREATE TABLE ai_conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_session_id VARCHAR(255) NOT NULL,
    source          VARCHAR(50)  NOT NULL DEFAULT 'website',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversations_session ON ai_conversations (user_session_id);

-- ------------------------------------------------------------
-- Messages (user questions + AI answers)
-- ------------------------------------------------------------
CREATE TABLE ai_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID         NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    role                VARCHAR(20)  NOT NULL,            -- user | assistant
    content             TEXT         NOT NULL,
    model_provider      VARCHAR(50),
    model_name          VARCHAR(100),
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,
    total_cost_estimate NUMERIC(10, 6),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_conversation ON ai_messages (conversation_id);

-- ------------------------------------------------------------
-- Which source chunks supported each answer (citation trail)
-- ------------------------------------------------------------
CREATE TABLE ai_answer_sources (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id       UUID          NOT NULL REFERENCES ai_messages (id) ON DELETE CASCADE,
    document_id      UUID          REFERENCES mortgage_documents (id) ON DELETE SET NULL,
    chunk_id         UUID          REFERENCES mortgage_document_chunks (id) ON DELETE SET NULL,
    similarity_score DOUBLE PRECISION,
    source_name      VARCHAR(255),
    document_name    VARCHAR(500),
    section          VARCHAR(255),
    page_number      INTEGER,
    effective_date   DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_answer_sources_message ON ai_answer_sources (message_id);

-- ------------------------------------------------------------
-- Full audit trail for compliance and debugging
-- ------------------------------------------------------------
CREATE TABLE ai_audit_logs (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id            UUID REFERENCES ai_conversations (id) ON DELETE SET NULL,
    user_question              TEXT         NOT NULL,
    rewritten_question         TEXT,
    retrieved_context          JSONB,
    final_prompt               TEXT,
    final_answer               TEXT,
    model_provider             VARCHAR(50),
    model_name                 VARCHAR(100),
    confidence_score           DOUBLE PRECISION,
    fallback_used              BOOLEAN      NOT NULL DEFAULT FALSE,
    human_escalation_required  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_conversation ON ai_audit_logs (conversation_id);
CREATE INDEX idx_audit_logs_created ON ai_audit_logs (created_at);
CREATE INDEX idx_audit_logs_escalation ON ai_audit_logs (human_escalation_required) WHERE human_escalation_required;
