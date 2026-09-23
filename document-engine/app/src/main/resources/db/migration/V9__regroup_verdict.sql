-- V9 — regroup & page-verdict overrides (Spec 2). No new table: the regroup path
-- edits logical_document_page (existing) and writes REGROUP review_decision rows
-- (existing action). Only the page-VERDICT decision needs new CHECK values:
-- a PAGE subject with an OVERRIDE_VERDICT action. Constraint-only; seeds nothing;
-- RLS and MigrationOwnershipIT seed counts are unchanged.

ALTER TABLE review_decision DROP CONSTRAINT review_decision_subject_type_check;
ALTER TABLE review_decision ADD CONSTRAINT review_decision_subject_type_check
    CHECK (subject_type IN ('EXTRACTED_FIELD', 'LOGICAL_DOCUMENT',
                            'PAGE_ASSIGNMENT', 'CLASSIFICATION', 'PAGE'));

ALTER TABLE review_decision DROP CONSTRAINT review_decision_action_check;
ALTER TABLE review_decision ADD CONSTRAINT review_decision_action_check
    CHECK (action IN ('CONFIRM', 'CORRECT', 'REJECT', 'RECLASSIFY', 'REGROUP',
                      'MARK_REVIEWED', 'OVERRIDE_VERDICT'));
