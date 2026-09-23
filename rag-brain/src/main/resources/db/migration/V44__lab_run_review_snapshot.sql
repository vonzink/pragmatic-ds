-- One value-free record of the engine read-model snapshot a run consumed. Immutable like
-- lab_run_document: the run's inputs are a historical fact.
CREATE TABLE lab_run_review_snapshot (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           UUID        NOT NULL REFERENCES lab_run (id) ON DELETE RESTRICT,
    fields_sha256    VARCHAR(64) NOT NULL,
    document_count   INT         NOT NULL,
    machine_count    INT         NOT NULL,
    corrected_count  INT         NOT NULL,
    rejected_count   INT         NOT NULL,
    schema_versions  JSONB       NOT NULL,
    captured_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_run_review_sha    CHECK (fields_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_run_review_counts CHECK (document_count >= 0 AND machine_count >= 0
                                                AND corrected_count >= 0 AND rejected_count >= 0),
    CONSTRAINT uq_lab_run_review_run UNIQUE (run_id)
);

CREATE TRIGGER trg_lab_run_review_immutable
    BEFORE UPDATE ON lab_run_review_snapshot
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();
