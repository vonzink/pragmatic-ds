-- V25 — Phase C of the Reducto-parity roadmap: separating two documents of the SAME type.
--
-- WHY: three consecutive monthly bank statements arrive as one run of BANK_STATEMENT pages. No
-- type changes between them, and no anchor distinguishes one statement's header from the next, so
-- the splitter merges all three into ONE document — which then reports a single beginning balance
-- for three months and fails an arithmetic reconciliation it was never wrong about. It is the
-- commonest same-type seam in a mortgage package and the splitter has been blind to it.
--
-- They are not actually indistinguishable: each statement prints its own PERIOD, and the schema
-- already knows how to read one. Two disjoint periods inside one run prove two documents. This
-- migration is the DATA half of that: which field(s) carry a type's instance identity is schema
-- configuration, not code, exactly like every other extraction rule (Phase 5 acceptance 5).
--
-- Section 1: the new boundary_provenance value.
-- Section 2: bank_statement@1.3.0, declaring its instance key.

-- ── 1. INSTANCE_CHANGE joins the boundary vocabulary ────────────────────────
--
-- Kept DISTINCT from RULE, though both are deterministic proofs, because a reviewer learns
-- something different from each: RULE says "a printed form header was found", INSTANCE_CHANGE says
-- "this is the next statement". It is also the only cut that can separate two documents no anchor
-- distinguishes, which makes it worth naming when auditing why a package split as it did.
--
-- DROP-then-ADD, the V23 pattern: migration history is immutable, so a CHECK is widened by
-- replacement.
ALTER TABLE logical_document DROP CONSTRAINT logical_document_boundary_provenance_check;

ALTER TABLE logical_document ADD CONSTRAINT logical_document_boundary_provenance_check
    CHECK (boundary_provenance IS NULL
           OR boundary_provenance IN ('HUMAN', 'RULE', 'PACKAGE_START', 'TYPE_CHANGE',
                                      'INSTANCE_CHANGE', 'AI'));

COMMENT ON COLUMN logical_document.boundary_provenance IS
    'How this document''s starting boundary was decided: HUMAN | RULE | PACKAGE_START | '
    'TYPE_CHANGE | INSTANCE_CHANGE | AI. NULL for rows written before V24. Precedence, strongest '
    'first: HUMAN > RULE > INSTANCE_CHANGE > TYPE_CHANGE > AI (PACKAGE_START is structural, not '
    'inferred).';

-- ── 2. bank_statement@1.3.0 — the instance key, declared as data ────────────
--
-- RLS: the V10/V12/V18/V21 late-seed dance — extraction_schema has FORCE ROW LEVEL SECURITY since
-- V7 and its INSERT policy admits only org_id = current_org(), which a global (org_id NULL) row can
-- never satisfy. Drop FORCE for the duration, restore before commit; a forgotten restore fails
-- RlsCoverageIT.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- Retired, never deleted: 1.2.0 stays for the provenance of every row extracted under it (the V10
-- rule). Nothing about how a field is READ changes here — 1.3.0 is 1.2.0 plus one declaration.
UPDATE extraction_schema
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.2.0';

-- Derived from 1.2.0 rather than retyped. The definition is ~90 lines of authored extractor
-- ladders and transcribing them to add one key would be a silent-corruption risk for no benefit;
-- jsonb concatenation adds the key and guarantees every field spec is byte-identical to the
-- version this supersedes.
--
-- BOTH period fields, not just the start: a page that prints a start date and no end date has told
-- us less than the schema asked for, and the probe requires every declared key field to be FOUND
-- before it will cut. Missing over wrong, applied to boundaries.
INSERT INTO extraction_schema (org_id, document_type_code, version, definition)
SELECT NULL,
       'BANK_STATEMENT',
       '1.3.0',
       definition || '{"instanceKey": ["statementPeriodStart", "statementPeriodEnd"]}'::jsonb
  FROM extraction_schema
 WHERE org_id IS NULL AND document_type_code = 'BANK_STATEMENT' AND version = '1.2.0';

ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
