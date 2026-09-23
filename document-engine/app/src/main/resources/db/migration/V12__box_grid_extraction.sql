-- V12 — Spec 4: box-grid extraction. ONE migration in TWO fixed sections; later
-- tasks append ONLY inside their marked section:
--   §1 extraction_method CHECK widening for LABEL_BELOW (T1)
--   §2 extraction schema versions (T3: w2@1.1.0, w2@1.0.0 retired · T4:
--      tax_return@1.1.0, tax_return@1.0.0 retired) — ONE NO FORCE dance on
--      extraction_schema wrapping every UPDATE and INSERT.
--
-- Legal because V12 is unreleased: Testcontainers remigrate from scratch on every
-- run, and a local compose stack needs a single `docker compose down -v` when the
-- spec lands.
--
-- WHY THIS SPEC EXISTS. A corpus sweep on 2026-08-10 ran thirteen real filled IRS
-- forms through the engine. The W-2 classified at a perfect 1.00 and extracted 2 of
-- 10 fields. The pack is right; the schema was authored against a fiction. Real
-- government and lender forms are BOX GRIDS — a label captions a cell and the value
-- sits on the next line inside it (measured on the filled W-2: label top 62.5, value
-- top 75.0, i.e. +12.5 pt below and x-overlapping) — while every ANCHOR_LABEL rung
-- searches LINE_RIGHT. LABEL_BELOW reads that geometry.

-- ── §1 extraction_method CHECK widening (T1) ────────────────────────────────
-- The V9 pattern: DROP then ADD. The constraint is extracted_field_method_check
-- (V7 — note: NOT extracted_field_extraction_method_check), last widened by V11 §1.
-- The Java ExtractionMethod enum mirrors this list exactly.
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE',
                                 'LABEL_BELOW'));

-- ── §2 extraction schema versions (T3/T4 append here) ───────────────────────
-- T3 adds w2@1.1.0 and retires w2@1.0.0; T4 adds tax_return@1.1.0 and retires
-- tax_return@1.0.0. BOTH live inside ONE NO FORCE dance on extraction_schema:
--
--   ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;
--   <UPDATE ... SET is_active = false ...; INSERT ...>
--   ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
--
-- Required, not optional (the V10/V11 lesson): the only INSERT policy on
-- extraction_schema is WITH CHECK (org_id = current_org()), and for a global
-- (org_id NULL) row `NULL = <anything>` is never TRUE — so no GUC value can admit
-- it — while FORCE ROW LEVEL SECURITY binds the migration owner too. The restore
-- happens in the same transaction, so no window exists for any other session, and
-- RlsCoverageIT's pg_class sweep fails the build if the restore is ever forgotten.
--
-- Versions are NEVER edited in place: extracted_field.schema_id points at the
-- extraction_schema row (and so at the version) that produced each stored value,
-- so mutating 1.0.0 would silently rewrite the meaning of every field already
-- extracted (design D4).

-- ── §2 schema versions (T3: w2@1.1.0 · T4: tax_return@1.1.0) ────────────────
-- Why a new VERSION and not an edit. extracted_field.schema_id is a foreign key to the
-- row that produced each stored field, so mutating 1.0.0 in place would silently
-- re-write the meaning of every W-2 field already extracted. Same convention V10
-- established for rule packs, and the same reason 1.0.0 is RETIRED rather than deleted:
-- it must stay resolvable, and a version with a known defect must not become reachable
-- again by deactivating its successor. ExtractionSchemaLoader takes the highest version
-- within the surviving scope, so 1.1.0 supersedes on load.
--
-- WHAT WAS WRONG. On 2026-08-10 the first real corpus sweep ran thirteen filled official
-- forms through the engine. The W-2 classified at 1.00 — every pack anchor firing — and
-- extracted 2 of 10 fields. The pack was right; the schema was authored against a
-- fixture we drew ourselves from the same belief as the schema:
--
--   fixtures/w2_form.pdf (as it was)   Employer identification number (EIN) 98-7654321
--   a real IRS W-2                     b Employer identification number (EIN) | 1 Wages, ...
--                                      <ein digits>                           | <amount>
--
-- Real government and lender forms are BOX GRIDS: the caption captions a cell and the
-- value sits on the next line INSIDE that cell (measured on the corpus form: label top
-- 62.5, value top 75.0 — +12.5pt below, x-overlapping, for both box 1 and box b). Every
-- rung in 1.0.0 searched LINE_RIGHT, so it found nothing. No synthetic fixture could have
-- caught it: we wrote the fixture from the same wrong assumption, so every test passed
-- while both were wrong. One real document found it in an afternoon.
--
-- WHAT CHANGED, precisely: the READING DIRECTION of nine rungs and nothing else. Same
-- value patterns (character for character), same strengths, same label phrases wherever a
-- real W-2 prints the same caption. Where 1.0.0's label was a synthetic wording no real
-- W-2 prints ("Employee:", "Employer:"), the REAL caption becomes the LABEL_BELOW label
-- and the synthetic one is kept as a lower ANCHOR_LABEL rung, so a genuinely flat W-2
-- still extracts. taxYear is UNTOUCHED: the year is printed to the RIGHT of "Wage and Tax
-- Statement" on the real form, which is exactly what its rung already reads — and it is
-- one of the two fields that did extract from the real document.
--
-- employeeSsn gains a LABEL_BELOW rung IN FRONT of its existing ladder rather than keeping
-- it as-is. It is one of the two fields that worked on the real form, but it worked
-- through its unanchored REGEX/PAGE rung (strength 0.5, no LABEL evidence) — a value with
-- no anchor is the Phase 5 trap class. Anchored on box a's own caption it comes back
-- evidence-bearing at 0.9, and the two old rungs stay beneath it untouched.
--
-- maxDropPt 24.0 / cellOverlap 0.5 are the loader defaults, written out here because they
-- are the bound on the spec's primary risk: on a W-2, box 3 sits directly beneath box 1,
-- and a rung that grabs the wrong cell returns a CONFIDENT WRONG value with a plausible
-- evidence box — strictly worse than the missing field. W2ExtractionIT's decoy test and
-- the T1 unit decoy test are what hold that bound.
--
-- Label phrases are authored TOGETHER with fixtures/generate.py's W-2 page builder, the
-- same rule V7/V11 §4 state: fixture text and schema labels are the same strings by
-- construction.
--
-- RLS: extraction_schema has been FORCEd since V7 and its only INSERT policy is
-- WITH CHECK (org_id = current_org()) — NULL = <anything> is never TRUE, so no GUC value
-- can admit a global row, and FORCE binds the migration owner too. Hence the V10 late-seed
-- dance: drop FORCE for the duration, restore before commit (same transaction, so no
-- window exists for any other session). A forgotten restore fails RlsCoverageIT's pg_class
-- sweep over relforcerowsecurity, with no per-table list to keep in sync.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- Retire the version authored against a layout no real W-2 has. Retired, never deleted.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'W2' AND version = '1.0.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'W2', '1.1.0', '{
  "fields": [
    {"name": "employeeName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employee''s first name and initial"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employee:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "employeeSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employee''s social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "social security number"},
        "value": {"pattern": "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "employerName", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer''s name, address, and ZIP code"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Za-z&''-]*(?: [A-Z&][A-Za-z&''-]*){0,4}(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}},
       {"method": "REGEX", "strength": 0.6,
        "value": {"pattern": "(?<![A-Za-z])[A-Z][A-Z&''-]+(?: [A-Z&][A-Z&''-]*){0,3} (?:LLC|L\\.L\\.C\\.|INC\\.?|CORP\\.?|CO\\.|LTD\\.?|COMPANY)(?![A-Za-z])",
                  "occurrence": 0, "scope": "PAGE"}}]},
    {"name": "employerEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer identification number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)", "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Employer identification number"},
        "value": {"pattern": "(?<!\\d)\\d{2}-\\d{7}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Wage and Tax Statement"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "wagesTipsOtherComp", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Wages, tips, other compensation"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Wages, tips, other compensation"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "federalIncomeTaxWithheld", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Federal income tax withheld"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Federal income tax withheld"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "socialSecurityWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social security wages"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Social security wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "medicareWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Medicare wages"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Medicare wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "stateWages", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "State wages"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "State wages"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

-- T4: tax_return@1.1.0 — the 1040's ONE genuinely box-shaped field, and a deliberate
-- refusal to move the others.
--
-- WHAT MOVED. primarySsn only. On a real Form 1040 the identity block is a box grid —
--   Your first name and middle initial | Last name | Your social security number
--   Jordan Q.                          | Fixture   | 987-65-4321
-- — so the SSN's caption captions a CELL and the value sits on the line inside it, exactly
-- like the W-2 boxes. The LABEL_BELOW rung goes IN FRONT of the existing ladder; the
-- ANCHOR_LABEL rung beneath it is unchanged, so the flat synthetic 1040 (and any real form
-- that prints the pair on one line) still extracts. Same value pattern, same strength: the
-- READING DIRECTION is the only thing that changed, which is the whole point of Spec 4.
--
-- WHAT DELIBERATELY DID NOT MOVE, and why the next author should not "finish the job":
--
--   * The numeric lines (totalIncome, adjustedGrossIncome, taxableIncome, totalTax,
--     refundAmount) are genuinely FLAT on a 1040: caption and amount share a visual row,
--     amount in the right-hand column. Moving them to LABEL_BELOW would be the W-2's
--     unfounded assumption pointed the other way.
--
--   * primaryTaxpayerName and spouseName span TWO cells (first-and-middle | last), and
--     LABEL_BELOW reads ONE cell. A rung anchored on "Your first name and middle initial"
--     would return a first-name-only value at full confidence with a perfectly plausible
--     evidence box: a CONFIDENT PARTIAL, which is the same failure class the decoy test
--     exists to prevent and strictly worse than the missing-field contract, which at least
--     tells a reviewer to look. Composing a value across sibling cells is a different
--     mechanism; Spec 4 does not invent it. TaxReturnExtractionIT pins this absence
--     (the_taxpayer_name_stays_missing_on_a_box_grid_rather_than_becoming_a_confident_partial)
--     so it cannot be undone silently.
--
--   * filingStatus stays CHECKBOX_STATE, unchanged.
--
-- TWO KNOWN LIMITS on the real corpus 1040, neither of them a schema problem, both recorded
-- rather than papered over. (1) Its page 1 classifies UNKNOWN while page 2 scores TAX_RETURN
-- 0.60, so page-1 fields are not even reached — that is a PACK problem, and packs and
-- schemas are separate concerns; conflating them is how the W-2 defect hid. (2) The 1040's
-- SSN inputs are AcroForm COMB fields whose text layer comes out as space-separated digits,
-- which no \d{3}-\d{2}-\d{4} pattern can match in ANY direction — a tokenization defect,
-- filed, not fixed here.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.0.0';

INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'TAX_RETURN', '1.1.0', '{
  "fields": [
    {"name": "primaryTaxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "spouseName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Spouse''s name:"},
        "value": {"pattern": "(?<![A-Za-z])[A-Z][a-z]+(?: [A-Z]\\.?){0,2}(?: [A-Z][a-z]+)?(?![A-Za-z])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "primarySsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "U.S. Individual Income Tax Return"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "filingStatus", "dataType": "ENUM", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "CHECKBOX_STATE", "strength": 0.9, "proximityPt": 18.0,
        "options": [
          {"label": {"kind": "literal", "pattern": "Single"},
           "value": "SINGLE"},
          {"label": {"kind": "literal", "pattern": "Married filing jointly"},
           "value": "MARRIED_FILING_JOINTLY"},
          {"label": {"kind": "literal", "pattern": "Married filing separately"},
           "value": "MARRIED_FILING_SEPARATELY"},
          {"label": {"kind": "literal", "pattern": "Head of household"},
           "value": "HEAD_OF_HOUSEHOLD"},
          {"label": {"kind": "literal", "pattern": "Qualifying surviving spouse"},
           "value": "QUALIFYING_SURVIVING_SPOUSE"}
        ]}]},
    {"name": "totalIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "adjustedGrossIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Adjusted gross income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxableIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Taxable income"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "totalTax", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "total tax"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "refundAmount", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Refund amount"},
        "value": {"pattern": "(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}'::jsonb);

-- (§2 complete: T3 seeded w2@1.1.0 and T4 tax_return@1.1.0 inside this ONE dance; the FORCE
-- restore below closes it. Corrections always ship as new schema versions, never as edits.)

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
