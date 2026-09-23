-- V53 — paystub@1.5.0: the stub's earnings LINES and its three closing totals become fields.
-- paystub@1.4.0 is RETIRED, never edited (the V10/V12 rule).
--
--   §1 extraction_schema: paystub@1.5.0 (1.4.0 retired) — ONE NO FORCE dance.
--
-- No extraction_method CHECK widening and no engine change to the deterministic reader.
--
-- ── WHY (2026-09-22, an underwriter's review of a real file) ─────────────────────────────
-- The AI paystub dialect (prompt paystub/1.1.0) has read EVERY row of a stub's earnings table
-- since the reconciler landed — description, hours, rate, current and YTD amounts — plus the
-- stub's own current/YTD total deductions and YTD net pay, and PaystubReconciler proves the ten
-- schema values against them. But only the ten schema fields were ever persisted: the lines and
-- the totals lived in the ledger's evidence JSON and nowhere a consumer could read. So a stub
-- whose YTD gross of, say, $52,000 is regular $46,000 + PTO $3,000 + holiday $2,000 + overtime
-- $1,000 (synthetic figures) reached the suite's parsed markdown (and the reconciliation engine
-- behind it) as a single $52,000 — and the YTD consistency test an underwriter actually runs
-- needs the BASE line, not the total. The reading existed and was proven; it was never written
-- down. This migration gives it landing coordinates.
--
-- ── THE FIELDS ────────────────────────────────────────────────────────────────────────────
-- Three scalars, MONEY, none required, none sensitive:
--   currentTotalDeductions  the current period's TOTAL deductions as the stub states them —
--                           the identity's third term (gross - deductions = net). Persisted now,
--                           it joins currentGrossPay and netPay as a reconciliation-gated field.
--   ytdTotalDeductions      the year-to-date total deductions.
--   ytdNetPay               the year-to-date net pay.
--
-- Five per-line fields, ONE ROW GROUP (kind ROW, one region, one maxRows), none required, none
-- sensitive — one occurrence per printed earnings row, in printed order:
--   earningDescription  STRING  the row's caption as printed ("Regular", "Overtime", "PTO")
--   earningHours        MONEY   hours worked on the row; MONEY because that is this schema's
--                               decimal type (there is no NUMBER-typed field anywhere in the
--                               shipped seeds, and PaystubExtraction.EarningLine already carries
--                               hours and rate as its decimal cell for the same reason)
--   earningRate         MONEY   the row's rate
--   earningCurrentAmount MONEY  the row's current-period amount
--   earningYtdAmount    MONEY   the row's year-to-date amount
--
-- The group key is the ROW-group key space the loader documents and the read model orders by:
-- the row ordinal zero-padded to TWO digits (01, 02, … — code-point order IS printed order),
-- capped at maxRows. Not the bank statement's six-digit transaction ordinal: those fields are
-- undeclared AI-only rows, while these are a DECLARED ROW group and inherit its contract
-- (MAX_ROWS_CEILING = 99 exists for exactly this format). maxRows 40 bounds a table no real stub
-- approaches; AiExtractionStageService caps its emission at the same constant.
--
-- ── WHY EVERY NEW RUNG IS `AI` ────────────────────────────────────────────────────────────
-- The loader refuses a field without extractors ("field has no extractors"), and no rule rung
-- can be trusted to read an earnings table across ADP / Paychex / Gusto / Workday / in-house
-- layouts — that is the whole reason the AI dialect exists. `AI` is a member of the
-- extraction_method vocabulary (V11's CHECK admits it, every AI-written row already carries it)
-- and DefaultFieldExtractionEngine's ladder treats it as an unimplemented rung: "an
-- unimplemented rung fails and the ladder moves on — never an error". So deterministically each
-- new field lands as the honest MISSING it is (method NONE): the three scalars as ungrouped
-- missing rows, and the five line fields as ONE null-keyed missing occurrence each — the
-- "region not read" shape every ROW group whose table the rules cannot address already has,
-- because no member declares a ROW_CELL column header and a frame needs one. The region's
-- anchors are the captions a real earnings block prints, so an authored ROW_CELL rung can land
-- on this group later without a schema bump; today they are inert by construction.
--
-- When the AI dialect runs (docengine.ai.paystub.enabled), AiExtractionStageService writes the
-- read lines under their row keys and RETIRES each field's null-keyed placeholder — "the rules
-- never located this table" stops being true the moment a reader did — so the read model and
-- the Markdown carry exactly the rows that were read: an `Earning 01–NN — ROW group` table. A
-- cell the model left empty on a line it DID read (a salaried row prints no hours) is written
-- as a keyed MISSING occurrence, exactly as the deterministic engine writes a located row's
-- blank cell (D5): the group stays rectangular, so the Markdown — which partitions tables by
-- exact key sequence — keeps the earnings block as one table instead of one per ragged column.
--
-- The `value` block on each AI rung is the loader's required shape for a value-bearing method
-- and nothing more: the pattern is never evaluated because the rung never runs.
--
-- ── WHAT THIS INVALIDATES ────────────────────────────────────────────────────────────────
-- extraction_schema definitions are part of the parse-behaviour fingerprint
-- (ReuseFingerprintService, `extractionSchemas`), so every held paystub fingerprint changes:
-- a package parsed under 1.4.0 re-parses instead of being served from reuse — correct, since
-- a reused result would lack the eighteen-field shape. The deterministic field rows of every
-- paystub grow from ten to eighteen occurrences (eight MISSING until AI fills them); the
-- recorded goldens under app/src/test/resources/extraction/golden were re-recorded for it.

-- ── §1 extraction_schema: paystub@1.5.0 ─────────────────────────────────────────────────

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.4.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
VALUES (NULL, 'PAYSTUB', '1.5.0', '{
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
    },
    {
      "name": "currentTotalDeductions",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "ytdTotalDeductions",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "ytdNetPay",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "earningDescription",
      "dataType": "STRING",
      "required": false,
      "sensitive": false,
      "group": {
        "kind": "ROW",
        "region": {
          "start": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])Earnings(?![A-Za-z])"},
          "end": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])(?:Gross|Total|Deductions)(?![A-Za-z])"}
        },
        "maxRows": 40
      },
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![A-Za-z])[A-Za-z][A-Za-z /&.-]*(?![A-Za-z])",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": null
    },
    {
      "name": "earningHours",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "group": {
        "kind": "ROW",
        "region": {
          "start": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])Earnings(?![A-Za-z])"},
          "end": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])(?:Gross|Total|Deductions)(?![A-Za-z])"}
        },
        "maxRows": 40
      },
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,4})?(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "earningRate",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "group": {
        "kind": "ROW",
        "region": {
          "start": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])Earnings(?![A-Za-z])"},
          "end": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])(?:Gross|Total|Deductions)(?![A-Za-z])"}
        },
        "maxRows": 40
      },
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,4})?(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "earningCurrentAmount",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "group": {
        "kind": "ROW",
        "region": {
          "start": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])Earnings(?![A-Za-z])"},
          "end": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])(?:Gross|Total|Deductions)(?![A-Za-z])"}
        },
        "maxRows": 40
      },
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    },
    {
      "name": "earningYtdAmount",
      "dataType": "MONEY",
      "required": false,
      "sensitive": false,
      "group": {
        "kind": "ROW",
        "region": {
          "start": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])Earnings(?![A-Za-z])"},
          "end": {"kind": "regex", "pattern": "(?i)(?<![A-Za-z])(?:Gross|Total|Deductions)(?![A-Za-z])"}
        },
        "maxRows": 40
      },
      "extractors": [
        {
          "value": {
            "scope": "PAGE",
            "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
            "occurrence": 0
          },
          "method": "AI",
          "strength": 0.9
        }
      ],
      "normalizer": "money"
    }
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
