-- V7 — extraction: versioned extraction schemas, extracted fields, and the
-- field-level evidence chain. docs/DATA_MODEL.md section 6.
--
-- Extraction schemas are DATA, not code (design decision D8/D15), exactly like
-- classification rule packs: the engine ships a generic paystub@1.0.0 built-in
-- (org_id NULL = global); richer domain schemas stay private and load as
-- org-scoped rows. Adding a field is a schema-version bump — no Java change
-- (Phase 5 acceptance criterion 5, proven by test).
--
-- Schema anchors below are authored TOGETHER with fixtures/generate.py's page
-- text: fixture phrases and label patterns are the same strings by construction.

CREATE TABLE extraction_schema (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL = global built-in, visible to every org (see RLS policy below).
    org_id              uuid        REFERENCES tenant (id),
    document_type_code  text        NOT NULL,
    version             text        NOT NULL,
    definition          jsonb       NOT NULL,
    is_active           boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX extraction_schema_scope_key
    ON extraction_schema (
        COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid),
        document_type_code,
        version
    );

-- Layer 2 of the four value layers: the deterministic normalized result.
-- A field that could not be found still gets a row — confidence 0, no evidence,
-- MANUAL_REVIEW_REQUIRED. A missing field is a RESULT, not an absence.
CREATE TABLE extracted_field (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 uuid        NOT NULL REFERENCES tenant (id),
    -- CASCADE: a re-split invalidates extraction wholesale. Human decisions are
    -- Phase 7's review_decision rows, which will NOT hang off this table.
    logical_document_id    uuid        NOT NULL REFERENCES logical_document (id)
                                           ON DELETE CASCADE,
    schema_id              uuid        NOT NULL REFERENCES extraction_schema (id),
    field_name             text        NOT NULL,
    data_type              text        NOT NULL,
    -- Exactly as it appears on the page: "$3,565.87".
    displayed_text         text,
    -- As captured, before normalization (may differ from displayed on OCR pages).
    raw_value              text,
    normalized_text        text,
    normalized_number      numeric(18,4),
    normalized_date        date,
    normalized_json        jsonb,
    extraction_method      text        NOT NULL,
    extractor_version      text        NOT NULL,
    confidence             numeric(5,4) NOT NULL,
    -- The formula's inputs stored separately so the score is auditable and
    -- tunable rather than a bare number (Phase 5 risk mitigation):
    -- {"spanConfidence", "anchorStrength", "normalizerCertainty"}.
    confidence_components  jsonb,
    validation_status      text        NOT NULL DEFAULT 'NOT_VALIDATED',
    review_status          text        NOT NULL DEFAULT 'NOT_REVIEWED',
    is_sensitive           boolean     NOT NULL DEFAULT false,
    is_current             boolean     NOT NULL DEFAULT true,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT extracted_field_data_type_check
        CHECK (data_type IN ('STRING', 'NUMBER', 'DATE', 'ENUM', 'MONEY')),
    CONSTRAINT extracted_field_method_check
        CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX',
                                     'FORM_FIELD', 'OCR_LINE', 'LLM', 'HUMAN',
                                     'NONE')),
    CONSTRAINT extracted_field_validation_check
        CHECK (validation_status IN ('NOT_VALIDATED', 'VALID', 'WARNING', 'ERROR',
                                     'UNABLE_TO_VALIDATE', 'MANUAL_REVIEW_REQUIRED')),
    CONSTRAINT extracted_field_review_check
        CHECK (review_status IN ('NOT_REVIEWED', 'CONFIRMED', 'CORRECTED', 'REJECTED'))
);

-- UNIQUE, not merely indexed: supersession rides on a database guarantee that a
-- duplicate is_current can never exist (same rule as classification_result).
CREATE UNIQUE INDEX extracted_field_one_current
    ON extracted_field (org_id, logical_document_id, field_name) WHERE is_current;

CREATE INDEX extracted_field_document_idx
    ON extracted_field (org_id, logical_document_id);

-- The traceability spine — the table the entire system exists to make possible.
-- Boxes are DENORMALIZED (D10): a reparse regenerates spans and layout elements,
-- and the box a human already reviewed must not silently move underneath them.
-- The span/element references may go NULL on reparse; the coordinates never do.
CREATE TABLE field_evidence (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        NOT NULL REFERENCES tenant (id),
    extracted_field_id  uuid        NOT NULL REFERENCES extracted_field (id)
                                        ON DELETE CASCADE,
    page_id             uuid        NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    layout_element_id   uuid        REFERENCES layout_element (id) ON DELETE SET NULL,
    text_span_id        bigint      REFERENCES text_span (id) ON DELETE SET NULL,
    x                   numeric(10,2) NOT NULL,
    y                   numeric(10,2) NOT NULL,
    width               numeric(10,2) NOT NULL,
    height              numeric(10,2) NOT NULL,
    -- The label is evidence: "$3,565.87" alone proves nothing — "Net Pay" to its
    -- left is the entire reason it was read as net pay.
    role                text        NOT NULL,
    ordinal             int         NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT field_evidence_role_check
        CHECK (role IN ('VALUE', 'LABEL', 'CONTEXT'))
);

CREATE INDEX field_evidence_field_idx
    ON field_evidence (org_id, extracted_field_id, role, ordinal);
CREATE INDEX field_evidence_page_idx ON field_evidence (org_id, page_id);

-- Phase 4 latent fix: a re-render (second job on the same package) bulk-deletes
-- page rows, which the V6 plain FK on the split join table would veto. The link
-- is derived state — it must follow its page, exactly as evidence does.
ALTER TABLE logical_document_page
    DROP CONSTRAINT logical_document_page_page_id_fkey;
ALTER TABLE logical_document_page
    ADD CONSTRAINT logical_document_page_page_id_fkey
        FOREIGN KEY (page_id) REFERENCES page (id) ON DELETE CASCADE;


-- ── Seed: the paystub@1.0.0 built-in schema ─────────────────────────────────
-- Seeds run BEFORE RLS is enabled (Phase 4 review, confirmed-by-test): FORCE ROW
-- LEVEL SECURITY binds the table OWNER too, so a migration that forces RLS first
-- can never apply under the documented Flyway-as-non-superuser-owner deployment.
-- MigrationOwnershipIT runs the whole chain as a plain login role.
--
-- Definition format (parsed by ExtractionSchemaLoader):
--   {"fields": [{"name", "dataType": STRING|NUMBER|DATE|ENUM|MONEY, "required",
--                "normalizer": money|date|payFrequency|personName|null,
--                "sensitive",
--                "extractors": [ordered, first success wins — each:
--                  {"method": ANCHOR_LABEL|TABLE_CLUSTER|REGEX,
--                   "strength": 0..1 (anchor-strength confidence component),
--                   "label": {"kind": literal|regex, "pattern"}   (ANCHOR_LABEL),
--                   "table": {"rowLabel": {...}, "columnHeader": {...}} (TABLE_CLUSTER),
--                   "value": {"pattern": regex, "occurrence": n,
--                             "scope": LINE_RIGHT|LINE|PAGE}}]}]}
INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'PAYSTUB', '1.0.0', '{
  "fields": [
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employee:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "employerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,3} (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}},
       {"method": "REGEX", "strength": 0.4,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,3} (?:LLC|Inc\\.?|Corp\\.?|Co\\.|Ltd\\.?|Company)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "payPeriodStart", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Pay Period"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "payPeriodEnd", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Pay Period"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 1, "scope": "LINE_RIGHT"}}]},
    {"name": "payDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Pay Date"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "payFrequency", "dataType": "ENUM", "required": true,
     "normalizer": "payFrequency", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Pay Frequency"},
        "value": {"pattern": "(?i)(?<![A-Za-z])(?<![A-Za-z][- ])(?:bi[- ]?weekly|semi[- ]?monthly|weekly|monthly)(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "currentGrossPay", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "TABLE_CLUSTER", "strength": 1.0,
        "table": {"rowLabel": {"kind": "literal", "pattern": "Gross"},
                  "columnHeader": {"kind": "literal", "pattern": "Current"}},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE"}},
       {"method": "ANCHOR_LABEL", "strength": 0.8,
        "label": {"kind": "literal", "pattern": "Gross Pay"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.7,
        "label": {"kind": "literal", "pattern": "Gross"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "ytdGrossPay", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "TABLE_CLUSTER", "strength": 1.0,
        "table": {"rowLabel": {"kind": "literal", "pattern": "Gross"},
                  "columnHeader": {"kind": "literal", "pattern": "YTD"}},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE"}},
       {"method": "ANCHOR_LABEL", "strength": 0.8,
        "label": {"kind": "literal", "pattern": "Gross Pay"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 1, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.7,
        "label": {"kind": "literal", "pattern": "Gross"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 1, "scope": "LINE_RIGHT"}}]},
    {"name": "netPay", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Net Pay"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "federalWithholding", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Federal Withholding"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);


-- ── RLS ─────────────────────────────────────────────────────────────────────

ALTER TABLE extraction_schema  ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_schema  FORCE  ROW LEVEL SECURITY;
ALTER TABLE extracted_field    ENABLE ROW LEVEL SECURITY;
ALTER TABLE extracted_field    FORCE  ROW LEVEL SECURITY;
ALTER TABLE field_evidence     ENABLE ROW LEVEL SECURITY;
ALTER TABLE field_evidence     FORCE  ROW LEVEL SECURITY;

-- Schemas: PER-COMMAND policies, same rule as classification_rule_pack — reads
-- include the globals; every write touches only own-org rows, so a built-in is
-- immutable from any tenant session.
CREATE POLICY extraction_schema_read ON extraction_schema
    FOR SELECT USING (org_id IS NULL OR org_id = current_org());
CREATE POLICY extraction_schema_insert ON extraction_schema
    FOR INSERT WITH CHECK (org_id = current_org());
CREATE POLICY extraction_schema_update ON extraction_schema
    FOR UPDATE USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY extraction_schema_delete ON extraction_schema
    FOR DELETE USING (org_id = current_org());

CREATE POLICY extracted_field_isolation ON extracted_field
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY field_evidence_isolation ON field_evidence
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
