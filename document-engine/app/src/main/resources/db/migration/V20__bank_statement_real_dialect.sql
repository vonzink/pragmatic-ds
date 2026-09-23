-- V18 — bank_statement@1.1.0: the vocabulary a REAL bank statement prints.
--
-- Measured against a real checking statement (2026-08-17 diagnosis of
-- "correctly classified, zero fields extracted") and pinned by the corpus
-- fixtures tools/gen_realworld_fixtures.py rebuilds:
--
--   * The summary block says "Deposits and Additions" / "Electronic
--     Withdrawals" — never "Total Deposits" / "Total Withdrawals", which are
--     the synthetic generator's own captions. The generator rungs stay (the
--     committed fixtures print them); the real wordings are ADDED as second
--     rungs whose money pattern admits the printed LEADING MINUS real banks
--     put on a withdrawals total — the sign is part of the value (the
--     money-sign lesson of Spec 5a, applied at capture).
--   * No "Statement Period" CAPTION exists anywhere. The phrase appears
--     inside footnote PROSE ("...the monthly statement period: ..."), and the
--     1.0.0 label anchored there: on a fixture whose prose carries dates
--     right of the phrase, statementPeriodStart/End captured them at 0.90 —
--     confident, evidence-backed and WRONG (measured live before this
--     migration; the real statement escapes only because its sentence ends at
--     the phrase). A phrase inside prose is not a caption, and no label-level
--     rule can tell them apart — so the naked label is RETIRED and both
--     period fields anchor on full RANGE CONSTRUCTIONS instead:
--       - "Statement Period: MM/DD/YYYY - MM/DD/YYYY" (the generator's line,
--         demanded whole — prose without the dash construction cannot match);
--       - "Month D, YYYY through Month D, YYYY" (the real masthead line),
--         tolerating the measured word fusion ("2026throughJune" arrives as
--         ONE pdfplumber word, so the seam may carry no space at all).
--     The date normalizer already parses month-name dates (MMMM d, uuuu).
--   * accountHolderName stays as seeded and stays MISSING on this dialect:
--     the real statement prints the holder bare in the address block, and a
--     PAGE-scope name guess is exactly the confident-wrong-value shape D5
--     forbids.
--
-- accountNumber/beginningBalance/endingBalance/bankName/accountHolderName are
-- 1.0.0 verbatim. Retired, never edited: corrections are new versions (the
-- V10 rule), and 1.0.0 stays for provenance of rows extracted under it.
--
-- RLS: the V10/V12 late-seed dance — extraction_schema has FORCE ROW LEVEL
-- SECURITY since V7 and its INSERT policy admits only org_id = current_org(),
-- which a global (org_id NULL) row can never satisfy. Drop FORCE for the
-- duration, restore before commit; a forgotten restore fails RlsCoverageIT.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- Retire the version whose period label reads prose. Retired, never deleted.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.0.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'BANK_STATEMENT', '1.1.0', '{
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
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Electronic Withdrawals"},
        "value": {"pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
