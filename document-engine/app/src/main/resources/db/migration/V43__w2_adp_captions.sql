-- V43 — w2@1.2.0: the ADP rendering of the IRS box grid.
--
-- w2@1.1.0 (V12) reads the W-2 as a box grid — caption in the box, value beneath it — and its
-- LABEL_BELOW rungs carry the IRS captions verbatim: "Wages, tips, other compensation",
-- "Employee's social security number", "Employer identification number", "Employee's first
-- name and initial". ADP, the country's largest payroll processor, prints the same grid with
-- its own captions, and a real 2024 ADP W-2 (2026-09-05) extracted ONE field of ten:
-- employerName, whose caption ADP happens to print verbatim.
--
-- What ADP prints, box by box, read off the document rather than assumed:
--   box 1   "Wages, tips, other comp."                 (abbreviated)
--   box a   "Employee's SSA number"                    (not "social security number")
--   box b   "Employer's FED ID number"                 (not "identification number")
--   box e/f "Employee's name, address, and ZIP code"   (e and f merged; name in CAPS)
--   header  the year set BELOW "Wage and Tax Statement" in display type, not beside it
-- Four LABEL_BELOW alternates for the four captions, each at 0.85 beneath the IRS-caption rung.
-- The year gets a page-scoped REGEX fallback at 0.7 instead: nothing prints to the right of
-- "Wage and Tax Statement" on its own row, so cellWindow's fail-closed half (no printed
-- boundary, no widening) confines a LABEL_BELOW cell to the caption's own width and the
-- display-type year to its right can never be admitted. A four-digit 20xx token has no other
-- home on a W-2 — amounts carry cents, ZIPs are five digits, the EIN is hyphenated — so the
-- first one on the page is the statement year.
-- The SSA rung also accepts the masked "XXX-XX-6142" ADP prints on the employee copy, and the
-- name rung accepts the all-caps run ADP sets. Every existing rung is untouched; the IRS-verbatim
-- form still binds first wherever a form prints it.
--
-- Gate: fixtures/w2_adp.pdf, the same grid geometry as w2_form with ADP's captions and ADP's
-- right-aligned values.

-- RLS: the V10/V12/V36/V41 late-seed dance — FORCE dropped for the duration, restored before
-- commit; a forgotten restore fails RlsCoverageIT. Supersession adds a row and never replaces
-- one: every extracted_field already produced still points at 1.1.0 through schema_id.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'W2' AND version = '1.1.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
VALUES (NULL, 'W2', '1.2.0', '{
  "fields": [
    {
      "name": "employeeName",
      "dataType": "STRING",
      "required": true,
      "sensitive": false,
      "extractors": [
        {
          "label": {
            "kind": "literal",
            "pattern": "Employee''s first name and initial"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
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

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
