-- V31 — Spec 6d: the K-1 family, authored from the committed 2025 blanks
-- (docs/reference-forms/f1065sk1.pdf, f1120ssk.pdf, f1041sk1.pdf).
--
-- Three siblings sharing heavier furniture than even the 1099s — "Final K-1 /
-- Amended K-1", the calendar-year line, the same numbered-box vocabulary
-- ("Ordinary business income", "Net rental real estate income", "Interest income"
-- print on ALL THREE) — so none of that vocabulary is anchored at qualifying
-- weight. What discriminates is the Part I/II identity (Partnership/Corporation/
-- Estate-or-Trust; Partner/Shareholder/Beneficiary) and the one-row footer
-- "Schedule K-1 (Form NNNN)", read by regex. The masthead stacks "Schedule K-1"
-- and "(Form NNNN)" on separate rows where no phrase can match — the footer is
-- the row that CAN, the same fact Schedule E's masthead documents.
--
-- The 1041 carries the codes back page every mailed one ships with: no page-1
-- anchor repeats there, so the page qualifies on its own header regex plus the
-- codes-list sentence — the Schedule C continuation pattern, third use.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'SCHEDULE_K1_1065', 'Partner''s Schedule K-1 (Form 1065)', 'INCOME',
     'Schedule K-1 (Form 1065): a partner''s share of partnership income — Part I '
     'partnership EIN/name block, Part II partner block, Part III numbered income '
     'boxes led by ordinary business income; usually one page per partner per year.'),
    (NULL, 'SCHEDULE_K1_1120S', 'Shareholder''s Schedule K-1 (Form 1120-S)', 'INCOME',
     'Schedule K-1 (Form 1120-S): a shareholder''s share of S-corporation income — '
     'Part I corporation block, Part II shareholder block with allocation percentage, '
     'Part III numbered income boxes; usually one page per shareholder per year.'),
    (NULL, 'SCHEDULE_K1_1041', 'Beneficiary''s Schedule K-1 (Form 1041)', 'INCOME',
     'Schedule K-1 (Form 1041): a beneficiary''s share of estate or trust income — '
     'Part I estate/trust block, Part II beneficiary block, Part III income boxes; '
     'commonly two pages, the form face plus its codes back page, one document.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- Weight = exclusivity (V10). Shared across all three K-1s: "Final K-1" at 1 —
-- the non-exclusive sum on any sibling's page stays at 0.10 against the 0.60 bar,
-- and the box vocabulary is not anchored at all. Note the schedule_e fixture
-- prints bare "Schedule K-1" on its Part II page (K-1 cross-references), which is
-- exactly why every title anchor here is the parenthesized-form REGEX and never
-- the bare phrase. Degraded recall with the footer/title regex lost to OCR:
-- 1065 3+2+1+1+1 = 8, 1120-S 3+2+1+1+1 = 8, 1041 3+2+3+1+1 = 10.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'SCHEDULE_K1_1065', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "k65-form", "kind": "regex", "pattern": "(?i)Schedule K-1 \\(Form 1065\\)", "weight": 4},
    {"id": "k65-share", "kind": "literal", "pattern": "Partner''s Share of Income", "weight": 3},
    {"id": "k65-parti", "kind": "literal", "pattern": "Information About the Partnership", "weight": 2},
    {"id": "k65-guaranteed", "kind": "literal", "pattern": "Guaranteed payments", "weight": 1},
    {"id": "k65-se", "kind": "literal", "pattern": "Self-employment earnings", "weight": 1},
    {"id": "k1-final", "kind": "literal", "pattern": "Final K-1", "weight": 1}
  ]
}'),
(NULL, 'SCHEDULE_K1_1120S', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "k20-form", "kind": "regex", "pattern": "(?i)Schedule K-1 \\(Form 1120-?S\\)", "weight": 4},
    {"id": "k20-share", "kind": "literal", "pattern": "Shareholder''s Share of Income", "weight": 3},
    {"id": "k20-parti", "kind": "literal", "pattern": "Information About the Corporation", "weight": 2},
    {"id": "k20-alloc", "kind": "literal", "pattern": "Current year allocation percentage", "weight": 1},
    {"id": "k20-loans", "kind": "literal", "pattern": "Loans from shareholder", "weight": 1},
    {"id": "k1-final", "kind": "literal", "pattern": "Final K-1", "weight": 1}
  ]
}'),
(NULL, 'SCHEDULE_K1_1041', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "k41-form", "kind": "regex", "pattern": "(?i)Schedule K-1 \\(Form 1041\\)", "weight": 4},
    {"id": "k41-share", "kind": "literal", "pattern": "Beneficiary''s Share of Income", "weight": 3},
    {"id": "k41-parti", "kind": "literal", "pattern": "Information About the Estate or Trust", "weight": 2},
    {"id": "k41-codes", "kind": "literal", "pattern": "codes used on Schedule K-1", "weight": 3},
    {"id": "k41-estate", "kind": "literal", "pattern": "Estate tax deduction", "weight": 1},
    {"id": "k1-final", "kind": "literal", "pattern": "Final K-1", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
-- Box-grid geometry throughout (caption above, value below, the V12 LABEL_BELOW
-- defaults written out). taxYear reads LINE_RIGHT off the title row, where the
-- year prints beside "Schedule K-1" — same shape as the W-2's title-row year.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_K1_1065', '1.0.0', '{
  "fields": [
    {"name": "partnershipEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Partnership''s employer identification number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "partnershipName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Partnership''s name, address, city, state, and ZIP code"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "partnerTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Partner''s SSN or TIN"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)(?:\\d{3}-\\d{2}-\\d{4}|\\d{2}-\\d{7})(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "partnerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "F Name, address, city, state, and ZIP code for partner"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "ordinaryBusinessIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Ordinary business income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "netRentalRealEstateIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2 Net rental real estate income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "guaranteedPayments", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "4c Total guaranteed payments"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "selfEmploymentEarnings", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "14 Self-employment earnings"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Schedule K-1"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'SCHEDULE_K1_1120S', '1.0.0', '{
  "fields": [
    {"name": "corporationEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Corporation''s employer identification number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "corporationName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Corporation''s name, address, city, state, and ZIP code"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "shareholderTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Shareholder''s identifying number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)(?:\\d{3}-\\d{2}-\\d{4}|\\d{2}-\\d{7})(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "shareholderName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Shareholder''s name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "ordinaryBusinessIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Ordinary business income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "netRentalRealEstateIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2 Net rental real estate income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "allocationPercentage", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Current year allocation percentage"},
        "value": {"pattern": "(?<![\\d.])\\d{1,3}(?:\\.\\d+)?%(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Schedule K-1"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'SCHEDULE_K1_1041', '1.0.0', '{
  "fields": [
    {"name": "estateOrTrustEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Estate''s or trust''s employer identification number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "estateOrTrustName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Estate''s or trust''s name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "beneficiaryTin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Beneficiary''s identifying number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)(?:\\d{3}-\\d{2}-\\d{4}|\\d{2}-\\d{7})(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "beneficiaryName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Beneficiary''s name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "interestIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1 Interest income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "ordinaryDividends", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2a Ordinary dividends"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "ordinaryBusinessIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "6 Ordinary business income"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Schedule K-1"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
