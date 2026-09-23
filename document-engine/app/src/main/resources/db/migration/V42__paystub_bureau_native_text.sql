-- V42 — paystub@1.3.0: the payroll-bureau layout as NATIVE text, not just as a scan.
--
-- V36 taught the engine the Denver payroll bureau's stub from a SCANNED copy, and wrote its
-- three trickiest rungs for the forms the OCR produced: "Name andAddress" with the space
-- swallowed, and a pay period fused to its dash — "07/12/26-07/18/26". Those forms were chosen
-- on purpose to be DISJOINT from the spaced captions other layouts print, so the rungs could not
-- double-count elsewhere. That reasoning was correct and it had a blind side.
--
-- The defect (empirical, five real stubs, 2026-09-05). The SAME bureau also issues its stub as a
-- native-text PDF, and the text layer keeps the spaces the scan lost: "Name and Address",
-- "07/12/26 - 07/18/26". Every rung V36 wrote for the fused forms then misses, and the stub
-- extracts 4 of 10 — pay date, frequency, net pay and federal withholding, the four whose
-- captions V36 already matched with optional-space regexes. Employee name and both pay-period
-- dates, which V36's own fixture PINS, come back MISSING on the native copy of the layout it
-- was built for.
--
-- Three pattern widenings, nothing else changes:
--   borrowerName    LABEL_BELOW  "Name ?andAddress"           -> "Name ?and ?Address"
--   payPeriodStart  ANCHOR_LABEL "\d{2}/\d{2}/\d{2}(?=-)"    -> "(?=\s?-)"
--   payPeriodEnd    ANCHOR_LABEL "(?<=-)\d{2}/\d{2}/\d{2}"   -> "(?<=-\s?)"
-- Each still requires the dash and the two-digit year, so the spaced form cannot bind a
-- four-digit "Pay Period" range from another layout; the fused form still matches as before.
--
-- DELIBERATELY STILL UNMAPPED: employerName and the gross-pay pair, for exactly V36's reasons —
-- the company block carries no caption and the totals strip's "Earnings" repeats are not
-- unique. Absent beats confidently wrong. Gate: fixtures/paystub_bureau_native.pdf.

-- RLS: the V10/V12/V36/V41 late-seed dance — FORCE dropped for the duration, restored before
-- commit; a forgotten restore fails RlsCoverageIT. Supersession adds a row and never replaces
-- one: every extracted_field already produced still points at 1.2.0 through schema_id.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.2.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
VALUES (NULL, 'PAYSTUB', '1.3.0', '{
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
        }
      ],
      "normalizer": "money"
    }
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
