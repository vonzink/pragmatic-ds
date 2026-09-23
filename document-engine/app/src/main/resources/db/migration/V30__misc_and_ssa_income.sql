-- V30 — Spec 6c: miscellaneous and social-security income.
--
--   FORM_1099_MISC  authored from the official Rev. December 2026 blank, which —
--                   like the NEC — carries the new tips/overtime boxes (13a Cash
--                   tips, 13b TTOC, 14 Overtime compensation), a renumbering any
--                   pre-2026 reference would get wrong. Fourth 1099-family member:
--                   the shared furniture (PAYER'S TIN / RECIPIENT'S TIN / For
--                   calendar year) stays weighted 1 each, so every pairwise family
--                   combination scores 0.20-0.30 against the 0.60 bar.
--   FORM_SSA_1099   measured on a real benefit statement, rebuilt synthetic. The
--                   layout finding: the SSA-1099 is NOT an IRS box grid but SSA's
--                   own statement — "Box N." captions with values below, and boxes
--                   4 and 6 print the WORD "NONE" when nothing was repaid or
--                   withheld, so those rungs read a NONE-or-money pattern rather
--                   than going quiet hunting for a number.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'FORM_1099_MISC', 'Form 1099-MISC Miscellaneous Information', 'INCOME',
     'IRS Form 1099-MISC Miscellaneous Information: payer and recipient TIN blocks with '
     'numbered boxes (rents, royalties, other income); payer-issued statements may stack '
     'several copies of the same form on one page, all one document.'),
    (NULL, 'FORM_SSA_1099', 'SSA-1099 Social Security Benefit Statement', 'INCOME',
     'Social Security Administration SSA-1099 Benefit Statement: its own non-IRS layout '
     'with "Box N." captions — beneficiary name and SSN, benefits paid/repaid/net for the '
     'year, claim number; a single statement page per beneficiary per year.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- FORM_1099_MISC 1.0.0. Weight = exclusivity (V10):
--   misc-title    "Form 1099-MISC"            4  EXCLUSIVE
--   misc-subtitle "Miscellaneous Information" 3  the Rev-2020+ subtitle.  EXCLUSIVE
--   misc-crop     "Crop insurance proceeds"   2  box 9, MISC-only.        EXCLUSIVE
--   misc-fishing  "Fishing boat proceeds"     2  box 5, MISC-only.        EXCLUSIVE
--   g-calyear     "For calendar year"         1  shared: 1099 family
--   payer-tin     "PAYER'S TIN"               1  shared
--   recip-tin     "RECIPIENT'S TIN"           1  shared
-- Non-exclusive sum = 3 = 0.30 < 0.60. Title lost to OCR: 3+2+2+1+1+1 = 10 = 1.00.
--
-- FORM_SSA_1099 1.0.0.
--   ssa-title    "Social Security Benefit Statement"    5  the form's title. EXCLUSIVE
--   ssa-net      "Net Benefits"                         2  box 5.            EXCLUSIVE
--   ssa-repaid   "Benefits Repaid to SSA"               2  box 4.            EXCLUSIVE
--   ssa-voluntary "Voluntary Federal Income Tax Withheld" 2  box 6.          EXCLUSIVE
--   ssa-paid     "Benefits Paid in"                     1  box 3, semi-generic
--   ssa-claim    "Claim Number"                         1  box 8, semi-generic
-- Non-exclusive sum = 2 = 0.20 < 0.60. Title lost: 2+2+2+1+1 = 8 = 0.80. The W2
-- neighborhood is the confusable one ("Social security" vocabulary): the W2 pack
-- anchors "Social security wages", which no SSA-1099 prints, and this pack's title
-- phrase appears on no W-2 — both directions pinned by CrossConfusionIT.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'FORM_1099_MISC', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "misc-title", "kind": "literal", "pattern": "Form 1099-MISC", "weight": 4},
    {"id": "misc-subtitle", "kind": "literal", "pattern": "Miscellaneous Information", "weight": 3},
    {"id": "misc-crop", "kind": "literal", "pattern": "Crop insurance proceeds", "weight": 2},
    {"id": "misc-fishing", "kind": "literal", "pattern": "Fishing boat proceeds", "weight": 2},
    {"id": "g-calyear", "kind": "literal", "pattern": "For calendar year", "weight": 1},
    {"id": "payer-tin", "kind": "literal", "pattern": "PAYER''S TIN", "weight": 1},
    {"id": "recip-tin", "kind": "literal", "pattern": "RECIPIENT''S TIN", "weight": 1}
  ]
}'),
(NULL, 'FORM_SSA_1099', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ssa-title", "kind": "literal", "pattern": "Social Security Benefit Statement", "weight": 5},
    {"id": "ssa-net", "kind": "literal", "pattern": "Net Benefits", "weight": 2},
    {"id": "ssa-repaid", "kind": "literal", "pattern": "Benefits Repaid to SSA", "weight": 2},
    {"id": "ssa-voluntary", "kind": "literal", "pattern": "Voluntary Federal Income Tax Withheld", "weight": 2},
    {"id": "ssa-paid", "kind": "literal", "pattern": "Benefits Paid in", "weight": 1},
    {"id": "ssa-claim", "kind": "literal", "pattern": "Claim Number", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'FORM_1099_MISC', '1.0.0', '{
  "fields": [
    {"name": "payerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "PAYER''S name"},
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
        "value": {"pattern": "(?<!\\d)(?:\\d{3}-\\d{2}-\\d{4}|\\d{2}-\\d{7})(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "recipientName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "RECIPIENT''S name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "rents", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Rents"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "royalties", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2 Royalties"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "otherIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "3 Other income"},
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
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "For calendar year"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0}}]}
  ]
}'),
(NULL, 'FORM_SSA_1099', '1.0.0', '{
  "fields": [
    {"name": "beneficiaryName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 1. Name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z]*(?: [A-Z][A-Z]*){1,3}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "beneficiarySsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Beneficiary''s Social Security Number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?![\\dA-Z])", "occurrence": 0}}]},
    {"name": "benefitsPaid", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 3. Benefits Paid in"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "benefitsRepaid", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 4. Benefits Repaid to SSA"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?:NONE|\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2})",
                  "occurrence": 0}}]},
    {"name": "netBenefits", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 5. Net Benefits"},
        "maxDropPt": 36.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "voluntaryTaxWithheld", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 6. Voluntary Federal Income Tax Withheld"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?:NONE|\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2})",
                  "occurrence": 0}}]},
    {"name": "claimNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Box 8. Claim Number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}[A-Z]\\d?(?![A-Za-z\\d])",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social Security Benefit Statement"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
