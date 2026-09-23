-- V10 — W2 rule pack 1.1.0: weight anchors by EXCLUSIVITY, not by salience.
--
-- The defect (empirical, a real 23-page scanned 2024 Form 1040 package processed
-- 2026-08-08). An IRS Form 1040 legitimately prints "Form(s) W-2" on line 25a and
-- "Federal income tax withheld" as the line 25 header. Against W2@1.0.0 those two
-- anchors alone scored 4 + 2 = 6 of targetScore 10 = 0.60, which is EXACTLY
-- min_confidence 0.60 — and PageClassifier qualifies on `score >= minConfidence`.
-- The 1040 page would have been labelled W2 at a plausible-looking 0.6000. The
-- observed package escaped only because OCR captured "income tax withheld"
-- without the leading "Federal": it missed by one token, not by design.
--
-- Today a wrong type label is a wrong LABEL only — no W2 extraction schema exists,
-- so either way the page yields zero fields. The moment Spec 3 ships a W2 schema
-- it becomes a wrong-DATA defect, so it is fixed while it is still cheap.
--
-- The fix is DATA, per D7/D15 ("adding a doc type = one pack row, zero code"): a
-- new pack VERSION, not an edit of 1.0.0. classification_result.rule_pack_version
-- names the pack that decided each stored row, so mutating 1.0.0 in place would
-- silently re-write the meaning of every result already recorded against it.
-- RulePackLoader takes the highest version within the surviving scope, so 1.1.0
-- supersedes on load; 1.0.0 is retired rather than left active, because a pack
-- with a known false-positive must not become reachable again by deactivating
-- its successor.
--
-- What changed, and why each weight moved. The bug was NOT that the threshold was
-- too low — it was that the pack's two heaviest anchors are not W-2-exclusive.
-- Weight now tracks how much a phrase proves the page IS a W-2:
--
--   form-w2         "W-2"                            4 -> 2  a form REFERENCE. Printed
--                                                            on 1040 line 25a, on 1099
--                                                            cover letters, on tax-prep
--                                                            worksheets. Naming the form
--                                                            is not being the form.
--   wage-tax-stmt   "Wage and Tax Statement"         3 -> 5  the form's actual TITLE.
--                                                            W-2/W-3 only.
--   ein             "Employer identification number" 2 -> 2  employer-form family; absent
--                                                            from a 1040 (which carries an
--                                                            SSN). Unchanged.
--   fed-income-tax  "Federal income tax withheld"    2 -> 1  shared label: 1040 line 25,
--                                                            1099 box 4, many paystubs.
--   ss-wages        "Social security wages"          2 -> 3  W-2 box 3 / W-3. Exclusive.
--                                                            (A 1040 says "Social security
--                                                            BENEFITS", which does not match.)
--   copy-b          "Copy B"                         1 -> 2  "Copy B—To Be Filed With
--                                                            Employee's FEDERAL Tax Return".
--                                                            Exclusive.
--
-- targetScore stays 10 and min_confidence stays 0.60, so the change is legible as
-- pure re-weighting. The resulting INVARIANT (pinned by W2PackExclusivityIT):
-- every non-exclusive anchor matching at once is 2 + 1 + 2 = 5 = 0.50 < 0.60, so
-- the pack CANNOT qualify without at least one W-2-exclusive anchor. The observed
-- 1040 pair now scores 3 = 0.30. Recall is unharmed: the w2_form fixture carries
-- every anchor (15, capped to 1.0), and a scan that loses the title line to OCR
-- still reaches 10 = 1.0 on the box labels alone.
--
-- Negative/disqualifying anchors ("Form 1040", "Schedule 1"/"2") were considered
-- and rejected. Two reasons. (1) They fail open on the confusable set: it is
-- unbounded — 1040-SR, 1040-X, 1040-NR, Schedules 1/2/3, state equivalents — and
-- a pack that must enumerate its enemies is weaker than one that demands positive
-- proof. (2) The nearest-looking disqualifier is actively wrong: a genuine W-2
-- prints "Department of the Treasury—Internal Revenue Service", and Copy B/C
-- "Notice to Employee" text references Form 1040 — so those anchors would suppress
-- real W-2s. Weighting by exclusivity needs no enemy list.
--
-- On `score >= minConfidence` vs `>`: DELIBERATELY LEFT AS `>=`. See the decision
-- note in PageClassifier#decide. In short — `>=` is the documented contract ("at
-- or above ITS OWN pack's threshold"), it is what an operator means by a MINIMUM
-- confidence, and flipping it would shift every pack's effective bar by an epsilon
-- to paper over one pack's mis-weighting. An operator wanting a strict bar seeds
-- 0.6001; that is data, and this is not.
--
-- RLS ORDERING — the V6 lesson, one migration later. V6's rule is "seeds BEFORE
-- RLS" because FORCE ROW LEVEL SECURITY binds the table OWNER too. That option is
-- gone here: V6 already forced RLS on classification_rule_pack, and the per-command
-- policies admit only `org_id = current_org()` — no GUC value can ever satisfy that
-- for a GLOBAL (org_id NULL) row. So a later migration seeding an already-forced
-- table must drop FORCE for the duration and restore it. With ENABLE but not FORCE,
-- the owner bypasses policies; the transaction restores FORCE before it commits, so
-- no window exists for any other session. MigrationOwnershipIT (a plain NOSUPERUSER
-- login role owning the database) is what proves this actually applies in the
-- documented deployment topology rather than only in a superuser test container.
--
-- Forgetting the restore below would silently undo V6's owner-is-bound property.
-- That is already guarded and needs no new test: RlsCoverageIT asserts over pg_class
-- that EVERY non-exempt table carries relrowsecurity AND relforcerowsecurity, with no
-- per-table list to keep in sync — so a missing restore fails CI immediately.

ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

-- Retire the pack that could label a 1040 a W-2.
UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'W2' AND version = '1.0.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'W2', '1.1.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "form-w2",        "kind": "literal", "pattern": "W-2",                             "weight": 2},
    {"id": "wage-tax-stmt",  "kind": "literal", "pattern": "Wage and Tax Statement",          "weight": 5},
    {"id": "ein",            "kind": "literal", "pattern": "Employer identification number",  "weight": 2},
    {"id": "fed-income-tax", "kind": "literal", "pattern": "Federal income tax withheld",     "weight": 1},
    {"id": "ss-wages",       "kind": "literal", "pattern": "Social security wages",           "weight": 3},
    {"id": "copy-b",         "kind": "literal", "pattern": "Copy B",                          "weight": 2}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;
