-- V47 — w2@1.3.0 and tax_return@1.3.0: a person's name printed ACROSS two box-grid cells,
-- and a filing-status box marked with a printed glyph. w2@1.2.0 and tax_return@1.2.0 are
-- RETIRED, never edited (the V10/V12 rule).
--
--   §1 extraction_schema: w2@1.3.0 (1.2.0 retired) and tax_return@1.3.0 (1.2.0 retired) —
--      ONE NO FORCE dance.
--
-- No extraction_method CHECK widening: every rung below is LABEL_BELOW or ANCHOR_LABEL. The
-- new shape is a LABEL_BELOW PARAMETER, `joinCells` (ExtractorSpec.joinCells, parsed by
-- ExtractionSchemaLoader for that rung alone), and the glyph rule lives in CHECKBOX_STATE's
-- engine code, not in schema data.
--
-- ── WHAT THE REAL DOCUMENTS MEASURED (2026-09-14) ───────────────────────────
-- Four real documents, rules-only, scored with tools/corpus_score.py against the running
-- stack: two W-2s (a filled official form; a payroll provider's two-up copy B / copy 2) and
-- two Form 1040 page 1s (a filled official form; a preparer-printed 2023 return). Every
-- W-2 money box, the EIN, the employer and the year captured at 0.81–0.90; primarySsn and
-- every 1040 money line captured. The employee's name was missing on BOTH W-2s; the
-- taxpayer's and spouse's names were missing on BOTH 1040s; the filing status was missing
-- on the preparer-printed return. The pages' own word geometry says why. (Measured
-- positions below; every printed VALUE is described by shape only — no value from a corpus
-- document may be written down here or anywhere else in this repository.)
--
--   1. THE NAME IS SPLIT ACROSS TWO CELLS. Box e of the official W-2 is three captions on
--      ONE row — `e Employee's first name and initial` at x 42, `Last name` at x 177,
--      `Suff.` at x 313 — and a filed form fills them as captioned: the first name and
--      initial under the first caption, the SURNAME under `Last name`, the suffix cell
--      empty. Page 1 of the 1040 is the same grid twice: `Your first name and middle
--      initial` | `Last name` | `Your social security number`, then `If joint return,
--      spouse's first name and middle initial` | `Last name` | `Spouse's social security
--      number`, the values 4–5 pt beneath, the surname under `Last name` on both rows.
--
--      1.2.0's rungs read ONE cell. V45 had measured a preparer-printed return that
--      left-packs the whole name in the first cell and shaped the value pattern to demand
--      two name words so that a first-name-only cell could never become a confident
--      partial (design D5). That guard is right, and on these forms it fires every time:
--      the first cell holds `First M.` — one name word — and the rung fails. The surname
--      is on the page, one caption to the right; nothing read it.
--
--      The new rung reads the row as the form prints it. `joinCells` names the adjacent
--      caption(s) on the label's OWN row; each joined cell is derived exactly as the
--      anchoring cell is (cellWindow, the same drop, the same ownership fraction) and its
--      first value line is appended when it is the same printed row as the anchoring
--      cell's — a value one row down under `Last name` belongs to that row. The value
--      pattern then matches the COMPOSED text, still demanding two name words, so an empty
--      joined cell leaves `First M.` unmatched and the field missing exactly as before,
--      while a whole name left-packed in the first cell (the V45 return) still reads with
--      the join contributing nothing. TaxReturnExtractionIT proves both halves on the
--      same box-grid page, filled and unfilled.
--
--      The join caption is looked for to the RIGHT of the label on its own visual line,
--      never page-globally: the 1040 prints `Last name` on BOTH identity rows, and a
--      page-wide first match would bind the spouse rung to the taxpayer row's cell.
--
--   2. THE W-2 VALUE STARTS LEFT OF THE CAPTION TEXT. On the official form the first name
--      is printed at x 39, the box letter `e` at x 42 and the caption text at x 50 — the
--      value overlaps the caption TEXT by 42% of its own width and is refused by the 50%
--      ownership test, while it overlaps the cell measured from the box LETTER by 84%.
--      The letter is a printed caption too (the fixture has always drawn it, and its
--      values 3 pt left of it), so the first rung anchors on `e Employee's first name and
--      initial` and the cell's left edge is the box's. The letterless caption stays as the
--      second rung for a rendering that omits it. Both edges still derive from printed
--      captions, never from a fixed offset (Spec 4's rule).
--
--   3. THE PAYROLL PROVIDER'S W-2 PRINTS NEITHER CAPTION. Its box e is captioned
--      `Employee's name` alone, the whole name beneath it in capitals, and the address in
--      a separately captioned box beneath that. A fifth rung reads that caption; it is
--      ordered AFTER the ADP rung because `Employee's name` is a prefix of ADP's
--      `Employee's name, address, and ZIP code` and the ADP rung must keep winning there.
--
--   4. THE FILING-STATUS MARK IS A PRINTED GLYPH. The preparer-printed 1040 marks its box
--      by printing an `X` in the page's text layer — a 6 pt span inside a 13 pt drawn
--      square — and the worker's pixel detector measured the box's inner-60% fill at 0.14
--      against its 0.15 threshold and reported it UNCHECKED. CHECKBOX_STATE now counts a
--      box as marked when the detector read it as checked OR a single-character mark
--      glyph (X, x, ✓, ✔, ✗, ✘, ☒) has its center inside the box; a glyph inside no box
--      marks nothing, and D6's exactly-one rule is applied after. No schema change; the
--      `filingStatus` rung is byte-identical to 1.2.0's.
--
-- ── LADDER ORDER ────────────────────────────────────────────────────────────
-- w2@1.3.0 employeeName: [box-letter caption + join 0.9] → [caption + join 0.9] →
-- [`Employee:` ANCHOR_LABEL 0.9] → [ADP merged caption 0.85] → [`Employee's name` 0.85].
-- The name pattern on every new rung is V45's two-word person shape, capitals admitted.
-- Every other W2 field is byte-identical to 1.2.0.
-- tax_return@1.3.0 primaryTaxpayerName: [caption + join 0.9] → [`Your name:` 0.9];
-- spouseName: [full printed caption + join 0.9] → [V45's mid-caption literal + join 0.9] →
-- [`Spouse's name:` 0.9]. The full caption comes first because the value is printed at the
-- ROW's left edge (x 38), 45 pt left of where `spouse's` begins, and a cell measured from
-- the mid-caption literal does not own it. Every other TAX_RETURN field is byte-identical.
--
-- ── WHAT WAS CHECKED AGAINST THE SYNTHETIC FIXTURES ─────────────────────────
-- w2_form / w2_form_typographic now draw the name SPLIT (design D3: the fixture that
-- certified a layout no real W-2 has is corrected, not supplemented); w2_form_fourup keeps
-- the whole name in the first cell and w2_adp its merged caption, and all four read 10 of
-- 10 through the eval corpus. tax_return.pdf's flat `Your name:` lines still read through
-- the ANCHOR_LABEL rungs; the new tax_return_boxgrid.pdf reads the split names, the SSN
-- cell and the glyph-marked box. TaxReturnExtractionIT's real-geometry page (the V45
-- return, whole name in one cell) is unchanged and still passes.
--
-- Versions are NEVER edited in place: extracted_field.schema_id points at the row that
-- produced each stored value, so 1.2.0 is retired, not deleted. ExtractionSchemaLoader
-- takes the highest version within the surviving scope, so 1.3.0 supersedes on load.
--
-- RLS: extraction_schema has been FORCEd since V7 and its only INSERT policy is
-- WITH CHECK (org_id = current_org()); for a global (org_id NULL) row `NULL = <anything>`
-- is never TRUE, so no GUC value can admit it while FORCE binds the migration owner too.
-- The restore happens in the SAME transaction, and RlsCoverageIT's pg_class sweep fails
-- the build if it is ever forgotten.

-- ── §1 extraction_schema: w2@1.3.0, tax_return@1.3.0 ─────────────────────────

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'W2' AND version = '1.2.0';

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.2.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'W2', '1.3.0', '{
  "fields": [
    {
      "name": "employeeName",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "e Employee''s first name and initial"
          },
          "maxDropPt": 24.0,
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
            "pattern": "Employee''s first name and initial"
          },
          "maxDropPt": 24.0,
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
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employee''s name, address, and ZIP code"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Z.''-]+(?: [A-Z][A-Z.''-]*){1,3}(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employee''s name"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![A-Za-z''\\-])(?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?:(?: [A-Z]\\.?)|(?: (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})))* (?:[A-Z][A-Za-z''\\-]+|[A-Z]{2,})(?![A-Za-z''\\-])",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ],
      "normalizer": "personName"
    },
    {
      "name": "employeeSsn",
      "dataType": "STRING",
      "required": true,
      "sensitive": true,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Employee''s social security number"
          },
          "value": {
            "pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "social security number"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
            "occurrence": 0
          },
          "method": "REGEX",
          "strength": 0.5
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employee''s SSA number"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<!\\d)(?:\\d{3}|XXX)-(?:\\d{2}|XX)-\\d{4}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ],
      "normalizer": null
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
            "pattern": "Employer''s name, address, and ZIP code"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
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
        }
      ],
      "normalizer": null
    },
    {
      "name": "employerEin",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Employer identification number"
          },
          "value": {
            "pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Employer identification number"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employer''s FED ID number"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ],
      "normalizer": null
    },
    {
      "name": "taxYear",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Wage and Tax Statement"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<!\\d)20\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Wage and Tax Statement"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<!\\d)20\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        },
        {
          "method": "REGEX",
          "strength": 0.7,
          "value": {
            "pattern": "(?<!\\d)20\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "PAGE"
          }
        }
      ],
      "normalizer": null
    },
    {
      "name": "wagesTipsOtherComp",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Wages, tips, other compensation"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Wages, tips, other compensation"
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
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Wages, tips, other comp."
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "federalIncomeTaxWithheld",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Federal income tax withheld"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Federal income tax withheld"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "socialSecurityWages",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Social security wages"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Social security wages"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "medicareWages",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Medicare wages"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "Medicare wages"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "stateWages",
      "dataType": "MONEY",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "State wages"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "LABEL_BELOW",
          "strength": 0.9,
          "maxDropPt": 24.0,
          "cellOverlap": 0.5
        },
        {
          "label": {
            "kind": "literal",
            "pattern": "State wages"
          },
          "value": {
            "scope": "LINE_RIGHT",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "ANCHOR_LABEL",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    }
  ]
}'::jsonb);

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'TAX_RETURN', '1.3.0', '{
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
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
            "pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    }
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
