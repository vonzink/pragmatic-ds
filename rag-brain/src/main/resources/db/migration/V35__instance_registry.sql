-- V35: brain-scoped Lab instance registry and idempotency receipts.
--
-- V34 established the Income prototype tables before a first-class instance identity existed.
-- This migration is deliberately additive: it backfills identities from every historical owner
-- and never updates immutable lab_instance_release rows or changes a pointer target.

CREATE TABLE lab_instance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id UUID NOT NULL REFERENCES brains(id) ON DELETE RESTRICT,
    slug VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    purpose VARCHAR(500) NOT NULL DEFAULT '',
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_instance_brain_slug UNIQUE (brain_id, slug),
    CONSTRAINT chk_lab_instance_slug CHECK (slug ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT chk_lab_instance_state CHECK (state IN ('ACTIVE', 'DISABLED'))
);

-- UNION, rather than UNION ALL, makes a single historical identity found in several V34 tables
-- one registry row. Registrations and runs are included so incomplete prototype histories are not
-- orphaned just because they have no release or production pointer.
INSERT INTO lab_instance (brain_id, slug, display_name, purpose)
SELECT brain_id, instance_slug,
       initcap(replace(instance_slug, '-', ' ')),
       CASE WHEN instance_slug = 'income' THEN 'Evaluate parsed income documents' ELSE '' END
FROM (
    SELECT brain_id, instance_slug FROM lab_instance_release
    UNION SELECT brain_id, instance_slug FROM lab_instance_pointer
    UNION SELECT brain_id, instance_slug FROM lab_document_registration
    UNION SELECT brain_id, instance_slug FROM lab_run
) historical;

ALTER TABLE lab_instance_release
    ADD CONSTRAINT fk_lab_release_instance
    FOREIGN KEY (brain_id, instance_slug)
    REFERENCES lab_instance(brain_id, slug) ON DELETE RESTRICT;
ALTER TABLE lab_instance_pointer
    ADD CONSTRAINT fk_lab_pointer_instance
    FOREIGN KEY (brain_id, instance_slug)
    REFERENCES lab_instance(brain_id, slug) ON DELETE RESTRICT;
ALTER TABLE lab_document_registration
    ADD CONSTRAINT fk_lab_registration_instance
    FOREIGN KEY (brain_id, instance_slug)
    REFERENCES lab_instance(brain_id, slug) ON DELETE RESTRICT;
ALTER TABLE lab_run
    ADD CONSTRAINT fk_lab_run_instance
    FOREIGN KEY (brain_id, instance_slug)
    REFERENCES lab_instance(brain_id, slug) ON DELETE RESTRICT;

-- Stores only a canonical request digest and the durable result identity. It intentionally has
-- no request or response body columns, so idempotent replay cannot become a data-retention path.
CREATE TABLE lab_idempotency_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id UUID NOT NULL REFERENCES brains(id) ON DELETE RESTRICT,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    result_kind VARCHAR(40) NOT NULL,
    result_id UUID NOT NULL,
    result_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lab_idempotency_operation_key
      UNIQUE (brain_id, operation, idempotency_key),
    CONSTRAINT chk_lab_idempotency_sha CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER trg_lab_idempotency_immutable
    BEFORE UPDATE OR DELETE ON lab_idempotency_record
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();
