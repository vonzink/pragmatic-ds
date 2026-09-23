-- V15 — immutable machine-result descriptor and explicit parse generations.
-- Seed-free and additive: V1 through V14 remain immutable migration history.

-- A job attempt counts resumes as well as deliberate re-extraction. A parse
-- generation changes only when machine rows are deliberately regenerated.
ALTER TABLE processing_job
    ADD COLUMN parse_generation integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT processing_job_parse_generation_check CHECK (parse_generation > 0);

-- Keep the V3 state vocabulary byte-for-byte equivalent apart from FINALIZING.
-- The Java enum is added with the pipeline integration in Task 6.
ALTER TABLE processing_job DROP CONSTRAINT processing_job_status_check;
ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_status_check CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'NORMALIZING', 'RENDERING', 'TEXT_EXTRACTION',
        'OCR_PROCESSING', 'PARSING', 'CLASSIFYING', 'SPLITTING', 'EXTRACTING',
        'FINALIZING', 'VALIDATING_DATA', 'AI_REVIEW', 'HUMAN_REVIEW_REQUIRED',
        'COMPLETED', 'FAILED'));

-- Composite parent keys let every engine_result FK prove tenant identity in
-- the database instead of relying on an independent org_id column.
ALTER TABLE document_package
    ADD CONSTRAINT document_package_org_id_id_key UNIQUE (org_id, id);
ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_org_package_id_key UNIQUE (org_id, package_id, id);

CREATE TABLE engine_result (
    id                         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                     uuid        NOT NULL,
    package_id                 uuid        NOT NULL,
    processing_job_id          uuid        NOT NULL,
    parse_generation           integer     NOT NULL,
    materialized_job_attempt   integer     NOT NULL,
    revision                   integer     NOT NULL,
    supersedes_result_id       uuid,
    envelope_schema_version    text        NOT NULL,
    canonicalization_version   text        NOT NULL,
    canonical_media_type       text        NOT NULL,
    source_set_sha256          char(64)    NOT NULL,
    provenance_sha256          char(64)    NOT NULL,
    envelope_storage_key       text        NOT NULL,
    envelope_sha256            char(64)    NOT NULL,
    envelope_size_bytes        bigint      NOT NULL,
    reuse_eligibility          text        NOT NULL,
    created_at                 timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT engine_result_org_job_generation_key
        UNIQUE (org_id, processing_job_id, parse_generation),
    CONSTRAINT engine_result_org_package_revision_key
        UNIQUE (org_id, package_id, revision),
    CONSTRAINT engine_result_storage_key_key UNIQUE (envelope_storage_key),
    CONSTRAINT engine_result_org_package_id_key UNIQUE (org_id, package_id, id),

    CONSTRAINT engine_result_positive_values_check CHECK (
        parse_generation > 0 AND materialized_job_attempt > 0
        AND revision > 0 AND envelope_size_bytes > 0),
    CONSTRAINT engine_result_digest_format_check CHECK (
        source_set_sha256 ~ '^[0-9a-f]{64}$'
        AND provenance_sha256 ~ '^[0-9a-f]{64}$'
        AND envelope_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT engine_result_media_type_check CHECK (
        canonical_media_type =
            'application/vnd.pragmaticds.document-engine-result+json;version=1'),
    CONSTRAINT engine_result_envelope_version_check CHECK (
        envelope_schema_version = '1.0.0'
        AND canonicalization_version = 'DOCENGINE-C14N-1'),
    CONSTRAINT engine_result_storage_key_not_blank_check
        CHECK (btrim(envelope_storage_key) <> ''),
    CONSTRAINT engine_result_reuse_eligibility_check
        CHECK (reuse_eligibility = 'PARSE_ONCE_CURRENT_PACKAGE'),
    CONSTRAINT engine_result_revision_chain_shape_check CHECK (
        (revision = 1 AND supersedes_result_id IS NULL)
        OR (revision > 1 AND supersedes_result_id IS NOT NULL)),

    CONSTRAINT engine_result_package_fk
        FOREIGN KEY (org_id, package_id)
        REFERENCES document_package (org_id, id),
    CONSTRAINT engine_result_job_fk
        FOREIGN KEY (org_id, package_id, processing_job_id)
        REFERENCES processing_job (org_id, package_id, id),
    CONSTRAINT engine_result_predecessor_fk
        FOREIGN KEY (org_id, package_id, supersedes_result_id)
        REFERENCES engine_result (org_id, package_id, id)
);

CREATE UNIQUE INDEX engine_result_one_root_per_package
    ON engine_result (org_id, package_id)
    WHERE supersedes_result_id IS NULL;

CREATE UNIQUE INDEX engine_result_one_successor_per_predecessor
    ON engine_result (org_id, supersedes_result_id)
    WHERE supersedes_result_id IS NOT NULL;

CREATE INDEX engine_result_org_package_history_idx
    ON engine_result (org_id, package_id, revision DESC);

-- Direct SQL and future callers must not skip or fork the revision chain. The
-- lookup carries both tenant and package keys and runs with the caller's RLS.
CREATE FUNCTION enforce_engine_result_revision_chain() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    predecessor_revision integer;
BEGIN
    IF NEW.revision = 1 THEN
        RETURN NEW;
    END IF;

    SELECT revision
      INTO predecessor_revision
      FROM public.engine_result
     WHERE org_id = NEW.org_id
       AND package_id = NEW.package_id
       AND id = NEW.supersedes_result_id;

    IF predecessor_revision IS NULL OR predecessor_revision <> NEW.revision - 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'engine_result_revision_chain',
            MESSAGE = 'engine result predecessor must be revision N-1';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER engine_result_revision_chain_trigger
    BEFORE INSERT ON engine_result
    FOR EACH ROW EXECUTE FUNCTION enforce_engine_result_revision_chain();

ALTER TABLE engine_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE engine_result FORCE  ROW LEVEL SECURITY;

CREATE POLICY engine_result_tenant_select ON engine_result
    FOR SELECT USING (org_id = current_org());
CREATE POLICY engine_result_tenant_insert ON engine_result
    FOR INSERT WITH CHECK (org_id = current_org());
CREATE POLICY engine_result_tenant_delete ON engine_result
    FOR DELETE USING (org_id = current_org());

-- V1 grants new tables through ALTER DEFAULT PRIVILEGES. Result rows are
-- append-only to the application role; retention purge keeps DELETE.
REVOKE UPDATE ON engine_result FROM docengine_app;
