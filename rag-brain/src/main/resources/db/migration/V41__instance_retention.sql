-- Instance-plane retention: one additive change.
--
-- Group purges are audited as their own subject rather than borrowing a member's RUN identity,
-- so the tombstone names what was actually removed. The V37 pattern: replace the CHECK with the
-- widened value set; every previously-valid row stays valid.
ALTER TABLE lab_audit_event DROP CONSTRAINT chk_lab_audit_subject;
ALTER TABLE lab_audit_event ADD CONSTRAINT chk_lab_audit_subject
    CHECK (subject_type IN (
        'INSTANCE', 'RELEASE', 'REGISTRATION', 'ENVELOPE', 'RUN', 'EXCHANGE',
        'COLLECTION', 'SNAPSHOT', 'GROUP'));

-- Deliberately absent from this migration, as retention decisions rather than oversights:
--
--   * lab_idempotency_record keeps its V35 refuse-UPDATE-and-DELETE trigger. Its rows guard
--     idempotency for operations whose results are themselves retained immutably (releases,
--     registrations); expiring a key while its result lives would let a stale retry create a
--     duplicate of something that must be unique. They are small, bounded by admin activity,
--     and correct to keep.
--
--   * lab_model_catalog_version / lab_model_catalog_entry / lab_release_evaluation keep their
--     refuse-DELETE triggers: historical cost and promotion evidence outlive any run purge.
--
--   * lab_audit_event keeps refusing DELETE: a purge leaves a value-free tombstone, never
--     erases the record that the thing existed.
