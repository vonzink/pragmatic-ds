-- V41 — bank_statement@1.6.0: the print-out's month-to-date tiles, as their OWN fields.
--
--   §1 extraction_schema: bank_statement@1.6.0 derived from 1.5.0 (the V25/V39/V40
--      idiom), APPENDING two fields; 1.5.0 retired — ONE NO FORCE dance
--
-- ── What the tiles are ──────────────────────────────────────────────────────
-- V40 read the real print-out's balance block and recorded its captions: a headline
-- "Available balance", then a row of tiles — "Present balance", "Deposits this
-- month" (+$), "Withdrawals this month" (-$), and a "Debit card coverage" toggle.
-- V40 deliberately bound none of the last three, and the reason for the two money
-- tiles stands: THIS MONTH is the calendar month to date at the moment of printing,
-- a window the document states nowhere else, while the activity listed beneath is
-- whatever range was on screen. They are not the totals of the ledger shown, and
-- under totalDeposits/totalWithdrawals — the period totals a partition check reads —
-- they would be the V21 shape: a printed, plausibly-named number that is not the
-- quantity the field means.
--
-- The reviewer wants the numbers on the card nonetheless, and the honest way to
-- carry them is under names that say what they are. So 1.6.0 adds
--
--   monthToDateDeposits      "+$2,180.40"  over "Deposits this month"
--   monthToDateWithdrawals   "-$674.20"    over "Withdrawals this month"
--
-- as new MONEY fields, not as rungs on the period totals. Nothing downstream reads
-- them by name: BankStatementReconciler proves a print-out by its running-balance
-- chain and consumes the AI ledger's ten summary keys, which are unchanged, so no
-- partition is ever checked against a month-to-date figure. A mailed statement
-- prints neither caption and lands both fields MISSING, which is correct — the
-- fixtures pin it. required is false: these are a genre's fields, not the type's.
--
-- ── Reading them ────────────────────────────────────────────────────────────
-- Both are LABEL_ABOVE (V40): the amount sits over its caption in a tile. The value
-- pattern admits a leading sign, [-+]?, so the captured text is the whole printed
-- token — "+$2,180.40" as rendered, V39's rule — and the money normalizer now reads
-- an explicit plus as a printed sign (this change), as it already read a minus and
-- accounting parentheses. The withdrawals figure keeps its minus, exactly as
-- totalWithdrawals does on the mailed dialect (V19).
--
-- Each ladder has two rungs. The first anchors the whole caption. The second
-- anchors its first two words, because the real page WRAPS the last caption:
-- "Withdrawals this" on the caption row and "month" on the line beneath, with the
-- next tile's caption ("Debit card coverage") printed between them in reading
-- order, so the three-word literal never occurs contiguously in the page text.
-- Anchored on "Withdrawals this", the cell window runs from that caption's left edge
-- to the next caption's left edge on the caption row ("Debit"), which holds the
-- amount and nothing else. The deposits caption does not wrap on the measured page;
-- its second rung exists for a narrower print where it would.
--
-- RLS: the V10/V12/V18/V21/V25/V39/V40 late-seed dance — FORCE dropped for the
-- duration, restored before commit; a forgotten restore fails RlsCoverageIT.

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.5.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
SELECT NULL,
       'BANK_STATEMENT',
       '1.6.0',
       jsonb_set(
           definition,
           '{fields}',
           (definition -> 'fields') || '[{
             "name": "monthToDateDeposits",
             "dataType": "MONEY",
             "required": false,
             "sensitive": false,
             "normalizer": "money",
             "extractors": [{
               "label": {"kind": "literal", "pattern": "Deposits this month"},
               "value": {"pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                         "occurrence": 0},
               "method": "LABEL_ABOVE",
               "strength": 0.8
             }, {
               "label": {"kind": "literal", "pattern": "Deposits this"},
               "value": {"pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                         "occurrence": 0},
               "method": "LABEL_ABOVE",
               "strength": 0.7
             }]
           }, {
             "name": "monthToDateWithdrawals",
             "dataType": "MONEY",
             "required": false,
             "sensitive": false,
             "normalizer": "money",
             "extractors": [{
               "label": {"kind": "literal", "pattern": "Withdrawals this month"},
               "value": {"pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                         "occurrence": 0},
               "method": "LABEL_ABOVE",
               "strength": 0.8
             }, {
               "label": {"kind": "literal", "pattern": "Withdrawals this"},
               "value": {"pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                         "occurrence": 0},
               "method": "LABEL_ABOVE",
               "strength": 0.7
             }]
           }]'::jsonb
       )
  FROM extraction_schema
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.5.0';

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
