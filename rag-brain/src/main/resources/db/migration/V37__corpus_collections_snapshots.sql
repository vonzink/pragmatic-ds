-- V37: brain-scoped corpus collections and immutable, run-ready snapshots.
--
-- Existing analyzer_scope values remain compatibility metadata. This migration only
-- bootstraps collection membership beside them; it neither clears the scope nor changes
-- legacy retrieval. Snapshots are intentionally not backfilled because no historical run
-- can prove which mutable collection version it used.

-- Composite identity lets every membership foreign key enforce the brain boundary in the
-- database without changing or rewriting any existing document row.
ALTER TABLE brain_documents
    ADD CONSTRAINT uq_brain_documents_id_brain UNIQUE (id, brain_id);

CREATE TABLE brain_corpus_collection (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id           UUID         NOT NULL REFERENCES brains(id) ON DELETE RESTRICT,
    slug               VARCHAR(48)  NOT NULL,
    display_name       VARCHAR(120) NOT NULL,
    state              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    collection_version BIGINT       NOT NULL DEFAULT 1,
    cloned_from_id     UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_corpus_collection_brain_slug UNIQUE (brain_id, slug),
    CONSTRAINT uq_corpus_collection_id_brain UNIQUE (id, brain_id),
    CONSTRAINT chk_corpus_collection_slug
        CHECK (slug ~ '^[a-z][a-z0-9-]{0,47}$'),
    CONSTRAINT chk_corpus_collection_state
        CHECK (state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_corpus_collection_version
        CHECK (collection_version >= 1),
    CONSTRAINT fk_corpus_collection_clone_same_brain
        FOREIGN KEY (cloned_from_id, brain_id)
        REFERENCES brain_corpus_collection(id, brain_id) ON DELETE RESTRICT
);

CREATE INDEX idx_corpus_collection_brain_state
    ON brain_corpus_collection (brain_id, state, display_name);

-- Membership is a mutable pointer, so it follows the document: deleting a document removes
-- it from every collection. Snapshot membership below is the opposite — a durable execution
-- fact that pins its documents with RESTRICT. Keeping RESTRICT here instead would make every
-- backfilled document permanently undeletable, including while the feature flag is off.
CREATE TABLE brain_corpus_collection_document (
    collection_id UUID        NOT NULL,
    brain_id      UUID        NOT NULL,
    document_id   UUID        NOT NULL,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, document_id),
    CONSTRAINT fk_corpus_membership_collection_same_brain
        FOREIGN KEY (collection_id, brain_id)
        REFERENCES brain_corpus_collection(id, brain_id) ON DELETE RESTRICT,
    CONSTRAINT fk_corpus_membership_document_same_brain
        FOREIGN KEY (document_id, brain_id)
        REFERENCES brain_documents(id, brain_id) ON DELETE CASCADE
);

CREATE INDEX idx_corpus_membership_brain_document
    ON brain_corpus_collection_document (brain_id, document_id);

CREATE TABLE brain_corpus_snapshot (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id        UUID        NOT NULL REFERENCES brains(id) ON DELETE RESTRICT,
    manifest        JSONB       NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_corpus_snapshot_id_brain UNIQUE (id, brain_id),
    CONSTRAINT uq_corpus_snapshot_brain_manifest UNIQUE (brain_id, manifest_sha256),
    CONSTRAINT chk_corpus_snapshot_manifest_object
        CHECK (jsonb_typeof(manifest) = 'object'),
    CONSTRAINT chk_corpus_snapshot_manifest_sha
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_corpus_snapshot_brain_created
    ON brain_corpus_snapshot (brain_id, created_at DESC);

CREATE TABLE brain_corpus_snapshot_collection (
    snapshot_id       UUID   NOT NULL,
    brain_id          UUID   NOT NULL,
    position          INT    NOT NULL,
    collection_id     UUID   NOT NULL,
    collection_version BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, position),
    CONSTRAINT uq_corpus_snapshot_collection_identity
        UNIQUE (snapshot_id, collection_id),
    CONSTRAINT uq_corpus_snapshot_collection_brain_identity
        UNIQUE (snapshot_id, brain_id, collection_id),
    CONSTRAINT fk_corpus_snapshot_collection_snapshot_same_brain
        FOREIGN KEY (snapshot_id, brain_id)
        REFERENCES brain_corpus_snapshot(id, brain_id) ON DELETE RESTRICT,
    CONSTRAINT fk_corpus_snapshot_collection_collection_same_brain
        FOREIGN KEY (collection_id, brain_id)
        REFERENCES brain_corpus_collection(id, brain_id) ON DELETE RESTRICT,
    CONSTRAINT chk_corpus_snapshot_collection_position CHECK (position >= 0),
    CONSTRAINT chk_corpus_snapshot_collection_version CHECK (collection_version >= 1)
);

CREATE INDEX idx_corpus_snapshot_collection_lookup
    ON brain_corpus_snapshot_collection (brain_id, collection_id, collection_version);

CREATE TABLE brain_corpus_snapshot_document (
    snapshot_id     UUID        NOT NULL,
    brain_id        UUID        NOT NULL,
    collection_id   UUID        NOT NULL,
    document_id     UUID        NOT NULL,
    document_version VARCHAR(50) NOT NULL,
    content_sha256  VARCHAR(64) NOT NULL,
    visibility      VARCHAR(20) NOT NULL,
    trust_level     VARCHAR(20) NOT NULL,
    effective_date  DATE,
    expiration_date DATE,
    PRIMARY KEY (snapshot_id, collection_id, document_id),
    CONSTRAINT fk_corpus_snapshot_document_collection
        FOREIGN KEY (snapshot_id, brain_id, collection_id)
        REFERENCES brain_corpus_snapshot_collection(snapshot_id, brain_id, collection_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_corpus_snapshot_document_same_brain
        FOREIGN KEY (document_id, brain_id)
        REFERENCES brain_documents(id, brain_id) ON DELETE RESTRICT,
    CONSTRAINT chk_corpus_snapshot_document_version
        CHECK (btrim(document_version) <> ''),
    CONSTRAINT chk_corpus_snapshot_document_sha
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_corpus_snapshot_document_visibility
        CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'SECURE')),
    CONSTRAINT chk_corpus_snapshot_document_trust
        CHECK (trust_level IN ('AUTHORITATIVE', 'APPROVED', 'REFERENCE', 'EXPERIMENTAL', 'BLOCKED'))
);

CREATE INDEX idx_corpus_snapshot_document_lookup
    ON brain_corpus_snapshot_document (brain_id, document_id, snapshot_id);

-- A snapshot is a durable execution fact. All of its rows reject both updates and deletes;
-- collection identity and membership remain mutable only through the versioned service.
CREATE TRIGGER trg_corpus_snapshot_immutable
    BEFORE UPDATE OR DELETE ON brain_corpus_snapshot
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

CREATE TRIGGER trg_corpus_snapshot_collection_immutable
    BEFORE UPDATE OR DELETE ON brain_corpus_snapshot_collection
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

CREATE TRIGGER trg_corpus_snapshot_document_immutable
    BEFORE UPDATE OR DELETE ON brain_corpus_snapshot_document
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- Corpus mutations and freezes use the existing append-only, counts-only audit trail.
ALTER TABLE lab_audit_event DROP CONSTRAINT chk_lab_audit_subject;
ALTER TABLE lab_audit_event ADD CONSTRAINT chk_lab_audit_subject
    CHECK (subject_type IN (
        'INSTANCE', 'RELEASE', 'REGISTRATION', 'ENVELOPE', 'RUN', 'EXCHANGE',
        'COLLECTION', 'SNAPSHOT'));

-- Bootstrap one generated shared collection for unscoped rows in each brain.
INSERT INTO brain_corpus_collection (brain_id, slug, display_name)
SELECT DISTINCT brain_id, 'shared', 'Mortgage Shared'
FROM brain_documents
WHERE is_active = TRUE
  AND (analyzer_scope IS NULL OR btrim(analyzer_scope) = '')
ON CONFLICT (brain_id, slug) DO NOTHING;

-- Normalize existing scope labels to stable slugs. analyzer_scope is at most 40 characters,
-- so the optional scope- prefix cannot exceed the collection slug limit.
WITH normalized_scope AS (
    SELECT DISTINCT brain_id,
           CASE
               WHEN normalized = '' THEN 'scope-' || left(md5(analyzer_scope), 8)
               WHEN normalized ~ '^[a-z]' THEN normalized
               ELSE 'scope-' || normalized
           END AS slug
    FROM brain_documents
    CROSS JOIN LATERAL (
        SELECT btrim(regexp_replace(lower(btrim(analyzer_scope)), '[^a-z0-9]+', '-', 'g'), '-')
            AS normalized
    ) value
    WHERE is_active = TRUE
      AND analyzer_scope IS NOT NULL AND btrim(analyzer_scope) <> ''
)
INSERT INTO brain_corpus_collection (brain_id, slug, display_name)
SELECT brain_id, slug, initcap(replace(slug, '-', ' '))
FROM normalized_scope
ON CONFLICT (brain_id, slug) DO NOTHING;

INSERT INTO brain_corpus_collection_document (collection_id, brain_id, document_id)
SELECT collection.id, document.brain_id, document.id
FROM brain_documents document
JOIN brain_corpus_collection collection
  ON collection.brain_id = document.brain_id
 AND collection.slug = 'shared'
WHERE document.is_active = TRUE
  AND (document.analyzer_scope IS NULL OR btrim(document.analyzer_scope) = '')
ON CONFLICT (collection_id, document_id) DO NOTHING;

WITH normalized_document AS (
    SELECT document.id,
           document.brain_id,
           CASE
               WHEN normalized = '' THEN 'scope-' || left(md5(document.analyzer_scope), 8)
               WHEN normalized ~ '^[a-z]' THEN normalized
               ELSE 'scope-' || normalized
           END AS slug
    FROM brain_documents document
    CROSS JOIN LATERAL (
        SELECT btrim(regexp_replace(lower(btrim(document.analyzer_scope)), '[^a-z0-9]+', '-', 'g'), '-')
            AS normalized
    ) value
    WHERE document.is_active = TRUE
      AND document.analyzer_scope IS NOT NULL AND btrim(document.analyzer_scope) <> ''
)
INSERT INTO brain_corpus_collection_document (collection_id, brain_id, document_id)
SELECT collection.id, document.brain_id, document.id
FROM normalized_document document
JOIN brain_corpus_collection collection
  ON collection.brain_id = document.brain_id
 AND collection.slug = document.slug
ON CONFLICT (collection_id, document_id) DO NOTHING;
