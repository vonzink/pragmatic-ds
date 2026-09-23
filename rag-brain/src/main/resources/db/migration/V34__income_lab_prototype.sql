-- V34: additive Income Lab prototype persistence (plan
-- docs/superpowers/plans/2026-08-15-income-lab-prototype.md, Task 3).
--
-- ADDITIVE ONLY. V1-V33 are applied migrations and are never edited. Nothing here
-- alters an existing table, so with ragbrain.lab.enabled=false these tables simply
-- stay empty and every existing route behaves exactly as before.
--
-- PRIVACY BOUNDARY, enforced by the column set itself rather than by convention:
--   * no document bytes, filename, declared MIME type, source URL, or engine storage key
--   * no envelope body and no parsed field value
--   * no provider credential and no encryption key
--   * no plaintext analysis output and no plaintext discussion body
-- Sensitive bodies exist only as AES-256-GCM ciphertext with a per-record nonce
-- (see LabPayloadCipher); the associated data that authenticates them is rebuilt from
-- each row's own identity columns, so ciphertext moved to another run cannot decrypt.
-- Document Engine remains the only document/parse authority: the Lab stores engine
-- identifiers and digests of the immutable engine result, never its content.

-- ============================================================ shared guards

-- Rejects any mutation of an immutable/append-only row. check_violation (23514) so the
-- JDBC layer maps it to the same DataIntegrityViolationException a CHECK would raise.
-- The message is a stable value-free code, matching the plan's safe-error rule.
CREATE FUNCTION lab_reject_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LAB_ROW_IMMUTABLE' USING ERRCODE = '23514';
END;
$$;

-- Audit metadata may hold counts and booleans and nothing else: no value, filename,
-- prompt, body, ciphertext, forbidden hash, or credential can hide in a JSONB blob.
-- A CHECK cannot contain a subquery, so the predicate lives in an IMMUTABLE function.
CREATE FUNCTION lab_metadata_is_counts_only(value JSONB) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE AS $$
    SELECT value IS NULL
        OR (jsonb_typeof(value) = 'object'
            AND NOT EXISTS (SELECT 1
                            FROM jsonb_each(value) entry
                            WHERE jsonb_typeof(entry.value) NOT IN ('number', 'boolean')));
$$;

-- ============================================================ instance releases

-- One immutable snapshot of the analyzer contract a Lab run was executed against.
-- The manifest is analyzer configuration (prompt, output schema, compatibility policy,
-- calculator method list, prototype limitations) — never borrower data.
CREATE TABLE lab_instance_release (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id               UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    instance_slug          VARCHAR(32) NOT NULL,
    release_number         INT         NOT NULL,
    provenance_mode        VARCHAR(24) NOT NULL,   -- PRODUCTION | CANDIDATE (drift record)
    manifest               JSONB       NOT NULL,
    manifest_sha256        VARCHAR(64) NOT NULL,
    predecessor_release_id UUID        REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_release_slug
        CHECK (instance_slug ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT chk_lab_release_number
        CHECK (release_number >= 1),
    CONSTRAINT chk_lab_release_mode
        CHECK (provenance_mode IN ('PRODUCTION', 'CANDIDATE')),
    CONSTRAINT chk_lab_release_manifest_sha
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    -- Brain-scoped composite identity: release numbers are per (brain, instance).
    CONSTRAINT uq_lab_release_number UNIQUE (brain_id, instance_slug, release_number),
    -- Repeated bootstrap of the same manifest is idempotent instead of appending.
    CONSTRAINT uq_lab_release_manifest UNIQUE (brain_id, instance_slug, manifest_sha256)
);
CREATE INDEX idx_lab_release_history
    ON lab_instance_release (brain_id, instance_slug, release_number);

CREATE TRIGGER trg_lab_release_immutable
    BEFORE UPDATE ON lab_instance_release
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- Exactly one production pointer per (brain, instance) — the primary key says so, so a
-- second production release for one instance is impossible rather than merely unlikely.
CREATE TABLE lab_instance_pointer (
    brain_id              UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    instance_slug         VARCHAR(32) NOT NULL,
    production_release_id UUID        NOT NULL REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (brain_id, instance_slug),
    CONSTRAINT chk_lab_pointer_slug
        CHECK (instance_slug ~ '^[a-z][a-z0-9-]{0,31}$')
);

-- ============================================================ document registration

-- Value-free binding of one brain/instance to the exact engine package and job.
-- UUIDs and lifecycle timestamps ONLY. Package identity is globally unique in this
-- prototype, so a package registered to one brain/instance can never be rebound to
-- another — cross-brain package substitution fails before any engine read.
CREATE TABLE lab_document_registration (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id          UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    instance_slug     VARCHAR(32) NOT NULL,
    engine_package_id UUID        NOT NULL,
    engine_job_id     UUID        NOT NULL,
    engine_source_id  UUID        NOT NULL,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_at      TIMESTAMPTZ,
    CONSTRAINT chk_lab_registration_slug
        CHECK (instance_slug ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT uq_lab_registration_package UNIQUE (engine_package_id),
    CONSTRAINT uq_lab_registration_job     UNIQUE (engine_job_id)
);
CREATE INDEX idx_lab_registration_scope
    ON lab_document_registration (brain_id, instance_slug, registered_at DESC);

-- ============================================================ runs

-- One Lab run. It does NOT invent a parallel analyzer identity: on success it pins the
-- exact existing analysis_runs.id the analyzer wrote (V33), which is why analysis_run_id
-- is nullable while PROCESSING, unique when present, and mandatory once SUCCEEDED.
CREATE TABLE lab_run (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id         UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    instance_slug    VARCHAR(32)  NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    release_id       UUID         NOT NULL REFERENCES lab_instance_release (id) ON DELETE RESTRICT,
    registration_id  UUID         NOT NULL REFERENCES lab_document_registration (id) ON DELETE RESTRICT,
    status           VARCHAR(16)  NOT NULL,
    failure_code     VARCHAR(64),
    attempt          INT          NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMPTZ,
    analysis_run_id  UUID         REFERENCES analysis_runs (id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    terminal_at      TIMESTAMPTZ,
    CONSTRAINT chk_lab_run_slug
        CHECK (instance_slug ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT chk_lab_run_status
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT chk_lab_run_attempt
        CHECK (attempt BETWEEN 1 AND 8),
    -- A PROCESSING run always holds a bounded lease and is not yet terminal; a terminal
    -- run has released its lease and recorded when it ended.
    CONSTRAINT chk_lab_run_lease CHECK (
        (status = 'PROCESSING' AND lease_expires_at IS NOT NULL AND terminal_at IS NULL)
        OR (status <> 'PROCESSING' AND terminal_at IS NOT NULL)),
    -- Nullable while PROCESSING (the analyzer row does not exist yet), mandatory for a
    -- successful terminal run, optional for FAILED/INTERRUPTED.
    CONSTRAINT chk_lab_run_analysis_link CHECK (
        (status = 'PROCESSING' AND analysis_run_id IS NULL)
        OR (status = 'SUCCEEDED' AND analysis_run_id IS NOT NULL)
        OR status IN ('FAILED', 'INTERRUPTED')),
    CONSTRAINT chk_lab_run_failure_code
        CHECK (status <> 'SUCCEEDED' OR failure_code IS NULL),
    CONSTRAINT uq_lab_run_idempotency UNIQUE (brain_id, instance_slug, idempotency_key),
    -- Unique WHEN PRESENT: Postgres treats NULLs as distinct, so many PROCESSING runs
    -- coexist while no two runs can ever claim one analyzer row.
    CONSTRAINT uq_lab_run_analysis_run UNIQUE (analysis_run_id)
);
-- History newest first.
CREATE INDEX idx_lab_run_history ON lab_run (brain_id, instance_slug, created_at DESC);
-- Bounded recovery scan for expired leases only.
CREATE INDEX idx_lab_run_expired_lease ON lab_run (lease_expires_at) WHERE status = 'PROCESSING';
-- Supports the operational retention hold (OpsDataRetentionService): an analysis_runs row
-- referenced by a retained Lab run is excluded from pruning.
CREATE INDEX idx_lab_run_analysis_run ON lab_run (analysis_run_id) WHERE analysis_run_id IS NOT NULL;

-- A run may move from PROCESSING to one terminal state exactly once and may never change
-- the identities it was created with.
CREATE FUNCTION lab_run_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PROCESSING' THEN
        RAISE EXCEPTION 'LAB_RUN_ALREADY_TERMINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.instance_slug <> OLD.instance_slug
        OR NEW.idempotency_key <> OLD.idempotency_key
        OR NEW.release_id <> OLD.release_id
        OR NEW.registration_id <> OLD.registration_id
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LAB_RUN_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_run_guard
    BEFORE UPDATE ON lab_run
    FOR EACH ROW EXECUTE FUNCTION lab_run_guard();

-- The immutable engine-result identity a run was executed against. Every column here is
-- supplied by the verified envelope the adapter already returns: the envelope's package id
-- and generation, plus the exact received bytes' digest and length. Nothing is invented.
CREATE TABLE lab_run_document (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                   UUID        NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    registration_id          UUID        NOT NULL REFERENCES lab_document_registration (id) ON DELETE RESTRICT,
    engine_package_id        UUID        NOT NULL,
    package_revision         INT         NOT NULL,
    processing_job_id        UUID        NOT NULL,
    parse_generation         INT         NOT NULL,
    envelope_version         VARCHAR(16) NOT NULL,
    canonicalization_version VARCHAR(32) NOT NULL,
    envelope_sha256          VARCHAR(64) NOT NULL,
    envelope_size_bytes      BIGINT      NOT NULL,
    source_set_sha256        VARCHAR(64) NOT NULL,
    reuse_eligibility        VARCHAR(32) NOT NULL,
    document_count           INT         NOT NULL,
    page_count               INT         NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_run_doc_revision   CHECK (package_revision >= 1),
    CONSTRAINT chk_lab_run_doc_generation CHECK (parse_generation >= 1),
    CONSTRAINT chk_lab_run_doc_env_sha    CHECK (envelope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_run_doc_src_sha    CHECK (source_set_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_run_doc_size       CHECK (envelope_size_bytes > 0),
    CONSTRAINT chk_lab_run_doc_counts     CHECK (document_count >= 0 AND page_count >= 0),
    CONSTRAINT uq_lab_run_doc_identity UNIQUE (run_id, engine_package_id, package_revision)
);
-- Ascending revision history for one package.
CREATE INDEX idx_lab_run_doc_revision ON lab_run_document (engine_package_id, package_revision);

CREATE TRIGGER trg_lab_run_doc_immutable
    BEFORE UPDATE ON lab_run_document
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ encrypted payload

-- The run's terminal analysis output, ciphertext only. There is deliberately no text,
-- jsonb, or varchar column here other than the two bounded discriminators, so no code
-- path — and no future careless patch — has anywhere to put plaintext.
CREATE TABLE lab_run_payload (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           UUID        NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    payload_type     VARCHAR(32) NOT NULL,
    cipher_algorithm VARCHAR(24) NOT NULL,
    nonce            BYTEA       NOT NULL,
    ciphertext       BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_payload_type       CHECK (payload_type IN ('ANALYSIS_OUTPUT')),
    CONSTRAINT chk_lab_payload_cipher     CHECK (cipher_algorithm = 'AES-256-GCM'),
    CONSTRAINT chk_lab_payload_nonce      CHECK (octet_length(nonce) = 12),
    -- At least a bare 128-bit GCM tag: a "ciphertext" too short to be authenticated
    -- cannot be stored at all.
    CONSTRAINT chk_lab_payload_ciphertext CHECK (octet_length(ciphertext) >= 16),
    CONSTRAINT uq_lab_payload_run UNIQUE (run_id, payload_type)
);

CREATE TRIGGER trg_lab_payload_immutable
    BEFORE UPDATE ON lab_run_payload
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ discussion

-- One idempotent run-pinned discussion turn holding exactly two monotonic message slots.
-- The slots are derived arithmetically from the exchange's position in the transcript, so
-- concurrent keys cannot interleave or reorder ordinals.
CREATE TABLE lab_discussion_exchange (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                UUID         NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    idempotency_key       VARCHAR(200) NOT NULL,
    sequence_number       INT          NOT NULL,
    user_ordinal          INT          NOT NULL,
    assistant_ordinal     INT          NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    failure_code          VARCHAR(64),
    prototype_limitations VARCHAR(32)  NOT NULL DEFAULT 'PROTOTYPE_LIVE_DEPENDENCIES',
    lease_expires_at      TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    terminal_at           TIMESTAMPTZ,
    CONSTRAINT chk_lab_exchange_status
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT chk_lab_exchange_sequence
        CHECK (sequence_number >= 1),
    CONSTRAINT chk_lab_exchange_slots
        CHECK (user_ordinal = 2 * sequence_number - 1 AND assistant_ordinal = 2 * sequence_number),
    CONSTRAINT chk_lab_exchange_lease CHECK (
        (status = 'PROCESSING' AND lease_expires_at IS NOT NULL AND terminal_at IS NULL)
        OR (status <> 'PROCESSING' AND terminal_at IS NOT NULL)),
    CONSTRAINT chk_lab_exchange_failure_code
        CHECK (status <> 'SUCCEEDED' OR failure_code IS NULL),
    CONSTRAINT chk_lab_exchange_limitations
        CHECK (prototype_limitations = 'PROTOTYPE_LIVE_DEPENDENCIES'),
    CONSTRAINT uq_lab_exchange_key       UNIQUE (run_id, idempotency_key),
    CONSTRAINT uq_lab_exchange_sequence  UNIQUE (run_id, sequence_number),
    CONSTRAINT uq_lab_exchange_user_slot UNIQUE (run_id, user_ordinal),
    CONSTRAINT uq_lab_exchange_asst_slot UNIQUE (run_id, assistant_ordinal)
);
CREATE INDEX idx_lab_exchange_transcript ON lab_discussion_exchange (run_id, sequence_number);
CREATE INDEX idx_lab_exchange_expired_lease
    ON lab_discussion_exchange (lease_expires_at) WHERE status = 'PROCESSING';

CREATE FUNCTION lab_exchange_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PROCESSING' THEN
        RAISE EXCEPTION 'LAB_EXCHANGE_ALREADY_TERMINAL' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.run_id <> OLD.run_id
        OR NEW.idempotency_key <> OLD.idempotency_key
        OR NEW.sequence_number <> OLD.sequence_number
        OR NEW.user_ordinal <> OLD.user_ordinal
        OR NEW.assistant_ordinal <> OLD.assistant_ordinal
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LAB_EXCHANGE_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_exchange_guard
    BEFORE UPDATE ON lab_discussion_exchange
    FOR EACH ROW EXECUTE FUNCTION lab_exchange_guard();

-- The two encrypted message bodies of one exchange. Ciphertext only, exactly as
-- lab_run_payload: one USER row and one ASSISTANT row, each written once.
CREATE TABLE lab_discussion_message (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange_id      UUID        NOT NULL REFERENCES lab_discussion_exchange (id) ON DELETE RESTRICT,
    role             VARCHAR(16) NOT NULL,
    ordinal          INT         NOT NULL,
    cipher_algorithm VARCHAR(24) NOT NULL,
    nonce            BYTEA       NOT NULL,
    ciphertext       BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_message_role       CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_lab_message_ordinal    CHECK (ordinal >= 1),
    CONSTRAINT chk_lab_message_cipher     CHECK (cipher_algorithm = 'AES-256-GCM'),
    CONSTRAINT chk_lab_message_nonce      CHECK (octet_length(nonce) = 12),
    CONSTRAINT chk_lab_message_ciphertext CHECK (octet_length(ciphertext) >= 16),
    CONSTRAINT uq_lab_message_role    UNIQUE (exchange_id, role),
    CONSTRAINT uq_lab_message_ordinal UNIQUE (exchange_id, ordinal)
);
CREATE INDEX idx_lab_message_order ON lab_discussion_message (exchange_id, ordinal);

CREATE TRIGGER trg_lab_message_immutable
    BEFORE UPDATE ON lab_discussion_message
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ audit

-- Append-only Lab audit trail. Action/status, brain and subject IDs, a bounded proxy
-- actor and correlation id, and count/boolean metadata — never a value, filename, prompt,
-- body, ciphertext, policy-forbidden hash, or credential. A purge appends a value-free
-- tombstone here; it never deletes an audit row, which is why DELETE is refused too.
CREATE TABLE lab_audit_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id       UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    action         VARCHAR(48) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    subject_type   VARCHAR(24) NOT NULL,
    subject_id     UUID,
    actor          VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64),
    failure_code   VARCHAR(64),
    metadata       JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_audit_status
        CHECK (status IN ('SUCCEEDED', 'FAILED', 'DENIED')),
    CONSTRAINT chk_lab_audit_subject
        CHECK (subject_type IN ('INSTANCE', 'RELEASE', 'REGISTRATION', 'ENVELOPE', 'RUN', 'EXCHANGE')),
    CONSTRAINT chk_lab_audit_metadata
        CHECK (lab_metadata_is_counts_only(metadata))
);
CREATE INDEX idx_lab_audit_brain_created ON lab_audit_event (brain_id, created_at DESC);

CREATE TRIGGER trg_lab_audit_append_only
    BEFORE UPDATE OR DELETE ON lab_audit_event
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();
