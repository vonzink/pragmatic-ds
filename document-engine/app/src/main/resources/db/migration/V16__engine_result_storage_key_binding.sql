-- V16 — bind every immutable result descriptor to its tenant-scoped,
-- content-addressed object key. The CHECK validates existing V15 rows when this
-- migration is applied and constrains every later insert.
ALTER TABLE engine_result
    ADD CONSTRAINT engine_result_storage_key_binding_check CHECK (
        envelope_storage_key =
            'org/' || org_id::text || '/engine-results/sha256/'
            || envelope_sha256::text || '.json'
    );
