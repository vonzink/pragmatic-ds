-- The loan-level facts an assets run needs and the Assets folder cannot contain.
--
-- Which agency's rule applies, whether the transaction is a purchase or a refinance, the
-- qualifying monthly income, and the Adjusted Value all live in the loan file, not on a bank
-- statement. Without them AssetsCalcService cannot select a large-deposit rule, and it reports
-- the screen as not run rather than borrowing another program's threshold.
--
-- Why this hangs off the REGISTRATION and not the run. A run group's request is identifiers
-- only, deliberately: RunGroupCommand exists so two comparison members "differ in exactly the
-- declared dimension because there is no other channel through which they could differ", and a
-- per-run income figure would be exactly such a channel. A registration is the loan's package,
-- shared by every member comparing releases against it, so attaching the facts here keeps the
-- comparison meaningful. RunGroupDispatcher already re-resolves the registration on every
-- dispatch, so a queued member picks these up with no new run-path storage.
--
-- Additive only. V1-V41 are untouched.

-- ============================================================ loan facts

-- Income and Adjusted Value are borrower financial figures, so this table stores AES-256-GCM
-- ciphertext and nothing else — the same treatment lab_run_payload gives report text. There is
-- no plaintext column here and no "list all facts" read: the only lookup is by the registration
-- a run is already authorized for.
--
-- facts_sha256 digests the canonical PLAINTEXT so a repeated write can be recognized as the
-- same facts without decrypting anything. It is a digest of numbers and enum names, never a
-- value in itself.
CREATE TABLE lab_registration_loan_facts (
    registration_id  UUID        PRIMARY KEY
                     REFERENCES lab_document_registration (id) ON DELETE RESTRICT,
    brain_id         UUID        NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    facts_sha256     VARCHAR(64) NOT NULL,
    cipher_algorithm VARCHAR(24) NOT NULL,
    nonce            BYTEA       NOT NULL,
    ciphertext       BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_lab_loan_facts_sha    CHECK (facts_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_lab_loan_facts_cipher CHECK (cipher_algorithm = 'AES-256-GCM'),
    CONSTRAINT chk_lab_loan_facts_nonce  CHECK (octet_length(nonce) = 12),
    -- At least a bare 128-bit GCM tag: a "ciphertext" too short to be authenticated cannot be
    -- stored at all.
    CONSTRAINT chk_lab_loan_facts_cipher_len CHECK (octet_length(ciphertext) >= 16)
);

CREATE INDEX idx_lab_loan_facts_brain ON lab_registration_loan_facts (brain_id, created_at DESC);

-- Immutable CONTENT, deletable ROW — the same split lab_run_payload makes, and for the same
-- reason. UPDATE is refused because a queued member re-resolves its registration at dispatch, so
-- facts that could be edited in place would let two members of one comparison group run against
-- different income figures depending only on when each was picked up; the service turns an
-- identical re-write into a no-op and a DIFFERING one into a refusal, so a correction is a
-- visible failure rather than a silent change under a run that already queued.
--
-- DELETE is deliberately NOT refused. These are borrower financial figures, and a row that can
-- never be removed is a worse property than one that can never be edited: it would put this
-- table permanently beyond the reach of any retention sweep.
CREATE TRIGGER trg_lab_loan_facts_immutable
    BEFORE UPDATE ON lab_registration_loan_facts
    FOR EACH ROW EXECUTE FUNCTION lab_reject_mutation();
