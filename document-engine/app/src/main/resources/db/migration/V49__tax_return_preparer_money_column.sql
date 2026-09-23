-- V49 — tax_return@1.4.0: the money column the way PREPARER SOFTWARE prints it. tax_return@1.3.0
-- is RETIRED, never edited (the V10/V12 rule).
--
--   §1 extraction_schema: tax_return@1.4.0, 1.3.0 retired — ONE NO FORCE dance.
--
-- No extraction_method CHECK widening and no engine change: every rung below is the 1.3.0 rung
-- with ONE regex replaced — the MONEY value pattern shared by all fifteen money rungs
-- (ShippedTaxReturnMoneyPatternTest pins that there is exactly one). Labels, methods, strengths,
-- scopes, occurrences, the name and identity rungs and the CHECKBOX_STATE options are byte-for-byte
-- 1.3.0's.
--
-- ── WHAT THE REAL DOCUMENTS MEASURED (2026-09-15) ───────────────────────────
-- Five real federal filings (multi-page preparer packages, the 1040 one logical document inside
-- each) plus the filled official 2024 blank, rules-only, scored with tools/corpus_score.py. On
-- EVERY preparer-printed filing the page-1 fields read (names, SSN, filing status, year, refund)
-- and all four money lines — totalIncome, adjustedGrossIncome, taxableIncome, totalTax — were
-- MISSING; on the filled blank all four matched. The pages' own word geometry says why. (Measured
-- positions below; every printed VALUE is described by shape only — no value from a corpus
-- document may be written down here or anywhere else in this repository.)
--
--   1. PREPARER SOFTWARE PRINTS BARE WHOLE DOLLARS. Three filings from one preparer package
--      (two at 612×792, one the same print scaled to 648×826) set every line amount in a
--      right-aligned column ending at x ≈ 583 as COMMA-GROUPED WHOLE DOLLARS WITH NOTHING
--      AFTER THEM — the printed token is `###,###` or `##,###`: no cents, and no trailing
--      period either. 1.2.0's pattern (kept by 1.3.0) was shaped to the V45 return, which
--      prints `12,345.` with an empty cents box, and it REQUIRES either `\.\d{2}` or a period
--      directly after the digits. So every money rung found its label — the full sentence
--      `This is your total income` / `...adjusted gross income` / `...taxable income` /
--      `...total tax` is printed verbatim, in that order, on pages 1 and 2 — and then matched
--      nothing at all. The same one-line cause on all four fields on all three filings.
--
--      The rest of the line's geometry is what the fixture reproduces and what the pattern
--      must still refuse: the caption at 8 pt (x ≈ 90–330), dot leaders drawn as SEPARATE
--      2 pt `.` spans at a 3.5 pt pitch out to x ≈ 470, the LINE NUMBER repeated as its own
--      span at x ≈ 477 (`15`, `24`, `11a`), and the amount in a 12 pt face whose box top sits
--      2.6–3.9 pt ABOVE the caption's. VisualLines groups by centre with a half-min-height
--      tolerance, and the taller amount's centre lands 1–2 pt from the caption's, so the amount
--      IS on the label's line and LINE_RIGHT does see it — nothing in the engine had to move.
--
--   2. AN OCR'D SCAN LOSES THE THOUSANDS SEPARATOR AND KEEPS THE PERIOD. The fourth filing is
--      a scanner's own text layer: the leaders arrive as one `~~~~~~~` span, the line number
--      separately (`11`), and the amount as `######.` — digits, no comma, the cents period
--      kept. 1.2.0's bare-digits-then-period branch already reads that shape, and 1.4.0 keeps
--      it (with the tightening in 3 below).
--
--   3. THE SAME PREPARER PRINTS LINE NUMBERS WITH A PERIOD ON ITS WORKSHEETS. The package's
--      adjustment worksheets print `Enter Total Tax from Form 1040, 1040-SR, or 1040-NR, line
--      24.` with the line number repeated as `24.` at the amount column's left. 1.2.0's
--      `\d+(?=\.(?!\d))` branch reads that `24.` as $24 the moment a worksheet page shares the
--      1040's document — a confident wrong value with a real evidence box (design D5). Today the
--      splitter keeps those worksheets out of the TAX_RETURN document, which is the only reason
--      it has not happened; the pattern should not depend on that.
--
-- ── THE NEW MONEY PATTERN ───────────────────────────────────────────────────
-- Old (1.2.0/1.3.0):
--   (?<![\d,])(?<![Ll]ine )\$?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{2}(?!\d)|(?=\.(?!\d)))
-- New (1.4.0):
--   (?<![\d,(\-])(?<![Ll]ine )\$?(?:\d{1,3}(?:,\d{3})+(?:\.\d{2})?(?![\d,\-]|\.\d)
--                                |\d{3,}(?:\.\d{2}(?!\d)|(?=\.(?!\d)))
--                                |\d{1,2}\.\d{2}(?!\d)
--                                |0(?=\.(?!\d))
--                                |(?<=(?:^| )\d{1,2}[a-z]? \$?)\d{1,2}(?=\.(?!\d)))
--
--   a. COMMA-GROUPED digits are an amount on their own. A thousands separator is something a
--      line number, a form number (`8888`, `8814`, `1040`), a year or a cross reference never
--      carries, so `72,430` needs neither cents nor a period to be admitted. Optional cents,
--      then a guard that the group is neither the head of a longer token nor a truncated one:
--      `12,345.` still reads `12,345` (the period is the cents separator, left out for
--      Normalizers.money exactly as V45 explains), `123,456.78` still reads whole and strict,
--      and `12,345.6` reads nothing rather than a value with its cents cut off. The same
--      branch refuses a SIGNED or BRACKETED token — `-1,234`, `(1,234)` — because it cannot
--      carry the sign into the capture and would read a loss as a positive amount, and a
--      suffixed one (`1,234-A`); each is pinned.
--   b. Digits WITHOUT a separator need three or more of them AND either printed cents or the
--      trailing period. The `\d{3,}` floor is the tightening from finding 3: a 1040 has no
--      three-digit line number, so a worksheet's `24.` can no longer read as an amount, while
--      `615.` and the scan's `######.` still do. A bare `850` — three digits, no period, no
--      cents — stays UNREADABLE on purpose: nothing distinguishes it from a form reference, and
--      a missing sub-thousand amount is the honest outcome.
--   c. A sub-hundred amount with printed cents (`47.50`) reads as 1.2.0 read it.
--   d. A sub-hundred amount WITHOUT cents — the official form's `0.` / `85.` with its empty
--      cents box — is the shape 1.2.0's `\d+(?=\.)` admitted and the `\d{3,}` floor alone
--      would have LOST: a `0.` total tax on a refund return is the common case, and a small
--      tax or refund prints as `47.` on the V45 geometry. It is restored by TWO branches the
--      worksheet hazard cannot satisfy: `0.` reads anywhere (no 1040 line, and no worksheet
--      line, is numbered 0), and `1.`–`99.` reads ONLY when the token directly before it is a
--      bare repeated line number — `24 85.`, `35a 47.`, `16 5.` — which is where the official
--      form prints every amount. A worksheet's `line 24. 24.` fails the lookbehind (the
--      preceding token ends in a period), and its `1.` at the head of the scope, left after
--      the `...adjusted gross income.` caption is excluded, has no line number before it at
--      all. Both refusals are pinned beside the captures.
--
--   Every refusal 1.2.0 made is kept and pinned: the bare line number (`9`, `11a`, `35a`), the
--   `line 34.` cross reference, a line of dot leaders, `Form 8888`, `1 8814 2 4972 3`, the
--   `1040, 1040-SR, or 1040-NR` reference and the footer year all capture nothing. What 1.4.0
--   deliberately STOPS reading, relative to 1.2.0: a sub-hundred `N.` with no repeated line
--   number beside it (a worksheet line number), and a signed, bracketed, truncated or
--   suffixed comma-grouped token — none of them an amount on the 1040's own lines.
--
-- The LINE NUMBER as an anchor was considered again and rejected again, for V45's reason: the
-- amount column is the durable half of the geometry, and the full-sentence caption is what
-- every one of these returns prints. Reading the amount as the LAST money token on the line
-- (rather than occurrence 0) was considered and rejected: on every measured line the amount is
-- the only token the pattern admits, so occurrence 0 is already the amount, and the change
-- would move the rung's contract for no measured gain.
--
-- What was checked: ShippedTaxReturnMoneyPatternTest (every scope above, captures and
-- refusals, through the engine's own TextFold seam) · TaxReturnExtractionIT on the V45
-- real-geometry page (unchanged, still 10/10), the V47 box-grid fixture (unchanged) and the
-- two new generated fixtures — tax_return_preparer (the bare whole-dollar column, raised,
-- dot-span leaders, page 1 + page 2) and tax_return_scan (tilde leaders, separator-less
-- amounts with the period) — each asserted on method, value, normalization and evidence
-- boxes · tools/corpus_score.py against the five real filings and the filled blank on a
-- rebuilt local stack: the four money fields captured on the preparer-printed filings that
-- print them, the filled blank's eight matches unchanged.
--
-- Versions are NEVER edited in place: extracted_field.schema_id points at the row that
-- produced each stored value, so 1.3.0 is retired, not deleted. ExtractionSchemaLoader
-- takes the highest version within the surviving scope, so 1.4.0 supersedes on load.
--
-- RLS: extraction_schema has been FORCEd since V7 and its only INSERT policy is
-- WITH CHECK (org_id = current_org()); for a global (org_id NULL) row `NULL = <anything>`
-- is never TRUE, so no GUC value can admit it while FORCE binds the migration owner too.
-- The restore happens in the SAME transaction, and RlsCoverageIT's pg_class sweep fails
-- the build if it is ever forgotten.

-- ── §1 extraction_schema: tax_return@1.4.0 ───────────────────────────────────

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.3.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'TAX_RETURN', '1.4.0', '{
  "fields": [
    {
      "name": "primaryTaxpayerName",
      "dataType": "STRING",
      "required": true,
      "normalizer": "personName",
      "sensitive": false,
      "extractors": [
        {
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Your first name and middle initial"
          },
          "maxDropPt": 12.0,
          "cellOverlap": 0.5,
          "joinCells": [
            {
              "kind": "literal",
              "pattern": "Last name"
            }
          ],
          "value": {
            "pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
            "occurrence": 0
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Your name:"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "spouseName",
      "dataType": "STRING",
      "required": true,
      "normalizer": "personName",
      "sensitive": false,
      "extractors": [
        {
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "If joint return, spouse''s first name and middle initial"
          },
          "maxDropPt": 12.0,
          "cellOverlap": 0.5,
          "joinCells": [
            {
              "kind": "literal",
              "pattern": "Last name"
            }
          ],
          "value": {
            "pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
            "occurrence": 0
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "spouse''s first name and middle initial"
          },
          "maxDropPt": 12.0,
          "cellOverlap": 0.5,
          "joinCells": [
            {
              "kind": "literal",
              "pattern": "Last name"
            }
          ],
          "value": {
            "pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
            "occurrence": 0
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Spouse''s name:"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "primarySsn",
      "dataType": "STRING",
      "required": true,
      "normalizer": null,
      "sensitive": true,
      "extractors": [
        {
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Your social security number"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
            "occurrence": 0
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Your social security number"
          },
          "value": {
            "pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "taxYear",
      "dataType": "STRING",
      "required": true,
      "normalizer": null,
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "U.S. Individual Income Tax Return"
          },
          "value": {
            "pattern": "(?<!\\d)20\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "filingStatus",
      "dataType": "ENUM",
      "required": true,
      "normalizer": null,
      "sensitive": false,
      "extractors": [
        {
          "method": "CHECKBOX_STATE",
          "strength": 0.9,
          "proximityPt": 18.0,
          "options": [
            {
              "label": {
                "kind": "literal",
                "pattern": "Single"
              },
              "value": "SINGLE"
            },
            {
              "label": {
                "kind": "literal",
                "pattern": "Married filing jointly"
              },
              "value": "MARRIED_FILING_JOINTLY"
            },
            {
              "label": {
                "kind": "literal",
                "pattern": "Married filing separately"
              },
              "value": "MARRIED_FILING_SEPARATELY"
            },
            {
              "label": {
                "kind": "literal",
                "pattern": "Head of household"
              },
              "value": "HEAD_OF_HOUSEHOLD"
            },
            {
              "label": {
                "kind": "literal",
                "pattern": "Qualifying surviving spouse"
              },
              "value": "QUALIFYING_SURVIVING_SPOUSE"
            }
          ]
        }
      ]
    },
    {
      "name": "totalIncome",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "This is your total income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "Thisisyourtotalincome"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Total income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "adjustedGrossIncome",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "This is your adjusted gross income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "Thisisyouradjustedgrossincome"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Adjusted gross income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "taxableIncome",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "This is your taxable income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "Thisisyourtaxableincome"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Taxable income"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "totalTax",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "This is your total tax"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "Thisisyourtotaltax"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "total tax"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "refundAmount",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "you want refunded to you"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.95,
          "label": {
            "kind": "literal",
            "pattern": "youwantrefundedtoyou"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Refund amount"
          },
          "value": {
            "pattern": "(?<![\\d,(\\-])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?![\\d,\\-]|\\.\\d)|\\d{3,}(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))|\\d{1,2}\\.\\d{2}(?!\\d)|0(?=\\.(?!\\d))|(?<=(?:^| )\\d{1,2}[a-z]? \\$?)\\d{1,2}(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    }
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
