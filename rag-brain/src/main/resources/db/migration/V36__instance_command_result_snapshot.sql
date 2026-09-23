-- V36: immutable, safe response snapshots for generalized instance mutation replays.
-- The idempotency receipt remains body-free; it points at this narrowly scoped result identity.
CREATE TABLE lab_instance_command_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id UUID NOT NULL REFERENCES brains(id) ON DELETE RESTRICT,
    instance_slug VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    state VARCHAR(16) NOT NULL,
    instance_created_at TIMESTAMPTZ NOT NULL,
    instance_updated_at TIMESTAMPTZ NOT NULL,
    candidate_count INTEGER NOT NULL,
    has_candidate_release BOOLEAN NOT NULL,
    live_release_id UUID,
    live_release_number INTEGER,
    live_provenance VARCHAR(24),
    live_manifest_version INTEGER,
    live_provider VARCHAR(120),
    live_model VARCHAR(240),
    live_collection_count INTEGER,
    live_limitation_code VARCHAR(120),
    live_limitation_flags JSONB NOT NULL DEFAULT '[]'::jsonb,
    live_created_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_lab_instance_command_result_instance FOREIGN KEY (brain_id, instance_slug)
      REFERENCES lab_instance(brain_id, slug) ON DELETE RESTRICT,
    CONSTRAINT chk_lab_instance_command_result_state CHECK (state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_lab_instance_command_result_candidate_count CHECK (candidate_count >= 0)
);

CREATE TRIGGER trg_lab_instance_command_result_immutable
    BEFORE UPDATE OR DELETE ON lab_instance_command_result
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();
