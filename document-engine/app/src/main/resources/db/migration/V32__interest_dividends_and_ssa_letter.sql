-- V32 — Spec 6e: interest/dividend income and the SSA award letter.
--
--   SCHEDULE_B        authored from the committed 2025 blank
--                     (docs/reference-forms/f1040sb.pdf). The payer lists are the
--                     eventual repeating-group follow-up; what underwriting reads —
--                     and what this schema pins — are the line 2/4/6 totals, which
--                     print label-left / amount-right and read LINE_RIGHT.
--   SSA_AWARD_LETTER  authored from SSA's own published sample
--                     (docs/reference-forms/SSAL.pdf, the fictional "Jonathan Doe"
--                     Benefit Verification Letter). The engine's first PROSE
--                     document: no boxes, no grid — the benefit amounts live inside
--                     sentences, and each rung reads LINE_RIGHT off the sentence
--                     fragment that names it, wrapped where SSA wraps it.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'SCHEDULE_B', 'Interest and Ordinary Dividends (Schedule B)', 'INCOME',
     'IRS Schedule B (Form 1040): payer lists for interest (Part I) and ordinary '
     'dividends (Part II) with line totals, then the foreign-accounts questions; '
     'usually a single page attached behind a 1040.'),
    (NULL, 'SSA_AWARD_LETTER', 'SSA Benefit Verification Letter', 'INCOME',
     'A Social Security Administration benefit verification (award) letter: SSA '
     'letterhead, date and BNC number, addressee block, then prose paragraphs '
     'stating the monthly benefit, deductions and net payment; one letter per '
     'beneficiary, usually one or two pages.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- SCHEDULE_B 1.0.0. Weight = exclusivity (V10):
--   b-title    "Interest and Ordinary Dividends"  5  the form's title.      EXCLUSIVE
--   b-payer    "List name of payer"               3  Parts I AND II print it. EXCLUSIVE
--   b-footer   (?i)Schedule B \(Form 1040\)       2  the one-row footer id. EXCLUSIVE
--   b-ee       "Excludable interest on series EE" 1  line 3.                EXCLUSIVE
--   b-fincen   "FinCEN Form 114"                  1  Part III caution.      EXCLUSIVE
--   b-names    "Name(s) shown on return"          1  shared: every 1040 schedule
-- Non-exclusive sum = 1 = 0.10 (the schedule_e fixture prints the shared header and
-- scores exactly that here). Title lost to OCR: 3+2+1+1+1 = 8 = 0.80.
--
-- SSA_AWARD_LETTER 1.0.0.
--   ssal-title   "Benefit Verification Letter"                       5  EXCLUSIVE
--   ssal-current "Information About Current Social Security Benefits" 3 EXCLUSIVE
--   ssal-monthly "monthly Social Security benefit"                   2  EXCLUSIVE —
--                the SSA-1099 prints "SOCIAL SECURITY BENEFIT STATEMENT", which does
--                NOT contain this phrase ("monthly" gates it), and the letter never
--                prints the statement title: both directions pinned by CrossConfusionIT.
--   ssal-ssa     "Social Security Administration"                    1  shared: SSA mail
--   ssal-bnc     \bBNC\b                                             1  letterhead id
-- Non-exclusive sum = 2 = 0.20. Title lost: 3+2+1+1 = 7 = 0.70.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'SCHEDULE_B', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "b-title", "kind": "literal", "pattern": "Interest and Ordinary Dividends", "weight": 5},
    {"id": "b-payer", "kind": "literal", "pattern": "List name of payer", "weight": 3},
    {"id": "b-footer", "kind": "regex", "pattern": "(?i)Schedule B \\(Form 1040\\)", "weight": 2},
    {"id": "b-ee", "kind": "literal", "pattern": "Excludable interest on series EE", "weight": 1},
    {"id": "b-fincen", "kind": "literal", "pattern": "FinCEN Form 114", "weight": 1},
    {"id": "b-names", "kind": "literal", "pattern": "Name(s) shown on return", "weight": 1}
  ]
}'),
(NULL, 'SSA_AWARD_LETTER', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ssal-title", "kind": "literal", "pattern": "Benefit Verification Letter", "weight": 5},
    {"id": "ssal-current", "kind": "literal", "pattern": "Information About Current Social Security Benefits", "weight": 3},
    {"id": "ssal-monthly", "kind": "literal", "pattern": "monthly Social Security benefit", "weight": 2},
    {"id": "ssal-ssa", "kind": "literal", "pattern": "Social Security Administration", "weight": 1},
    {"id": "ssal-bnc", "kind": "regex", "pattern": "\\bBNC\\b", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_B', '1.0.0', '{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name(s) shown on return"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "taxpayerSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "totalInterest", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Add the amounts on line 1"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxableInterest", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Subtract line 3 from line 2"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalDividends", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Add the amounts on line 5"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Interest and Ordinary Dividends"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'SSA_AWARD_LETTER', '1.0.0', '{
  "fields": [
    {"name": "beneficiaryName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z]{2,} [A-Z] [A-Z]{2,}(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "letterDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Date:"},
        "value": {"pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "monthlyBenefit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "before any deductions is"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "medicareDeduction", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "We deduct"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "netMonthlyPayment", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "regular monthly Social Security payment"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
