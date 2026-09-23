-- V6 — classification: document types, versioned rule packs, results, and the
-- logical documents that package splitting produces. docs/DATA_MODEL.md section 5.
--
-- Rule packs are DATA, not code (design decision D7/D15): the engine ships three
-- generic built-ins (org_id NULL = global); richer domain packs stay in a
-- private repository and load as org-scoped rows at runtime. Adding a document
-- type requires a document_type row + a pack row — no Java change (Phase 4
-- acceptance criterion 4, proven by test).
--
-- Pack anchors below are authored TOGETHER with fixtures/generate.py's page text:
-- fixture phrases and anchor patterns are the same strings by construction.

CREATE TABLE document_type (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL = global built-in, visible to every org (see RLS policy below).
    org_id        uuid        REFERENCES tenant (id),
    code          text        NOT NULL,
    display_name  text        NOT NULL,
    category      text,
    is_active     boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX document_type_scope_code_key
    ON document_type (COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TABLE classification_rule_pack (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        REFERENCES tenant (id),
    document_type_code  text        NOT NULL,
    version             text        NOT NULL,
    definition          jsonb       NOT NULL,
    min_confidence      numeric(5,4) NOT NULL,
    is_active           boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX classification_rule_pack_scope_key
    ON classification_rule_pack (
        COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid),
        document_type_code,
        version
    );

-- Append-only: one row per classification attempt (docs/DATA_MODEL.md).
CREATE TABLE classification_result (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        NOT NULL REFERENCES tenant (id),
    subject_type        text        NOT NULL,
    subject_id          uuid        NOT NULL,
    document_type_code  text        NOT NULL,
    confidence          numeric(5,4) NOT NULL,
    method              text        NOT NULL,
    rule_pack_version   text,
    -- Matched anchors WITH their span ids and boxes — the reviewer-arguable
    -- evidence that is the whole point of a deterministic classifier.
    evidence            jsonb       NOT NULL,
    is_current          boolean     NOT NULL DEFAULT true,
    created_by          uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT classification_result_subject_check
        CHECK (subject_type IN ('PAGE', 'LOGICAL_DOCUMENT')),
    CONSTRAINT classification_result_method_check
        CHECK (method IN ('RULE_ANCHOR', 'ML', 'LLM', 'HUMAN'))
);

-- UNIQUE, not merely indexed: the supersession code path rides on a database
-- guarantee that a duplicate is_current can never exist (Phase 4 review).
CREATE UNIQUE INDEX classification_result_one_current
    ON classification_result (org_id, subject_type, subject_id) WHERE is_current;

CREATE TABLE logical_document (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                      uuid        NOT NULL REFERENCES tenant (id),
    package_id                  uuid        NOT NULL REFERENCES document_package (id),
    ordinal                     int         NOT NULL,
    document_type_code          text        NOT NULL,
    classification_confidence   numeric(5,4),
    review_status               text        NOT NULL DEFAULT 'NOT_REVIEWED',
    reviewed_by                 uuid,
    reviewed_at                 timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT logical_document_review_check
        CHECK (review_status IN ('NOT_REVIEWED', 'IN_REVIEW', 'REVIEWED'))
);

CREATE INDEX logical_document_package_idx ON logical_document (org_id, package_id, ordinal);

-- A join table rather than a page range: human regrouping (Spec 2) can produce
-- non-contiguous documents. A page belongs to AT MOST one document; blank and
-- duplicate pages stay unassigned in Phase 4.
CREATE TABLE logical_document_page (
    org_id               uuid NOT NULL REFERENCES tenant (id),
    logical_document_id  uuid NOT NULL REFERENCES logical_document (id),
    page_id              uuid NOT NULL REFERENCES page (id),
    ordinal              int  NOT NULL,
    PRIMARY KEY (logical_document_id, page_id)
);

CREATE UNIQUE INDEX logical_document_page_page_key ON logical_document_page (page_id);

-- ── Seeds: eight built-in types ─────────────────────────────────────────────
-- Seeds run BEFORE RLS is enabled (Phase 4 review, confirmed-by-test): FORCE ROW
-- LEVEL SECURITY binds the table OWNER too, so a migration that forces RLS first
-- can never apply under the documented Flyway-as-non-superuser-owner deployment —
-- it only ever worked in superuser test containers, which bypass RLS entirely.
-- MigrationOwnershipIT now runs the whole chain as a plain login role.
INSERT INTO document_type (org_id, code, display_name, category) VALUES
    (NULL, 'PAYSTUB',            'Paystub',                        'INCOME'),
    (NULL, 'W2',                 'W-2 Wage and Tax Statement',     'INCOME'),
    (NULL, 'BANK_STATEMENT',     'Bank Statement',                 'ASSET'),
    (NULL, 'DRIVERS_LICENSE',    'Driver''s License',              'IDENTITY'),
    (NULL, 'MORTGAGE_STATEMENT', 'Mortgage Statement',             'LIABILITY'),
    (NULL, 'HOI_DECLARATION',    'Homeowners Insurance Declaration', 'PROPERTY'),
    (NULL, 'PURCHASE_CONTRACT',  'Purchase Contract',              'PROPERTY'),
    (NULL, 'UNKNOWN',            'Unknown',                        NULL);

-- ── Seeds: three built-in rule packs (v1 pack format) ───────────────────────
-- Format: {"anchors": [{"id", "kind": literal|regex, "pattern", "weight"}],
--          "targetScore": N} — score = min(1, sum(matched weights)/targetScore);
-- literal = case-insensitive containment over the page's reading-order text with
-- span mapping; evidence records each matched anchor's span ids + boxes.
INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'PAYSTUB', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "pay-period",     "kind": "literal", "pattern": "Pay Period",          "weight": 3},
    {"id": "gross-pay",      "kind": "literal", "pattern": "Gross Pay",           "weight": 3},
    {"id": "net-pay",        "kind": "literal", "pattern": "Net Pay",             "weight": 2},
    {"id": "pay-date",       "kind": "literal", "pattern": "Pay Date",            "weight": 2},
    {"id": "earnings",       "kind": "literal", "pattern": "Earnings",            "weight": 2},
    {"id": "fed-withholding","kind": "literal", "pattern": "Federal Withholding", "weight": 2},
    {"id": "ytd",            "kind": "regex",   "pattern": "\\bYTD\\b",           "weight": 1}
  ]
}'::jsonb),
(NULL, 'W2', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "form-w2",        "kind": "literal", "pattern": "W-2",                             "weight": 4},
    {"id": "wage-tax-stmt",  "kind": "literal", "pattern": "Wage and Tax Statement",          "weight": 3},
    {"id": "ein",            "kind": "literal", "pattern": "Employer identification number",  "weight": 2},
    {"id": "fed-income-tax", "kind": "literal", "pattern": "Federal income tax withheld",     "weight": 2},
    {"id": "ss-wages",       "kind": "literal", "pattern": "Social security wages",           "weight": 2},
    {"id": "copy-b",         "kind": "literal", "pattern": "Copy B",                          "weight": 1}
  ]
}'::jsonb),
(NULL, 'BANK_STATEMENT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "stmt-period",    "kind": "literal", "pattern": "Statement Period",     "weight": 3},
    {"id": "begin-balance",  "kind": "literal", "pattern": "Beginning Balance",    "weight": 2},
    {"id": "end-balance",    "kind": "literal", "pattern": "Ending Balance",       "weight": 2},
    {"id": "acct-statement", "kind": "literal", "pattern": "Account Statement",    "weight": 2},
    {"id": "deposits",       "kind": "literal", "pattern": "Deposits and Credits", "weight": 2},
    {"id": "withdrawals",    "kind": "literal", "pattern": "Withdrawals",          "weight": 1}
  ]
}'::jsonb);

-- ── RLS ─────────────────────────────────────────────────────────────────────
ALTER TABLE document_type            ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_type            FORCE  ROW LEVEL SECURITY;
ALTER TABLE classification_rule_pack ENABLE ROW LEVEL SECURITY;
ALTER TABLE classification_rule_pack FORCE  ROW LEVEL SECURITY;
ALTER TABLE classification_result    ENABLE ROW LEVEL SECURITY;
ALTER TABLE classification_result    FORCE  ROW LEVEL SECURITY;
ALTER TABLE logical_document         ENABLE ROW LEVEL SECURITY;
ALTER TABLE logical_document         FORCE  ROW LEVEL SECURITY;
ALTER TABLE logical_document_page    ENABLE ROW LEVEL SECURITY;
ALTER TABLE logical_document_page    FORCE  ROW LEVEL SECURITY;

-- Types and packs: PER-COMMAND policies (Phase 4 review, confirmed-by-test). A
-- single shared USING clause makes globals readable — but DELETE consults only
-- USING (any tenant could delete the built-ins) and UPDATE could hijack a global
-- row into the tenant's own org. Reads include globals; every write touches only
-- own-org rows, so a built-in is immutable from any tenant session.
CREATE POLICY document_type_read ON document_type
    FOR SELECT USING (org_id IS NULL OR org_id = current_org());
CREATE POLICY document_type_insert ON document_type
    FOR INSERT WITH CHECK (org_id = current_org());
CREATE POLICY document_type_update ON document_type
    FOR UPDATE USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY document_type_delete ON document_type
    FOR DELETE USING (org_id = current_org());

CREATE POLICY classification_rule_pack_read ON classification_rule_pack
    FOR SELECT USING (org_id IS NULL OR org_id = current_org());
CREATE POLICY classification_rule_pack_insert ON classification_rule_pack
    FOR INSERT WITH CHECK (org_id = current_org());
CREATE POLICY classification_rule_pack_update ON classification_rule_pack
    FOR UPDATE USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY classification_rule_pack_delete ON classification_rule_pack
    FOR DELETE USING (org_id = current_org());

CREATE POLICY classification_result_isolation ON classification_result
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY logical_document_isolation ON logical_document
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY logical_document_page_isolation ON logical_document_page
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
