-- V28 — Spec 6: three income-verification document types measured on real borrower
-- documents and rebuilt synthetic (corpus/README.md's flywheel: the FINDING lands in
-- the repo, the document never does).
--
--   FORM_1099_R  retirement/pension distributions. The real-world layout finding:
--                payer-issued 1099s arrive as SUBSTITUTE STATEMENTS — several copies
--                (B/C/2) stacked on one page — so anchors and labels repeat on the
--                page and occurrence-0 semantics are load-bearing.
--   FORM_1099_G  government payments (unemployment income), the same boxed geometry.
--   VOE          Request for Verification of Employment (the Fannie Form 1005 shape):
--                numbered item captions with the filled value below each.
--
-- Weight = exclusivity, not salience (the V10 rule): shared 1099-family furniture
-- (PAYER'S TIN / RECIPIENT'S TIN) is weighted so that no pack can qualify on it
-- alone, and each pack still qualifies with its title lost to OCR.
--
-- Fixtures: form_1099r / form_1099g / voe_form (+ w2_form_fourup, which needs NO row
-- here — the 4-up payroll layout classifies and extracts as plain W2, which is the
-- point of that fixture). Cross-confusion is gated by CrossConfusionIT over the
-- migrated packs, exactly as for every earlier type.

-- ── §1 document types ───────────────────────────────────────────────────────
-- The V10 dance: document_type is RLS-FORCED since V6 and its INSERT policy is
-- WITH CHECK (org_id = current_org()), which a global row can never satisfy. Drop
-- FORCE, seed, restore before commit; RlsCoverageIT fails if the restore is missed.
-- split_description exists since V27 and its census test rejects a NULL on any
-- seeded type, so the sentences ride in the INSERT.
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'FORM_1099_R', 'Form 1099-R Retirement Distributions', 'INCOME',
     'IRS Form 1099-R distribution statement: numbered boxes (gross distribution, '
     'taxable amount, distribution code), payer and recipient TIN blocks; payer-issued '
     'statements often stack several copies of the same form on one page, all one document.'),
    (NULL, 'FORM_1099_G', 'Form 1099-G Government Payments', 'INCOME',
     'IRS Form 1099-G Certain Government Payments: numbered boxes led by unemployment '
     'compensation, payer and recipient TIN blocks, state tax band; one page per payer year.'),
    (NULL, 'VOE', 'Verification of Employment (Form 1005)', 'INCOME',
     'A lender-originated Request for Verification of Employment: Part I request block '
     'naming employer, lender and applicant, Part II present-employment answers '
     '(position, dates, base pay) filled by the employer; usually a single form page.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- FORM_1099_R 1.0.0.
--   r-title      "Form 1099-R"                 5  the form's own title.      EXCLUSIVE
--   r-gross      "Gross distribution"          3  1099-R-only box caption.   EXCLUSIVE
--   r-pensions   "Distributions From Pensions" 2  the title's subtitle.      EXCLUSIVE
--   r-code       "Distribution code"           2  box 7's caption.           EXCLUSIVE
--   payer-tin    "PAYER'S TIN"                 1  shared: whole 1099 family
--   recip-tin    "RECIPIENT'S TIN"             1  shared: whole 1099 family
-- Non-exclusive sum = 2 = 0.20 < 0.60: the 1099 family's shared furniture cannot
-- qualify this pack (a 1099-G page scores exactly that 0.20 here). Degraded recall:
-- title lost to OCR still reaches 3+2+2+1+1 = 9 = 0.90.
--
-- FORM_1099_G 1.0.0. Same shape:
--   g-title      "Form 1099-G"                 4  EXCLUSIVE
--   g-unemp      "Unemployment compensation"   3  box 1's caption.           EXCLUSIVE
--   g-certain    "Certain Government Payments" 3  the title's subtitle.      EXCLUSIVE
--   g-calyear    "For calendar year"           1  shared: several IRS forms
--   payer-tin    "PAYER'S TIN"                 1  shared
--   recip-tin    "RECIPIENT'S TIN"             1  shared
-- Non-exclusive sum = 3 = 0.30 < 0.60. Title lost: 3+3+1+1+1 = 9 = 0.90.
--
-- VOE 1.0.0.
--   voe-title    "Request for Verification of Employment" 5  the form's title. EXCLUSIVE
--   voe-prob     "Probability of Continued Employment"    3  item 11.          EXCLUSIVE
--   voe-partii   "Verification of Present Employment"     2  Part II heading.  EXCLUSIVE
--   voe-basepay  "Current Gross Base Pay"                 2  item 12A.         EXCLUSIVE
--   voe-empdate  "Date of Employment"                     1  shared: HR docs generally
--   voe-position "Present Position"                       1  shared
-- Non-exclusive sum = 2 = 0.20 < 0.60. Title lost: 3+2+2+1+1 = 9 = 0.90. The paystub
-- confusables ("Gross Earnings", "Base Pay", "Overtime") are deliberately NOT anchors:
-- a paystub page scores 0 here, and the VOE fixture scores 0.20 on the PAYSTUB pack
-- (its "Earnings" table furniture) — both far under qualification, pinned by
-- CrossConfusionIT.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'FORM_1099_R', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "r-title", "kind": "literal", "pattern": "Form 1099-R", "weight": 5},
    {"id": "r-gross", "kind": "literal", "pattern": "Gross distribution", "weight": 3},
    {"id": "r-pensions", "kind": "literal", "pattern": "Distributions From Pensions", "weight": 2},
    {"id": "r-code", "kind": "literal", "pattern": "Distribution code", "weight": 2},
    {"id": "payer-tin", "kind": "literal", "pattern": "PAYER''S TIN", "weight": 1},
    {"id": "recip-tin", "kind": "literal", "pattern": "RECIPIENT''S TIN", "weight": 1}
  ]
}'),
(NULL, 'FORM_1099_G', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "g-title", "kind": "literal", "pattern": "Form 1099-G", "weight": 4},
    {"id": "g-unemp", "kind": "literal", "pattern": "Unemployment compensation", "weight": 3},
    {"id": "g-certain", "kind": "literal", "pattern": "Certain Government Payments", "weight": 3},
    {"id": "g-calyear", "kind": "literal", "pattern": "For calendar year", "weight": 1},
    {"id": "payer-tin", "kind": "literal", "pattern": "PAYER''S TIN", "weight": 1},
    {"id": "recip-tin", "kind": "literal", "pattern": "RECIPIENT''S TIN", "weight": 1}
  ]
}'),
(NULL, 'VOE', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "voe-title", "kind": "literal", "pattern": "Request for Verification of Employment", "weight": 5},
    {"id": "voe-prob", "kind": "literal", "pattern": "Probability of Continued Employment", "weight": 3},
    {"id": "voe-partii", "kind": "literal", "pattern": "Verification of Present Employment", "weight": 2},
    {"id": "voe-basepay", "kind": "literal", "pattern": "Current Gross Base Pay", "weight": 2},
    {"id": "voe-empdate", "kind": "literal", "pattern": "Date of Employment", "weight": 1},
    {"id": "voe-position", "kind": "literal", "pattern": "Present Position", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
-- Boxed-form geometry throughout: the caption captions a cell and the value sits on
-- the next line inside it, so the primary rung is LABEL_BELOW with the V12 defaults
-- written out (maxDropPt 24.0 / cellOverlap 0.5) — the same bound that keeps a rung
-- from grabbing the box BELOW the box it read. Payer/recipient address lines are a
-- full row below the name on the drawn fixture, deliberately outside maxDropPt, so
-- the name rungs capture the name and stop.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'FORM_1099_R', '1.0.0', '{
  "fields": [
    {"name": "payerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "PAYER''S name, street address, and telephone no."},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "payerTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "PAYER''S TIN"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "recipientTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "RECIPIENT''S TIN"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "recipientName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "RECIPIENT''S name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "grossDistribution", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Gross distribution"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "taxableAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2a Taxable amount"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "federalTaxWithheld", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "4 Federal income tax withheld"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "distributionCode", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "7 Distribution code(s)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z\\d])[1-9A-HJ-NP-W](?![A-Za-z\\d])", "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Form 1099-R"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'FORM_1099_G', '1.0.0', '{
  "fields": [
    {"name": "payerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "PAYER''S name, street address, city or town, state and ZIP code"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "payerTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "PAYER''S TIN"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "recipientTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "RECIPIENT''S TIN"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "recipientName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "RECIPIENT''S name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "unemploymentCompensation", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Unemployment compensation"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "federalTaxWithheld", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "4 Federal income tax withheld"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "stateTaxWithheld", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "11 State income tax withheld"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "For calendar year"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0}}]}
  ]
}'),
(NULL, 'VOE', '1.0.0', '{
  "fields": [
    {"name": "employerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "To (Name and address of employer)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "lenderName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "From (Name and address of lender)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "verificationDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "5. Date"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0}}]},
    {"name": "applicantName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name and Address of Applicant"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "employmentDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Applicant''s Date of Employment"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0}}]},
    {"name": "presentPosition", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Present Position"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z][a-z]+){0,3}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "continuedEmployment", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Probability of Continued Employment"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "grossBasePay", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Current Gross Base Pay"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
