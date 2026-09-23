CREATE TABLE brain_vocabulary_revisions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id    UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    content     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(100) NOT NULL
);

CREATE INDEX idx_vocab_revisions_brain_created
    ON brain_vocabulary_revisions (brain_id, created_at DESC, id DESC);

CREATE TABLE brain_source_links (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id           UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    name               VARCHAR(500) NOT NULL,
    url                VARCHAR(2000) NOT NULL,
    domain             VARCHAR(255),
    authority          VARCHAR(50)  NOT NULL,
    topics             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    freshness_required BOOLEAN      NOT NULL DEFAULT FALSE,
    allowed_use        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    do_not_use_for     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    surface            VARCHAR(50)  NOT NULL,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(100) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         VARCHAR(100) NOT NULL
);

CREATE INDEX idx_source_links_brain_active ON brain_source_links (brain_id, is_active);
CREATE INDEX idx_source_links_brain_created ON brain_source_links (brain_id, created_at DESC, id DESC);

CREATE TABLE brain_page_guides (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id          UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    route             VARCHAR(500),
    title             VARCHAR(500) NOT NULL,
    purpose           TEXT         NOT NULL,
    surface           VARCHAR(50)  NOT NULL,
    user_intents      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    allowed_guidance  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    internal_links    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    source_link_ids   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    topics            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(100) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(100) NOT NULL
);

CREATE INDEX idx_page_guides_brain_active ON brain_page_guides (brain_id, is_active);
CREATE INDEX idx_page_guides_brain_created ON brain_page_guides (brain_id, created_at DESC, id DESC);
