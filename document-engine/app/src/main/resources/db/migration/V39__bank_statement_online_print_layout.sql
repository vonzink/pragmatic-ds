-- V39 — bank_statement@1.4.0: reading the ONLINE ACTIVITY PRINT-OUT.
--
-- V38 taught the classifier to recognise a real bank's document. This is the other
-- half of that fix, and without it V38 buys a reviewer very little: measured against
-- bank_statement@1.3.0, the online print-out that prompted V38 captures NOTHING AT
-- ALL — nine of nine fields MISSING. The document classifies, draws a schema, and
-- still renders a summary card of em-dashes. A type label nobody can extract from is
-- a label, not an answer.
--
-- ── Why the schema misses a document it can classify ────────────────────────
-- An online print-out is a different GENRE from a mailed statement, not a dialect of
-- one. A statement is struck for a closed period and prints that period, an opening
-- balance and a closing balance. A print-out is a view of an account AS OF NOW: it
-- prints the brand, the account and its balance, then the rows. So most of the
-- ladder has nothing to read, and that is not a defect to fix — it is the document.
-- Six of the nine fields therefore stay MISSING BY DESIGN and V39 adds no rung for
-- them. What the layout does carry is exactly three things the schema could not read:
--
--   bankName       The masthead prints the brand in caps ("CHASE"), and the 1.0.0
--                  rung needs the literal word BANK, which no major bank's logotype
--                  includes. The print HEADER, though, writes it as a word: "Printed
--                  from Chase Personal Online". A new rung anchors that header and
--                  takes the brand out of it. Vendor-NEUTRAL by construction — the
--                  same header reads "Wells Fargo Online" and yields "Wells Fargo" —
--                  for the reason V38 gives at length: a rule that names one bank
--                  cannot read the next one.
--   accountNumber  The print-out shows only the last four, under the account type
--                  ("TOTAL CHECKING (...6227)"). The 1.0.0 rung wants 10-17 digits
--                  and correctly declines. The new rung anchors the ACCOUNT TYPE and
--                  reads the truncated run to its right. It is deliberately NOT a
--                  page-wide regex: transaction rows in this very layout print
--                  "Online Transfer To Sav ...8890", and a page-scope pattern would
--                  cheerfully return a counterparty's account as the statement's.
--                  Anchored to the account-type caption's own line, that cannot
--                  happen.
--                    The persisted value is "...6227" — what the document renders,
--                  which is also the only thing that MASKS correctly: MaskingService
--                  reveals the last four of a value longer than four, so "...6227"
--                  serves "••••6227" while a bare "6227" would serve "••••" and tell
--                  the reviewer nothing at all.
--   endingBalance  "Present balance" is the balance as of printing — the closing
--                  balance of the activity shown, and the only balance this genre
--                  states. A second rung reads "Current balance", the same genre's
--                  other common wording.
--                    "Available balance", printed directly beneath it, is NOT read by
--                  anything. It nets out holds and pending items, so it is a
--                  DIFFERENT QUANTITY, and serving it as the ending balance would be
--                  the confident-wrong-value shape D5 forbids. The fixture draws it
--                  precisely so the ladder is proven to walk past it.
--
-- ── Ladder ORDER: the new rungs go last, and that is load-bearing ───────────
-- The first rung that captures wins. Each new rung is APPENDED, so a document that
-- prints a real "Ending Balance" caption still answers from it and the online reading
-- is only ever consulted when the statement vocabulary is absent. A print-out that
-- somehow carried both would be read as the statement it is.
--
-- ── What is deliberately NOT added ─────────────────────────────────────────
--   statementPeriodStart/End  The print-out states no period. Deriving one from the
--                             first and last transaction dates would be arithmetic
--                             the extractor invented, and it feeds the instance key
--                             the splitter cuts on. A page with no key CONTINUES the
--                             current instance (InstanceKeyBoundaryDetector), so
--                             missing here costs nothing and inventing would cut a
--                             five-page print-out into fragments.
--   beginningBalance          Not printed. There is nothing to read.
--   totalDeposits/Withdrawals Not printed as totals. V21 already settled the harder
--                             version of this question for the mailed dialect — a
--                             CATEGORY SUBTOTAL is not the total, and it retired a
--                             rung rather than serve one — and the same rule decides
--                             it here with even less to argue about.
--   accountHolderName         The observed print-out carries no holder caption. An
--                             unanchored name capture on a page dense with merchant
--                             names is exactly the wrong trade.
--
-- Consequence, stated plainly because it does not go away: with no beginning balance
-- and no totals, BankStatementReconciler still cannot prove this document's
-- arithmetic, and it should not pretend to. The reviewer gets a bank, an account, a
-- balance and the ledger — a partially populated card instead of an empty one — and
-- a reconciliation status that honestly says it could not be checked.
--
-- ── Mechanics ──────────────────────────────────────────────────────────────
-- DERIVED from 1.3.0 rather than retyped, the V25 rule: the definition is ~200 lines
-- of authored ladders and transcribing them to append three rungs would be a
-- silent-corruption risk for no benefit. The aggregate below rebuilds the fields
-- array in ORDER, touching three fields by NAME and passing every other field
-- through byte-identical.
--
-- 1.3.0 is retired, never deleted: every extracted_field already written under it
-- still points at that row through schema_id (the V10 rule).
--
-- RLS: the V10/V12/V18/V21/V25 late-seed dance — extraction_schema has FORCE ROW
-- LEVEL SECURITY since V7 and its INSERT policy admits only org_id = current_org(),
-- which a global (org_id NULL) row can never satisfy. FORCE is dropped for the
-- duration and restored before commit; a forgotten restore fails RlsCoverageIT.

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.3.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
SELECT NULL,
       'BANK_STATEMENT',
       '1.4.0',
       jsonb_set(
           definition,
           '{fields}',
           (SELECT jsonb_agg(
                       CASE field ->> 'name'
                           WHEN 'bankName' THEN
                               jsonb_set(field, '{extractors}',
                                   (field -> 'extractors') || '[{
                                     "label": {"kind": "literal", "pattern": "Printed from"},
                                     "value": {"scope": "LINE_RIGHT",
                                               "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z][A-Za-z&''-]*)*?(?= (?:Personal|Business|Online))",
                                               "occurrence": 0},
                                     "method": "ANCHOR_LABEL",
                                     "strength": 0.8
                                   }]'::jsonb)
                           WHEN 'accountNumber' THEN
                               jsonb_set(field, '{extractors}',
                                   (field -> 'extractors') || '[{
                                     "label": {"kind": "regex",
                                               "pattern": "(?i)\\b(?:TOTAL )?(?:CHECKING|SAVINGS|MONEY MARKET)\\b"},
                                     "value": {"scope": "LINE_RIGHT",
                                               "pattern": "(?:\\.{3,}|\\*{3,}|[xX]{3,})\\d{4}(?!\\d)",
                                               "occurrence": 0},
                                     "method": "ANCHOR_LABEL",
                                     "strength": 0.7
                                   }]'::jsonb)
                           WHEN 'endingBalance' THEN
                               jsonb_set(field, '{extractors}',
                                   (field -> 'extractors') || '[{
                                     "label": {"kind": "literal", "pattern": "Present balance"},
                                     "value": {"scope": "LINE_RIGHT",
                                               "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                                               "occurrence": 0},
                                     "method": "ANCHOR_LABEL",
                                     "strength": 0.8
                                   }, {
                                     "label": {"kind": "literal", "pattern": "Current balance"},
                                     "value": {"scope": "LINE_RIGHT",
                                               "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                                               "occurrence": 0},
                                     "method": "ANCHOR_LABEL",
                                     "strength": 0.8
                                   }]'::jsonb)
                           ELSE field
                       END
                       ORDER BY ordinality)
              FROM jsonb_array_elements(definition -> 'fields')
                   WITH ORDINALITY AS elements(field, ordinality))
       )
  FROM extraction_schema
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.3.0';

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
