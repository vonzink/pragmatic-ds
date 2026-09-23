-- V29 — Spec 6b: the self-employment income pair, authored from the OFFICIAL BLANK
-- FORMS (2025 Schedule C; 1099-NEC Rev. December 2026). The NEC revision matters:
-- its nonemployee-compensation box is now the 1a-1d split (1a compensation, 1b cash
-- tips, 1c TTOC, 1d overtime compensation), so anchors and labels here target the
-- current layout, not the pre-2026 single-box one.
--
--   SCHEDULE_C     Profit or Loss From Business. Two pages, ONE document: page 2
--                  carries no page-1 anchors, so it qualifies on its OWN headings
--                  (Cost of Goods Sold, Information on Your Vehicle) — the same
--                  continuation problem SCHEDULE_E's page 2 solves with Part II/III.
--   FORM_1099_NEC  Nonemployee compensation — the third 1099-family member, sharing
--                  the family furniture (PAYER'S TIN / RECIPIENT'S TIN / For calendar
--                  year) at weight 1 each so no family member qualifies on another's
--                  page: a 1099-G page scores 0.30 here, a 1099-R page 0.20, both far
--                  under the 0.60 bar (pinned by CrossConfusionIT).

-- ── §1 document types ───────────────────────────────────────────────────────
-- The V10 dance, split_description riding in the INSERT (V27's census rejects NULL).
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'SCHEDULE_C', 'Profit or Loss From Business (Schedule C)', 'INCOME',
     'IRS Schedule C (Form 1040) Profit or Loss From Business: proprietor and business '
     'identity boxes, then line-numbered income and expense entries; one form spans two '
     'pages (Parts I-II, then Parts III-V) that belong to the same document.'),
    (NULL, 'FORM_1099_NEC', 'Form 1099-NEC Nonemployee Compensation', 'INCOME',
     'IRS Form 1099-NEC Nonemployee Compensation: payer and recipient TIN blocks with '
     'numbered boxes led by 1a nonemployee compensation; payer-issued statements may '
     'stack several copies of the same form on one page, all one document.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- SCHEDULE_C 1.0.0. Weight = exclusivity (V10):
--   c-title    "Profit or Loss From Business"    5  the form's own title.   EXCLUSIVE
--   c-sole     "Sole Proprietorship"             2  the title's subtitle.   EXCLUSIVE
--   c-receipts "Gross receipts or sales"         2  line 1's caption.       EXCLUSIVE
--   c-footer   (?i)Schedule C \(Form 1040\)      2  footer/continuation id. EXCLUSIVE
--   c-cogs     "Cost of Goods Sold"              3  Part III heading — the PAGE-2
--                                                   anchor, with c-footer and c-vehicle.
--   c-vehicle  "Information on Your Vehicle"     2  Part IV heading, page 2. EXCLUSIVE
--   c-propr    "Name of proprietor"              1  the identity caption.
-- Page 1 scores 5+2+2+2+3+1 = 15 -> 1.00 (line 4 prints "Cost of goods sold" too);
-- page 2 scores 2+3+2 = 7 -> 0.70, which is what keeps a two-page form ONE document.
-- The page prints "(Form 1040)" and "Attach to Form 1040", worth 2 = 0.20 on the
-- TAX_RETURN pack — its em-dash Treasury literal cannot match the stacked masthead
-- the real form prints (Schedule E's page-1 note, same fact).
--
-- FORM_1099_NEC 1.0.0.
--   nec-title    "Form 1099-NEC"                   4  EXCLUSIVE
--   nec-comp     "Nonemployee compensation"        4  box 1a AND the subtitle. EXCLUSIVE
--   nec-golden   "Excess golden parachute payments" 2  box 3.                  EXCLUSIVE
--   g-calyear    "For calendar year"               1  shared: 1099 family
--   payer-tin    "PAYER'S TIN"                     1  shared
--   recip-tin    "RECIPIENT'S TIN"                 1  shared
-- Non-exclusive sum = 3 = 0.30 < 0.60. Title lost to OCR: 4+2+1+1+1 = 9 = 0.90.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'SCHEDULE_C', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "c-title", "kind": "literal", "pattern": "Profit or Loss From Business", "weight": 5},
    {"id": "c-sole", "kind": "literal", "pattern": "Sole Proprietorship", "weight": 2},
    {"id": "c-receipts", "kind": "literal", "pattern": "Gross receipts or sales", "weight": 2},
    {"id": "c-footer", "kind": "regex", "pattern": "(?i)Schedule C \\(Form 1040\\)", "weight": 2},
    {"id": "c-cogs", "kind": "literal", "pattern": "Cost of Goods Sold", "weight": 3},
    {"id": "c-vehicle", "kind": "literal", "pattern": "Information on Your Vehicle", "weight": 2},
    {"id": "c-propr", "kind": "literal", "pattern": "Name of proprietor", "weight": 1}
  ]
}'),
(NULL, 'FORM_1099_NEC', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "nec-title", "kind": "literal", "pattern": "Form 1099-NEC", "weight": 4},
    {"id": "nec-comp", "kind": "literal", "pattern": "Nonemployee compensation", "weight": 4},
    {"id": "nec-golden", "kind": "literal", "pattern": "Excess golden parachute payments", "weight": 2},
    {"id": "g-calyear", "kind": "literal", "pattern": "For calendar year", "weight": 1},
    {"id": "payer-tin", "kind": "literal", "pattern": "PAYER''S TIN", "weight": 1},
    {"id": "recip-tin", "kind": "literal", "pattern": "RECIPIENT''S TIN", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
-- Schedule C mixes both geometries the engine has: the identity block is a box grid
-- (LABEL_BELOW, V12 defaults written out), and the numbered income lines print label
-- left / amount right (ANCHOR_LABEL LINE_RIGHT, the tax_return geometry). The NEC is
-- pure box grid, same as its 1099 siblings.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_C', '1.0.0', '{
  "fields": [
    {"name": "proprietorName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name of proprietor"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "proprietorSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social security number (SSN)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "businessName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Business name. If no separate business name, leave blank."},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "businessEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer ID number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "grossReceipts", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross receipts or sales"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "grossProfit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross profit"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "grossIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalExpenses", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total expenses"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "netProfit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Net profit"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Profit or Loss From Business"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'FORM_1099_NEC', '1.0.0', '{
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
    {"name": "nonemployeeCompensation", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1a Nonemployee compensation"},
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
        "label": {"kind": "literal", "pattern": "5 State tax withheld"},
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
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
