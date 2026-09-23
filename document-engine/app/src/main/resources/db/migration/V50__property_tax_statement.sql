-- V50 — a county real-estate tax statement gets a type of its own.
--
-- A property tax statement (tax bill) is in almost every refinance and purchase package
-- that carries an REO schedule or an escrow analysis: underwriting reads the annual tax
-- off it for the housing expense and the escrow. Until now it matched no pack at all, so
-- it classified UNKNOWN with no data — and PackageSplitter's continuation rule (an
-- UNKNOWN page joins the open run, V46) glued it onto whatever typed document preceded
-- it, a mortgage statement or an HOI declaration more often than not.
--
--   §1 document_type: PROPERTY_TAX_STATEMENT, category PROPERTY — the category V6 gave
--      HOI_DECLARATION and PURCHASE_CONTRACT and V34 gave APPRAISAL and DISASTER_CERT,
--      the documents about the collateral rather than the borrower's income or debt.
--   §2 classification_rule_pack: property_tax_statement@1.0.0, ONE startsDocument anchor
--      on the printed title so the splitter cuts at the statement's first page.
--   §3 extraction_schema: property_tax_statement@1.0.0 — headline fields only.
--
-- ── EXCLUSIVITY, the V10 rule ───────────────────────────────────────────────────────
--
-- Weight is what a phrase RULES OUT, not how salient it is. Authored against a real
-- native-text county statement (a North Dakota county, read 2026-09-16 and NOT committed;
-- the fixture is synthetic) and against what every US county bill prints in some wording:
-- a title naming the tax, a parcel identifier, an assessed or taxable value, a levy rate,
-- special assessments and the treasurer or collector it is paid to. Nothing below is that
-- county's own furniture.
--
-- PROPERTY_TAX_STATEMENT 1.0.0.
--   pt-title      \b(?:Real Estate|Real Property|Secured Property|Property|Ad Valorem) Tax
--                 (?:Statement|Bill)\b, Title Case OR ALL CAPS             5  EXCLUSIVE;
--                 startsDocument. "Tax Statement" alone is NOT the title: the W-2 is
--                 the "Wage and Tax Statement", so the property prefix is what makes it
--                 exclusive. CASE-SENSITIVE, and that is load-bearing (the s2-title
--                 lesson, V46): on an UNKNOWN page the splitter cuts at any startsDocument
--                 anchor whether or not its pack won, so an escrow letter's prose "we paid
--                 your property tax bill" must not start a document. The two spellings a
--                 county actually prints — Title Case and a CAPS banner — are listed; the
--                 mixed case prose uses is not. Known cost: a statement whose title is set
--                 in some third casing classifies on the rest of the pack or not at all.
--   pt-levy       (?i)mill levy | millage [rate] | mill rate | levy rate |
--                 tax rate area                                             2  EXCLUSIVE.
--                 Only a tax bill prints the rate it was levied at. A mortgage statement's
--                 escrow block, a closing disclosure and an appraisal print the tax AMOUNT.
--   pt-parcel     (?i)Parcel (?:Number|No.|ID|#)                            2  SHARED: the
--                 URAR prints "Assessor's Parcel #" and a title commitment's legal
--                 description cites the parcel. Identifies the property, not the document.
--   pt-value      (?i)(?:Assessed|Taxable) Valu(?:e|ation)s?                1  SHARED: an
--                 appraisal's tax section or a commitment's tax requirement can say
--                 "assessed value"; a 1040 says "taxable INCOME" and never matches.
--   pt-special    (?i)Special Assessments?                                  1  SHARED: the
--                 URAR's "Special Assessments $" box, and title exceptions for them.
--   pt-collector  (?i)County Treasurer | Treasurer-Tax Collector | Tax Collector |
--                 Tax Commissioner | Collector of Revenue                  1  SHARED: a
--                 commitment's requirement can name the office a payoff goes to.
-- Shared sum = 2+1+1+1 = 5 = 0.50 < 0.60: no appraisal or commitment page qualifies on
-- property vocabulary alone. Title lost to OCR on a full bill: 2+2+1+1+1 = 7 = 0.70.
-- A bill that prints no levy rate and loses its title: 5 = 0.50 — does not qualify;
-- accepted, the same trade V44 and V46 record.
-- The real statement scores 5+2+2+1+1+1 = 12 -> 1.00.
--
-- What the neighbours print, and why none of it is here:
--   MORTGAGE_STATEMENT  "Escrow Balance", "Property Taxes" as an escrow disbursement line,
--                       "Payment Due Date" — so no anchor on "Property Taxes", "Escrow",
--                       "Due Date" or "Amount Due". A mortgage statement scores 0 here.
--   HOI_DECLARATION     "Declarations Page", "Dwelling Coverage", the insured location's
--                       address — no parcel, value, levy or collector. Scores 0.
--   TAX_RETURN          "Form 1040", "taxable income", Schedule A's "State and local real
--                       estate taxes" — "real estate taxes" is not "Real Estate Tax
--                       Statement", and "taxable income" is not "Taxable Value". Scores 0.
--   W2                  "Wage and Tax Statement" — no property prefix. Scores 0.
-- NOT anchored: "Installment" (a loan, a land contract and a payment plan all print it),
-- "Tax Year" (every tax form), "Legal Description" (deeds, commitments, appraisals),
-- "Statement No" (bank and mortgage statements).
--
-- ── SCHEMA ──────────────────────────────────────────────────────────────────
-- Headline fields only: what underwriting reads for the housing expense and the escrow.
-- Every label is authored for the wording family, not the one county:
--   taxYear          the first 20xx on the TITLE line (county bills print the year first:
--                    "2025 <County> Real Estate Tax Statement").
--   parcelNumber     LABEL_BELOW under "Parcel Number|Parcel No.|Parcel ID"
--                    (box-grid, the real layout), then LINE_RIGHT after the same caption
--                    (the remittance stub's "Parcel Number: 09-4471025" shape). The value must
--                    carry a digit, so a caption word is never read as a parcel.
--   ownerName        LABEL_BELOW under "Owner|Owner Name|Owner(s)|Taxpayer". Not a
--                    personName: a county prints "LAST/FIRST M & SPOUSE" in caps, often
--                    truncated, and often a trust or LLC — the normalizer would score a
--                    faithful read as suspicious. Stored as printed.
--   propertyAddress  LABEL_BELOW under "Physical Location|Property Address|Property
--                    Location|Situs Address|Location Address". The FIRST line of the cell
--                    (street line), per the first-line-owns-the-cell rule.
--   totalTaxDue      LINE_RIGHT after "Total tax due|Total taxes due|Total amount due".
--   netTax           LINE_RIGHT after "Net [consolidated] tax" (the tax before special
--                    assessments). `tax\b` keeps "Net Taxable Value" out.
--   specialAssessments LINE_RIGHT after "Special Assessments" — the first printed.
--   assessedValue    LINE_RIGHT after "Assessed|Taxable Value", the LAST amount on
--                    the line: a county that prints a three-year comparison prints the
--                    current year rightmost, and `(?!.*\d)` takes it.
--   first/secondInstallmentAmount, first/secondInstallmentDueDate — LINE_RIGHT after
--                    "Payment 1|1st Half|1st Installment|First Half|First Installment" (and
--                    the 2/2nd/Second family). Optional: a county that bills once prints
--                    none of them.
-- Money patterns require cents (a bill prints them) and refuse a number glued to a date:
-- "Pay by March 1, 2026   2,502.70" yields the amount, never the 1 or the 2026.
--
-- ── RLS ─────────────────────────────────────────────────────────────────────
-- Same three NO FORCE dances as V44 and V46: each seeded table has been FORCEd since V6/V7
-- and admits only org_id = current_org(), which a global (org_id NULL) row can never
-- satisfy. Each restore happens in the SAME transaction; RlsCoverageIT's pg_class sweep
-- fails the build if one is ever forgotten.

-- ── §1 document type ────────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'PROPERTY_TAX_STATEMENT', 'Property Tax Statement', 'PROPERTY',
     'A county real-estate property tax statement or tax bill: the tax year and county in '
     'the title, the parcel number, owner and property location, assessed or taxable value '
     'and levy, the tax, special assessments and total due with installment due dates, and '
     'often a tear-off remittance stub at the foot of the same page; usually one page per '
     'parcel, and a document of its own rather than part of the mortgage statement or '
     'insurance declaration around it.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule pack ────────────────────────────────────────────────────────────
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'PROPERTY_TAX_STATEMENT', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "pt-title",     "kind": "regex", "pattern": "\\b(?:(?:Real Estate|Real Property|Secured Property|Property|Ad Valorem) Tax (?:Statement|Bill)|(?:REAL ESTATE|REAL PROPERTY|SECURED PROPERTY|PROPERTY|AD VALOREM) TAX (?:STATEMENT|BILL))\\b", "weight": 5, "startsDocument": true},
    {"id": "pt-levy",      "kind": "regex", "pattern": "(?i)\\b(?:mill levy|millage(?: rate)?|mill rate|levy rate|tax rate area)\\b", "weight": 2},
    {"id": "pt-parcel",    "kind": "regex", "pattern": "(?i)\\bParcel (?:Number|No\\b\\.?|ID\\b|#)",                          "weight": 2},
    {"id": "pt-value",     "kind": "regex", "pattern": "(?i)\\b(?:Assessed|Taxable) Valu(?:e|ation)s?\\b",                    "weight": 1},
    {"id": "pt-special",   "kind": "regex", "pattern": "(?i)\\bSpecial Assessments?\\b",                                        "weight": 1},
    {"id": "pt-collector", "kind": "regex", "pattern": "(?i)\\b(?:County Treasurer|Treasurer-Tax Collector|Tax Collector|Tax Commissioner|Collector of Revenue)\\b", "weight": 1}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schema ────────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'PROPERTY_TAX_STATEMENT', '1.0.0', '{
  "fields": [
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Real Estate|Real Property|Secured Property|Property|Ad Valorem) Tax (?:Statement|Bill)\\b"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE"}}]},
    {"name": "parcelNumber", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\bParcel (?:Number|No\\b\\.?|ID\\b)"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\w.-])(?=[\\w.-]*\\d)[0-9A-Za-z][0-9A-Za-z.-]{3,}[0-9A-Za-z](?![\\w.-])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.8,
        "label": {"kind": "regex", "pattern": "(?i)\\bParcel (?:Number|No\\b\\.?|ID\\b):?"},
        "value": {"pattern": "(?<![\\w.-])(?=[\\w.-]*\\d)[0-9A-Za-z][0-9A-Za-z.-]{3,}[0-9A-Za-z](?![\\w.-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "ownerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "\\b(?:Owner(?:\\(s\\)| Name(?:\\(s\\))?)?|OWNER(?:\\(S\\)| NAME(?:\\(S\\))?)?|Taxpayer|TAXPAYER)(?![A-Za-z(])(?! ?(?:ID|Id|No|Number|#))"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![0-9A-Za-z])[0-9A-Za-z][0-9A-Za-z/&.,''-]*(?: [0-9A-Za-z/&.,''-]+)*",
                  "occurrence": 0}}]},
    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Physical Location|Property Address|Property Location|Situs Address|Location Address)\\b"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\w-])\\d{1,6}[A-Za-z]?(?: [0-9A-Za-z.#''-]+)+",
                  "occurrence": 0}}]},
    {"name": "totalTaxDue", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\bTotal (?:tax|taxes|amount) due\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "netTax", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\bNet (?:consolidated )?tax\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "specialAssessments", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\bSpecial Assessments?\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "assessedValue", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Assessed|Taxable) Value\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2})?(?![\\d,.])(?!.*\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "firstInstallmentAmount", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Payment 1|1st (?:Half|Installment)|First (?:Half|Installment))\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "firstInstallmentDueDate", "dataType": "DATE", "required": false,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Payment 1|1st (?:Half|Installment)|First (?:Half|Installment))\\b"},
        "value": {"pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}|(?<!\\d)\\d{1,2}/\\d{1,2}/\\d{4}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "secondInstallmentAmount", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Payment 2|2nd (?:Half|Installment)|Second (?:Half|Installment))\\b"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "secondInstallmentDueDate", "dataType": "DATE", "required": false,
     "normalizer": "date", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "regex", "pattern": "(?i)\\b(?:Payment 2|2nd (?:Half|Installment)|Second (?:Half|Installment))\\b"},
        "value": {"pattern": "(?:January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}|(?<!\\d)\\d{1,2}/\\d{1,2}/\\d{4}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
