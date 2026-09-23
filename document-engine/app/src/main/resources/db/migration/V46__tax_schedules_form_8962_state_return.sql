-- V46 — issue #60: the pages a tax package prints that nothing could type.
--
-- Measured on a real 44-page federal-plus-state filing: 11 unrelated pages became
-- "Schedule B" and Schedule C claimed pages 13-44. Two defects, one symptom:
--
--   1. Schedules 1 and 2 print "(Form 1040)" and their own titles, and
--      tax_return@1.1.0 anchored those titles at weight 5 (numbered-schedule-title,
--      V13) — so a schedule page classified TAX_RETURN and the splitter folded it
--      into the 1040 in front of it. Correct only while the schedule sits directly
--      behind its 1040; in a package it does not, and the page is then a TAX_RETURN
--      island that glues to whatever precedes it.
--   2. Form 8962 and every STATE return matched no pack at all. PackageSplitter's
--      continuation rule — an UNKNOWN page joins the open run — is deliberate and
--      measured (a 26-page return used to shred into seven documents), and its
--      javadoc names this exact loss: "a whole document that classifies UNKNOWN end
--      to end is absorbed by the typed document in front of it". The rule stays.
--      What changes is that these documents earn a type, so they cut instead of glue,
--      and that the glue is COUNTED where it still happens (§5).
--
--   §1 document_type: SCHEDULE_1, SCHEDULE_2, FORM_8962, STATE_TAX_RETURN — one NO
--      FORCE dance, every row with a split_description (MigrationOwnershipIT's census
--      rejects a NULL).
--   §2 classification_rule_pack: four new 1.0.0 packs, each with ONE startsDocument
--      anchor on its printed title so the splitter cuts at the form's first page;
--      plus tax_return@1.2.0, which drops the numbered-schedule titles that used to
--      hand Schedules 1 and 2 to TAX_RETURN. 1.1.0 is retired, never edited (V10).
--   §3 extraction_schema: four minimal 1.0.0 schemas — headline fields only.
--   §4 logical_document.absorbed_untyped_pages: the count that makes absorption
--      visible.
--
-- ── EXCLUSIVITY, the V10 rule, and how each pack stays clear of TAX_RETURN ──────
--
-- Weight is what a phrase RULES OUT, not how salient it is. Every page below prints
-- "(Form 1040)" or "Attach to Form 1040", worth exactly 2 on TAX_RETURN's literal, and
-- the real forms print the Treasury line STACKED ("Department of the Treasury" over
-- "Internal Revenue Service"), so TAX_RETURN's em-dash literal cannot fire (the
-- Schedule E finding, V13). A schedule or 8962 page therefore scores 0.20 on
-- TAX_RETURN — and with the schedule titles gone from tax_return@1.2.0 there is no
-- anchor left that could lift it to 0.60. In the other direction, a real 1040 page
-- prints NONE of the titles below: it says "Additional income from Schedule 1, line
-- 10", never "Additional Income and Adjustments to Income". CrossConfusionIT gates
-- every cell of the matrix.
--
-- What the 1040 still keeps: "Additional Credits and Payments" (Schedule 3) stays a
-- TAX_RETURN anchor, at the weight V13 gave it. Schedule 3 is out of this issue's
-- scope, and dropping its title would turn a typed page into an absorbed one — the
-- defect this migration exists to remove.
--
-- SCHEDULE_1 1.0.0.
--   s1-title      "Additional Income and Adjustments to Income"  5  the printed title. EXCLUSIVE; startsDocument
--   s1-footer     (?i)Schedule 1 \(Form 1040\)                   2  masthead + footer id. Near-exclusive: a
--                                                                  lettered schedule CITES it ("Schedule 1
--                                                                  (Form 1040), line 5") — a reference is not
--                                                                  identity, so it cannot qualify alone.
--   s1-add-income "This is your additional income"              2  line 10's caption. EXCLUSIVE
--   s1-adjust     "These are your adjustments to income"        2  line 26's caption. EXCLUSIVE
--   s1-se-deduct  "Deductible part of self-employment tax"      1  line 15. Schedule SE says "Deduction for
--                                                                  one-half of self-employment tax".
--   s1-educator   "Educator expenses"                           1  line 11.
-- Non-exclusive sum = 2 = 0.20. Title lost to OCR: 2+2+2+1+1 = 8 = 0.80.
-- NOT anchored: "Unemployment compensation" (line 7) — it is FORM_1099_G's own anchor.
--
-- SCHEDULE_2 1.0.0.
--   s2-title      \bAdditional Taxes\b(?! on)                   5  the printed title. EXCLUSIVE; startsDocument.
--                                                                  The lookahead keeps Form 5329's "Additional
--                                                                  Taxes on Qualified Plans" out. CASE-SENSITIVE,
--                                                                  and that is load-bearing: on an UNKNOWN page the
--                                                                  classifier records EVERY pack's matched anchors
--                                                                  and the splitter cuts at any startsDocument
--                                                                  anchor it finds there, whether or not the pack
--                                                                  won — so a cover letter saying "no additional
--                                                                  taxes are due" would otherwise start a document
--                                                                  mid-return. The form prints Title Case.
--   s2-footer     (?i)Schedule 2 \(Form 1040\)                   2  masthead + footer id. Near-exclusive: Form
--                                                                  8962 line 29 cites it — reference, not identity.
--   s2-other      "These are your total other taxes"            2  line 21's caption. EXCLUSIVE
--   s2-ira        "Additional tax on IRAs or other tax-favored accounts" 1  line 8. Form 5329's title differs.
--   s2-homebuyer  "Repayment of first-time homebuyer credit"    1  line 10. Form 5405 says "of the First-Time".
--   s2-ss-medicare "Total additional social security and Medicare tax" 1  line 7.
-- Non-exclusive sum = 2 = 0.20. Title lost: 2+2+1+1+1 = 7 = 0.70.
-- NOT anchored: "Excess advance premium tax credit repayment" (line 2) — Form 8962
-- line 29 prints the same words; "Household employment taxes" — Schedule H's title;
-- "Self-employment tax" — Schedule SE's title; "Net investment income tax" — Form
-- 8960's title; "Alternative minimum tax" — Form 6251's title.
--
-- FORM_8962 1.0.0.
--   ptc-title     "Premium Tax Credit (PTC)"                    5  the printed title. EXCLUSIVE; startsDocument
--   ptc-reconcile "Reconciliation of Advance Payment of Premium Tax Credit" 2  Part II heading. EXCLUSIVE
--   ptc-poverty   "Federal poverty line"                        2  lines 4-5. EXCLUSIVE in a mortgage package.
--   ptc-form      "Form 8962"                                   1  the masthead id — but Schedules 2 and 3 both
--                                                                  say "Attach Form 8962", so a reference.
--   ptc-annual    "Annual contribution amount"                  1  line 8a.
--   ptc-excess    "Excess advance payment of PTC"               1  line 27.
-- Non-exclusive sum = 1 = 0.10. Title lost: 2+2+1+1+1 = 7 = 0.70.
-- NOT anchored: "Net premium tax credit" — Schedule 3 line 9 prints it.
--
-- STATE_TAX_RETURN 1.0.0 — one GENERIC type for every state's individual return.
-- A state return is identified by three things a federal 1040 never prints:
--   st-title      <State> [State] [Resident|Nonresident|Part-Year Resident|Full-Year
--                 Resident] [Individual|Personal] Income Tax Return               5  EXCLUSIVE; startsDocument.
--                 The 1040 says "U.S. Individual Income Tax Return" — no state name
--                 precedes it, so the state-name alternation is what makes this
--                 exclusive. Every state that levies a wage tax is listed. The state
--                 name matches case-insensitively (banners print COLORADO and
--                 Colorado both); the "Income Tax Return" tail is Title Case only, for
--                 the same boundary-on-an-UNKNOWN-page reason s2-title is — prose
--                 saying "your colorado individual income tax return" must not cut.
--   st-return     (?i)(Individual|Personal|Resident) Income Tax Return  2  NOT a boundary. Most big
--                 states put the form number INSIDE the title ("Georgia Form 500
--                 Individual Income Tax Return", "Virginia Resident Form 760
--                 Individual Income Tax Return", "Arizona Form 140 Resident Personal
--                 Income Tax Return", "Ohio IT 1040 Individual Income Tax Return",
--                 "Form IL-1040 Individual Income Tax Return"), so st-title's
--                 contiguous shape misses them and they scored 3+2 = 0.50 — under the
--                 bar, UNKNOWN, absorbed: the issue-60 symptom, merely counted. This
--                 anchor takes them to 3+2+2 = 0.70. A 1040 page prints "U.S.
--                 Individual Income Tax Return" and scores 0.20 on it, never reaching
--                 st-form or st-dept.
--   st-form       the common state form numbers (CA 540, CO DR 0104, NY IT-201/203,
--                 AZ 140, GA 500, VA 760, OR-40, IL-1040, NC D-400, MN M1, UT TC-40,
--                 NJ-1040, PA-40, OH IT 1040, MI-1040, SC1040, MD 502, ID 40, MT 2,
--                 KS K-40, IA 1040, MO-1040, NM PIT-1, OK 511, KY 740, DC D-40,
--                 HI N-11, NE 1040N, LA IT-540, AR1000F, CT-1040, RI-1040, VT IN-111,
--                 ME 1040ME, ND-1, WV IT-140, IN IT-40, AL 40), word-bounded   3
--                 Near-exclusive: a form number is printed on EVERY page of the
--                 return, so a continuation page scores 0.30 on it — under the bar,
--                 which is what keeps page 2 a continuation rather than a second
--                 document. NOTE: authored from the states' published form numbers,
--                 not verified against a real return of each state.
--   st-resident   (?i)Resident Income Tax Return                 2  NY IT-201's title carries no state name
--                                                                  ("Resident Income Tax Return" under the
--                                                                  department line). "Nonresident Alien
--                                                                  Income Tax Return" (1040-NR) does not
--                                                                  contain it — "Alien" intervenes.
--   st-dept       the revenue departments (Department of Revenue [Services],
--                 Franchise Tax Board, Department of Taxation [and Finance],
--                 Comptroller of Maryland, Michigan Department of Treasury, [State]
--                 Tax Commission, Department of Finance and Administration,
--                 Division of Taxation, Department of Taxes)                     2
--                 "Department of Treasury" without "the" is Michigan's; the federal
--                 "Department of the Treasury" does not match it.
-- Non-exclusive sum = 2 = 0.20 (st-return, the 1040's own title). NY IT-201 (no
-- state-named title): 3+2+2+2 = 9 = 0.90. A form-number-in-title state: 3+2+2 = 0.70.
-- Title lost to OCR with the form number too: 2+2 = 0.40 — does not qualify;
-- accepted, and the same trade V44 records.
-- ⚠ Only the Colorado shape is exercised by a fixture; the other states' numbers and
-- department names are authored from their published forms, not from documents.
--
-- ── SCHEMAS ─────────────────────────────────────────────────────────────────
-- Headline fields only. Money rungs reuse tax_return@1.2.0's whole-dollar-or-cents
-- value pattern (V45 §1): a preparer's `12,345.` matches its lenient branch, the
-- fixture's `12,345.00` its strict one, and a bare line number NEVER matches. The
-- taxpayerName rungs are SCHEDULE_B's LABEL_BELOW over the schedule's own identity
-- caption ("Name(s) shown on Form 1040, 1040-SR, or 1040-NR" on the numbered
-- schedules, "Name shown on your return" on 8962). STATE_TAX_RETURN's schema is the
-- thinnest, deliberately: only the tax year (read off the title LINE, because
-- Colorado prints "2025 Colorado Individual Income Tax Return" with the year FIRST),
-- the federal-taxable-income carry-in most states open with, and the refund line —
-- the two money rungs are optional because their captions are Colorado's.
--
-- ── RLS ─────────────────────────────────────────────────────────────────────
-- Same three NO FORCE dances as V44: each seeded table has been FORCEd since V6/V7
-- and admits only org_id = current_org(), which a global (org_id NULL) row can never
-- satisfy. Each restore happens in the SAME transaction; RlsCoverageIT's pg_class
-- sweep fails the build if one is ever forgotten.

-- ── §1 document types ───────────────────────────────────────────────────────
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category, split_description) VALUES
    (NULL, 'SCHEDULE_1', 'Additional Income and Adjustments to Income (Schedule 1)', 'INCOME',
     'IRS Schedule 1 (Form 1040): Part I additional income lines (business, rental, '
     'farm, unemployment) ending in the line 10 total, then Part II adjustments to '
     'income ending in the line 26 total; a one- or two-page form attached behind a '
     '1040, and its own document rather than part of the 1040.'),
    (NULL, 'SCHEDULE_2', 'Additional Taxes (Schedule 2)', 'INCOME',
     'IRS Schedule 2 (Form 1040): Part I tax (alternative minimum tax, excess advance '
     'premium tax credit repayment), Part II other taxes (self-employment tax and the '
     'rest) ending in the line 21 total; a one- or two-page form attached behind a '
     '1040, and its own document rather than part of the 1040.'),
    (NULL, 'FORM_8962', 'Premium Tax Credit (Form 8962)', 'INCOME',
     'IRS Form 8962 Premium Tax Credit (PTC): Part I household income against the '
     'federal poverty line, Part II the monthly reconciliation table ending in the net '
     'credit, Part III the excess-advance repayment; usually two pages, filed with a '
     '1040 that carries an Affordable Care Act marketplace policy.'),
    (NULL, 'STATE_TAX_RETURN', 'State Individual Income Tax Return', 'INCOME',
     'A state individual income tax return (California 540, Colorado DR 0104, New '
     'York IT-201 and the like): the state revenue department banner, a state-named '
     'return title, then line-numbered income, tax, withholding and refund entries; '
     'one return spans several pages that all belong to the same document, and '
     'follows the federal return in a package without being part of it.');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §2 rule packs ───────────────────────────────────────────────────────────
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

-- tax_return@1.2.0: 1.1.0 minus the numbered-schedule titles. Everything else is
-- byte-identical to V13's row, INCLUDING startsDocument staying unset on every
-- anchor: PackageSplitter's AnchorRef keys on (packType, anchorId) and drops the
-- version, so a flag set here would retroactively turn every page classified under
-- 1.0.0/1.1.0 into a form boundary (the V13 warning, still binding).
UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.1.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'TAX_RETURN', '1.2.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "individual-return-title", "kind": "literal", "pattern": "U.S. Individual Income Tax Return",                   "weight": 5},
    {"id": "numbered-schedule-title", "kind": "regex",   "pattern": "(?i)Additional Credits and Payments",                 "weight": 5},
    {"id": "form-1040-page",          "kind": "regex",   "pattern": "(?i)Form 1040 \\(20\\d\\d\\)",                        "weight": 4},
    {"id": "form-1040",               "kind": "literal", "pattern": "Form 1040",                                           "weight": 2},
    {"id": "treasury-irs",            "kind": "literal", "pattern": "Department of the Treasury—Internal Revenue Service", "weight": 2},
    {"id": "filing-status",           "kind": "literal", "pattern": "Filing Status",                                       "weight": 1},
    {"id": "schedule-form-1040",      "kind": "regex",   "pattern": "(?i)Schedule \\d \\(Form 1040\\)",                    "weight": 0}
  ]
}'::jsonb),
(NULL, 'SCHEDULE_1', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "s1-title",      "kind": "literal", "pattern": "Additional Income and Adjustments to Income", "weight": 5, "startsDocument": true},
    {"id": "s1-footer",     "kind": "regex",   "pattern": "(?i)Schedule 1 \\(Form 1040\\)",            "weight": 2},
    {"id": "s1-add-income", "kind": "literal", "pattern": "This is your additional income",             "weight": 2},
    {"id": "s1-adjust",     "kind": "literal", "pattern": "These are your adjustments to income",       "weight": 2},
    {"id": "s1-se-deduct",  "kind": "literal", "pattern": "Deductible part of self-employment tax",     "weight": 1},
    {"id": "s1-educator",   "kind": "literal", "pattern": "Educator expenses",                          "weight": 1}
  ]
}'::jsonb),
(NULL, 'SCHEDULE_2', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "s2-title",       "kind": "regex",   "pattern": "\\bAdditional Taxes\\b(?! on)",                              "weight": 5, "startsDocument": true},
    {"id": "s2-footer",      "kind": "regex",   "pattern": "(?i)Schedule 2 \\(Form 1040\\)",                              "weight": 2},
    {"id": "s2-other",       "kind": "literal", "pattern": "These are your total other taxes",                           "weight": 2},
    {"id": "s2-ira",         "kind": "literal", "pattern": "Additional tax on IRAs or other tax-favored accounts",        "weight": 1},
    {"id": "s2-homebuyer",   "kind": "literal", "pattern": "Repayment of first-time homebuyer credit",                    "weight": 1},
    {"id": "s2-ss-medicare", "kind": "literal", "pattern": "Total additional social security and Medicare tax",          "weight": 1}
  ]
}'::jsonb),
(NULL, 'FORM_8962', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "ptc-title",     "kind": "literal", "pattern": "Premium Tax Credit (PTC)",                                    "weight": 5, "startsDocument": true},
    {"id": "ptc-reconcile", "kind": "literal", "pattern": "Reconciliation of Advance Payment of Premium Tax Credit",     "weight": 2},
    {"id": "ptc-poverty",   "kind": "literal", "pattern": "Federal poverty line",                                        "weight": 2},
    {"id": "ptc-form",      "kind": "literal", "pattern": "Form 8962",                                                   "weight": 1},
    {"id": "ptc-annual",    "kind": "literal", "pattern": "Annual contribution amount",                                  "weight": 1},
    {"id": "ptc-excess",    "kind": "literal", "pattern": "Excess advance payment of PTC",                               "weight": 1}
  ]
}'::jsonb),
(NULL, 'STATE_TAX_RETURN', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "st-title",    "kind": "regex", "pattern": "\\b(?i:Alabama|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|District of Columbia|Georgia|Hawaii|Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|Missouri|Montana|Nebraska|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|Oklahoma|Oregon|Pennsylvania|Rhode Island|South Carolina|Utah|Vermont|Virginia|West Virginia|Wisconsin)(?: State)?(?: (?:Resident|Nonresident|Part-Year Resident|Full-Year Resident))?(?: (?:Individual|Personal))? Income Tax Return\\b", "weight": 5, "startsDocument": true},
    {"id": "st-return",   "kind": "regex", "pattern": "(?i)\\b(?:Individual|Personal|Resident) Income Tax Return\\b", "weight": 2},
    {"id": "st-form",     "kind": "regex", "pattern": "(?i)\\b(?:Form 540(?:NR| ?2EZ)?|DR 0104|IT-201|IT-203|Form 140(?:NR|PY)?|Form 500|Form 760|Form OR-40|IL-1040|D-400|Form M1|TC-40|NJ-1040(?:NR)?|PA-40|IT 1040|MI-1040|SC1040|Form 502|Form 40|Form 2|K-40|IA 1040|MO-1040|PIT-1|Form 511(?:NR)?|Form 740(?:-NP)?|D-40|Form N-11|Form 1040N|IT-540(?:B)?|AR1000F|CT-1040|RI-1040|IN-111|Form 1040ME|Form ND-1|IT-140|IT-40)\\b", "weight": 3},
    {"id": "st-resident", "kind": "regex", "pattern": "(?i)Resident Income Tax Return", "weight": 2},
    {"id": "st-dept",     "kind": "regex", "pattern": "(?i)\\b(?:Department of Revenue(?: Services)?|Franchise Tax Board|Department of Taxation(?: and Finance)?|Comptroller of Maryland|Michigan Department of Treasury|(?:State )?Tax Commission|Department of Finance and Administration|Division of Taxation|Department of Taxes)\\b", "weight": 2}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §3 extraction schemas ───────────────────────────────────────────────────
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_1', '1.0.0', '{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name(s) shown on Form 1040, 1040-SR, or 1040-NR"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Additional Income and Adjustments to Income"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalAdditionalIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "This is your additional income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalAdjustments", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "These are your adjustments to income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb),
(NULL, 'SCHEDULE_2', '1.0.0', '{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name(s) shown on Form 1040, 1040-SR, or 1040-NR"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Additional Taxes"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "selfEmploymentTax", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Self-employment tax"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalOtherTaxes", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "These are your total other taxes"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb),
(NULL, 'FORM_8962', '1.0.0', '{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name shown on your return"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Premium Tax Credit (PTC)"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "householdIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Household income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalPremiumTaxCredit", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total premium tax credit"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "advancePaymentOfPtc", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Advance payment of PTC"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "excessAdvancePtcRepayment", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Excess advance premium tax credit repayment"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb),
(NULL, 'STATE_TAX_RETURN', '1.0.0', '{
  "fields": [
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Income Tax Return"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE"}}]},
    {"name": "federalTaxableIncome", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Enter Federal Taxable Income"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "refundAmount", "dataType": "MONEY", "required": false,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Refund"},
        "value": {"pattern": "(?<![\\d,])(?<![Ll]ine )\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2}(?!\\d)|(?=\\.(?!\\d)))",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;

-- ── §4 the absorbed-page count ──────────────────────────────────────────────
-- The continuation rule in PackageSplitter.group is kept exactly as it is. What was
-- invisible is now a number on the row: how many of this document's pages were
-- UNTYPED continuations the rule absorbed. "Schedule C, pp. 13-44, 30 untyped pages
-- absorbed" is a document a reviewer opens first; "Schedule C, pp. 13-14, 0 absorbed"
-- is one they can trust. Nullable, no backfill: rows split before this column existed
-- read NULL ("not counted"), the same honesty V24 kept for boundary_provenance, and a
-- human regroup nulls it again because the machine's account no longer applies.
ALTER TABLE logical_document ADD COLUMN absorbed_untyped_pages integer;

ALTER TABLE logical_document ADD CONSTRAINT logical_document_absorbed_untyped_pages_check
    CHECK (absorbed_untyped_pages IS NULL OR absorbed_untyped_pages >= 0);

COMMENT ON COLUMN logical_document.absorbed_untyped_pages IS
    'How many member pages had no type of their own and were absorbed as continuations '
    'of this document by the splitter''s UNKNOWN-is-a-continuation rule. 0 for a document '
    'every page of which classified as its type; NULL for rows split before V46 or '
    'reshaped by a human, where the machine''s count no longer describes the document. '
    'Review sorts the largest counts first: they are where an unrelated document was glued on.';
