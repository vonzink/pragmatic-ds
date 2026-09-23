-- Phase 3: generalize document registration so one immutable Document Engine parse can be
-- selected by several instances inside one brain, without ever letting a package cross a
-- brain boundary. V34's prototype made package identity globally unique, which enforced
-- isolation by forbidding reuse entirely. That is too strict: comparing two instances
-- against the same parse is the point of this phase. Isolation moves to an explicit
-- package-to-brain binding instead, so reuse is allowed exactly where it is safe.
--
-- Additive only. V1-V37 are untouched, and every existing upload registration keeps its
-- columns, its engine source id, and its behavior under the backfilled UPLOAD_ONE mode.

-- ============================================================ package binding

-- One engine package belongs to exactly one brain, forever. The primary key says so, so a
-- second brain cannot claim a package that another brain already registered — cross-brain
-- package substitution fails on insert rather than during a later engine read. The
-- redundant-looking unique on (engine_package_id, brain_id) exists so registration rows can
-- carry a composite foreign key and inherit that isolation structurally.
CREATE TABLE lab_engine_package_binding (
    engine_package_id UUID        PRIMARY KEY,
    brain_id          UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    bound_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_package_brain UNIQUE (engine_package_id, brain_id)
);

CREATE INDEX idx_lab_package_binding_brain
    ON lab_engine_package_binding (brain_id, bound_at DESC);

-- Every package already registered keeps the brain it was registered to. DISTINCT is safe
-- because V34's uq_lab_registration_package made (engine_package_id) globally unique, so no
-- historical package can resolve to two brains here.
INSERT INTO lab_engine_package_binding (engine_package_id, brain_id)
SELECT DISTINCT engine_package_id, brain_id
FROM lab_document_registration;

-- A binding is an identity decision, not a mutable setting: rebinding a package to another
-- brain must be impossible rather than merely discouraged.
CREATE TRIGGER trg_lab_package_binding_immutable
    BEFORE UPDATE ON lab_engine_package_binding
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ registration modes

-- UPLOAD_ONE is the historical admin/fallback path: one uploaded source, one job, one
-- source id. EXISTING_PARSE selects an already-immutable package revision and records the
-- exact reconciled source set instead. The shape CHECK makes the two modes structurally
-- exclusive, so a row can never be half of each.
ALTER TABLE lab_document_registration
    DROP CONSTRAINT uq_lab_registration_package,
    DROP CONSTRAINT uq_lab_registration_job,
    ADD COLUMN registration_mode VARCHAR(24) NOT NULL DEFAULT 'UPLOAD_ONE',
    ADD COLUMN selected_revision INT,
    ADD COLUMN source_set_sha256 VARCHAR(64),
    ALTER COLUMN engine_source_id DROP NOT NULL,
    ADD CONSTRAINT fk_lab_registration_package_brain
        FOREIGN KEY (engine_package_id, brain_id)
        REFERENCES lab_engine_package_binding (engine_package_id, brain_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_lab_registration_mode
        CHECK (registration_mode IN ('UPLOAD_ONE', 'EXISTING_PARSE')),
    ADD CONSTRAINT chk_lab_registration_shape CHECK (
        (registration_mode = 'UPLOAD_ONE' AND engine_source_id IS NOT NULL
         AND selected_revision IS NULL AND source_set_sha256 IS NULL)
        OR
        -- Every comparison below is guarded by an explicit IS NOT NULL. A CHECK is satisfied
        -- when it evaluates to TRUE *or NULL*, so a bare `selected_revision >= 1` would let a
        -- NULL revision through the constraint entirely.
        (registration_mode = 'EXISTING_PARSE' AND engine_source_id IS NULL
         AND selected_revision IS NOT NULL AND selected_revision >= 1
         AND source_set_sha256 IS NOT NULL AND source_set_sha256 ~ '^[0-9a-f]{64}$')),
    -- NULLS NOT DISTINCT so the historical upload rows, whose selected_revision is NULL,
    -- still collide on a repeated (brain, instance, package) instead of silently
    -- duplicating. Selecting the same revision twice for one instance is idempotent;
    -- two different instances in the brain may select the same revision.
    ADD CONSTRAINT uq_lab_registration_selection
        UNIQUE NULLS NOT DISTINCT
        (brain_id, instance_slug, engine_package_id, selected_revision);

-- Registration identity is what a run is executed against, so only the read timestamp may
-- ever move. Everything a verification decision depended on is frozen.
CREATE FUNCTION lab_registration_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.brain_id <> OLD.brain_id
        OR NEW.instance_slug <> OLD.instance_slug
        OR NEW.engine_package_id <> OLD.engine_package_id
        OR NEW.engine_job_id <> OLD.engine_job_id
        OR NEW.engine_source_id IS DISTINCT FROM OLD.engine_source_id
        OR NEW.registration_mode <> OLD.registration_mode
        OR NEW.selected_revision IS DISTINCT FROM OLD.selected_revision
        OR NEW.source_set_sha256 IS DISTINCT FROM OLD.source_set_sha256
        OR NEW.registered_at <> OLD.registered_at THEN
        RAISE EXCEPTION 'LAB_REGISTRATION_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lab_registration_guard
    BEFORE UPDATE ON lab_document_registration
    FOR EACH ROW EXECUTE FUNCTION lab_registration_guard();

-- ============================================================ selected sources

-- The exact sources a registration resolved to, as identity only: engine source id, the
-- digest that id was reconciled against, and caller-selected order. There is deliberately
-- no filename, URL, storage locator, media type, text, or parsed value column, so no code
-- path has anywhere to put source content.
--
-- content_sha256 is nullable ONLY to carry V34's historical upload rows honestly. Those
-- registrations predate source-digest verification and no digest for them exists anywhere
-- in V1-V37, so a backfilled NULL states "never verified" rather than inventing a hash.
-- Every EXISTING_PARSE selection writes a digest reconciled against the verified envelope,
-- which the application enforces before insert.
CREATE TABLE lab_document_registration_source (
    registration_id  UUID        NOT NULL REFERENCES lab_document_registration (id) ON DELETE RESTRICT,
    engine_source_id UUID        NOT NULL,
    content_sha256   VARCHAR(64),
    source_position  INT         NOT NULL,
    PRIMARY KEY (registration_id, engine_source_id),
    CONSTRAINT uq_lab_registration_source_position UNIQUE (registration_id, source_position),
    CONSTRAINT chk_lab_registration_source_sha
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_registration_source_position CHECK (source_position >= 0)
);

CREATE INDEX idx_lab_registration_source_lookup
    ON lab_document_registration_source (engine_source_id);

-- Each historical upload registration owned exactly one source, at position 0.
INSERT INTO lab_document_registration_source
    (registration_id, engine_source_id, content_sha256, source_position)
SELECT id, engine_source_id, NULL, 0
FROM lab_document_registration
WHERE registration_mode = 'UPLOAD_ONE' AND engine_source_id IS NOT NULL;

-- A resolved source set is a verification fact, not a mutable list. DELETE is refused as well
-- as UPDATE: dropping one child row would silently shrink what a registration resolved to, and
-- nothing on the parent would contradict it. Matching brain_corpus_snapshot_document in V37.
CREATE TRIGGER trg_lab_registration_source_immutable
    BEFORE UPDATE OR DELETE ON lab_document_registration_source
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();

-- ============================================================ run provenance payload

-- Phase 3 stores the pinned execution provenance beside the analysis output, both as
-- ciphertext under the existing AES-256-GCM columns and the existing immutable trigger.
-- uq_lab_payload_run already keys on (run_id, payload_type), so one run holds at most one
-- of each without further change.
ALTER TABLE lab_run_payload DROP CONSTRAINT chk_lab_payload_type;
ALTER TABLE lab_run_payload ADD CONSTRAINT chk_lab_payload_type
    CHECK (payload_type IN ('ANALYSIS_OUTPUT', 'RUN_PROVENANCE'));
