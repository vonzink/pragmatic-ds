-- V54 — DERIVED joins the extraction methods; bank_statement@1.7.0: the three real
-- layouts of the first BANK_STATEMENT gold session (2026-09-22).
--
--   §1 extracted_field_method_check widened for DERIVED (spec 2026-09-23 §4)
--   §2 extraction_schema: bank_statement@1.7.0, written out in full (the V21 idiom);
--      1.6.0 retired — ONE NO FORCE dance
--
-- Measured on seven real statements (U.S. Bank ×3, ANB Bank ×2, Chase ×2), 1.6.0 filled
-- 25 of 61 expected fields. Every miss had a printed cause:
--   bankName            "JPMorgan Chase Bank, N.A." is mixed case; the all-caps regex read
--                       ANB's check images instead → a known-bank rung goes FIRST
--   accountNumber       U.S. Bank prints 1-555-0101-2233; ANB prints Account Number:XXXXXXXXnnnn
--                       as ONE span (so LINE, not LINE_RIGHT); Chase prints Primary Account:
--   statementPeriod*    U.S. Bank: "Jul 11, 2020 … through … Aug 12, 2020", abbreviated
--                       months on different visual lines → a page regex by occurrence;
--                       ANB: "Statement Ending 07/31/2020" and "07/01/2020 Beginning Balance"
--   totalDeposits       ANB "29 Credit(s) This Period"; U.S. Bank "Deposits / Credits"
--   totalWithdrawals    ANB "159 Debit(s) This Period"; U.S. Bank and Chase print only
--                       categories → derived: beginning + deposits − ending
--   accountHolderName   never labelled; the first line of the address block, joint holders
--                       on a second "OR …" line → layout-anchored rungs with joinNextLine
--   endingBalance       ANB prints the caption first as a table header; the engine now tries
--                       every occurrence (no schema change)
-- Rung order and strengths of 1.6.0 are unchanged; new rungs are appended per field except
-- the known-bank rung, which leads bankName.
--
-- RLS: the V10/V12/V18/V21/V25/V39/V40/V41 late-seed dance — FORCE dropped for the
-- duration, restored before commit; a forgotten restore fails RlsCoverageIT.

-- ── §1 ────────────────────────────────────────────────────────────────────────
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'AI', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE',
                                 'LABEL_BELOW', 'ROW_CELL', 'LABEL_ABOVE', 'DERIVED'));

-- ── §2 ────────────────────────────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.6.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'BANK_STATEMENT', '1.7.0', '
{
    "fields": [
        {
            "name": "accountHolderName",
            "dataType": "STRING",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Account Holder:"
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
                    "value": {
                        "pattern": "(?<![A-Za-z])[A-Z][A-Z''.-]+(?: [A-Z][A-Z''.-]*){1,4}(?: (?:JR|SR|II|III|IV))?(?= To Contact)",
                        "occurrence": 0,
                        "scope": "PAGE",
                        "joinNextLine": "^(?:OR|AND) ([A-Z][A-Z''.-]+(?: [A-Z][A-Z''.-]*){1,4})$"
                    },
                    "method": "REGEX",
                    "strength": 0.6
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Account Number:"
                    },
                    "value": {
                        "pattern": "(?<![A-Za-z])[A-Z][A-Z''.-]+(?: [A-Z][A-Z''.-]*){1,4}(?= Account Number)",
                        "occurrence": 0,
                        "scope": "LINE",
                        "joinNextLine": "^(?:OR|AND) ([A-Z][A-Z''.-]+(?: [A-Z][A-Z''.-]*){1,4})$"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.6
                },
                {
                    "value": {
                        "pattern": "(?<![A-Za-z])[A-Z][A-Z&''.-]+(?: (?:&|[A-Z][A-Z&''.-]*)){1,6}(?= Page \\d+ of \\d+)",
                        "occurrence": 0,
                        "scope": "PAGE",
                        "joinNextLine": "^(?:OR|AND) ([A-Z][A-Z''.-]+(?: [A-Z][A-Z''.-]*){1,4})$"
                    },
                    "method": "REGEX",
                    "strength": 0.5
                }
            ],
            "normalizer": "personName"
        },
        {
            "name": "bankName",
            "dataType": "STRING",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "method": "REGEX",
                    "strength": 0.8,
                    "value": {
                        "pattern": "(?<![A-Za-z])(?:U\\.S\\. Bank|JPMorgan Chase Bank, N\\.A\\.|ANB Bank|Wells Fargo Bank, N\\.A\\.|Bank of America, N\\.A\\.|PNC Bank|TD Bank|Capital One, N\\.A\\.)(?![A-Za-z])",
                        "occurrence": 0,
                        "scope": "PAGE"
                    }
                },
                {
                    "value": {
                        "scope": "PAGE",
                        "pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]*(?: [A-Z][A-Z&''-]*){0,3} BANK(?![A-Za-z])",
                        "occurrence": 0
                    },
                    "method": "REGEX",
                    "strength": 0.6
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Printed from"
                    },
                    "value": {
                        "scope": "LINE_RIGHT",
                        "pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z][A-Za-z&''-]*)*?(?= (?:Personal|Business|Online))",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                }
            ],
            "normalizer": null
        },
        {
            "name": "accountNumber",
            "dataType": "STRING",
            "required": true,
            "sensitive": true,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Account Number:"
                    },
                    "value": {
                        "scope": "LINE_RIGHT",
                        "pattern": "(?<![\\d-])\\d{10,17}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "(?i)\\b(?:TOTAL )?(?:CHECKING|SAVINGS|MONEY MARKET)\\b"
                    },
                    "value": {
                        "scope": "LINE_RIGHT",
                        "pattern": "(?:\\.{3,}|\\*{3,}|[xX]{3,})\\d{4}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.7
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Account Number"
                    },
                    "value": {
                        "pattern": "(?<![\\d-])\\d(?:\\d|-\\d| \\d){8,20}\\d(?![\\d-])",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Account Number"
                    },
                    "value": {
                        "pattern": "(?<![A-Za-z\\d])[Xx*\u2022]{3,}\\d{4}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Primary Account:"
                    },
                    "value": {
                        "pattern": "(?<!\\d)\\d{10,17}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                }
            ],
            "normalizer": null
        },
        {
            "name": "statementPeriodStart",
            "dataType": "DATE",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "Statement Period:? ?\\d{2}/\\d{2}/\\d{4} ?- ?\\d{2}/\\d{2}/\\d{4}"
                    },
                    "value": {
                        "scope": "LINE",
                        "pattern": "\\d{2}/\\d{2}/\\d{4}",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}\\s?through\\s?(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"
                    },
                    "value": {
                        "scope": "LINE",
                        "pattern": "(?<!\\d)(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Beginning Balance"
                    },
                    "value": {
                        "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{4}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.6
                },
                {
                    "value": {
                        "pattern": "(?<![A-Za-z\\d])(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec) \\d{1,2}, \\d{4}(?!\\d)",
                        "occurrence": 0,
                        "scope": "PAGE"
                    },
                    "method": "REGEX",
                    "strength": 0.5
                }
            ],
            "normalizer": "date"
        },
        {
            "name": "statementPeriodEnd",
            "dataType": "DATE",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "Statement Period:? ?\\d{2}/\\d{2}/\\d{4} ?- ?\\d{2}/\\d{2}/\\d{4}"
                    },
                    "value": {
                        "scope": "LINE",
                        "pattern": "\\d{2}/\\d{2}/\\d{4}",
                        "occurrence": 1
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}\\s?through\\s?(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"
                    },
                    "value": {
                        "scope": "LINE",
                        "pattern": "(?<!\\d)(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}(?!\\d)",
                        "occurrence": 1
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "Statement Ending"
                    },
                    "value": {
                        "pattern": "(?<!\\d)\\d{2}/\\d{2}/\\d{4}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.7
                },
                {
                    "value": {
                        "pattern": "(?<![A-Za-z\\d])(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec) \\d{1,2}, \\d{4}(?!\\d)",
                        "occurrence": 1,
                        "scope": "PAGE"
                    },
                    "method": "REGEX",
                    "strength": 0.5
                }
            ],
            "normalizer": "date"
        },
        {
            "name": "beginningBalance",
            "dataType": "MONEY",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Beginning Balance"
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
            "name": "endingBalance",
            "dataType": "MONEY",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Ending Balance"
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
                        "kind": "literal",
                        "pattern": "Present balance"
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
                        "pattern": "Current balance"
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
                        "pattern": "Present balance"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Current balance"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.8
                }
            ],
            "normalizer": "money"
        },
        {
            "name": "totalDeposits",
            "dataType": "MONEY",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Total Deposits"
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
                        "kind": "literal",
                        "pattern": "Deposits and Additions"
                    },
                    "value": {
                        "scope": "LINE_RIGHT",
                        "pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "\\d+ Credit\\(s\\) This Period"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Deposits / Credits"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                }
            ],
            "normalizer": "money"
        },
        {
            "name": "totalWithdrawals",
            "dataType": "MONEY",
            "required": true,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Total Withdrawals"
                    },
                    "value": {
                        "scope": "LINE_RIGHT",
                        "pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.9
                },
                {
                    "label": {
                        "kind": "regex",
                        "pattern": "\\d+ Debit\\(s\\) This Period"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0,
                        "scope": "LINE_RIGHT"
                    },
                    "method": "ANCHOR_LABEL",
                    "strength": 0.8
                }
            ],
            "normalizer": "money",
            "derivation": {
                "plus": [
                    "beginningBalance",
                    "totalDeposits"
                ],
                "minus": [
                    "endingBalance"
                ]
            }
        },
        {
            "name": "monthToDateDeposits",
            "dataType": "MONEY",
            "required": false,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Deposits this month"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Deposits this"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.7
                }
            ],
            "normalizer": "money"
        },
        {
            "name": "monthToDateWithdrawals",
            "dataType": "MONEY",
            "required": false,
            "sensitive": false,
            "extractors": [
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Withdrawals this month"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.8
                },
                {
                    "label": {
                        "kind": "literal",
                        "pattern": "Withdrawals this"
                    },
                    "value": {
                        "pattern": "(?<![\\d,.])[-+]?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                        "occurrence": 0
                    },
                    "method": "LABEL_ABOVE",
                    "strength": 0.7
                }
            ],
            "normalizer": "money"
        }
    ],
    "instanceKey": [
        "statementPeriodStart",
        "statementPeriodEnd"
    ]
}
'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
