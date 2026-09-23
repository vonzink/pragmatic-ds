-- V8 — review and audit: the four Layer-3/4 tables that turn the four-value-layer
-- model into an audit trail instead of decoration. docs/DATA_MODEL.md section 7.
--
-- All four are APPEND-ONLY and all four carry org_id + ENABLE/FORCE RLS + an
-- isolation policy (RlsCoverageIT enforces this structurally). No seeds, so no
-- owner-binding ordering concern (V6/V7's seeds-before-RLS rule is moot here) and
-- MigrationOwnershipIT's seed counts (rule_pack==3, extraction_schema==1) are
-- untouched. No grants — V1's ALTER DEFAULT PRIVILEGES already covers new tables.
--
-- This phase creates validation_finding and ai_interpretation as TABLES ONLY:
-- their schema exists now so Spec 4 (validation) and Spec 5 (AI) add RULES, not
-- migrations. review_decision and audit_event get JPA entities in :review and
-- :platform respectively.

-- ── validation_finding (Spec 4) — Layer of derived validation, is_current ───────
-- Supersession via is_current (a re-validation flips the prior finding off), same
-- rule as extracted_field. message_template + message_params are PII-FREE.
CREATE TABLE validation_finding (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid        NOT NULL REFERENCES tenant (id),
    package_id           uuid        NOT NULL REFERENCES document_package (id),
    logical_document_id  uuid        REFERENCES logical_document (id),
    rule_code            text        NOT NULL,
    rule_version         text        NOT NULL,
    severity             text        NOT NULL,
    -- PII-free template + non-sensitive params only (never document content).
    message_template     text        NOT NULL,
    message_params       jsonb,
    -- Fields the rule spans — cross-document rules cite several.
    subject_field_ids    uuid[],
    is_current           boolean     NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT validation_finding_severity_check
        CHECK (severity IN ('VALID', 'WARNING', 'ERROR', 'UNABLE_TO_VALIDATE',
                            'MANUAL_REVIEW_REQUIRED'))
);

CREATE INDEX validation_finding_package_idx
    ON validation_finding (org_id, package_id) WHERE is_current;
CREATE INDEX validation_finding_document_idx
    ON validation_finding (org_id, logical_document_id) WHERE is_current;

-- ── ai_interpretation (Spec 5) — Layer 3. Append-only, NEVER auto-merged ────────
-- The LLM proposes; it never overwrites. A user promoting an interpretation writes
-- a review_decision, which keeps the human accountable for the change.
CREATE TABLE ai_interpretation (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid         NOT NULL REFERENCES tenant (id),
    subject_type    text         NOT NULL,
    subject_id      uuid         NOT NULL,
    provider        text         NOT NULL,
    model           text         NOT NULL,
    prompt_version  text         NOT NULL,
    interpretation  jsonb        NOT NULL,
    confidence      numeric(5,4),
    tokens_in       int,
    tokens_out      int,
    cost_usd        numeric(10,6),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ai_interpretation_subject_type_check
        CHECK (subject_type IN ('PAGE', 'LOGICAL_DOCUMENT', 'EXTRACTED_FIELD'))
);

CREATE INDEX ai_interpretation_subject_idx
    ON ai_interpretation (org_id, subject_type, subject_id, created_at DESC);

-- ── review_decision — Layer 4. Append-only human decisions ──────────────────────
-- The audit trail's SUBSTANCE. A correction is a row here (previous_value ->
-- new_value), never an in-place overwrite of the machine's Layer-2 extracted_field
-- value — which stays permanently readable. There is deliberately NO is_current:
-- the effective value is derived at READ time (latest CORRECT wins), so there is
-- nothing to supersede. This drives the UI's "original -> corrected -> user ->
-- timestamp" strip directly, with no projection table that could drift.
--
-- decided_by is uuid NOT NULL but carries NO foreign key, exactly like
-- classification_result.created_by and logical_document.reviewed_by: users are
-- provisioned out-of-band, so the engine records the accountable id without owning
-- the app_user lifecycle. NOT NULL is the invariant that matters — a SYSTEM actor
-- (no userId) can never author a review decision; a human is always accountable.
CREATE TABLE review_decision (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid         NOT NULL REFERENCES tenant (id),
    subject_type    text         NOT NULL,
    subject_id      uuid         NOT NULL,
    action          text         NOT NULL,
    -- PII WARNING: previous_value/new_value MAY carry a corrected field value the
    -- user typed. They live only here (never in a log, never in audit_event.metadata)
    -- and are masked at the read boundary exactly like extracted_field.
    previous_value  jsonb,
    new_value       jsonb,
    reason          text,
    decided_by      uuid         NOT NULL,
    decided_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT review_decision_subject_type_check
        CHECK (subject_type IN ('EXTRACTED_FIELD', 'LOGICAL_DOCUMENT',
                                'PAGE_ASSIGNMENT', 'CLASSIFICATION')),
    CONSTRAINT review_decision_action_check
        CHECK (action IN ('CONFIRM', 'CORRECT', 'REJECT', 'RECLASSIFY', 'REGROUP',
                          'MARK_REVIEWED'))
);

CREATE INDEX review_decision_subject_idx
    ON review_decision (org_id, subject_type, subject_id, decided_at DESC);

-- ── audit_event — everything that happened, including reads of sensitive data ────
-- High-volume, so a bigint identity key (same trade as text_span). metadata is
-- PII-FREE (ids/counts/codes only) and ip_hash is HASHED, never raw.
CREATE TABLE audit_event (
    id            bigint       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    org_id        uuid         NOT NULL REFERENCES tenant (id),
    actor_type    text         NOT NULL,
    actor_id      uuid,
    action        text         NOT NULL,
    subject_type  text,
    subject_id    uuid,
    request_id    text,
    -- KEYED hash (HMAC-SHA256, server secret), never raw. NULL when no secret is
    -- configured — an unkeyed hash over the 2^32 IPv4 space is reversible (Phase 7b review).
    ip_hash       text,
    -- PII-free: ids, counts, codes — never a field value or document content.
    metadata      jsonb,
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT audit_event_actor_type_check
        CHECK (actor_type IN ('USER', 'SYSTEM', 'API_KEY'))
);

CREATE INDEX audit_event_occurred_idx ON audit_event (org_id, occurred_at DESC);
CREATE INDEX audit_event_subject_idx ON audit_event (org_id, subject_type, subject_id);


-- ── RLS ─────────────────────────────────────────────────────────────────────
-- Every table: ENABLE + FORCE (binds the owner too), plus a single isolation
-- policy scoping every command to the caller's org. No globals here, so no
-- per-command policy split (unlike extraction_schema / classification_rule_pack).

ALTER TABLE validation_finding  ENABLE ROW LEVEL SECURITY;
ALTER TABLE validation_finding  FORCE  ROW LEVEL SECURITY;
ALTER TABLE ai_interpretation   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_interpretation   FORCE  ROW LEVEL SECURITY;
ALTER TABLE review_decision     ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_decision     FORCE  ROW LEVEL SECURITY;
ALTER TABLE audit_event         ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event         FORCE  ROW LEVEL SECURITY;

CREATE POLICY validation_finding_isolation ON validation_finding
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY ai_interpretation_isolation ON ai_interpretation
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY review_decision_isolation ON review_decision
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
CREATE POLICY audit_event_isolation ON audit_event
    USING (org_id = current_org()) WITH CHECK (org_id = current_org());
