-- V45 — tax_return@1.2.0: the extraction schema read against a REAL Form 1040 instead of
-- the fixture we drew from the same belief as the schema. tax_return@1.1.0 is RETIRED.
--
--   §1 extraction_schema: tax_return@1.2.0, 1.1.0 retired — ONE NO FORCE dance.
--
-- No extraction_method CHECK widening: every rung below is ANCHOR_LABEL, LABEL_BELOW or
-- CHECKBOX_STATE, all of which V12 already admits.
--
-- ── WHAT THE REAL DOCUMENT MEASURED ─────────────────────────────────────────
-- On 2026-09-10 a real 18-page 2025 federal filing (native text layer, 6,182 chars on
-- page 1 — nothing wrong with the parse) extracted 3 of its 10 TAX_RETURN fields. Only
-- filingStatus, primarySsn and taxYear answered. The page's own geometry says why, and
-- it is the W-2 lesson of V12 a second time: the schema was authored against wording and
-- a value shape the modern 1040 does not use.
--
--   1. THE MONEY LINES DO NOT PRINT CENTS. Every amount on the measured return is set in
--      a right-aligned column ending at x 574.5 as WHOLE DOLLARS WITH A TRAILING PERIOD
--      and an empty cents box beside it — the printed token is `12,345.`, seven glyphs,
--      one per 6 pt. Every money rung's value pattern required `\.\d{2}`, so totalIncome,
--      adjustedGrossIncome and totalTax found their LABELS and then matched nothing at
--      all. Their labels were never the problem; the value shape was.
--
--      The trailing period is the cents SEPARATOR, not part of the amount, and
--      `Normalizers.money` refuses `12,345.` (MONEY_LENIENT must match the whole cleaned
--      string). So the pattern below captures the digits and looks AHEAD at the period —
--      `12,345` normalizes lenient (certainty 0.9, which is honest: no cents were
--      printed) while `104,982.00` still matches whole and strict exactly as before.
--
--      What the pattern deliberately CANNOT match is a bare integer. The 1040 repeats its
--      line number immediately left of the amount column (`11a` at x 474, the amount at
--      x 532), and the whole line is filled with dot leaders. A rung that admitted bare
--      digits would read the LINE NUMBER as the amount — a confident wrong value with a
--      real evidence box, which is strictly worse than the missing field it replaced
--      (design D5). Either cents or a trailing period, or no match.
--
--      The one remaining shape that could pass that test is the form's own CROSS
--      REFERENCE — `...from line 34.` ends a sentence with digits and a period — so the
--      pattern also refuses a number directly preceded by `line `/`Line `. On the real
--      return every such reference lies to the LEFT of the anchoring sentence and is out
--      of LINE_RIGHT's reach anyway; the guard is what keeps that true for the
--      short-caption rungs, which anchor mid-sentence and can see prose to their right.
--
--   2. `Your name:` / `Spouse''s name:` ARE NOT PRINTED ANYWHERE. Zero occurrences on the
--      real return. The form captions the identity block as a BOX GRID, the shape V12
--      built LABEL_BELOW for:
--
--        y 99.4    Your first name and middle initial | Last name | Your social security number
--        y 110.2   MORGAN T FIXTURE                                 ###-##-####
--                  ^x 38.5   ^x 80.5  ^x 92.5
--
--      (measured columns; the values above are the invented ones this repository uses —
--      no value from the measured return may ever be written down here or anywhere else
--      in this repository).
--      The taxpayer's WHOLE name is left-packed inside the first-name cell — the surname
--      does NOT sit under the `Last name` caption — so the cell running from the caption's
--      left edge to the next caption's left edge (x 38.3 → x 273.8) holds all three spans
--      and LABEL_BELOW reads the complete name.
--
--      That is the whole reason this rung is admissible at all.
--      `TaxReturnExtractionIT#the_taxpayer_name_stays_missing_on_a_box_grid_rather_than_
--      becoming_a_confident_partial` pins that a first-name-only capture must NEVER be
--      persisted, and sets the burden explicitly: show the composed value is COMPLETE.
--      The burden is met by the value pattern's SHAPE, not by loosening the rung. It
--      requires two name words — `FIRST [M.] LAST` — so a cell holding only `Jordan Q.`
--      matches nothing and the field stays missing exactly as that IT demands, while
--      `MORGAN T FIXTURE` matches whole. The IT is unchanged and still passes.
--
--      The name patterns also accept ALL CAPS: real returns are printed by preparer
--      software in caps, and 1.1.0's `[A-Z][a-z]+` could not have read one.
--
--   3. maxDropPt 12.0 ON THE NAME RUNGS, not the 24.0 default. Measured: the value row's
--      top sits 4.8 pt below its caption's bottom edge. The row 24.0 would ALSO admit is
--      the next printed caption row — for spouseName, on a return with no spouse, that is
--      `Home address (number and street). If you have a P.O. box, see instructions.` at a
--      drop of 14.3 pt. 12.0 keeps the true value row and excludes it. (The pattern
--      refuses that text too — no two consecutive capitalised words — so the two guards
--      are independent, which is the point.)
--
--   4. `Taxable income` AND `Refund amount` ARE NOT PRINTED EITHER. The form says
--      `...This is your taxable income.` and `Amount of line 34 you want refunded to you.`
--      Worse, the measured preparer PDF emits some of those sentences with NO SPACE
--      GLYPHS AT ALL — line 16 arrives as the single span
--      `Subtractln12fromln11b.Ifzeroorless,enter-0-.Thisisyourtaxableincome.` — so each
--      money field carries the spaced sentence AND its glued spelling as separate rungs.
--      SpanJoin only inserts a separator where the page printed a gap, so a glued span
--      stays glued and no spaced literal can ever match it.
--
-- ── LADDER ORDER ────────────────────────────────────────────────────────────
-- Every money field now has THREE rungs instead of one, strongest first: the full printed
-- sentence (0.95), its glued spelling (0.95), then the short caption (0.9). The full
-- sentence is the strongest anchor available because it ENDS the label text — everything
-- numeric in the line's prose ("Subtract line 10 from line 9.") lies to its LEFT, so
-- LINE_RIGHT after it can only see leaders, the repeated line number and the amount
-- column. The short caption is kept last because it is what the flat fixture prints and
-- what a transcript or a differently-worded year prints.
--
-- The LINE NUMBER was considered as the primary anchor and rejected on the evidence: this
-- 2025 form numbers AGI as line 11a and total income as line 9, while a 2023 1040 numbers
-- AGI as line 11 — the numbers are renumbered between tax years exactly as the prose is
-- reworded, and a rung pinned to `11` would read a DIFFERENT line's amount on a different
-- year rather than missing. The amount COLUMN is the durable half of the geometry, and it
-- is what the value pattern is shaped to, on the label's own printed row.
--
-- Versions are NEVER edited in place (the V10/V12 rule): extracted_field.schema_id points
-- at the row that produced each stored value, so 1.1.0 is retired, not deleted, and stays
-- resolvable for every field already extracted under it. ExtractionSchemaLoader takes the
-- highest version within the surviving scope, so 1.2.0 supersedes on load.
--
-- RLS: extraction_schema has been FORCEd since V7 and its only INSERT policy is
-- WITH CHECK (org_id = current_org()); for a global (org_id NULL) row `NULL = <anything>`
-- is never TRUE, so no GUC value can admit it while FORCE binds the migration owner too.
-- The restore happens in the SAME transaction, so no window exists for any other session,
-- and RlsCoverageIT's pg_class sweep fails the build if it is ever forgotten.

-- ── §1 extraction_schema: tax_return@1.2.0 ──────────────────────────────────

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.1.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'TAX_RETURN', '1.2.0', '{
  "fields": [
    {"name": "primaryTaxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your first name and middle initial"},
        "maxDropPt": 12.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "spouseName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "spouse''s first name and middle initial"},
        "maxDropPt": 12.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Spouse''s name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "primarySsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0}},
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
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "This is your total income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "Thisisyourtotalincome"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "adjustedGrossIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "This is your adjusted gross income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "Thisisyouradjustedgrossincome"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Adjusted gross income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxableIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "This is your taxable income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "Thisisyourtaxableincome"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Taxable income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalTax", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "This is your total tax"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "Thisisyourtotaltax"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "total tax"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "refundAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "you want refunded to you"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.95,
        "label": {"kind": "literal", "pattern": "youwantrefundedtoyou"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Refund amount"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
