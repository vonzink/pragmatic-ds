-- The opaque per-loan scope a finding's subject key is hashed against.
--
-- A finding carries three identities: an anchor saying where to highlight it, an input digest
-- saying what the values were, and a subject key saying WHICH PROBLEM it is. Only the subject key
-- survives a re-upload, and it is what lets a loan officer's waiver on Monday match the same
-- problem on Tuesday's corrected document. Hashing it requires a per-loan scope, and without one
-- every finding publishes with a null subject key and no waiver can ever carry.
--
-- Why this hangs off the REGISTRATION and not the run. Identical to V42's reasoning for loan
-- facts: a run group's request is identifiers only, deliberately, so that two comparison members
-- "differ in exactly the declared dimension because there is no other channel through which they
-- could differ" — and a per-run scope would be exactly such a channel. A registration is the
-- loan's package, shared by every member comparing releases against it. RunGroupDispatcher
-- already re-resolves the registration on every dispatch, so a queued member picks this up with
-- no new run-path storage.
--
-- Why PLAINTEXT, where V42 is ciphertext-only. Loan facts are borrower financial figures. A
-- subject scope is a token the host app derives from a loan id and that this process contractually
-- cannot reverse — the boundary lab_connector_run_group_context states as "deliberately absent:
-- loan ids, folder ids, document names". Encrypting an opaque correlator would buy no
-- confidentiality while adding a key dependency to a read that must not fail for want of one.
-- tenant_id and external_request_id sit in plaintext next to it for the same reason.
--
-- Additive only. V1-V42 are untouched.

-- ============================================================ subject scope

CREATE TABLE lab_registration_subject_scope (
    registration_id UUID         PRIMARY KEY
                    REFERENCES lab_document_registration (id) ON DELETE RESTRICT,
    brain_id        UUID         NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    subject_scope   VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Bounded, non-blank, single-line. The same [[:cntrl:]] discipline V39 and V40 apply to
    -- actor_id and tenant_id: a caller's own string must never be able to forge a second line in
    -- any log or export that prints it.
    CONSTRAINT chk_lab_subject_scope_present
        CHECK (length(btrim(subject_scope)) BETWEEN 1 AND 200),
    CONSTRAINT chk_lab_subject_scope_clean
        CHECK (subject_scope !~ '[[:cntrl:]]')
);

-- The scope a run was queued under is not a fact that legitimately changes afterwards. UPDATE is
-- refused outright rather than column by column: a scope that can be edited is not a scope, and
-- editing one would silently rebind a queued run — and every waiver taken against it — to a
-- different loan. The service turns an identical re-write into a no-op and a differing one into
-- SUBJECT_SCOPE_CONFLICT; this trigger is what makes that true even if the service is wrong.
CREATE FUNCTION lab_registration_subject_scope_guard() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LAB_REGISTRATION_SUBJECT_SCOPE_IMMUTABLE' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_lab_registration_subject_scope_guard
    BEFORE UPDATE ON lab_registration_subject_scope
    FOR EACH ROW EXECUTE FUNCTION lab_registration_subject_scope_guard();

-- DELETE is deliberately NOT refused, the same asymmetry V42 makes for loan facts. A per-loan
-- correlator that no future retention sweep can reach is a worse property than one that cannot be
-- edited, and LabRegistrationSubjectScopeRepository already carries the FK-safe purge step.
