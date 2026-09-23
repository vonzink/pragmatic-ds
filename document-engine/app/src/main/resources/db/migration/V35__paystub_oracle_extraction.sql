-- V35 — paystub@1.1.0: the Oracle Cloud HCM layout becomes EXTRACTABLE.
--
-- V34 fixed RECALL for this layout: paystub@1.1.0 (the rule PACK) taught
-- classification the Oracle vocabulary, so a real borrower's Oracle stub stopped
-- landing in UNKNOWN. It did nothing for EXTRACTION — the schema still spoke only
-- the caption vocabulary of the four layouts that already worked ("Employee:",
-- "Pay Period", "Pay Date", "Pay Frequency"), none of which Oracle prints. The
-- document classified correctly and then gave up nine of its ten fields.
--
-- Oracle prints its captions ABOVE their values in a header band, not beside them:
--
--     Employer Name          Employee Name        Job Title
--     Synthetic Health ...   Jordan Q. Fixture    Widget Technician
--
--     Base Rate   Period Type  Period Start Date  Period End Date  Payment Date
--     48.0800 ..  Biweekly     01/04/2026         01/17/2026       01/23/2026
--
-- That is LABEL_BELOW geometry — the same rung the W-2 box grid uses — so the six
-- rungs added here are APPENDED to each field's existing ladder rather than
-- replacing it. The four layouts that already extract keep winning on their own
-- first rung and never reach the Oracle one; verified against paystub_complete,
-- paystub_missing_field, paystub_twopage and paystub_complete_rot180, where no
-- Oracle rung fires at all. That ordering is what keeps paystub_missing_field's
-- deliberately-undrawn payDate MISSING instead of newly guessed.
--
-- DELIBERATELY NOT MAPPED: federalWithholding. Oracle's summary prints "Employee
-- Tax Deductions" (694.35 on the fixture), which is the TOTAL of every employee
-- tax withheld — federal, Social Security, Medicare and state together — not the
-- federal line. Binding it to federalWithholding would report a confident number
-- that is roughly triple the truth, and an income calculation built on it would be
-- wrong in the borrower's favour. Missing is the correct answer until the layout
-- prints a federal line of its own.
--
-- currentGrossPay, ytdGrossPay and netPay already reach the Oracle summary through
-- their existing rungs ("Gross" LINE_RIGHT at occurrence 0 and 1, "Net Pay"), so
-- they gain nothing here.
--
-- The NO FORCE dance is required for the same reason V12 documents: the only INSERT
-- policy on extraction_schema is WITH CHECK (org_id = current_org()), and for a
-- global (org_id NULL) row `NULL = <anything>` is never TRUE, while FORCE ROW LEVEL
-- SECURITY binds the migration owner too. The restore happens in the same
-- transaction, and RlsCoverageIT's pg_class sweep fails the build if it is skipped.

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- Retired, never deleted: every extracted_field produced under 1.0.0 still points at
-- that row through schema_id. A supersession adds a row, it never replaces one.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.0.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'PAYSTUB', '1.1.0', '{
  "fields": [
    {
      "name": "borrowerName",
      "dataType": "STRING",
      "required": true,
      "normalizer": "personName",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Employee:"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employee Name"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "employerName",
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
            "pattern": "Employer:"
          },
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "REGEX",
          "strength": 0.6,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,3} (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
            "occurrence": 0,
            "scope": "PAGE"
          }
        },
        {
          "method": "REGEX",
          "strength": 0.4,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][a-z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,3} (?:LLC|Inc\\.?|Corp\\.?|Co\\.|Ltd\\.?|Company)(?![A-Za-z])",
            "occurrence": 0,
            "scope": "PAGE"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Employer Name"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?:(?: of| and| the)?(?: [A-Z&][A-Za-z&''-]*)){0,5}(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "payPeriodStart",
      "dataType": "DATE",
      "required": true,
      "normalizer": "date",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Period Start Date"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "payPeriodEnd",
      "dataType": "DATE",
      "required": true,
      "normalizer": "date",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 1,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Period End Date"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "payDate",
      "dataType": "DATE",
      "required": true,
      "normalizer": "date",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Pay Date"
          },
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Payment Date"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "payFrequency",
      "dataType": "ENUM",
      "required": true,
      "normalizer": "payFrequency",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Pay Frequency"
          },
          "value": {
            "pattern": "(?i)(?<![A-Za-z])(?<![A-Za-z][- ])(?:bi[- ]?weekly|semi[- ]?monthly|weekly|monthly)(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.85,
          "label": {
            "kind": "literal",
            "pattern": "Period Type"
          },
          "maxDropPt": 24.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?i)(?<![A-Za-z])(?<![A-Za-z][- ])(?:bi[- ]?weekly|semi[- ]?monthly|weekly|monthly)(?![A-Za-z])",
            "occurrence": 0,
            "scope": "LINE"
          }
        }
      ]
    },
    {
      "name": "currentGrossPay",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "TABLE_CLUSTER",
          "strength": 1.0,
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
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "literal",
            "pattern": "Gross Pay"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.7,
          "label": {
            "kind": "literal",
            "pattern": "Gross"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "ytdGrossPay",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "TABLE_CLUSTER",
          "strength": 1.0,
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
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "literal",
            "pattern": "Gross Pay"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 1,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.7,
          "label": {
            "kind": "literal",
            "pattern": "Gross"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 1,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "netPay",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Net Pay"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    },
    {
      "name": "federalWithholding",
      "dataType": "MONEY",
      "required": true,
      "normalizer": "money",
      "sensitive": false,
      "extractors": [
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.9,
          "label": {
            "kind": "literal",
            "pattern": "Federal Withholding"
          },
          "value": {
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        }
      ]
    }
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
