-- V38 — BANK_STATEMENT rule pack 1.1.0: the vocabulary real banks actually print.
--
-- The defect (empirical, a real 5-page Chase checking document, 2026-09-01). The
-- reviewer opened the document manager's Parsing tab and got a "Statement summary"
-- card of em-dashes and "No transactions extracted". The parse had not failed —
-- CLASSIFICATION had. bank_statement@1.0.0 anchors the vocabulary this repository's
-- own synthetic fixture prints ("Statement Period", "Account Statement", "Deposits
-- and Credits"), and Chase prints almost none of it. Measured against 1.0.0:
--
--   Chase online activity print-out   0.00  ("Printed from Chase Personal Online",
--                                            "TOTAL CHECKING (...6227)", "Present
--                                            balance", "Available balance") — not
--                                            one anchor matched.
--   Chase mailed/downloaded statement 0.50  (Beginning Balance, Ending Balance, and
--                                            "Withdrawals" inside "Electronic
--                                            Withdrawals") — it prints a bare
--                                            month-name date range instead of a
--                                            "Statement Period" caption, and
--                                            "Deposits and ADDITIONS", not Credits.
--
-- Both land UNKNOWN. An UNKNOWN document draws no extraction schema and no AI
-- dialect, so it yields zero fields — the empty summary card, exactly. This is the
-- V10 defect in its other direction: V10 was a pack that could fire on the wrong
-- document, this is a pack that cannot fire on the right one. corpus/README.md
-- predicted it in as many words ("bank statements, several banks — layouts differ
-- wildly"), and the SAME miss shape as V34's Oracle-HCM paystub (0.40 on a real
-- employer's stub because the pack knew only one vendor's captions).
--
-- The fix is DATA, per D7/D15, and a new pack VERSION rather than an edit of 1.0.0:
-- classification_result.rule_pack_version names the pack that decided every stored
-- row, so mutating 1.0.0 in place would silently rewrite the meaning of results
-- already recorded against it. RulePackLoader takes the highest version within the
-- surviving scope, so 1.1.0 supersedes on load; 1.0.0 is RETIRED rather than left
-- active, because a pack with a known recall hole must not become reachable again
-- by deactivating its successor (V10's rule, unchanged).
--
-- ── The anchors, in three tiers ─────────────────────────────────────────────
-- Weight tracks EXCLUSIVITY — how much a phrase proves the page IS a deposit
-- account statement — which is V10's discipline applied to a second pack. The
-- tiers are not decoration; the invariant below is stated in terms of them.
--
-- TIER 1, DEPOSIT-ACCOUNT EXCLUSIVE (a credit card, mortgage or brokerage
-- statement does not print these):
--   deposits               "Deposits and Credits"         2 -> 3  the 1.0.0 phrase.
--                                                                 Deposit-account
--                                                                 exclusive, so it
--                                                                 rises like V10's
--                                                                 "Wage and Tax
--                                                                 Statement" did.
--   deposits-additions     "Deposits and Additions"       new 3   Chase/BoA wording
--                                                                 for the same block.
--   deposit-account        /checking|savings|money market/ new 3  the ACCOUNT TYPE
--                                                                 named anywhere on
--                                                                 the page ("TOTAL
--                                                                 CHECKING (...6227)",
--                                                                 "CHECKING SUMMARY",
--                                                                 "Savings Account").
--                                                                 This is the anchor
--                                                                 that makes the
--                                                                 online dialect safe
--                                                                 — see the invariant.
--   electronic-withdrawals "Electronic Withdrawals"       new 2   Chase's withdrawal
--                                                                 block heading.
--   checks-paid            "Checks Paid"                  new 2   checking-exclusive:
--                                                                 no other statement
--                                                                 genre pays checks.
--   deposit-summary        /(CHECKING|SAVINGS) SUMMARY/   new 2   the summary block's
--                                                                 own heading. Layered
--                                                                 DELIBERATELY on top
--                                                                 of deposit-account
--                                                                 (which it also
--                                                                 matches): naming the
--                                                                 account type in a
--                                                                 summary heading is
--                                                                 stronger evidence
--                                                                 than naming it in
--                                                                 prose.
--
-- TIER 2, STATEMENT FURNITURE (shared with other statement genres):
--   stmt-period    "Statement Period"      3 -> 2  DEMOTED. It was the pack's heaviest
--                                                  anchor and it is not deposit-
--                                                  exclusive — mortgage, brokerage,
--                                                  card and utility statements all
--                                                  print it. Exactly V10's "form-w2"
--                                                  demotion: naming a period is not
--                                                  being a bank statement.
--   begin-balance  "Beginning Balance"     2 -> 2  unchanged.
--   end-balance    "Ending Balance"        2 -> 2  unchanged.
--   acct-statement "Account Statement"     2 -> 2  unchanged.
--   period-through /Month DD, YYYY through/ new 2  the masthead Chase prints INSTEAD
--                                                  of a "Statement Period" caption
--                                                  ("June 01, 2026 through June 30,
--                                                  2026"). Tier 2, not tier 1: an
--                                                  insurance declaration prints a
--                                                  policy period the same way.
--   withdrawals    "Withdrawals"           1 -> 1  unchanged, and deliberately the
--                                                  pack's lightest: it is one word,
--                                                  and it is a SUBSTRING of
--                                                  "Electronic Withdrawals", so a
--                                                  Chase page scores both (1+2). That
--                                                  double count is stated rather than
--                                                  hidden — it only ever adds to a
--                                                  phrase that is already deposit-
--                                                  exclusive, and 1.0.0 already
--                                                  behaved this way.
--
-- TIER 3, ONLINE-VIEW FURNITURE (shared with ANY online account view, a credit
-- card's included — so weighted so that it CANNOT qualify on its own):
--   online-print      /Printed from ... Online/  new 2  the online print-out genre
--                                                       marker, vendor-NEUTRAL by
--                                                       construction (it matches Wells
--                                                       Fargo's and BoA's print
--                                                       headers too). Anchoring the
--                                                       literal words "Chase" was
--                                                       rejected: a pack that names
--                                                       one bank cannot classify the
--                                                       next one, which is the very
--                                                       defect being fixed.
--   available-balance "Available balance"        new 1
--   present-balance   "Present balance"          new 1
--   posted-activity   /(Transaction|Account|Recent) activity/ new 1
--
-- ── The invariant (pinned by BankStatementDialectIT) ────────────────────────
-- Tier 3 sums to 2+1+1+1 = 5 = 0.50 < 0.60. A page carrying EVERY piece of online-
-- view furniture and no deposit-account signal CANNOT qualify. That is not a
-- rounding accident, it is the guard against the one false positive this change
-- could otherwise introduce: a CREDIT CARD online print-out, which prints "Printed
-- from ... Online", "Present balance", "Available balance" and "Transaction
-- activity" verbatim and is a LIABILITY document, not an asset one. Measured at
-- 0.50 on a reproduction; the same page with a checking account named reaches 0.80.
--
-- Recall, measured on the same reproductions: online print-out 0.00 -> 0.80, mailed
-- statement 0.50 -> 1.00, and the committed bank_statement/bank_statement_three
-- fixtures hold at 1.00 (they lose 1 point as stmt-period is demoted and are far
-- above the bar regardless). Cross-confusion: BANK_STATEMENT@1.1.0 scores at most
-- 0.30 on ANY page of ANY other type's fixture (schedule_b, whose interest-and-
-- dividends prose names savings accounts) — gated in both directions by
-- CrossConfusionIT, which now also carries the bank_statement_chase fixture.
--
-- targetScore stays 10 and min_confidence stays 0.60, so the change reads as pure
-- re-weighting plus additions. On `>=` vs `>`: unchanged and deliberate — see the
-- decision note in PageClassifier#decide.
--
-- WHAT THIS DOES NOT FIX, stated so nobody reads more into it. Classification is
-- the gate, not the whole answer. An online activity print-out prints no statement
-- period and no beginning/ending balance for a period, so the bank_statement
-- extraction schema's rungs for those fields will find nothing and they will land
-- MISSING (confidence 0, MANUAL_REVIEW_REQUIRED) — correctly, per the missing-over-
-- wrong rule — and BankStatementReconciler cannot prove an arithmetic it has no
-- opening balance for. The reviewer gets the transaction ledger and a partially
-- populated summary card instead of an empty one. Closing that gap is an extraction
-- concern (a schema version bump, or the AI dialect reading the online layout), not
-- a classification one, and it is deliberately not attempted here.
--
-- RLS ORDERING — V6's rule, unchanged since V10: classification_rule_pack is FORCE
-- ROW LEVEL SECURITY and its policies admit only `org_id = current_org()`, which no
-- GUC value can satisfy for a GLOBAL (org_id NULL) row. So FORCE is dropped for the
-- duration and restored before commit; with ENABLE but not FORCE the owner bypasses
-- policies and no window exists for any other session. Forgetting the restore is
-- already guarded — RlsCoverageIT asserts relforcerowsecurity over every non-exempt
-- table — so no new test is needed for it.

ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

-- Retire the pack that cannot see a Chase statement.
UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.0.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'BANK_STATEMENT', '1.1.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "deposits",               "kind": "literal", "pattern": "Deposits and Credits",   "weight": 3},
    {"id": "deposits-additions",     "kind": "literal", "pattern": "Deposits and Additions", "weight": 3},
    {"id": "deposit-account",        "kind": "regex",   "pattern": "(?i)\\b(?:checking|savings|money market)\\b", "weight": 3},
    {"id": "electronic-withdrawals", "kind": "literal", "pattern": "Electronic Withdrawals", "weight": 2},
    {"id": "checks-paid",            "kind": "literal", "pattern": "Checks Paid",            "weight": 2},
    {"id": "deposit-summary",        "kind": "regex",   "pattern": "(?i)\\b(?:CHECKING|SAVINGS)\\s+SUMMARY\\b", "weight": 2},
    {"id": "stmt-period",            "kind": "literal", "pattern": "Statement Period",       "weight": 2},
    {"id": "begin-balance",          "kind": "literal", "pattern": "Beginning Balance",      "weight": 2},
    {"id": "end-balance",            "kind": "literal", "pattern": "Ending Balance",         "weight": 2},
    {"id": "acct-statement",         "kind": "literal", "pattern": "Account Statement",      "weight": 2},
    {"id": "period-through",         "kind": "regex",   "pattern": "(?i)\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}\\s+through\\b", "weight": 2},
    {"id": "withdrawals",            "kind": "literal", "pattern": "Withdrawals",            "weight": 1},
    {"id": "online-print",           "kind": "regex",   "pattern": "(?i)\\bPrinted from\\b.{0,40}\\bOnline\\b", "weight": 2},
    {"id": "available-balance",      "kind": "literal", "pattern": "Available balance",      "weight": 1},
    {"id": "present-balance",        "kind": "literal", "pattern": "Present balance",        "weight": 1},
    {"id": "posted-activity",        "kind": "regex",   "pattern": "(?i)\\b(?:Transaction|Account|Recent)\\s+activity\\b", "weight": 1}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;
