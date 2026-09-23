-- V19 — bank_statement@1.2.0: a CATEGORY SUBTOTAL is not the total.
--
-- V18 added a second totalWithdrawals rung anchored on "Electronic
-- Withdrawals", asserting in its comment that this is what the real dialect
-- calls its withdrawals total. The measured documents refute it. Two
-- consecutive real Chase checking statements print FIVE summary rows —
--
--     Beginning Balance
--     Deposits and Additions
--     Checks Paid            (nonzero, negative)
--     Electronic Withdrawals (negative)
--     Ending Balance
--
-- — and the block's own arithmetic settles the question:
--
--     begin + deposits + checksPaid + electronic == ending   TRUE
--     begin + deposits +              electronic == ending   FALSE
--
-- So "Electronic Withdrawals" is one CATEGORY among several, and the V18 rung
-- served a number 1.62x (May) and 2.62x (June) short of the money that
-- actually left the account — at 0.9 confidence, with an evidence box on a row
-- that really is printed, so nothing downstream could tell it was wrong. Other
-- products in this dialect add "ATM & Debit Card Withdrawals" and "Fees" rows
-- and widen the error further.
--
-- The rung is RETIRED with nothing put in its place. Chase prints no
-- withdrawals total, so the correct outcome on this dialect is MISSING:
-- summing the categories would be arithmetic the extractor invented, and a
-- category wearing the total's name is the confident-wrong-value shape D5
-- forbids. A field that goes missing costs a reviewer one lookup; a field that
-- serves a confidently wrong number corrupts an underwriting decision.
--
-- The surviving rung is the generator dialect's literal "Total Withdrawals"
-- caption — a genuine total row — and it inherits V18's SIGNED money pattern,
-- because a real bank that does print a withdrawals total prints its minus and
-- the sign is part of the value (the Spec 5a money-sign rule). Everything else
-- is 1.1.0 verbatim, including totalDeposits' "Deposits and Additions" rung:
-- the same identity above balances with that row as the ONLY credit row, so it
-- IS the whole deposit side, not a category of it.
--
-- V18 is not edited. It has been applied outside this branch, and corrections
-- are new versions (the V10 rule): 1.1.0 stays for provenance of every row
-- extracted under it.
--
-- RLS: the V10/V12/V18 late-seed dance — extraction_schema has FORCE ROW LEVEL
-- SECURITY since V7 and its INSERT policy admits only org_id = current_org(),
-- which a global (org_id NULL) row can never satisfy. Drop FORCE for the
-- duration, restore before commit; a forgotten restore fails RlsCoverageIT.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- Retire the version whose withdrawals rung reads a category. Retired, never deleted.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.1.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'BANK_STATEMENT', '1.2.0', '{
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
        "label": {"kind": "regex",
                  "pattern": "Statement Period:? ?\\d{2}/\\d{2}/\\d{4} ?- ?\\d{2}/\\d{2}/\\d{4}"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 0, "scope": "LINE"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex",
                  "pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}\\s?through\\s?(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"},
        "value": {"pattern": "(?<!\\d)(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}(?!\\d)",
                  "occurrence": 0, "scope": "LINE"}}]},
    {"name": "statementPeriodEnd", "dataType": "DATE", "required": true,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex",
                  "pattern": "Statement Period:? ?\\d{2}/\\d{2}/\\d{4} ?- ?\\d{2}/\\d{2}/\\d{4}"},
        "value": {"pattern": "\\d{2}/\\d{2}/\\d{4}", "occurrence": 1, "scope": "LINE"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex",
                  "pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}\\s?through\\s?(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"},
        "value": {"pattern": "(?<!\\d)(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}(?!\\d)",
                  "occurrence": 1, "scope": "LINE"}}]},
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
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Deposits and Additions"},
        "value": {"pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalWithdrawals", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total Withdrawals"},
        "value": {"pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
