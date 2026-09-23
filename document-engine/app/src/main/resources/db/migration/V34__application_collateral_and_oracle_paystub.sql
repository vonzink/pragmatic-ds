-- V34 — Spec 6g: the application-and-collateral batch, plus the first REAL-WORLD
-- paystub recall fix since the pack was born.
--
-- Editing V34 in place is legal while it is UNRELEASED (not on main): Testcontainers
-- remigrate from scratch every run, and a local compose stack needs one
-- `docker compose down -v`. Same rule V11 documents.
--
--   URLA           BOTH circulating revisions matter: the legacy Form 1003 (all-caps
--                  section bars) and the redesigned 2020+ URLA ("Section 1: Borrower
--                  Information") were BOTH measured on real applications in the same
--                  week. One pack anchors both revisions' exclusive furniture under
--                  the shared title.
--   APPRAISAL      the URAR report. Three wording traps, all of them the same shape:
--                  ANCHOR_LABEL takes the FIRST match on the page, and this form's
--                  prose says the caption's words before the caption prints.
--                    - "opinion of the market value" appears in the PURPOSE sentence
--                      long before the reconciliation, so the appraised-value rung
--                      anchors "subject of this report is" instead.
--                    - that same sentence says "provide the lender/client with", and
--                      a literal label matches case-INsensitively, so lenderClient
--                      read the purpose sentence. Its label is a case-sensitive regex
--                      requiring an uppercase value to follow.
--                    - the reconciliation's lead-in says "exterior areas of the
--                      subject property", and "as of" matches inside "areas of", so
--                      effectiveDate's label carries letter boundaries.
--                  propertyAddress is bounded by the caption that follows it on the
--                  row ("City"/"State"/"Zip Code"); unbounded, [A-Za-z ]+ ran greedily
--                  through the rest of the row and captured the captions too.
--   FORM_4506      Rev. 9-2024, Request for COPY of Tax Return — deliberately not
--                  called 4506-C: the IVES transcript request is a distinct future
--                  sibling with its own title anchor.
--   DISASTER_CERT  a lender disaster certification in GENERIC industry language
--                  (the measured document was a lender's proprietary template; the
--                  fixture keeps the semantics and FEMA vocabulary, never the
--                  vendor's wording).
--
-- And paystub@1.1.0: five real paystubs from five employers were scored against
-- paystub@1.0.0 — four qualified at 0.70-1.00, and the Oracle-HCM layout (a major
-- hospital system's payroll) FAILED at 0.40: it prints none of "Pay Period",
-- "Gross Pay", "Pay Date" or "Federal Withholding". 1.1.0 keeps every 1.0.0 anchor
-- and adds the Oracle vocabulary; the paystub_oracle fixture pins the layout at
-- 0.40-on-1.0.0 / 1.00-on-1.1.0, the same shape as the V10 W-2 fix.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'URLA', 'Uniform Residential Loan Application', 'LOAN',
     'The Uniform Residential Loan Application (Fannie 1003/Freddie 65), either '
     'revision: the legacy layout with all-caps section bars (TYPE OF MORTGAGE, '
     'PROPERTY INFORMATION) or the redesigned form with numbered sections; one '
     'application spans many pages that belong together.'),
    (NULL, 'APPRAISAL', 'Uniform Residential Appraisal Report', 'PROPERTY',
     'A URAR appraisal report: subject and contract sections, sales-comparison '
     'grid, reconciliation with the opinion of market value, then addenda, sketches, '
     'maps and photo pages; one report commonly spans dozens of pages.'),
    (NULL, 'FORM_4506', 'Form 4506 Request for Copy of Tax Return', 'LOAN',
     'IRS Form 4506 (Request for Copy of Tax Return): numbered request lines 1a-9 '
     'with taxpayer identity, return type and year boxes, fee lines and a signature '
     'block; distinct from the 4506-C transcript request.'),
    (NULL, 'DISASTER_CERT', 'Disaster Certification', 'PROPERTY',
     'A lender disaster-certification page: a borrower attestation that the subject '
     'property was not damaged by a federally declared disaster, is habitable with '
     'utilities functioning, followed by a signature block; often trailed by an '
     'e-signature audit page.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- URLA 1.0.0. One pack, both revisions:
--   u-title    "Uniform Residential Loan Application"     5  both revisions. EXCLUSIVE
--   u-mortgage "TYPE OF MORTGAGE AND TERMS OF LOAN"       2  legacy bar.     EXCLUSIVE
--   u-property "PROPERTY INFORMATION AND PURPOSE OF LOAN" 2  legacy bar.     EXCLUSIVE
--   u-s1       "Section 1: Borrower Information"          2  redesigned.     EXCLUSIVE
--   u-1a       "1a. Personal Information"                 1  redesigned.     EXCLUSIVE
--   u-amort    "Amortization Type"                        1  legacy terms box
--   u-agency   "Agency Case"                              1  both revisions
-- A legacy page with the title lost: 2+2+1+1 = 0.60; a redesigned page likewise
-- leans on its own section anchors. Neither revision's furniture can qualify the
-- other's pack-mates.
--
-- APPRAISAL 1.0.0:
--   a-title  "Uniform Residential Appraisal Report" 5 EXCLUSIVE
--   a-value  "opinion of the market value"          2 EXCLUSIVE
--   a-sca    "Sales Comparison Approach"            2 EXCLUSIVE
--   a-rights "Property Rights Appraised"            1 EXCLUSIVE
--   a-lc     "Lender/Client"                        1 report header caption
-- Title lost: 2+2+1+1 = 0.60.
--
-- FORM_4506 1.0.0:
--   f-title  "Request for Copy of Tax Return" 4 EXCLUSIVE
--   f-form   "Form 4506"                      3 EXCLUSIVE (4506-C prints "4506-C")
--   f-line6  "Tax return requested"           1
--   f-line7  "Year or period requested"       1
--   f-treas  "United States Treasury"         1 the fee line
--   f-nosign "Do not sign this form unless"   1 the header caution
-- Title lost: 3+1+1+1+1 = 0.70.
--
-- DISASTER_CERT 1.0.0:
--   d-title    "DISASTER CERTIFICATION"      5 EXCLUSIVE
--   d-fema     "federally declared disaster" 3 the FEMA phrase every version prints
--   d-habit    "habitable"                   1
--   d-borrsig  "Borrower Signature"          1 (contracts print Buyer's/Seller's, not this)
--   d-claim    "insurance claim"             1
-- Title lost: 3+1+1+1 = 0.60.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'URLA', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "u-title", "kind": "literal", "pattern": "Uniform Residential Loan Application", "weight": 5},
    {"id": "u-mortgage", "kind": "literal", "pattern": "TYPE OF MORTGAGE AND TERMS OF LOAN", "weight": 2},
    {"id": "u-property", "kind": "literal", "pattern": "PROPERTY INFORMATION AND PURPOSE OF LOAN", "weight": 2},
    {"id": "u-s1", "kind": "literal", "pattern": "Section 1: Borrower Information", "weight": 2},
    {"id": "u-1a", "kind": "literal", "pattern": "1a. Personal Information", "weight": 1},
    {"id": "u-amort", "kind": "literal", "pattern": "Amortization Type", "weight": 1},
    {"id": "u-agency", "kind": "literal", "pattern": "Agency Case", "weight": 1}
  ]
}'),
(NULL, 'APPRAISAL', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "a-title", "kind": "literal", "pattern": "Uniform Residential Appraisal Report", "weight": 5},
    {"id": "a-value", "kind": "literal", "pattern": "opinion of the market value", "weight": 2},
    {"id": "a-sca", "kind": "literal", "pattern": "Sales Comparison Approach", "weight": 2},
    {"id": "a-rights", "kind": "literal", "pattern": "Property Rights Appraised", "weight": 1},
    {"id": "a-lc", "kind": "literal", "pattern": "Lender/Client", "weight": 1}
  ]
}'),
(NULL, 'FORM_4506', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "f-title", "kind": "literal", "pattern": "Request for Copy of Tax Return", "weight": 4},
    {"id": "f-form", "kind": "literal", "pattern": "Form 4506", "weight": 3},
    {"id": "f-line6", "kind": "literal", "pattern": "Tax return requested", "weight": 1},
    {"id": "f-line7", "kind": "literal", "pattern": "Year or period requested", "weight": 1},
    {"id": "f-treas", "kind": "literal", "pattern": "United States Treasury", "weight": 1},
    {"id": "f-nosign", "kind": "literal", "pattern": "Do not sign this form unless", "weight": 1}
  ]
}'),
(NULL, 'DISASTER_CERT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "d-title", "kind": "literal", "pattern": "DISASTER CERTIFICATION", "weight": 5},
    {"id": "d-fema", "kind": "literal", "pattern": "federally declared disaster", "weight": 3},
    {"id": "d-habit", "kind": "literal", "pattern": "habitable", "weight": 1},
    {"id": "d-borrsig", "kind": "literal", "pattern": "Borrower Signature", "weight": 1},
    {"id": "d-claim", "kind": "literal", "pattern": "insurance claim", "weight": 1}
  ]
}');

-- The paystub supersession, V10's exact shape: retire 1.0.0 (never delete — every
-- classification_result it decided still resolves through it), insert 1.1.0 with
-- every 1.0.0 anchor intact plus the Oracle-HCM vocabulary.
UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.0.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'PAYSTUB', '1.1.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3},
    {"id": "gross-pay", "kind": "literal", "pattern": "Gross Pay", "weight": 3},
    {"id": "net-pay", "kind": "literal", "pattern": "Net Pay", "weight": 2},
    {"id": "pay-date", "kind": "literal", "pattern": "Pay Date", "weight": 2},
    {"id": "earnings", "kind": "literal", "pattern": "Earnings", "weight": 2},
    {"id": "fed-withholding", "kind": "literal", "pattern": "Federal Withholding", "weight": 2},
    {"id": "ytd", "kind": "regex", "pattern": "\\bYTD\\b", "weight": 1},
    {"id": "gross-earnings", "kind": "literal", "pattern": "Gross Earnings", "weight": 2},
    {"id": "payroll-rel", "kind": "literal", "pattern": "Payroll Relationship Number", "weight": 2},
    {"id": "period-end", "kind": "literal", "pattern": "Period End Date", "weight": 1},
    {"id": "payment-date", "kind": "literal", "pattern": "Payment Date", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'URLA', '1.0.0', '{
  "fields": [
    {"name": "loanAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Amount"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "interestRate", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Interest Rate"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d.])\\d{1,2}\\.\\d{1,4} ?%(?!\\d)",
                  "occurrence": 0}}]},
    {"name": "loanTermMonths", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "No. of Months"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2,3}(?!\\d)", "occurrence": 0}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Subject Property Address (street, city, state & ZIP)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0}}]},
    {"name": "agencyCaseNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Agency Case Number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\w-])[\\dA-Z][\\d-]{4,}\\d(?![\\w-])", "occurrence": 0}}]},
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Borrower''s Name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "borrowerSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social Security Number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "borrowerDob", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "DOB (mm/dd/yyyy)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0}}]}
  ]
}'),
(NULL, 'APPRAISAL', '1.0.0', '{
  "fields": [
    {"name": "fileNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "File #"},
        "value": {"pattern": "(?<![\\w])[A-Z]{1,4}\\d{4,}(?![\\w])", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Borrower"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Property Address"},
        "value": {"pattern": "\\d{1,5} [A-Za-z][A-Za-z ]+?(?= City | State | Zip Code |$)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "lenderClient", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "Lender/Client(?= [A-Z])"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "contractPrice", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Contract Price"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "indicatedValueSales", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Indicated Value by Sales Comparison Approach"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "appraisedValue", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "subject of this report is"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "effectiveDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?<![A-Za-z])as of(?![A-Za-z])"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "appraiserName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Appraiser Name"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'FORM_4506', '1.0.0', '{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1a Name shown on tax return"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "taxpayerSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "1b First social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "spouseName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "2a If a joint return, enter spouse''s name"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "currentAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "3 Current name, address, city, state, and ZIP code"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0}}]},
    {"name": "returnRequested", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Tax return requested"},
        "value": {"pattern": "(?<!\\d)(?:1040|1120|1065|941)(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "periodRequested", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Year or period requested"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0}}]},
    {"name": "signatureDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Date"},
        "maxDropPt": 24.0, "cellOverlap": 0.3,
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0}}]}
  ]
}'),
(NULL, 'DISASTER_CERT', '1.0.0', '{
  "fields": [
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Subject Property:"},
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Borrower Name"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "certificationDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Date:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
