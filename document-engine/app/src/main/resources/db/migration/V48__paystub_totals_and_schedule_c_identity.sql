-- V48 — paystub@1.4.0 and schedule_c@1.1.0: a paystub's gross-pay pair read from the two
-- places real stubs actually print it, and a Schedule C's business name and tax year read
-- from where the filled official form actually puts them. paystub@1.3.0 and schedule_c@1.0.0
-- are RETIRED, never edited (the V10/V12 rule).
--
--   §1 extraction_schema: paystub@1.4.0 (1.3.0 retired) and schedule_c@1.1.0 (1.0.0 retired)
--      — ONE NO FORCE dance.
--
-- No extraction_method CHECK widening and no engine change: every rung below is ANCHOR_LABEL
-- or LABEL_BELOW with parameters the loader already parses (maxDropPt, cellOverlap).
--
-- ── WHAT THE REAL DOCUMENTS MEASURED (2026-09-14) ───────────────────────────
-- Four real documents, rules-only, scored with tools/corpus_score.py against the running
-- stack: two paystubs (a Denver payroll bureau's native-text stub; a national payroll
-- provider's "Statement of Earnings and Deductions") and two filled Schedule C returns (one
-- reporting a profit, one a loss). currentGrossPay and ytdGrossPay were MISSING on BOTH
-- stubs; businessName and taxYear were MISSING on BOTH Schedule Cs. The pages' own word
-- geometry says why. (Measured positions below; every printed VALUE is described by shape
-- only — no value from a corpus document may be written down here or anywhere else in this
-- repository.)
--
--   1. NEITHER STUB PRINTS A "GROSS" ROW INSIDE A DETECTED TABLE. paystub@1.3.0's first
--      rung is TABLE_CLUSTER `Gross` × `Current`/`YTD`, its fallbacks ANCHOR_LABEL `Gross
--      Pay` / `Gross`. The bureau stub prints no "Gross" word anywhere; the provider stub
--      prints none either. The worker (with #70's header-row absorption) emitted for the
--      bureau stub ONE unruled table — the three-row employee header block at y 80–109 — and
--      nothing for the earnings detail, which is two side-by-side blocks (earnings at x 25–334,
--      deductions at x 347–594) under a two-level header (`This Pay Period` | `Year To Date`
--      over `Description Rate Hrs Amount Amount`). For the provider stub it emitted the
--      earnings detail as a 6-column, 5-row table WITHOUT its caption row (`DESCRIPTION HOURS
--      RATE EARNINGS YEAR-TO-DATE` sits 11.7 pt above a grid whose row pitch is 9.0, outside
--      the absorption rule's pitch test) and the totals row 120 pt below it as ordinary
--      lines. So #70 did not make either total addressable through TABLE_CLUSTER, and the
--      fix is where the totals really print:
--
--      a. THE BUREAU STUB'S TOTALS STRIP. At y 501 `This Pay Period` (x 145) and `Year To
--         Date` (x 443); at y 516 the caption row `Earnings Deductions Net Pay Earnings
--         Deductions Net Pay` (x 149, 220, 308, 403, 462, 562); at y 528 six amounts under
--         them. V36/V42 left this pair "deliberately unmapped" because the word `Earnings`
--         repeats (the detail block's section header at y 122 prints `Earnings` too). The
--         caption ROW, read whole, does not repeat: the section header line reads only
--         `Earnings Deductions`. So currentGrossPay reads LABEL_BELOW off the regex
--         `Earnings Deductions Net ?Pay` (the first strip block — `Net ?Pay` because the
--         same bureau's SCANNED copy fuses it to `NetPay`, paystub_bureau's dialect), and
--         ytdGrossPay off `(?<=Net ?Pay )Earnings Deductions Net ?Pay` — a whole block that
--         FOLLOWS a `Net Pay`, i.e. the second — so a stub printing a single current-only
--         strip can never hand its current earnings to the YTD field. The block must carry
--         its OWN `Net Pay`: page text is SpanJoin-folded in row order with one space between
--         rows and every label rung binds its FIRST page match, so a lookbehind that asked
--         only what PRECEDES `Earnings Deductions` would also bind a detail block's section
--         header sitting directly below any heading row that ends `... Net Pay` — the cell's
--         first line is then the first detail row and occurrence 0 is a line item, confident
--         and wrong (ShippedPaystubTotalsRungsTest pins that page). The cell window (label's
--         left edge to the next caption on its row) confines each block to its own three
--         amounts: the first block's window ends at the second `Earnings` (x 403), the
--         second block's is its own caption extent (x 403–588, nothing follows it on the
--         row), and occurrence 0 of the money pattern in each is the block's own first
--         amount — current earnings, then YTD earnings.
--
--      b. THE PROVIDER STUB'S TOTALS ROW. At y 282 `TOTAL EARNINGS` (x 23–85), a `>>>` glyph
--         where an hours figure would sit, then the current total (x 219) and the YTD total
--         (x 276), then the deductions block's own total row to the right (x 331+). Both are
--         ANCHOR_LABEL `Total Earnings` LINE_RIGHT, occurrence 0 and occurrence 1 — the same
--         shape as the existing `Gross Pay` occurrence 0/1 rungs, ordered AFTER them so every
--         layout that already extracted keeps winning on its own rung. Known and out of scope:
--         a provider that prints total HOURS as a figure on that line (where this one prints
--         `>>>`) would shift occurrence 0/1 by one — the same exposure the `Gross Pay` rungs
--         have carried since V7; a stub like that needs its own measured rung, not a guess.
--
--   2. THE PROVIDER STUB'S FEDERAL TAX IS CAPTIONED `FIT WH`. The deductions column captions
--      its rows `CO WH`, `FAMLI EE`, `SS EE`, `Medicare`, `FIT WH`, ... — `FIT` (federal
--      income tax) at x 331, the current and YTD amounts at x 464 and 535. paystub@1.3.0's
--      regex admits only `Federal` / `Fed Tax`. A third rung anchors the regex
--      `(?<![A-Za-z])FIT(?![A-Za-z])(?![-/])(?! ?(?:TAXABLE|WAGES|EXEMPT|STATUS))` with
--      LINE_RIGHT occurrence 0 — case-sensitive by design (`FIT` is the payroll
--      abbreviation; a prose `fit` is not), and fenced because a word-bounded `FIT` alone is
--      also the first word of `FIT TAXABLE WAGES`, `FIT EXEMPT` and `FIT STATUS`, and the
--      stem of `FIT-S` / `FIT/SS` header codes: a provider that prints its taxable-wages
--      block ABOVE the deductions would otherwise hand its wages to the withholding field at
--      0.75 on the rung's first page match. With the fence the rung walks past those to the
--      `FIT WH` row, and a stub that prints only `FIT TAXABLE WAGES` leaves the field MISSING
--      (ShippedPaystubTotalsRungsTest pins both).
--
--      Left as DOCUMENTED GAPS on the provider stub, on purpose: employerName (the company
--      block carries no caption; the 0.4-strength Title-case REGEX rung binds an unrelated
--      "<Word> Inc" fragment elsewhere on the page — that rung's known weakness, and 0.4 is
--      below the type's 0.6 floor so it lands in review), borrowerName (no caption — only
--      `EMPLOYEE ID:` followed by a number), payDate (the only caption is a bare `DATE:` on
--      a line that starts with the company block; a bare `DATE:` also captions the check
--      stub, and no regex can tell either from `Period End Date:` on another layout), and
--      payFrequency (not printed at all). Absent beats confidently wrong.
--
--   3. THE SCHEDULE C BUSINESS NAME IS TYPED LEFT OF ITS CAPTION, IN TITLE CASE. Section C
--      of the filled official form prints the box LETTER `C` at x 36 and the caption
--      `Business name. If no separate business name, leave blank.` from x 64 to 276, with
--      `D Employer ID number (EIN)` at x 450 on the same row (y 134); the filed value sits
--      one row down (y 147) starting at x 38 — under the LETTER, 26 pt left of the caption
--      text — in Title Case ("Aaaaaaaaa Aaaaaaaaaa"). schedule_c@1.0.0 anchored the caption
--      text alone, so the cell window began at x 64 and the value's first word (x 38–74)
--      owned only 28% of it and was REFUSED; and its value pattern demanded ALL-CAPS words,
--      so even the admitted remainder could not match. Two rungs replace it: the caption
--      anchored WITH its box letter (`C Business name. If no ...`), which is what V47 did for
--      the W-2's box e, so the window starts at the letter; then the letter-less caption as a
--      fallback for a page whose letter did not survive. Both use a value pattern that
--      admits Title Case as well as capitals.
--
--      THE BLANK-CELL GUARD. The caption says "leave blank", and a filer with no separate
--      business name does. The next caption row (`E Business address ...`) prints 17 pt below
--      this caption's bottom edge, and on one of the two measured returns the STREET ADDRESS
--      is typed on that caption's own line — Title-Case words that a name pattern would
--      accept. So both rungs set maxDropPt 12.0 (the value's top sits 4.6 pt below the
--      caption's bottom on both returns and 4 pt on the fixture; the E row at 17 pt is
--      outside), and an empty box leaves the field MISSING rather than reading the next
--      caption's text. fixtures/schedule_c_blank_business.pdf pins exactly that.
--
--   4. THE SCHEDULE C MASTHEAD YEAR IS TWO SPANS UNDER THE OMB NUMBER. The title row (y 37)
--      reads `SCHEDULE C  Profit or Loss From Business  OMB No. 1545-0074`; the year is NOT
--      on it. It prints in the top-right box BELOW the OMB number as two 20 pt-tall spans —
--      `20` at x 506.8, 25.3 pt wide, so its right edge is x 532.1; `25` with its left edge
--      at x 532.1, 26.7 pt wide — the same on both measured returns (the IRS sets the
--      century and the year in different faces). The gap between them is 0.00 pt against
--      SpanJoin's join cutoff of 0.10 em = 2.00 pt at this height, so the two spans fold into
--      ONE token with nothing between them. (A 20 pt Helvetica `20` is only 22.2 pt wide —
--      a fixture drawn at the real x positions with that face would open a 2.8 pt gap and
--      read `20 25`; the fixture therefore draws the two runs at the MEASURED gap, 0, and
--      the margin the real page depends on is the 2.00 pt cutoff.) They sit on their own
--      visual line. schedule_c@1.0.0's only rung read LINE_RIGHT of the title, where there
--      is no year. Two rungs are appended: LABEL_BELOW off the literal `OMB No. 1545-0074`
--      (the full number, so the cell window spans the whole box — with `OMB No.` alone the
--      window would end at the number's own left edge and refuse the second span), whose
--      owning line is that one token, `2025`; then ANCHOR_LABEL LINE_RIGHT off the footer
--      `Schedule C (Form 1040)`, which every page of the form prints followed by the year as
--      ONE token — the fallback for a masthead whose spans do not abut. The fixture's own
--      title-row year keeps reading through the unchanged first rung.
--
-- ── WHAT WAS CHECKED SO THE SYNTHETIC FIXTURES DO NOT REGRESS ────────────────
-- paystub_complete (+ the three rotations), paystub_missing_field, paystub_twopage and
-- paystub_stacked_period still bind their gross pair on the unchanged first rungs (TABLE_CLUSTER
-- / `Gross Pay`); paystub_oracle on `Gross Pay`; native_paystub / degraded_paystub as before.
-- paystub_bureau and paystub_bureau_native now PIN the strip pair (their truths gain two
-- expectations each — the fields V36/V42 left "honestly absent" are read now, and an unpinned
-- capture would score as a phantom); paystub_bureau_native's YTD block now prints its own
-- `Net Pay` and net amount, as the scanned page and the real stub both do. The new paystub_adp fixture pins the `Total Earnings`
-- pair and `FIT`, and pins employerName / borrowerName / payDate / payFrequency as MISSING.
-- schedule_c still reads businessName on the letter-anchored rung (its value starts under the
-- letter too) and taxYear on the unchanged title rung; schedule_c_filled reads the Title-Case
-- name and the masthead year; schedule_c_blank_business proves the blank cell stays MISSING.
--
-- Versions are NEVER edited in place: extracted_field.schema_id points at the row that
-- produced each stored value, so 1.3.0 / 1.0.0 are retired, not deleted. ExtractionSchemaLoader
-- takes the highest version within the surviving scope, so 1.4.0 / 1.1.0 supersede on load.
--
-- RLS: extraction_schema has been FORCEd since V7 and its only INSERT policy is
-- WITH CHECK (org_id = current_org()); for a global (org_id NULL) row `NULL = <anything>`
-- is never TRUE, so no GUC value can admit it while FORCE binds the migration owner too.
-- The restore happens in the SAME transaction, and RlsCoverageIT's pg_class sweep fails
-- the build if it is ever forgotten.

-- ── §1 extraction_schema: paystub@1.4.0, schedule_c@1.1.0 ────────────────────

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.3.0';

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'SCHEDULE_C' AND version = '1.0.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
VALUES (NULL, 'PAYSTUB', '1.4.0', '{
  "fields": [
    {
      "name": "borrowerName",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Employee:"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Employee Name"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Name ?and ?Address"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![A-Za-z])[A-Z][A-Z.''-]+(?: [A-Z][A-Z.''-]*){1,3}(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.8,
          "maxDropPt": 18.0,
          "cellOverlap": 0.5
        }
      ],
      "normalizer": "personName"
    },
    {
      "name": "employerName",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Employer:"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,3} (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "REGEX",
          "strength": 0.6
        },
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![A-Za-z])[A-Z][a-z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,3} (?:LLC|Inc\\.?|Corp\\.?|Co\\.|Ltd\\.?|Company)(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "REGEX",
          "strength": 0.4
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Employer Name"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?:(?: of| and| the)?(?: [A-Z&][A-Za-z&''-]*)){0,5}(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        }
      ],
      "normalizer": null
    },
    {
      "name": "payPeriodStart",
      "dataType": "DATE",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Period Start Date"
          },
          "value": {
            "scope": "LINE",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{2}(?=\\s?-)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Period ?Beginning:?"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.85
        }
      ],
      "normalizer": "date"
    },
    {
      "name": "payPeriodEnd",
      "dataType": "DATE",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 1
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Period End Date"
          },
          "value": {
            "scope": "LINE",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<=-\\s?)\\d{2}/\\d{2}/\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Period ?Ending:?"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.85
        }
      ],
      "normalizer": "date"
    },
    {
      "name": "payDate",
      "dataType": "DATE",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Date"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Payment Date"
          },
          "value": {
            "scope": "LINE",
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Deposit ?Date:?"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        }
      ],
      "normalizer": "date"
    },
    {
      "name": "payFrequency",
      "dataType": "ENUM",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Pay Frequency"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?i)(?<![A-Za-z])(?<![A-Za-z][- ])(?:bi[- ]?weekly|semi[- ]?monthly|weekly|monthly)(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Period Type"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?i)(?<![A-Za-z])(?<![A-Za-z][- ])(?:bi[- ]?weekly|semi[- ]?monthly|weekly|monthly)(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "PayFrequency:"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?:Weekly|Biweekly|Bi-Weekly|Semimonthly|Semi-Monthly|Monthly)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        }
      ],
      "normalizer": "payFrequency"
    },
    {
      "name": "currentGrossPay",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "table": {
            "rowLabel": {
              "kind": "literal",
              "pattern": "Gross"
            },
            "columnHeader": {
              "kind": "literal",
              "pattern": "Current"
            }
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "TABLE_CLUSTER",
          "strength": 1.0
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Gross Pay"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Gross"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.7
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Total Earnings"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Earnings Deductions Net ?Pay"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.8,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "ytdGrossPay",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "table": {
            "rowLabel": {
              "kind": "literal",
              "pattern": "Gross"
            },
            "columnHeader": {
              "kind": "literal",
              "pattern": "YTD"
            }
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "TABLE_CLUSTER",
          "strength": 1.0
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Gross Pay"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 1
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Gross"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 1
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.7
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Total Earnings"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 1
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "(?<=Net ?Pay )Earnings Deductions Net ?Pay"
          },
          "value": {
            "scope": "LINE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.8,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "netPay",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Net Pay"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "Total ?Current ?Net:?"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "federalWithholding",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Federal Withholding"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "(?<![A-Za-z])Fed(?:eral| ?Tax)(?![A-Za-z])"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.8
        },
        {
          "label": {
            "kind": "regex",
            "pattern": "(?<![A-Za-z])FIT(?![A-Za-z])(?![-/])(?! ?(?:TAXABLE|WAGES|EXEMPT|STATUS))"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.75
        }
      ],
      "normalizer": "money"
    }
  ]
}'::jsonb);

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_C', '1.1.0', '{
  "fields": [
    {"name": "proprietorName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name of proprietor"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "proprietorSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social security number (SSN)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "occurrence": 0}}]},
    {"name": "businessName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "C Business name. If no separate business name, leave blank."},
        "maxDropPt": 12.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''.-]*(?:(?: of| and| the|,)?(?: [A-Z&][A-Za-z&''.,-]*)){1,5}(?![A-Za-z])",
                  "occurrence": 0}},
       {"method": "LABEL_BELOW", "strength": 0.8,
        "label": {"kind": "literal", "pattern": "Business name. If no separate business name, leave blank."},
        "maxDropPt": 12.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''.-]*(?:(?: of| and| the|,)?(?: [A-Z&][A-Za-z&''.,-]*)){1,5}(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "businessEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer ID number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}}]},
    {"name": "grossReceipts", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross receipts or sales"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "grossProfit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross profit"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "grossIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Gross income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalExpenses", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total expenses"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "netProfit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Net profit"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Profit or Loss From Business"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "LABEL_BELOW", "strength": 0.85,
        "label": {"kind": "literal", "pattern": "OMB No. 1545-0074"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.8,
        "label": {"kind": "literal", "pattern": "Schedule C (Form 1040)"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}');

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
