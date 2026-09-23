-- V36 — paystub@1.2.0: the payroll-bureau layout becomes CLASSIFIABLE and EXTRACTABLE.
--
-- A real borrower's stub from a Denver payroll bureau — a SCANNED document whose
-- spans reach the engine through OCR — matched only "Pay Period" (3) and
-- "Earnings" (2) of paystub@1.1.0's vocabulary: 5 of the 10-point target, below
-- the 0.6 threshold, so the document classified UNKNOWN and its review panel
-- rendered blank. Same disease as the Oracle layout V34 fixed, sixth dialect.
--
-- Two facts of that layout shape everything here, both read off the stub's own
-- OCR transcript rather than guessed:
--
--   1. Its vocabulary is its own: "Deposit Date" for the pay date, "Pay
--      Frequency", "Ref Number", "Fed Filing Status", "Hrs/Units", and a
--      "Total Current Net" caption in the deposit-advice footer.
--   2. OCR FUSES many captions into single tokens: "PayFrequency:",
--      "FedFilingStatus/MultipleJobs:", "ThisPayPeriod", "NetPay",
--      "TotalCurrentNet:". An anchor written for the printed spacing misses
--      the span stream the engine actually receives.
--
-- So the six new anchors are optional-space regexes ("Pay ?Frequency") or
-- fused-form patterns, matching both the clean and the fused reading. They are
-- chosen to be DISJOINT from other layouts'' spellings where double-counting
-- could inflate a score, and scoring is min(1, matched/targetScore) — absolute,
-- not normalized — so appending anchors cannot dilute the five layouts that
-- already qualify. The paystub_bureau fixture pins both halves: it must fail
-- 1.1.0''s vocabulary and qualify 1.2.0''s.
--
-- The schema side appends seven rungs, every one inert on other layouts either
-- because its caption is fused-form ("PayFrequency:", "TotalCurrentNet:") or
-- because an earlier rung on the same ladder captures first there. The two-digit
-- year rungs exist because this bureau prints 07/24/26 — the existing "Pay
-- Period" rung demands \d{4} years and fails here, which is exactly the
-- fall-through the ladder design intends. Normalizers pivot two-digit years to
-- the 2000s (Normalizers.twoDigitYearPivot2000).
--
-- DELIBERATELY NOT MAPPED: employerName (the company block carries no caption,
-- and an uncaptioned name rung was exactly the confident-wrong-value defect the
-- DISASTER_CERT borrowerName fix removed) and currentGrossPay/ytdGrossPay — the
-- only captions over those values are the totals strip''s repeated "Earnings",
-- and LABEL_BELOW binds a label''s FIRST page occurrence (findFirst; there is no
-- label-occurrence selector), which on this page is the earnings TABLE header.
-- Absent beats confidently wrong; the gap is recorded as an engine limit in the
-- eval case, alongside VOE lenderName and SSA-1099 netBenefits.
--
-- The NO FORCE dance is required for the same reason V12 documents: the only
-- INSERT policy on these tables is WITH CHECK (org_id = current_org()), and for
-- a global (org_id NULL) row `NULL = <anything>` is never TRUE, while FORCE ROW
-- LEVEL SECURITY binds the migration owner too. The restore happens in the same
-- transaction, and RlsCoverageIT''s pg_class sweep fails the build if skipped.

ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

-- Retired, never deleted: every classification_result 1.1.0 decided still
-- resolves through it. A supersession adds a row, it never replaces one.
UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.1.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'PAYSTUB', '1.2.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3},
    {"id": "gross-pay", "kind": "literal", "pattern": "Gross Pay", "weight": 3},
    {"id": "net-pay", "kind": "literal", "pattern": "Net Pay", "weight": 2},
    {"id": "pay-date", "kind": "literal", "pattern": "Pay Date", "weight": 2},
    {"id": "earnings", "kind": "literal", "pattern": "Earnings", "weight": 2},
    {"id": "fed-withholding", "kind": "literal", "pattern": "Federal Withholding", "weight": 2},
    {"id": "ytd", "kind": "regex", "pattern": "\\bYTD\\b", "weight": 1},
    {"id": "gross-earnings", "kind": "literal", "pattern": "Gross Earnings", "weight": 2},
    {"id": "payroll-rel", "kind": "literal", "pattern": "Payroll Relationship Number", "weight": 2},
    {"id": "period-end", "kind": "literal", "pattern": "Period End Date", "weight": 1},
    {"id": "payment-date", "kind": "literal", "pattern": "Payment Date", "weight": 1},
    {"id": "deposit-date", "kind": "regex", "pattern": "Deposit ?Date", "weight": 3},
    {"id": "pay-frequency", "kind": "regex", "pattern": "Pay ?Frequency", "weight": 2},
    {"id": "fed-filing-status", "kind": "regex", "pattern": "Fed ?Filing ?Status", "weight": 2},
    {"id": "hrs-units", "kind": "regex", "pattern": "Hrs/ ?Units", "weight": 2},
    {"id": "this-pay-period", "kind": "regex", "pattern": "This ?Pay ?Period", "weight": 2},
    {"id": "total-current-net", "kind": "regex", "pattern": "Total ?Current ?Net", "weight": 3}
  ]
}');

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'PAYSTUB' AND version = '1.1.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'PAYSTUB', '1.2.0', '{
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
        },
        {
          "method": "LABEL_BELOW",
          "strength": 0.8,
          "label": {
            "kind": "regex",
            "pattern": "Name ?andAddress"
          },
          "maxDropPt": 18.0,
          "cellOverlap": 0.5,
          "value": {
            "pattern": "(?<![A-Za-z])[A-Z][A-Z.''-]+(?: [A-Z][A-Z.''-]*){1,3}(?![A-Za-z])",
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{2}(?=-)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.85,
          "label": {
            "kind": "regex",
            "pattern": "Period ?Beginning:?"
          },
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "literal",
            "pattern": "Pay Period"
          },
          "value": {
            "pattern": "(?<=-)\\d{2}/\\d{2}/\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
          }
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.85,
          "label": {
            "kind": "regex",
            "pattern": "Period ?Ending:?"
          },
          "value": {
            "pattern": "\\d{2}/\\d{2}/\\d{4}",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "regex",
            "pattern": "Deposit ?Date:?"
          },
          "value": {
            "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{2}(?!\\d)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "literal",
            "pattern": "PayFrequency:"
          },
          "value": {
            "pattern": "(?:Weekly|Biweekly|Bi-Weekly|Semimonthly|Semi-Monthly|Monthly)",
            "occurrence": 0,
            "scope": "LINE_RIGHT"
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "regex",
            "pattern": "Total ?Current ?Net:?"
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
        },
        {
          "method": "ANCHOR_LABEL",
          "strength": 0.8,
          "label": {
            "kind": "regex",
            "pattern": "(?<![A-Za-z])Fed(?:eral| ?Tax)(?![A-Za-z])"
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
