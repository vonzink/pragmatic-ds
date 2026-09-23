-- V33 — Spec 6f: the title-work trio, measured on real closing packages and
-- rebuilt synthetic (the corpus flywheel; every name, number and amount in the
-- fixtures is fabricated).
--
--   CLOSING_DISCLOSURE   the TRID page-1 layout: three information blocks, the
--                        Loan Terms table, Costs at Closing. The one wording trap
--                        pinned by the fixture: the page's own SUBTITLE prints
--                        "closing costs", so no rung anchors that phrase — the
--                        rows a lender reads anchor on their own captions.
--   TITLE_COMMITMENT     the 2021 ALTA form. Schedule A is the data page; the
--                        Schedule B parts classify on their headings plus the
--                        footer identity line (the continuation pattern again).
--   WIRING_INSTRUCTIONS  the escrow wire page every closing package carries.
--                        This is a FRAUD-SURFACE document: the point of typing it
--                        is that wire coordinates become comparable against
--                        known-good instructions. ABA and account numbers are
--                        SENSITIVE — masked by the serializer like SSNs.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'CLOSING_DISCLOSURE', 'Closing Disclosure', 'LOAN',
     'The five-page TRID Closing Disclosure: "Closing Disclosure" title over Closing/'
     'Transaction/Loan information blocks, then Loan Terms, Projected Payments and '
     'Costs at Closing tables; later pages carry Closing Cost Details and loan '
     'disclosures, all one document.'),
    (NULL, 'TITLE_COMMITMENT', 'ALTA Title Insurance Commitment', 'PROPERTY',
     'An ALTA Commitment for Title Insurance: commitment jacket and conditions, '
     'Schedule A (commitment date, policies, proposed insured and amount, vesting, '
     'legal description), then Schedule B Part I requirements and Part II exceptions; '
     'one commitment spans many pages that belong together.'),
    (NULL, 'WIRING_INSTRUCTIONS', 'Escrow Wiring Instructions', 'PROPERTY',
     'A title or escrow company''s incoming wire instructions page: company '
     'letterhead, wire-fraud warnings, then bank name, ABA routing number, account '
     'number and credit/beneficiary block; usually a single page.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
-- CLOSING_DISCLOSURE 1.0.0. Weight = exclusivity (V10):
--   cd-title    "Closing Disclosure"                              4  EXCLUSIVE
--   cd-subtitle "statement of final loan terms and closing costs" 3  EXCLUSIVE
--   cd-costs    "Costs at Closing"                                2  EXCLUSIVE
--   cd-projected "Projected Payments"                             2  EXCLUSIVE
--   cd-cash     "Cash to Close"                                   1  EXCLUSIVE
--   cd-terms    "Loan Terms"                                      1  shared-ish (notes)
-- Non-exclusive sum = 1 = 0.10. Title lost to OCR: 3+2+2+1+1 = 9 = 0.90. The page
-- prints "Closing Date" (a PURCHASE_CONTRACT anchor, 0.20 there) and "Interest
-- Rate" (MORTGAGE_STATEMENT, 0.10) — both gated by CrossConfusionIT.
--
-- TITLE_COMMITMENT 1.0.0.
--   tc-title    "Commitment for Title Insurance"  4  title AND both footers. EXCLUSIVE
--   tc-insured  "Proposed Insured"                2  Schedule A vocabulary.  EXCLUSIVE
--   tc-amount   "Proposed Amount of Insurance"    2  EXCLUSIVE
--   tc-schedb   (?i)Schedule B, Part I{1,2}       2  the part headings.      EXCLUSIVE
--   tc-date     "Commitment Date"                 1  EXCLUSIVE
--   tc-tid      "Transaction Identification Data" 1  EXCLUSIVE
-- Schedule A page: 4+2+2+1+1 = 10 = 1.00; Schedule B page: 4+2+2(B-I prints
-- "proposed insured") = 0.80.
--
-- WIRING_INSTRUCTIONS 1.0.0.
--   wi-title    "WIRE INSTRUCTIONS"  4  EXCLUSIVE
--   wi-aba      \bABA\b              2  routing-number caption
--   wi-cashiers "Cashier''s Checks"  2  the payable-to block
--   wi-cyber    "cyber-crime"        1  the fraud warning every vendor prints
--   wi-ach      \bACH\b              1  the ACH warning
--   wi-bank     "Bank Name"          1  the coordinate captions
-- Full = 11; title lost: 2+2+1+1+1 = 7 = 0.70.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'CLOSING_DISCLOSURE', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "cd-title", "kind": "literal", "pattern": "Closing Disclosure", "weight": 4},
    {"id": "cd-subtitle", "kind": "literal", "pattern": "statement of final loan terms and closing costs", "weight": 3},
    {"id": "cd-costs", "kind": "literal", "pattern": "Costs at Closing", "weight": 2},
    {"id": "cd-projected", "kind": "literal", "pattern": "Projected Payments", "weight": 2},
    {"id": "cd-cash", "kind": "literal", "pattern": "Cash to Close", "weight": 1},
    {"id": "cd-terms", "kind": "literal", "pattern": "Loan Terms", "weight": 1}
  ]
}'),
(NULL, 'TITLE_COMMITMENT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "tc-title", "kind": "literal", "pattern": "Commitment for Title Insurance", "weight": 4},
    {"id": "tc-insured", "kind": "literal", "pattern": "Proposed Insured", "weight": 2},
    {"id": "tc-amount", "kind": "literal", "pattern": "Proposed Amount of Insurance", "weight": 2},
    {"id": "tc-schedb", "kind": "regex", "pattern": "(?i)Schedule B, Part I{1,2}", "weight": 2},
    {"id": "tc-date", "kind": "literal", "pattern": "Commitment Date", "weight": 1},
    {"id": "tc-tid", "kind": "literal", "pattern": "Transaction Identification Data", "weight": 1}
  ]
}'),
(NULL, 'WIRING_INSTRUCTIONS', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "wi-title", "kind": "literal", "pattern": "WIRE INSTRUCTIONS", "weight": 4},
    {"id": "wi-aba", "kind": "regex", "pattern": "\\bABA\\b", "weight": 2},
    {"id": "wi-cashiers", "kind": "literal", "pattern": "Cashier''s Checks", "weight": 2},
    {"id": "wi-cyber", "kind": "literal", "pattern": "cyber-crime", "weight": 1},
    {"id": "wi-ach", "kind": "regex", "pattern": "\\bACH\\b", "weight": 1},
    {"id": "wi-bank", "kind": "literal", "pattern": "Bank Name", "weight": 1}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'CLOSING_DISCLOSURE', '1.0.0', '{
  "fields": [
    {"name": "closingDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Closing Date"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Borrower"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "sellerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Seller"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "lenderName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Lender"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "salePrice", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Sale Price"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "loanAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Loan Amount"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "interestRate", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Interest Rate"},
        "value": {"pattern": "(?<![\\d.])\\d{1,2}\\.\\d{1,4}%(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "monthlyPrincipalInterest", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Monthly Principal & Interest"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "cashToClose", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Cash to Close"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'TITLE_COMMITMENT', '1.0.0', '{
  "fields": [
    {"name": "issuingAgent", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Issuing Agent:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "commitmentNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Commitment No.:"},
        "value": {"pattern": "(?<![\\w-])\\d[\\d-]{5,}[A-Z0-9](?![\\w-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Property Address:"},
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "commitmentDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Commitment Date:"},
        "value": {"pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "proposedInsured", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Proposed Insured:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "proposedAmountOfInsurance", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Proposed Amount of Insurance:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "vestedOwner", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "The Title is, at the Commitment Date, vested in:"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "policyPremium", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "ALTA Loan Policy"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'),
(NULL, 'WIRING_INSTRUCTIONS', '1.0.0', '{
  "fields": [
    {"name": "escrowCompany", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "bankName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Bank Name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "abaNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "ABA No.:"},
        "value": {"pattern": "(?<!\\d)\\d{9}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "accountNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Account:"},
        "value": {"pattern": "(?<!\\d)\\d{7,12}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "creditName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Credit:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]*(?: [A-Z][A-Z&''.-]*){1,5}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "referenceNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Reference:"},
        "value": {"pattern": "(?<![\\w-])\\d[\\d-]{5,}[A-Z0-9](?![\\w-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
