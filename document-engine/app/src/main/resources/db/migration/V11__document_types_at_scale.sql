-- V11 — Spec 3: document types at scale. ONE migration that grows across the
-- spec's tasks in FIXED sections — legal because V11 is unreleased: Testcontainers
-- remigrate from scratch on every run, and a local compose stack needs a single
-- `docker compose down -v` when the spec lands (plan T16). Later tasks append ONLY
-- inside their marked section:
--   §1 extraction_method CHECK widening (T1)
--   §2 TAX_RETURN document type (T1; NO FORCE dance on document_type)
--   §3 rule packs (T8: DRIVERS_LICENSE, MORTGAGE_STATEMENT · T9: HOI_DECLARATION,
--      PURCHASE_CONTRACT, TAX_RETURN; one NO FORCE dance on classification_rule_pack)
--   §4 extraction schemas (T10: W2, BANK_STATEMENT · T11: DRIVERS_LICENSE,
--      MORTGAGE_STATEMENT · T12: HOI_DECLARATION, PURCHASE_CONTRACT · T13: TAX_RETURN;
--      one NO FORCE dance on extraction_schema)

-- ── §1 extraction_method CHECK widening (T1) ────────────────────────────────
-- The V9 pattern: DROP then ADD. The constraint is extracted_field_method_check
-- (V7 — note: NOT extracted_field_extraction_method_check). The two new values
-- are the Spec 3 detector-backed rungs; the Java ExtractionMethod enum mirrors
-- this list exactly.
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE'));

-- ── §2 TAX_RETURN document type (T1) ────────────────────────────────────────
-- One type covers Form 1040 pages AND schedules (design D4): underwriting treats
-- "the return" as one document; consecutive same-type pages group via existing
-- splitting. Late seed into a table V6 already FORCEd: the only INSERT policy is
-- WITH CHECK (org_id = current_org()), and NULL = <anything> is never TRUE, so no
-- GUC value can admit a global row — and FORCE binds the migration owner too.
-- Hence the V10 dance: drop FORCE for the seed, restore before commit (same
-- transaction, so no window exists for any other session). A forgotten restore
-- fails RlsCoverageIT's pg_class sweep (relforcerowsecurity), not just review.
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category) VALUES
    (NULL, 'TAX_RETURN', 'Individual Income Tax Return', 'INCOME');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §3 rule packs (T8/T9 append here) ───────────────────────────────────────
-- T8 adds DRIVERS_LICENSE + MORTGAGE_STATEMENT, T9 adds HOI_DECLARATION +
-- PURCHASE_CONTRACT + TAX_RETURN: ONE NO FORCE dance on classification_rule_pack
-- wrapping all five INSERTs.

-- Late seeds into an RLS-FORCED table need the V10 dance: the INSERT policy is
-- WITH CHECK (org_id = current_org()) and NULL = anything is never TRUE, so no
-- GUC value can admit a global row — and FORCE binds the migration owner too.
-- Drop FORCE, seed, restore FORCE before commit; no window exists for any other
-- session, and RlsCoverageIT fails the build if the restore is ever forgotten.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

-- DRIVERS_LICENSE 1.0.0. Weight = exclusivity, not salience (the V10 rule):
--   dl-title   "DRIVER LICENSE"  5  the card's own title block.        EXCLUSIVE
--   dl-number  "DL No"           2  only license cards label a DL No.  EXCLUSIVE
--   dob        \bDOB\b           2  shared: identity/medical/loan forms print DOB
--   exp        \bEXP\b           1  shared: expiries generally (credit cards too)
--   class      \bCLASS\b         1  shared: uppercase CLASS appears beyond licenses
--   iss        \bISS\b           1  shared: issue-date abbreviation
-- Non-exclusive sum = 2+1+1+1 = 5 = 0.50 < 0.60: the pack cannot qualify on
-- shared vocabulary alone (invariant pinned by DriversLicensePackIT). The regex
-- anchors compile as authored — UPPERCASE, case-sensitive by design: prose "dob"
-- must not score; a card prints the abbreviations in caps.
--
-- MORTGAGE_STATEMENT 1.0.0. Confusable neighbors (design §4): BANK_STATEMENT
-- (balances, "statement"), HOI_DECLARATION (mortgagee clauses print lender names
-- and loan numbers), PURCHASE_CONTRACT (property addresses, dollar amounts).
--   ms-title           "Mortgage Statement"  5  the document's own title. EXCLUSIVE
--   principal-balance  "Principal Balance"   3  servicing vocabulary; banks say
--                                               Beginning/Ending Balance. EXCLUSIVE
--   escrow-balance     "Escrow Balance"      2  exact phrase is servicing-only
--                                               ("escrow" alone is not).  EXCLUSIVE
--   loan-number        "Loan Number"         2  shared: HOI mortgagee clauses, payoffs
--   payment-due        "Payment Due Date"    1  shared: any billing statement
--   interest-rate      "Interest Rate"       1  shared: notes, disclosures, deposits
-- Non-exclusive sum = 2+1+1 = 4 = 0.40 < 0.60 (pinned by MortgageStatementPackIT).
-- Degraded recall: title lost to OCR still reaches 3+2+2 = 7 = 0.70.
INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'DRIVERS_LICENSE', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "dl-title",  "kind": "literal", "pattern": "DRIVER LICENSE", "weight": 5},
    {"id": "dl-number", "kind": "literal", "pattern": "DL No",          "weight": 2},
    {"id": "dob",       "kind": "regex",   "pattern": "\\bDOB\\b",      "weight": 2},
    {"id": "exp",       "kind": "regex",   "pattern": "\\bEXP\\b",      "weight": 1},
    {"id": "class",     "kind": "regex",   "pattern": "\\bCLASS\\b",    "weight": 1},
    {"id": "iss",       "kind": "regex",   "pattern": "\\bISS\\b",      "weight": 1}
  ]
}'::jsonb),
(NULL, 'MORTGAGE_STATEMENT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ms-title",          "kind": "literal", "pattern": "Mortgage Statement", "weight": 5},
    {"id": "principal-balance", "kind": "literal", "pattern": "Principal Balance",  "weight": 3},
    {"id": "escrow-balance",    "kind": "literal", "pattern": "Escrow Balance",     "weight": 2},
    {"id": "loan-number",       "kind": "literal", "pattern": "Loan Number",        "weight": 2},
    {"id": "payment-due",       "kind": "literal", "pattern": "Payment Due Date",   "weight": 1},
    {"id": "interest-rate",     "kind": "literal", "pattern": "Interest Rate",      "weight": 1}
  ]
}'::jsonb);

-- T9: HOI_DECLARATION, PURCHASE_CONTRACT, TAX_RETURN (still inside the §3 dance).
--
-- Exclusivity invariant (V10, generalized): each pack's non-type-exclusive anchors sum
-- to <= 5 = 0.50 < 0.60 x targetScore 10 — no pack can qualify on shared vocabulary
-- alone. Pinned by HoiPurchaseTaxPackTripIT and the T14 cross-confusion gate.
--
--   HOI_DECLARATION    exclusive: decl-page 5 ("Declarations Page" is the document's
--                      own title), dwelling-cov 3 (homeowners-dec vocabulary only).
--                      shared:    policy-period 2 + homeowners-ins 2 + annual-premium 1
--                      = 5. "Homeowners Insurance" is an escrow LINE ITEM on mortgage
--                      statements; "Policy Period" is any insurance document. The HOI
--                      fixture's mortgagee clause deliberately names a lender to prove
--                      the reverse direction (MORTGAGE_STATEMENT must not trip on it).
--
--   PURCHASE_CONTRACT  exclusive: purchase-agreement 4 (the contract's title),
--                      earnest-money 4 (contract-only concept), buyers-signature 2,
--                      sellers-signature 2.
--                      shared:    closing-date 2 + purchase-price 1 = 3 (closing
--                      disclosures, appraisals, escrow letters all print both).
--
--   TAX_RETURN         exclusive: individual-return-title 5 (the form's own title),
--                      schedule-form-1040 5 (schedule headers exist only on actual
--                      1040 schedules), form-1040-page 4 (the form's own dated page
--                      footer, "Form 1040 (2025)").
--                      shared:    form-1040 2 + treasury-irs 2 + filing-status 1 = 5.
--                      "Form 1040" is a REFERENCE — genuine W-2 Copy B notice text and
--                      1099 letters print it; the Treasury line is printed on W-2s too
--                      (the very reason V10 rejected it as a W2 disqualifier). The
--                      V10 lesson, applied from birth: naming the form is not being
--                      the form.
INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'HOI_DECLARATION', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "decl-page",      "kind": "literal", "pattern": "Declarations Page",    "weight": 5},
    {"id": "dwelling-cov",   "kind": "literal", "pattern": "Dwelling Coverage",    "weight": 3},
    {"id": "policy-period",  "kind": "literal", "pattern": "Policy Period",        "weight": 2},
    {"id": "homeowners-ins", "kind": "literal", "pattern": "Homeowners Insurance", "weight": 2},
    {"id": "annual-premium", "kind": "literal", "pattern": "Annual Premium",       "weight": 1}
  ]
}'::jsonb),
(NULL, 'PURCHASE_CONTRACT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "purchase-agreement", "kind": "literal", "pattern": "Purchase Agreement",  "weight": 4},
    {"id": "earnest-money",      "kind": "literal", "pattern": "Earnest Money",       "weight": 4},
    {"id": "buyers-signature",   "kind": "literal", "pattern": "Buyer''s Signature",  "weight": 2},
    {"id": "sellers-signature",  "kind": "literal", "pattern": "Seller''s Signature", "weight": 2},
    {"id": "closing-date",       "kind": "literal", "pattern": "Closing Date",        "weight": 2},
    {"id": "purchase-price",     "kind": "literal", "pattern": "Purchase Price",      "weight": 1}
  ]
}'::jsonb),
(NULL, 'TAX_RETURN', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "individual-return-title", "kind": "literal", "pattern": "U.S. Individual Income Tax Return",                   "weight": 5},
    {"id": "schedule-form-1040",      "kind": "regex",   "pattern": "(?i)Schedule \\d \\(Form 1040\\)",                    "weight": 5},
    {"id": "form-1040-page",          "kind": "regex",   "pattern": "(?i)Form 1040 \\(20\\d\\d\\)",                        "weight": 4},
    {"id": "form-1040",               "kind": "literal", "pattern": "Form 1040",                                           "weight": 2},
    {"id": "treasury-irs",            "kind": "literal", "pattern": "Department of the Treasury—Internal Revenue Service", "weight": 2},
    {"id": "filing-status",           "kind": "literal", "pattern": "Filing Status",                                       "weight": 1}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §4 extraction schemas ────────────────────────────────────────────────────
-- T10: W2, BANK_STATEMENT · T11: DRIVERS_LICENSE, MORTGAGE_STATEMENT ·
-- T12: HOI_DECLARATION, PURCHASE_CONTRACT · T13: TAX_RETURN.
--
-- Late-seed RLS dance (the V10 pattern): extraction_schema has had FORCE ROW
-- LEVEL SECURITY since V7 and its INSERT policy admits only org_id =
-- current_org() — which a global (org_id NULL) row can never satisfy, for any
-- GUC value, because NULL = anything is never TRUE. So the seed drops FORCE for
-- the duration and restores it before commit. A forgotten restore is caught by
-- RlsCoverageIT (relforcerowsecurity over pg_class, no per-table list).
--
-- Label phrases below are authored TOGETHER with fixtures/generate.py's page
-- builders (_w2_page, _bank_statement_page) — fixture text and schema labels
-- are the same strings by construction. Value patterns are BOTH-SIDE-BOUNDED
-- (the Phase 5 lesson); money/date patterns are the V7 paystub patterns
-- verbatim. employeeSsn and accountNumber carry "sensitive": true — the first
-- production PII fields; masking is unconditional at the serializer (no unmask
-- path exists, amended-spec D3).
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'W2', '1.0.0', '{
  "fields": [
    {"name": "employeeName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employee:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "employeeSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "social security number"},
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "employerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,3} (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "employerEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer identification number"},
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Wage and Tax Statement"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "wagesTipsOtherComp", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Wages, tips, other compensation"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "federalIncomeTaxWithheld", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Federal income tax withheld"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "socialSecurityWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social security wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "medicareWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Medicare wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "stateWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "State wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'BANK_STATEMENT', '1.0.0', '{
  "fields": [
    {"name": "accountHolderName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Account Holder:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "bankName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]*(?: [A-Z][A-Z&''-]*){0,3} BANK(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "accountNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Account Number:"},
        "value": {"pattern": "(?<![\\d-])\\d{10,17}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "statementPeriodStart", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Statement Period"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "statementPeriodEnd", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Statement Period"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 1, "scope": "LINE_RIGHT"}}]},
    {"name": "beginningBalance", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Beginning Balance"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "endingBalance", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Ending Balance"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalDeposits", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total Deposits"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalWithdrawals", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total Withdrawals"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

-- T11: DRIVERS_LICENSE + MORTGAGE_STATEMENT — appended INSIDE T10's single §4
-- NO FORCE dance.
--
-- Label phrases below are authored TOGETHER with fixtures/generate.py's page
-- builders (_drivers_license_page, _mortgage_statement_page). The DL schema
-- reuses the card's field abbreviations (DL No / DOB / EXP / ISS / CLASS) as
-- extraction labels: the PACK matches them as case-sensitive \b regexes
-- (classification must not trip on prose "dob"), while these labels are
-- literal (case-insensitive containment) — extraction only runs on pages
-- already classified DRIVERS_LICENSE, so containment is safe and survives
-- OCR case wobble. MS labels are colon-suffixed on purpose: "Amount Due:"
-- must not match the "Explanation of Amount Due" heading.
--
-- Mask-branch choices (MaskingService: EXACTLY nine digits ignoring
-- punctuation -> SSN mask; otherwise •••• + last four CHARACTERS):
--   licenseNumber 941-234-5678  10 digits -> ••••5678 (nine would take the
--                               SSN branch — never seed a 9-digit non-SSN)
--   dateOfBirth   01/15/1988    generic length rule -> ••••1988
--   loanNumber    0087-445-921  10 digits -> ••••-921 (last four CHARACTERS,
--                               hyphen included — pinned by the masking IT)
--
-- Value patterns are BOTH-SIDE-BOUNDED (the Phase 5 lesson); money/date
-- patterns are the V7 paystub patterns verbatim. issuingState enumerates the
-- fifty state names: a card header carries no label to anchor on, and an
-- explicit alternation is deterministic where "first long uppercase word" is
-- not. interestRate is STRING with a null normalizer — no percent normalizer
-- exists and inventing one is out of this task's scope.
INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'DRIVERS_LICENSE', '1.0.0', '{
  "fields": [
    {"name": "fullName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z]+, [A-Z]+ [A-Z]+(?![A-Za-z,])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "licenseNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "DL No"},
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{3}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{3}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "dateOfBirth", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "DOB"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "address", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z0-9])\\d{1,5}(?: [A-Z]+)* (?:AVE|ST|RD|DR|BLVD|LN|CT|WAY),? [A-Z]+, [A-Z]{2} \\d{5}(?!\\d)",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "issueDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "ISS"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "expirationDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "EXP"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "issuingState", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])(?:ALABAMA|ALASKA|ARIZONA|ARKANSAS|CALIFORNIA|COLORADO|CONNECTICUT|DELAWARE|FLORIDA|GEORGIA|HAWAII|IDAHO|ILLINOIS|INDIANA|IOWA|KANSAS|KENTUCKY|LOUISIANA|MAINE|MARYLAND|MASSACHUSETTS|MICHIGAN|MINNESOTA|MISSISSIPPI|MISSOURI|MONTANA|NEBRASKA|NEVADA|NEW HAMPSHIRE|NEW JERSEY|NEW MEXICO|NEW YORK|NORTH CAROLINA|NORTH DAKOTA|OHIO|OKLAHOMA|OREGON|PENNSYLVANIA|RHODE ISLAND|SOUTH CAROLINA|SOUTH DAKOTA|TENNESSEE|TEXAS|UTAH|VERMONT|VIRGINIA|WASHINGTON|WEST VIRGINIA|WISCONSIN|WYOMING)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "licenseClass", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "CLASS"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z](?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'MORTGAGE_STATEMENT', '1.0.0', '{
  "fields": [
    {"name": "borrowerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Borrower:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "lenderName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,4},? (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "loanNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Loan Number:"},
        "value": {"pattern": "(?<![\\d-])\\d{4}-\\d{3}-\\d{3}(?![\\d-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{4}-\\d{3}-\\d{3}(?![\\d-])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "statementDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Statement Date:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "paymentDueDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Payment Due Date:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalAmountDue", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Amount Due:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "principalBalance", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Principal Balance:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "escrowBalance", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Escrow Balance:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "interestRate", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Interest Rate:"},
        "value": {"pattern": "(?<![\\d.])\\d{1,2}\\.\\d{1,4}%(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

-- T12: HOI_DECLARATION + PURCHASE_CONTRACT — appended INSIDE T10's single §4
-- NO FORCE dance.
--
-- Label phrases are authored TOGETHER with fixtures/generate.py's page builders
-- (_hoi_declaration_page, _purchase_contract_terms_page,
-- _purchase_contract_signature_page): fixture text and schema labels are the
-- same strings by construction.
--
-- insurerName is a REGEX rung, not an anchored one: a carrier masthead carries
-- no label to anchor on — the same shape T11 met with the DL card header and
-- the MS lender name, answered the same way (page scope, value-only evidence).
-- The pattern requires the literal INSURANCE plus a company suffix so the
-- deliberately drawn mortgagee clause ("FIRST SYNTHETIC MORTGAGE LLC") can
-- never win it.
--
-- Value patterns are BOTH-SIDE-BOUNDED (the Phase 5 lesson) and money/date
-- patterns are the V7 paystub patterns verbatim, with ONE deliberate exception:
-- dwellingCoverage. Dec pages print Coverage A in whole dollars ($425,000), so
-- its pattern admits a comma-grouped amount with OPTIONAL cents. Both branches
-- stay bounded — the comma branch needs a real thousands group and the plain
-- branch still needs cents, so neither can carve "1234" or "234.56" out of
-- "1234.5678" the way an unanchored money pattern once did.
--
-- policyNumber ships sensitive: the full value is extracted and stored, and the
-- serializer masks it unconditionally (design D3, no unmask path). HO-8842716
-- is EIGHT digits ignoring punctuation, so MaskingService takes the generic
-- branch — ••••2716, last four CHARACTERS. Never seed a nine-digit non-SSN.
--
-- buyerSigned/sellerSigned are the first production SIGNATURE_PRESENCE fields
-- (design D5): the region label anchors the search, windowPt grows that label
-- box in canonical points (y increases DOWNWARD, so "above" reaches up toward
-- the signature rule), and the answer is always SIGNED or UNSIGNED. UNSIGNED is
-- a real value with LABEL-only evidence; only a MISSING signature BLOCK is the
-- missing-field contract. The fixture stacks the two blocks ~100pt apart, well
-- outside the 40pt "above" reach, so neither field can claim the other party's
-- ink. Both carry NO value block — the T5 loader requires its ABSENCE, plus a
-- region with label and windowPt, for this method.
INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'HOI_DECLARATION', '1.0.0', '{
  "fields": [
    {"name": "insuredName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Named Insured:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "insurerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]*(?: [A-Z&][A-Z&''-]*){0,3} INSURANCE (?:COMPANY|GROUP|MUTUAL|EXCHANGE|SERVICES|CO\\.)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "policyNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Policy Number:"},
        "value": {"pattern": "(?<![A-Za-z0-9-])[A-Z]{2,4}-[0-9]{5,12}(?![A-Za-z0-9-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Property Address:"},
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "effectiveDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Policy Period:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "expirationDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Policy Period:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 1, "scope": "LINE_RIGHT"}}]},
    {"name": "dwellingCoverage", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Dwelling Coverage"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?|\\d+\\.\\d{2})(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "annualPremium", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Annual Premium:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'PURCHASE_CONTRACT', '1.0.0', '{
  "fields": [
    {"name": "buyerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Buyer:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "sellerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Seller:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Property Address:"},
        "value": {"pattern": "\\d{1,5} .+", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "purchasePrice", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Purchase Price:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "earnestMoney", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Earnest Money:"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "contractDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Contract Date:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "closingDate", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Closing Date:"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "buyerSigned", "dataType": "ENUM", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "SIGNATURE_PRESENCE", "strength": 0.9,
        "region": {"label": {"kind": "literal", "pattern": "Buyer''s Signature"},
                   "windowPt": {"left": 0.0, "right": 240.0, "above": 40.0, "below": 8.0}}}]},
    {"name": "sellerSigned", "dataType": "ENUM", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "SIGNATURE_PRESENCE", "strength": 0.9,
        "region": {"label": {"kind": "literal", "pattern": "Seller''s Signature"},
                   "windowPt": {"left": 0.0, "right": 240.0, "above": 40.0, "below": 8.0}}}]}
  ]
}'::jsonb);

-- T13: tax_return@1.0.0 — the LAST §4 schema and the CHECKBOX_STATE production
-- proof. filingStatus maps ALL FIVE 1040 filing-status checkboxes to enum codes
-- by label anchor + nearest-detection proximity (18pt cap; the fixture draws
-- each box 18pt left of its label so its own centre lands 13pt away, while the
-- next row's box — rows are 18pt apart — sits 18.4pt away and falls outside the
-- cap). Zero or two checked bindings is a review case, not a guess (design D6):
-- the rung fails and the field persists as the missing contract — the degraded
-- fixture pins that end to end, because below-floor detections never reach the
-- database at all. primarySsn ships sensitive: the full value is extracted and
-- stored, and the serializer masks it unconditionally (design D3, no unmask
-- path); 987-65-4321 is exactly nine digits ignoring punctuation, so
-- MaskingService takes the SSN branch — •••-••-4321.
--
-- Label phrases are authored TOGETHER with fixtures/generate.py's
-- _tax_return_1040_p1 / _tax_return_1040_p2. "total tax" is deliberately
-- lowercase: it mirrors the drawn line ("24 This is your total tax 12,921.00"),
-- and literal matching is case-insensitive containment either way — fixture text
-- and schema label stay the same string by construction. filingStatus carries NO
-- value block: the T5 loader requires its ABSENCE, plus options and proximityPt,
-- for this method.
INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'TAX_RETURN', '1.0.0', '{
  "fields": [
    {"name": "primaryTaxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "spouseName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Spouse''s name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "primarySsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "U.S. Individual Income Tax Return"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "filingStatus", "dataType": "ENUM", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "CHECKBOX_STATE", "strength": 0.9, "proximityPt": 18.0,
        "options": [
          {"label": {"kind": "literal", "pattern": "Single"},
           "value": "SINGLE"},
          {"label": {"kind": "literal", "pattern": "Married filing jointly"},
           "value": "MARRIED_FILING_JOINTLY"},
          {"label": {"kind": "literal", "pattern": "Married filing separately"},
           "value": "MARRIED_FILING_SEPARATELY"},
          {"label": {"kind": "literal", "pattern": "Head of household"},
           "value": "HEAD_OF_HOUSEHOLD"},
          {"label": {"kind": "literal", "pattern": "Qualifying surviving spouse"},
           "value": "QUALIFYING_SURVIVING_SPOUSE"}
        ]}]},
    {"name": "totalIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "adjustedGrossIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Adjusted gross income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxableIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Taxable income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalTax", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "total tax"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "refundAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Refund amount"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

-- (§4 complete: T10–T13 seeded all seven schemas inside this one dance; the FORCE
-- restore below closes it. Corrections ship as new schema versions, never edits.)
ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
