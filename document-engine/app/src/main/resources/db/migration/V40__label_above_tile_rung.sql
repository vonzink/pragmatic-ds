-- V40 — LABEL_ABOVE, the tile rung; bank_statement@1.5.0 reads the balance a print-out
-- states BENEATH its caption.
--
--   §1 extraction_method CHECK widening for LABEL_ABOVE (the V9/V12/V13 pattern)
--   §2 extraction_schema: bank_statement@1.5.0 derived from 1.4.0 (the V25/V39 idiom);
--      1.4.0 retired — ONE NO FORCE dance on extraction_schema
--
-- ── What the real document measured ─────────────────────────────────────────
-- V38 taught the classifier the online print-out's vocabulary and V39 appended the
-- three rungs the genre can carry. Run against the reviewer's actual five-page
-- checking print-out on 2026-09-02 (tools/corpus_score.py, the corpus never enters
-- git), the result was two-thirds of the fix:
--
--   page 0        BANK_STATEMENT 0.80  [deposit-account, withdrawals, online-print,
--                                       available-balance, present-balance]
--   pages 1-4     UNKNOWN — continuation pages, one five-page document (correct)
--   bankName      Chase       ANCHOR_LABEL   0.78
--   accountNumber ••••6327    ANCHOR_LABEL   0.65
--   endingBalance MISSING
--
-- The classification anchor for "Present balance" was LIT — the caption is on the
-- page — and the extraction rung anchored on the very same words captured nothing.
-- The page's own geometry (text_span, coordinates only) says why:
--
--   y 185.4-185.8   $#,###.##      +$#,###.##      -$##,###.##        ← the amounts
--   y 202.1         Present balance   <caption>       <caption>        ← the captions
--   x               45.4              177.6           307.8
--
-- The print-out states its balances as TILES: each amount in a larger face with its
-- caption directly beneath it, x-aligned, the amount's bottom edge 3.7 pt above the
-- caption's top. V39's rung reads LINE_RIGHT — along the caption row, to the right of
-- "Present balance" — and the only things there are the next tiles' captions, which
-- no money pattern matches. It looked in the one direction the layout does not use.
--
-- ── Why a new rung and not a schema tweak ────────────────────────────────────
-- Nothing in the ladder reads UPWARD. ANCHOR_LABEL scopes LINE, LINE_RIGHT or PAGE;
-- LABEL_BELOW reads the cell under a caption (IRS box grids, the Oracle paystub
-- header); lineOffset is positive only and, by the V14 rule, needs a COLUMN band. A
-- PAGE-scope regex could name the amount by its position in reading order — "the
-- money token before 'Present balance'" — but that is the layout re-encoded as text:
-- it breaks the moment a bank reorders its tiles, and it reports value-only evidence
-- for a field whose caption is right there on the page.
--
-- LABEL_ABOVE is LABEL_BELOW's vertical mirror and reuses its horizontal cell
-- verbatim — from the caption's left edge to the NEXT caption's left edge on the
-- caption row, a span admitted only when the majority of ITS OWN width lies inside.
-- On this page that window is x 45.4-177.6, and the next tile's "+$#,###.##" begins
-- 0.4 pt inside it and runs 65 pt outside: refused. Two rules flip: a span is
-- admitted when its BOTTOM edge lies at or above the caption's top by at most
-- maxRisePt (default 24.0, maxDropPt's mirror), and the LAST of the cell's lines —
-- the one nearest the caption — owns it, so an account caption printed over a tile
-- can never answer for the tile. No match on that line fails the rung: missing over
-- wrong, design D5. The rung is DefaultFieldExtractionEngine.labelAbove, its decoys
-- LabelAboveExtractionTest, drawn from this document's coordinates.
--
-- ── §2, the ladder ──────────────────────────────────────────────────────────
-- endingBalance gains two LABEL_ABOVE rungs, "Present balance" then "Current
-- balance", the same two captions V39 reads and at the same strength. APPENDED, so
-- V39's LINE_RIGHT rungs keep answering first for a print-out that writes the amount
-- beside its caption, and the tile reading is consulted only when that fails. A
-- mailed statement never reaches either: its "Ending Balance" caption answers from
-- the earlier rungs.
--
-- Deliberately NOT added: the two neighbouring tiles. Their captions, read from the
-- same page, are "Deposits this month" (+$) and "Withdrawals this month" (-$) — and
-- the fourth tile is a "Debit card coverage" toggle. THIS MONTH is the calendar month
-- to date at the moment of printing, a window the document states nowhere else,
-- while the activity listed beneath is whatever range the account holder had on
-- screen. The two do not coincide in general, so these are not the totals of the
-- ledger shown: bound to totalDeposits/totalWithdrawals they would sit under a
-- period-total caption on a document that states no period, and a reconciler that
-- partitions the rows against them would fail a correct ledger. That is the same
-- shape V21 retired a rung over — a printed, plausibly-named number that is not the
-- quantity the field means — and the fixture now draws both tiles precisely so the
-- schema is proven to leave them alone. The headline figure above the strip is the
-- "Available balance", which nothing reads (V39, unchanged).
--
-- RLS: extraction_schema has FORCE ROW LEVEL SECURITY since V7 and its INSERT policy
-- admits only org_id = current_org(), which a global (NULL) row can never satisfy.
-- FORCE is dropped for the duration of §2 and restored before commit; a forgotten
-- restore fails RlsCoverageIT. §1 touches extracted_field's CHECK only and needs no
-- dance.

-- ── §1 extraction_method CHECK widening for LABEL_ABOVE ──────────────────────
-- The V9/V12/V13/V23 pattern: DROP then ADD, repeating the FULL value list. The
-- constraint is extracted_field_method_check (V7 — NOT
-- extracted_field_extraction_method_check), last widened by V23 to include AI — the
-- AI extraction stage's own method, which every AI-written row carries. The list
-- below is V23's plus LABEL_ABOVE and nothing less: a widening that repeats an OLDER
-- list silently narrows the constraint, and the first AI row after it fails its
-- insert. The Java ExtractionMethod enum mirrors this list exactly.
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'AI', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE',
                                 'LABEL_BELOW', 'ROW_CELL', 'LABEL_ABOVE'));

-- ── §2 extraction_schema: bank_statement@1.5.0 ───────────────────────────────
-- DERIVED from 1.4.0 rather than retyped (the V25 rule): the aggregate rebuilds the
-- fields array in ORDER, touches one field by NAME and passes every other field
-- through byte-identical. 1.4.0 is retired, never deleted (the V10 rule).

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.4.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
SELECT NULL,
       'BANK_STATEMENT',
       '1.5.0',
       jsonb_set(
           definition,
           '{fields}',
           (SELECT jsonb_agg(
                       CASE field ->> 'name'
                           WHEN 'endingBalance' THEN
                               jsonb_set(field, '{extractors}',
                                   (field -> 'extractors') || '[{
                                     "label": {"kind": "literal", "pattern": "Present balance"},
                                     "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                                               "occurrence": 0},
                                     "method": "LABEL_ABOVE",
                                     "strength": 0.8
                                   }, {
                                     "label": {"kind": "literal", "pattern": "Current balance"},
                                     "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                                               "occurrence": 0},
                                     "method": "LABEL_ABOVE",
                                     "strength": 0.8
                                   }]'::jsonb)
                           ELSE field
                       END
                       ORDER BY ordinality)
              FROM jsonb_array_elements(definition -> 'fields')
                   WITH ORDINALITY AS elements(field, ordinality))
       )
  FROM extraction_schema
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.4.0';

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
