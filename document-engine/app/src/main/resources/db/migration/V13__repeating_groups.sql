-- V13 — Spec 5a: repeating-group extraction. ONE migration in FIVE fixed sections;
-- later tasks FILL their own marked section IN PLACE — never a new header, never a
-- renumber, never a second dance on one table:
--   §1 extracted_field.group_key + the rebuilt current-row unique index (T1)
--   §2 extraction_method CHECK widening for ROW_CELL (T4)
--   §3 classification_rule_pack: tax_return@1.1.0 (T7) and schedule_e@1.0.0 (T8)
--      — ONE NO FORCE dance on classification_rule_pack, SHARED by both tasks
--   §4 document_type: SCHEDULE_E (T8) — ONE NO FORCE dance on document_type
--   §5 extraction_schema: schedule_e@1.0.0 (T8) — ONE NO FORCE dance on
--      extraction_schema
--
-- Legal because V13 is unreleased: Testcontainers remigrate from scratch on every
-- run, and a local compose stack needs a single `docker compose down -v` when the
-- spec lands.
--
-- WHY THIS SPEC EXISTS. extracted_field enforces one value per field name per
-- document. Schedule E breaks that three ways at once: Part I lists up to three
-- rental properties in side-by-side columns, Parts II-IV are open-ended entity
-- tables, and a borrower with more than three properties files more than one form.
-- Authoring schedule schemas on the old model would capture column A and silently
-- discard B and C, at full confidence, with a correct-looking evidence box — the
-- confident-partial failure class Spec 4 eliminated, reappearing one layer down.

-- ── §1 the group dimension (T1) ─────────────────────────────────────────────
-- NULL group_key is EXACTLY today's behaviour. coalesce(group_key, '') preserves
-- the old uniqueness rule for ungrouped fields exactly: every pre-existing row has
-- a NULL key, which collapses to '', so any two rows that collided before still
-- collide. The migration is additive and index-only — there is nothing to backfill.
--
-- The index is REBUILT, not supplemented, and it KEEPS ITS NAME: supersession rides
-- on a database guarantee that a duplicate is_current can never exist (V7's words),
-- and RlsCoverageIT asserts on the literal string 'extracted_field_one_current' in
-- the violation message. A differently named index would pass Postgres and fail the
-- build, which is the correct outcome for an accidental rename.
ALTER TABLE extracted_field ADD COLUMN group_key text;

DROP INDEX extracted_field_one_current;
CREATE UNIQUE INDEX extracted_field_one_current
    ON extracted_field (org_id, logical_document_id, field_name, coalesce(group_key, ''))
    WHERE is_current;

-- group_key is the value PRINTED ON THE FORM (design D2): 'A'/'B'/'C' for Schedule
-- E's property columns, and the PREPRINTED ROW LETTER ('A'..'D') for entity tables
-- the form letters in its left-margin gutter — Parts II and III print their rows
-- TWICE (an entity band and a money band) and letter both, so the letter is the join
-- key the form itself provides. Only a table the form does not letter (line 1a,
-- Part IV, the K-1s) keys by the row ordinal ZERO-PADDED TO TWO DIGITS ('01', '02',
-- ... '20') — not '1', '2'. This column is text, so every ordering that touches it
-- (OrderBy...GroupKeyAsc, the export's natural-order comparator, this index) sorts
-- LEXICALLY, and unpadded '10' sorts before '2': a ten-entity table would present
-- as 1, 10, 2, 3... Nothing would be wrong — every value and box stays correct —
-- but a consumer taking "the first entity" would take the wrong one. Plan root
-- CONTRACTS, owner T4. Never a synthetic surrogate: a reviewer reading "property B,
-- rents received" must still be able to find it on the page.
COMMENT ON COLUMN extracted_field.group_key IS
    'Repeating-group occurrence key (Spec 5a D1/D2): as printed on the form —'
    ' column keys (A/B/C) and preprinted row letters (A..Z, single characters) —'
    ' else the row ordinal zero-padded to two digits (01, 02, ...) for tables the'
    ' form does not letter, because this column sorts lexically. NULL for a'
    ' single-valued field, which is exactly the pre-V13 behaviour.';

-- ── §2 extraction_method CHECK widening for ROW_CELL (T4 fills here) ────────
-- The V9/V12 pattern: DROP then ADD, repeating the full value list. The constraint
-- is extracted_field_method_check (V7 — NOT extracted_field_extraction_method_check),
-- last widened by V12 §1 to include LABEL_BELOW. The Java ExtractionMethod enum
-- mirrors this list exactly.
--
-- ROW_CELL reads one occurrence per ROW of a bounded table region: Schedule E's
-- Parts II-IV are open-ended entity lists, and a row is not a field but one of N,
-- where N is discovered from the page rather than declared in the schema. Both
-- K-1s inherit the same rung (Spec 5d) rather than motivating a second mechanism.
ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_method_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_method_check
    CHECK (extraction_method IN ('ANCHOR_LABEL', 'TABLE_CLUSTER', 'REGEX', 'FORM_FIELD',
                                 'OCR_LINE', 'LLM', 'HUMAN', 'NONE',
                                 'CHECKBOX_STATE', 'SIGNATURE_PRESENCE',
                                 'LABEL_BELOW', 'ROW_CELL'));

-- ── §3 classification_rule_pack (T7: tax_return@1.1.0 · T8: schedule_e) ─────
-- ONE NO FORCE dance on classification_rule_pack, SHARED: T7 fills this section
-- first and writes the ALTER pair; T8 appends its statements BETWEEN them. Never
-- two dances on one table, and never a second §3 header.
--
--   ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;
--   <UPDATE ... SET is_active = false ...; INSERT ...>
--   ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;
--
-- Packs are NEVER edited in place: classification_result.rule_pack_version must keep
-- resolving, so a defective version is RETIRED (is_active = false), never deleted
-- and never rewritten. V10 is the worked example.

-- ONE NO FORCE dance on classification_rule_pack for BOTH tasks. T8 appends its
-- SCHEDULE_E pack INSIDE this dance at the marker below — never a second pair.
--
-- The V10 late-seed pattern, required and not optional: V6 already FORCEd RLS on
-- classification_rule_pack and its only INSERT policy is WITH CHECK (org_id =
-- current_org()). For a GLOBAL (org_id NULL) row `NULL = <anything>` is never TRUE,
-- so no GUC value can admit it, and FORCE binds the migration OWNER too. Drop FORCE
-- for the duration, restore before commit — same transaction, so no window exists
-- for any other session. A forgotten restore fails RlsCoverageIT's pg_class sweep
-- over relforcerowsecurity, which keeps no per-table list to go stale.
--
-- WHY tax_return IS RE-AUTHORED (Spec 5a design §5). TAX_RETURN@1.0.0 carries
-- schedule-form-1040, regex (?i)Schedule \d \(Form 1040\), weight 5 — seeded under
-- Spec 3's D4, when schedules were meant to classify as part of the return. Spec 5a
-- supersedes D4: SCHEDULE_E becomes its own type, and the cross-confusion gate then
-- sees TAX_RETURN qualifying on a Schedule E and fails the build. The gate is right.
--
-- THREE PRECISIONS, all verified against real filled forms, because the obvious
-- reading of this is wrong on all three:
--
--   1. The regex needs a DIGIT. It does NOT match "SCHEDULE E (Form 1040)". It fires
--      on the CROSS-REFERENCE "Schedule 1 (Form 1040), line 5" printed inside
--      Schedule E's own Part I instructions: 5 (that citation) + 2 (the literal
--      "Form 1040" in the page's own header) = 7/10 = 0.70. The em-dashed Treasury
--      line does NOT match a real schedule, which prints it without one — so the
--      0.70 is exactly those two anchors and no third.
--   2. It is not only Schedule E. A real filled Schedule C scores the same 0.70 the
--      same way. Fixing this properly fixes C and F ahead of Spec 5c.
--   3. Demoting that anchor ALONE would break the tax_return FIXTURE, not just the
--      corpus: its page 2 is a "SCHEDULE 2 (Form 1040)" page scoring 0.90 entirely
--      on schedule-form-1040 (5) + form-1040 (2) + treasury-irs (2). Demote and it
--      falls to 0.50 -> UNKNOWN, which reddens HoiPurchaseTaxPackTripIT's
--      all-three-pages assertion AND its one-logical-document split assertion.
--
-- So the pack must keep NUMBERED schedules classifying while ceasing to qualify on a
-- LETTERED schedule that merely cites one. WHAT CHANGED:
--
--   numbered-schedule-title  NEW, weight 5. The printed TITLES of the three numbered
--                            schedules: 1 "Additional Income and Adjustments to
--                            Income", 2 "Additional Taxes", 3 "Additional Credits and
--                            Payments". A page that IS Schedule 2 prints its title; a
--                            page that CITES Schedule 1 does not.        EXCLUSIVE
--   schedule-form-1040       5 -> 0. It matches a CITATION, and a citation proves
--                            nothing. Kept at zero rather than deleted so the
--                            evidence document still shows a reviewer that the page
--                            cited a numbered schedule, while it contributes nothing
--                            to the score. Weight is exclusivity; this has none.
--   everything else          UNCHANGED, weight for weight. targetScore stays 10 and
--                            min_confidence stays 0.6, so the diff reads as a
--                            re-authoring of anchors and nothing else.
--
-- WHY ENUMERATE THE THREE TITLES rather than write a disqualifier. V10 rejected
-- negative anchors because the confusable set is unbounded — a pack that must
-- enumerate its enemies is weaker than one that demands positive proof. Enumerating
-- its FRIENDS is the opposite move: the numbered schedules of Form 1040 are a CLOSED
-- SET OF THREE and their titles are what the IRS prints on them. A fourth would be a
-- 1.2.0, which is how packs-as-data is meant to work.
--
-- THE RESULTING SCORES (computed, and every fixture page unchanged):
--   fixture tax_return p0 (the 1040)          5+2+2+1 = 10 -> 1.00  (was 1.00)
--   fixture tax_return p1 ("Form 1040 (2025)")    4+2 =  6 -> 0.60  (was 0.60, still
--                                                         EXACTLY at threshold)
--   fixture tax_return p2 ("SCHEDULE 2 ...")   5+2+2+0 =  9 -> 0.90  (was 0.90)
--   a real Schedule 1/2/3 page                     5+2 =  7 -> 0.70  (was 0.70)
--   a real Schedule E / C / F page                 0+2 =  2 -> 0.20  (was 0.70) <-- the fix
--   shared vocabulary alone                    2+2+1+0 =  5 -> 0.50 < 0.60
--   the new title anchor alone                       5 =  5 -> 0.50 < 0.60
-- So the pack cannot qualify without an exclusive anchor, and the NEW exclusive
-- anchor cannot qualify on its own either. Pinned by TaxReturnNarrowingIT and by
-- HoiPurchaseTaxPackTripIT#no_T9_pack_can_qualify_on_shared_vocabulary_alone, whose
-- shared set now names schedule-form-1040.
--
-- ONE LIMIT, recorded rather than papered over: the corpus holds no filled numbered
-- schedule, so numbered-schedule-title is verified against the synthetic fixture and
-- against the printed IRS titles — not against a real document. Every other claim
-- above is corpus-verified. Said plainly, because Spec 4's lesson is that a fixture
-- authored from the same belief as the thing it tests proves nothing.
--
-- NO ANCHOR HERE CARRIES startsDocument. T6 ships that flag dark and T8 is the task
-- that first sets it in seeded data. It matters beyond tidiness: PackageSplitter's
-- AnchorRef keys on (packType, anchorId) and deliberately DROPS packVersion, while
-- splitting reads PERSISTED evidence written by whatever pack version ran at the
-- time. schedule-form-1040 keeps its id across 1.0.0 and 1.1.0, so had it been given
-- the flag, evidence recorded under 1.0.0 would match the 1.1.0 boundary set and
-- pages classified before this migration would retroactively become form boundaries.
-- Leaving every tax_return anchor at startsDocument = false (the loader's default for
-- an absent key) makes that unreachable.
--
-- This is the exclusivity lesson for the THIRD time: a reference to a form is not
-- evidence of being that form. V10 fixed it for W-2 after a real 1040 nearly
-- misclassified; here the same defect sat inside TAX_RETURN's own pack, put there by
-- a design decision this spec supersedes.
--
-- 1.0.0 is RETIRED, never edited and never deleted: classification_result
-- .rule_pack_version names the pack that decided every result already stored, so
-- mutating it in place would silently rewrite the meaning of history. Retired rather
-- than left active, because a pack with a known false positive must not become
-- reachable again by deactivating its successor. RulePackLoader takes the highest
-- version within the surviving scope, so 1.1.0 supersedes on load.
ALTER TABLE classification_rule_pack NO FORCE ROW LEVEL SECURITY;

UPDATE classification_rule_pack
   SET is_active = false, updated_at = now()
 WHERE org_id IS NULL AND document_type_code = 'TAX_RETURN' AND version = '1.0.0';

INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'TAX_RETURN', '1.1.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "individual-return-title", "kind": "literal", "pattern": "U.S. Individual Income Tax Return",                                                "weight": 5},
    {"id": "numbered-schedule-title", "kind": "regex",   "pattern": "(?i)Additional (?:Taxes|Income and Adjustments to Income|Credits and Payments)",   "weight": 5},
    {"id": "form-1040-page",          "kind": "regex",   "pattern": "(?i)Form 1040 \\(20\\d\\d\\)",                                                     "weight": 4},
    {"id": "form-1040",               "kind": "literal", "pattern": "Form 1040",                                                                        "weight": 2},
    {"id": "treasury-irs",            "kind": "literal", "pattern": "Department of the Treasury—Internal Revenue Service",                              "weight": 2},
    {"id": "filing-status",           "kind": "literal", "pattern": "Filing Status",                                                                    "weight": 1},
    {"id": "schedule-form-1040",      "kind": "regex",   "pattern": "(?i)Schedule \\d \\(Form 1040\\)",                                                 "weight": 0}
  ]
}'::jsonb);

-- T8: SCHEDULE_E 1.0.0 — appended INSIDE T7's single §3 dance, never a second one.
--
-- Exclusivity, the V10 rule, applied from birth and in BOTH directions. Weight is
-- EXCLUSIVITY, not salience: a phrase gets weight for what it RULES OUT.
--
--   EXCLUSIVE (Schedule E prints these and no other form in the corpus does):
--     supplemental-income  5  the masthead subtitle. PAGE 1 ONLY — page 2 carries the
--                             continuation header instead. This is the ONE anchor that
--                             declares startsDocument, and that is not an accident:
--                             "Schedule E (Form 1040)" is printed in page 1's FOOTER and
--                             page 2's HEADER, so keying the boundary on it would split
--                             every two-page form in half.
--     partnerships-scorps  4  Part II's heading (page 2)
--     rental-real-estate   3  Part I's heading (page 1)
--     estates-trusts       3  Part III's heading (page 2)
--   NON-EXCLUSIVE, scored accordingly:
--     schedule-e-page      2  the header/footer line, on BOTH pages. Near-exclusive, but a
--                             reference is not identity — the lesson this spec is applying
--                             to TAX_RETURN in the same migration, so SCHEDULE_E does not
--                             get to lean on its own name either.
--     rents-received       0  rental vocabulary; a rent roll or a lease prints it too
--     fair-rental-days     0  same
--
-- WHY THE RENTAL VOCABULARY SCORES ZERO, and what it fixes. V10's rule is that weight is
-- EXCLUSIVITY, not salience: "Rents received" and "Fair Rental Days" are salient on this
-- form and rule out nothing, so a weight of 1 was salience talking. Zero is not a demotion
-- for convenience — it is the same reading of the same rule that gives TAX_RETURN's
-- schedule-form-1040 a weight of 0: the anchor still MATCHES and is still recorded in the
-- evidence a reviewer reads, it simply does not vote on identity.
--
-- The defect that made this urgent. supplemental-income is both the heaviest identity
-- anchor AND the only startsDocument declaration. At weight 1 apiece the remaining page-1
-- anchors summed to 3+2+1+1 = 7 = 0.70 >= 0.60, so a page-1 rescan with a cropped or
-- unreadable masthead still classified SCHEDULE_E while startsDocument stayed FALSE — and
-- PackageSplitter.group, which cuts a same-type run only where a page claims a boundary,
-- then merged the borrower's SECOND Schedule E into the first. Two forms' worth of
-- properties collapse into ONE A/B/C key set, silently, and the merged document looks
-- exactly like a well-formed four-page one.
--
-- Three ways out were available: imply the boundary from qualification (wrong — page 2
-- qualifies on its own headings and must NOT start a document, or every two-page form
-- splits in half); teach the splitter to handle a type-run with no boundary anchor in it
-- (it cannot: nothing in the evidence says where form 2 begins, so it would have to guess);
-- or make qualification UNREACHABLE on page-1 vocabulary without the boundary anchor. The
-- third is the only one that is a fact about the data rather than a guess, and it costs
-- nothing that was carrying identity. Re-verified arithmetic, all against min 0.60:
--
--   page 1, whole            5+3+2+0+0 = 10/10 = 1.00  qualifies (unchanged: it capped at
--                                                      1.00 before too)
--   page 1, masthead lost      3+2+0+0 =  5/10 = 0.50  DOES NOT qualify — the boundary
--                                                      anchor is now necessary, not merely
--                                                      heaviest
--   page 2                       4+3+2 =  9/10 = 0.90  qualifies (untouched) — a two-page
--                                                      form still classifies on BOTH pages
--                                                      and stays ONE run
--   non-exclusive alone          2+0+0 =  2/10 = 0.20  cannot qualify on shared vocabulary
--
-- THE TRADE, stated: a masthead-cropped page 1 now falls out of SCHEDULE_E and reaches the
-- reviewer as an unclassified page instead of joining the previous borrower's document. A
-- page that announces it could not be identified is a fact a reviewer can act on; a
-- silently merged document is a confident wrong answer. That is the trade this project
-- makes every time. Note also that no committed fixture changes score by one point: page 1
-- was capped at 1.00 before and is exactly 1.00 now, page 2 is 0.90 either way. The whole
-- effect of the re-weighting is on the degraded case, which is where it belongs.
--
-- SEVEN anchors still, which also clears RlsCoverageIT's active-pack census filter: that
-- query counts only packs with jsonb_array_length(definition->'anchors') >= 4, so a thin
-- pack would silently vanish from the census and fail the assertion confusingly. A
-- zero-weight anchor is a full member of that array — dropping the two outright would have
-- thrown away recorded evidence for nothing.
--
-- NOTHING HERE ANCHORS ON GENERIC SCHEDULE FURNITURE. "Attachment Sequence",
-- "Department of the Treasury" and "(Form 1040)" are printed by every schedule and by
-- the tax_return fixture's own SCHEDULE 2 page; anchoring on any of them would
-- cross-qualify in exactly the opposite direction from the one tax_return@1.1.0 above
-- just fixed. The schedule-e-page regex needs the LETTER E and TAX_RETURN's
-- schedule-form-1040 needs a DIGIT, so neither can match the other's header:
-- "SCHEDULE 2 (Form 1040)" is not "Schedule E (Form 1040)".
--
-- WHY supplemental-income IS A SAFE BOUNDARY ID. PackageSplitter's AnchorRef keys on
-- (packType, anchorId) and deliberately DROPS packVersion, while splitting reads
-- PERSISTED classification evidence written by whatever pack version ran at the time.
-- An id reused across two versions of one pack could therefore make old evidence a
-- boundary retroactively. SCHEDULE_E@1.0.0 is a BIRTH — it supersedes nothing, no
-- earlier pack of this type exists, and no persisted evidence can carry the pair
-- ('SCHEDULE_E', 'supplemental-income'). The id is new because the pack is.
INSERT INTO classification_rule_pack
    (org_id, document_type_code, version, min_confidence, definition) VALUES
(NULL, 'SCHEDULE_E', '1.0.0', 0.6, '{
  "targetScore": 10,
  "anchors": [
    {"id": "supplemental-income", "kind": "literal", "pattern": "Supplemental Income and Loss",                         "weight": 5, "startsDocument": true},
    {"id": "partnerships-scorps", "kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations",  "weight": 4},
    {"id": "rental-real-estate",  "kind": "literal", "pattern": "Income or Loss From Rental Real Estate and Royalties", "weight": 3},
    {"id": "estates-trusts",      "kind": "literal", "pattern": "Income or Loss From Estates and Trusts",               "weight": 3},
    {"id": "schedule-e-page",     "kind": "regex",   "pattern": "(?i)Schedule E \\(Form 1040\\)",                       "weight": 2},
    {"id": "rents-received",      "kind": "literal", "pattern": "Rents received",                                       "weight": 0},
    {"id": "fair-rental-days",    "kind": "literal", "pattern": "Fair Rental Days",                                     "weight": 0}
  ]
}'::jsonb);

ALTER TABLE classification_rule_pack FORCE ROW LEVEL SECURITY;

-- ── §4 document_type: SCHEDULE_E (T8 fills here) ────────────────────────────
-- ONE NO FORCE dance on document_type, opened and closed by whichever task writes
-- into this section:
--
--   ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;
--   <INSERT ...>
--   ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- Supersedes Spec 3's design decision D4, which folded schedules into TAX_RETURN on the
-- reasoning that underwriting treats "the return" as one document. That was true of
-- CLASSIFICATION and false of EXTRACTION: a Schedule E has three rental properties in
-- side-by-side columns and three open-ended entity tables, none of which a 1040 schema
-- can express. A type of its own is what lets it carry its own schema.
--
-- There is NO foreign key from classification_rule_pack.document_type_code to
-- document_type.code (V6 declares it plain text NOT NULL), so §3's pack row legally
-- precedes this type row inside the same migration — which is what the root contract's
-- "one dance per table, never two" forces, since T7 already opened and closed §3's.
--
-- Late seed into a table V6 already FORCEd: the only INSERT policy is
-- WITH CHECK (org_id = current_org()), and NULL = <anything> is never TRUE, so no GUC
-- value can admit a global row — and FORCE binds the migration owner too. Hence the V10
-- dance: drop FORCE for the seed, restore before commit (same transaction, so no window
-- exists for any other session). A forgotten restore fails RlsCoverageIT's pg_class sweep
-- over relforcerowsecurity, with no per-table list to keep in sync.
ALTER TABLE document_type NO FORCE ROW LEVEL SECURITY;

INSERT INTO document_type (org_id, code, display_name, category) VALUES
    (NULL, 'SCHEDULE_E', 'Supplemental Income and Loss (Schedule E)', 'INCOME');

ALTER TABLE document_type FORCE ROW LEVEL SECURITY;

-- ── §5 extraction_schema: schedule_e@1.0.0 (T8 fills here) ──────────────────
-- ONE NO FORCE dance on extraction_schema:
--
--   ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;
--   <UPDATE ... SET is_active = false ...; INSERT ...>
--   ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
--
-- Required, not optional (the V10/V11 lesson): the only INSERT policy on these
-- tables is WITH CHECK (org_id = current_org()), and for a global (org_id NULL) row
-- `NULL = <anything>` is never TRUE — so no GUC value can admit it — while FORCE
-- ROW LEVEL SECURITY binds the migration owner too. The restore happens in the same
-- transaction, so no window exists for any other session, and RlsCoverageIT's
-- pg_class sweep fails the build if a restore is ever forgotten.
--
-- Versions are NEVER edited in place: extracted_field.schema_id is a foreign key to
-- the extraction_schema row that produced each stored value, so mutating a released
-- version would silently rewrite the meaning of every field already extracted.

-- The first schema with a repeating-group dimension, and the reason the dimension
-- exists. Twenty-nine fields across all four parts plus the Part V bottom line: eight
-- single-valued, five COLUMN-grouped on Part I's printed A/B/C property columns, and
-- sixteen ROW-grouped across four bounded tables — line 1a's stacked property addresses
-- and Parts II, III and IV's entity lists.
--
-- EVERY COLUMN CAPTION BELOW IS THE IRS'S OWN TEXT, checked word for word against the
-- blank f1040se.pdf. That sentence is here because the first version of this schema was
-- not: fixtures/generate.py could not fit the real captions on a 612 pt page, shortened
-- them, and the schema was then authored against the shortened text. Three separate
-- defects came out of that one habit, and all three are fixed here.
--
--   * WRONG LETTERS IN PART II. The schema declared "(g) Passive income",
--     "(h) Nonpassive loss" and "(j) Nonpassive income". The form prints
--     "(g) Passive loss allowed", "(h) Passive income", "(i) Nonpassive loss allowed",
--     "(j) Section 179 expense" and "(k) Nonpassive income". Every one of those three
--     columns was off by one or two letters, so none of them matched and all Part II
--     money came back MISSING on a real return.
--
--     THE TRAP, named so it is not sprung by the next correction: do NOT anchor on the
--     column LETTER, and do not loosen these to a "(j)" prefix. On the real form (j) is
--     the SECTION 179 DEDUCTION. A letter-only anchor would read that deduction as
--     nonpassive INCOME — a confident wrong number with a correct-looking evidence box,
--     feeding a qualifying-income calculation. The failure this replaced was safe
--     because it was missing; the careless fix for it is not.
--
--   * CAPTIONS THAT WRAP. "(d) Employer identification number" is not a line on the
--     form. "(d) Employer" prints on the caption row and "identification number" on the
--     row beneath it, and a literal is matched against ONE assembled line, so no literal
--     spanning the wrap can ever match. Same for Part IV's "(e) Income from Schedules
--     Q", whose tail is on the next row. Both now anchor on the caption's FIRST printed
--     line and nothing more. That is the general rule for this form: a column caption is
--     only as long as its first line.
--
--   * A CAPTION THAT CANNOT BE NAMED AT ALL. Part IV's (c) column is captioned "(c)
--     Excess inclusion from" directly above "Schedules Q, line 2c", in the same
--     x-range, close enough that line assembly merges the two rows into one visual line
--     — which, sorted by x, interleaves them word by word:
--
--       38 (a) Name (b) Employer (c) Schedules Excess inclusion Q , line from 2c ...
--
--     No literal can match a caption whose words a neighbour's words are threaded
--     through. remicExcessInclusion therefore keeps the caption the form actually
--     prints and comes back as ONE missing occurrence with a null key, which is the
--     required outcome: a column that cannot be identified yields MISSING, never the
--     number from the column beside it. It starts working, with no schema change, on
--     the day line assembly stops collapsing a stacked caption block into one line —
--     which is a parse-layer fix with its own blast radius across every document type,
--     and is filed rather than attempted here.
--
-- EIGHT THINGS ABOUT IT ARE DELIBERATE:
--
-- 1. THE MONEY PATTERN ADMITS A COMMA GROUP OR CENTS, NEVER A BARE INTEGER. Schedule E
--    prints whole dollars with commas ("44,400") and the form is dense with bare line
--    numbers — "20", "3", "19", "1040", "41" — sitting on the very lines the rungs read.
--    An unbounded \d+ would read a LINE NUMBER as an amount: a confident wrong value with
--    a plausible evidence box. The V11 dwellingCoverage precedent, applied to a form that
--    needs it far more. The cost is honest and recorded: an amount under $1,000 printed
--    without cents does not match. That is a missing field a reviewer can act on, which is
--    the trade this project makes every time.
--
-- 2. THE SIGN IS PART OF THE MATCH, IN EVERY SPELLING A RETURN PRINTS. Line 21 is income
--    OR (loss) per property, line 28(h) is a nonpassive loss, and the sign is the entire
--    meaning of the number: an eighteen-thousand-dollar rental LOSS booked as eighteen
--    thousand of INCOME is an underwriting error with a correct-looking evidence box.
--
--    The pattern therefore has FOUR alternatives, and their ORDER is load-bearing, because
--    a regex takes the leftmost match and a sign glyph sits to the LEFT of its digits:
--
--      1) \(\s*\$?\s*AMT\s*\)   accounting parentheses, with whitespace and a "$" INSIDE
--                               them. The paren pair is preprinted on the form and the
--                               amount typed between it, so the text layer hands back the
--                               paren and the digits as separate runs and SpanText joins
--                               them with a space: "( 18,470 )", "(18,470 )", "($18,470)"
--                               are all the SAME number as "(18,470)".
--      2) -\s*\$?\s*AMT         a leading minus, adjacent or spaced, the other convention.
--      3) \$?AMT-               a TRAILING minus. Captured DELIBERATELY even though the
--                               money normalizer does not read it: normalization then
--                               fails, the rung fails, and the occurrence goes MISSING.
--                               Declining to match it instead would leave the amount on
--                               the line and shift `occurrence` onto the NEXT number —
--                               a confident value from the wrong column, which is the one
--                               outcome worse than missing.
--      4) (?<![-(]\s{0,3})\$?AMT  the unsigned amount, and the negative lookbehind is what
--                               stops it from being reached past a sign glyph the three
--                               alternatives above could not consume (an unbalanced "(18,470"
--                               from a clipped scan). Unsigned digits sitting immediately
--                               after "-" or "(" are not a positive number; they are a
--                               number whose sign we could not read, and a rung that fails
--                               says so.
--
--    THE DEFECT THIS REPLACED, so it is not reintroduced: the alternatives were
--    "\((?:AMT)\)|\$?(?:AMT)" — not mutually exclusive and with no whitespace inside the
--    parentheses. Every spelling but the tightly-typeset "(18,470)" fell through to the
--    unsigned arm, which matched the DIGITS ALONE: the sign was dropped, and because
--    capture() draws the evidence box around the matched substring, the sign glyphs ended
--    up OUTSIDE the box a reviewer is shown. The fixture printed the one spelling that
--    worked, so the sign test passed while the defect was live — Spec 4's w2_form.pdf trap
--    (fixture and schema sharing one wrong assumption) reappearing one layer down. The
--    fixture now prints "( 18,470 )" and "($6,310)".
--
--    The pattern is AUTHORED ONCE, below, and stamped into all twenty-one MONEY fields with
--    replace() — see the note above the INSERT. Twenty-one hand-kept copies is not a style
--    question: a sign rule corrected in twenty of them is the same defect, quieter.
--
-- 3. LINE 1a IS A ROW GROUP, NOT A FOURTH COLUMN. The real form stacks the three
--    properties as ROWS under one caption and puts only the numeric lines in the A/B/C
--    columns. Forcing the addresses into the column group would be a schema describing a
--    layout the form does not have. maxRows is 3 because the form prints three property
--    rows; a borrower with more files another form, which is what the pack's
--    startsDocument anchor is for.
--
-- 4. EACH ROW REGION STARTS AT ITS PART'S HEADING, NOT AT ITS CAPTION ROW. The rung takes
--    the region's top from the start line's BOTTOM edge and then looks for the column
--    caption at or below that, so a start anchor sharing the caption's own line would
--    leave the column unlocatable and the whole field would come back as one null-keyed
--    MISSING. Every region's start and end print on the SAME page as its rows, for the
--    same reason: the rung looks for region.end among the lines of the page that carried
--    region.start, so a region cannot span a page break.
--
-- 5. THE ENTITY-NAME PATTERN READS A WHOLE NAME OR NONE OF IT. Parts II, III and IV name
--    legal entities, and the shapes they print are not the shapes a person's name takes:
--    "1ST CHOICE PROPERTIES LLC" opens with a DIGIT, "O'BRIEN FAMILY TRUST" carries an
--    APOSTROPHE, "Meridian Fixture Estate" is MIXED CASE because someone typed it into a
--    field, and "McALLISTER REMIC TRUST" hides a lowercase run inside an all-caps token.
--
--    THE DEFECT THIS REPLACED: the pattern was "[A-Z][A-Z&.-]*(?: [A-Z&][A-Z&.-]*){0,7}"
--    with a null normalizer — all caps, no apostrophe, no digit, no mixed case — bounded by
--    (?<![A-Za-z]). A value pattern is an unanchored find(), so on each of those four names
--    it did not fail: it matched a FRAGMENT. "ST CHOICE PROPERTIES LLC", "O", "Estate",
--    "ALLISTER REMIC TRUST". A fragment is a WRONG value, not a missing one — it names a
--    different legal entity — and a null normalizer answers certainty 1.0 to anything it is
--    handed, so each fragment persisted fully confident with an evidence box drawn around
--    part of a word. V7:158, V11:215 and V12:150 each pair their all-caps rung with a
--    mixed-case one and each include the apostrophe; this pattern is that established shape,
--    widened for the digit, and it is likewise AUTHORED ONCE and stamped into all three.
--
--    TWO changes make a partial capture cost something instead of passing silently:
--
--      * the boundaries are (?<!\S) ... (?!\S), whole-token on BOTH sides rather than
--        letter-only. A match can no longer begin or end in the MIDDLE of a word, which is
--        exactly how all four fragments above were manufactured. The residual — a name of
--        more than twelve tokens, past the {0,11} bound — is named here rather than
--        discovered later.
--      * the fields declare the entityName normalizer instead of null, so the captured text
--        is SCORED: certainty 1.0 for a clean whole name, 0.7 for one carrying characters
--        outside the entity-name set or for a degenerate single short token — the shape a
--        truncation leaves behind ("O", "LP"). This is personName's contract, which these
--        columns went out without.
--
-- 6. THE FIRST TOKEN MUST CONTAIN A LETTER. Without it the widened class would read the EIN
--    in the next column as a name the moment a cell boundary drifted — a confident wrong
--    value, which is the failure class this whole spec exists to stop.
--
-- 7. PART I'S COLUMN HEADER IS THE "Income:" ROW, NOT THE "Properties:" CAPTION ABOVE IT.
--    The rung locates the header anchor, takes THAT ANCHOR'S VISUAL LINE, and then needs
--    every declared key on it; if any key is absent the group yields no bands at all and
--    every occurrence goes MISSING. The form prints two separate lines —
--
--      Properties:                     <- a caption spanning the three columns
--      Income:   A      B      C       <- the printed keys
--
--    — so an anchor on "Properties:" resolves a line that carries no keys, and all five
--    Part I money fields returned MISSING on every real Schedule E while the fixture,
--    which drew both on ONE baseline, passed. The anchor is now the key row itself.
--
--    It is a REGEX, not a literal, for one reason: whether the colon arrives as part of
--    the "Income:" span or as its own span is a property of the PRODUCER, and spans are
--    joined with single spaces — so a filing that emits them separately reads "Income :".
--    "Income\s{0,2}:" matches both spellings; a literal matches whichever one it was
--    authored against and silently loses the other. The leading (?<!\S) keeps it a whole
--    token, and "Income" is the first colon-terminated "Income" on the page: the masthead
--    prints "Supplemental Income and Loss" and Part I's heading "Income or Loss From
--    Rental Real Estate and Royalties", neither with a colon.
--
--    An anchor that matched the WRONG line is still safe: the keys would not be found on
--    it, columnBands returns empty, and every occurrence is MISSING. Nothing here can
--    manufacture a band, which is why this anchor is allowed to be as short as it is.
--
-- 8. PARTS II AND III DECLARE THE FORM'S PREPRINTED ROW LETTERS (rowLabels), AND THE
--    LETTER — NOT A COUNT — IS THE ROW KEY. The real form prints each of these tables
--    TWICE: an upper sub-table under the entity captions ((a) name, (b) code, (d) EIN,
--    checkboxes) and a lower sub-table under the money captions, with the row letters
--    A-D (Part II) / A-B (Part III) preprinted in the left-margin gutter of BOTH. One
--    counted row origin can only serve one band: resolved per group, the name fields
--    read their rows from the MONEY sub-table, and on a real document the caption
--    continuation line and the money rows became phantom "name" occurrences at 0.63-0.90
--    confidence with real evidence boxes — partnershipName[08]/[09] and
--    estateOrTrustName[01] captured text matching NOTHING in the answer key. The letters
--    are the join key the form itself provides (design D2, the same rule that keys
--    Part I's columns by the printed A/B/C): name row A and money row A are the same
--    entity BY THE FORM'S OWN LABELING. Each field reads the rows under its OWN caption
--    line, keys each row by the letter at the row's left edge, and a row whose letter
--    cannot be read is MISSING under its declared letter — never keyed by position,
--    because a positional fallback would silently revert to the misalignment the labels
--    remove. Part IV is NOT lettered on the real form and keeps the counted ordinal,
--    as do line 1a and every unlabeled table (the K-1s inherit that scheme unchanged).
--
-- WHAT IS DELIBERATELY NOT HERE, so the next author does not "finish the job":
--
--   * Line 22 (deductible rental real estate loss) is omitted. On a real form its value
--     overlays a preprinted "( )" pair whose interior is literal space glyphs, so the text
--     layer yields "( 1 8 , 5 9 0 )" — the digits themselves broken apart, which is a
--     different thing from the "( 18,590 )" the money pattern now reads, and no money
--     pattern can match it in any reading direction without also matching noise. That is a
--     tokenization defect in the parse layer, not a schema problem, and inventing a
--     whitespace-stripping value pattern here would paper over it. Filed, not fixed.
--   * A ROW group reads at most ONE page of rows: the rung returns the first page whose
--     region yields rows, which is what keeps two pages of the same table from colliding
--     on row ordinal 01. A table continued onto a second sheet is Spec 5c's problem, and
--     naming it here is cheaper than rediscovering it.
--   * Part II's (b) partnership/S-corporation code and (c)/(e)/(f) checkboxes are drawn on
--     the fixture but not extracted: a single-letter value pattern would match the row's
--     first capital in any column whose header box drifted, and the type of entity is
--     derivable from the name suffix until a checkbox rung earns its place.
--   * Part IV's (b) EIN and (d) taxable income are not extracted. (d)'s caption
--     interleaves with the row beneath it exactly as (c)'s does, so it is unnameable for
--     the same reason; (b) is nameable but is an identifier the REMIC name already
--     carries, and adding a field whose only justification is symmetry is how a schema
--     grows rows nobody reads.
--
-- WHAT THE MONEY PATTERN'S AMOUNT ALTERNATION IS NOT ALLOWED TO BECOME, checked again
-- against the blank form rather than assumed: it stays "a comma group or exact cents",
-- never a bare integer. Widening it to \d+ is tempting because a real return prints
-- whole dollars, and it is exactly wrong here. The totals rungs are ANCHOR_LABEL with
-- scope LINE_RIGHT and NO column band to confine them, and every total line on the form
-- carries its own line number in the gutter between the label and the amount:
--
--   32 Total partnership and S corporation income or (loss). Combine lines 30 and 31 . 32
--
-- With a bare-integer alternative, occurrence 0 of that line is "32" — the LINE NUMBER
-- reported as the total, at full confidence, with an evidence box drawn around it. The
-- same page also prints 8582, 4562, 6198, 1040, 1065, 1041, 4835 and the tax year 2025.
-- The cost of not widening is recorded and accepted: an amount under $1,000 printed
-- without cents does not match, and goes MISSING where a reviewer can act on it.
--
-- Late-seed RLS dance, the V10 pattern: extraction_schema has been FORCEd since V7 and its
-- only INSERT policy is WITH CHECK (org_id = current_org()) — NULL = <anything> is never
-- TRUE, so no GUC value can admit a global row, and FORCE binds the migration owner too.
-- Drop FORCE for the duration, restore before commit (same transaction, so no window
-- exists for any other session). A forgotten restore fails RlsCoverageIT's pg_class sweep.
ALTER TABLE extraction_schema NO FORCE ROW LEVEL SECURITY;

-- ONE AUTHORING OF EACH REPEATED PATTERN. The money pattern belongs to twenty-one fields,
-- the entity-name pattern to three, and the taxpayer-name pattern to one field's TWO rungs;
-- each is written ONCE here and stamped into the JSON with replace(), so the seeded rows
-- carry twenty-six copies that CANNOT have drifted from each other. Twenty-one
-- hand-maintained copies of a sign rule is not a style question — it is a defect waiting
-- for the correction that lands in twenty. (ScheduleESeedPatternIT reads the SEEDED
-- definition back and asserts the copies are byte-identical, which is what catches a
-- hand-edit that reintroduces a per-field copy; asserting on this file would only prove that
-- replace() was typed.)
--
-- Substitution order matters: @MONEY@ goes in first and itself contains @AMT@, which the next
-- replace() fills. Backslashes are literal in a SQL string under standard_conforming_strings,
-- so "\\d" here is the two characters JSON needs to yield the regex \d — the same escaping the
-- surrounding literal already uses.
INSERT INTO extraction_schema (org_id, document_type_code, version, definition) VALUES
(NULL, 'SCHEDULE_E', '1.0.0',
 replace(
   replace(
     replace(
       replace('{
  "fields": [
    {"name": "taxpayerName", "dataType": "STRING", "required": true,
     "normalizer": "personName", "sensitive": false,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name(s) shown on return"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "@PERSON@",
                  "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Name(s) shown on return"},
        "value": {"pattern": "@PERSON@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxpayerSsn", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": true,
     "extractors": [
       {"method": "LABEL_BELOW", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "maxDropPt": 24.0, "cellOverlap": 0.5,
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])", "occurrence": 0}},
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Your social security number"},
        "value": {"pattern": "(?<![\\d-])\\d{3}-\\d{2}-\\d{4}(?![\\d-])",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},
    {"name": "taxYear", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "(Form 1040)"},
        "value": {"pattern": "(?<!\\d)20\\d{2}(?!\\d)", "occurrence": 0, "scope": "LINE_RIGHT"}}]},

    {"name": "propertyAddress", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Rental Real Estate and Royalties"},
                          "end":   {"kind": "literal", "pattern": "Type of Property"}},
               "maxRows": 3},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "Physical address of each property"},
        "value": {"pattern": "(?<!\\d)\\d{1,6} [A-Z][A-Z0-9 ,.#/-]*[A-Z0-9](?![A-Za-z0-9])",
                  "occurrence": 0}}]},

    {"name": "rentsReceived", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "COLUMN",
               "header": {"kind": "regex", "pattern": "(?<!\\S)Income\\s{0,2}:"},
               "keys": ["A", "B", "C"]},
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Rents received"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE"}}]},
    {"name": "mortgageInterest", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "COLUMN",
               "header": {"kind": "regex", "pattern": "(?<!\\S)Income\\s{0,2}:"},
               "keys": ["A", "B", "C"]},
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Mortgage interest paid to banks"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE"}}]},
    {"name": "depreciationExpense", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "COLUMN",
               "header": {"kind": "regex", "pattern": "(?<!\\S)Income\\s{0,2}:"},
               "keys": ["A", "B", "C"]},
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Depreciation expense or depletion"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE"}}]},
    {"name": "totalExpenses", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "COLUMN",
               "header": {"kind": "regex", "pattern": "(?<!\\S)Income\\s{0,2}:"},
               "keys": ["A", "B", "C"]},
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total expenses. Add lines 5 through 19"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE"}}]},
    {"name": "incomeOrLoss", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "COLUMN",
               "header": {"kind": "regex", "pattern": "(?<!\\S)Income\\s{0,2}:"},
               "keys": ["A", "B", "C"]},
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Subtract line 20 from line 3"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE"}}]},

    {"name": "totalRentalRealEstateIncomeOrLoss", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total rental real estate and royalty income or (loss)"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},

    {"name": "partnershipName", "dataType": "STRING", "required": true,
     "normalizer": "entityName", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
        "value": {"pattern": "@ENTITY@",
                  "occurrence": 0}}]},
    {"name": "partnershipEin", "dataType": "STRING", "required": true,
     "normalizer": null, "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(d) Employer"},
        "value": {"pattern": "(?<![\\d-])\\d{2}-\\d{7}(?![\\d-])", "occurrence": 0}}]},
    {"name": "partnershipPassiveLossAllowed", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(g) Passive loss allowed"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "partnershipPassiveIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(h) Passive income"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "partnershipNonpassiveLossAllowed", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(i) Nonpassive loss allowed"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "partnershipSection179Expense", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(j) Section 179 expense"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "partnershipNonpassiveIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Partnerships and S Corporations"},
                          "end":   {"kind": "literal", "pattern": "Total partnership and S corporation"}},
               "maxRows": 20,
               "rowLabels": ["A", "B", "C", "D"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(k) Nonpassive income"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "partnershipAndSCorpTotal", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total partnership and S corporation income or (loss)"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},

    {"name": "estateOrTrustName", "dataType": "STRING", "required": true,
     "normalizer": "entityName", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Estates and Trusts"},
                          "end":   {"kind": "literal", "pattern": "Total estate and trust"}},
               "maxRows": 20,
               "rowLabels": ["A", "B"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
        "value": {"pattern": "@ENTITY@",
                  "occurrence": 0}}]},
    {"name": "estateOrTrustPassiveDeductionOrLoss", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Estates and Trusts"},
                          "end":   {"kind": "literal", "pattern": "Total estate and trust"}},
               "maxRows": 20,
               "rowLabels": ["A", "B"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(c) Passive deduction or loss allowed"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "estateOrTrustPassiveIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Estates and Trusts"},
                          "end":   {"kind": "literal", "pattern": "Total estate and trust"}},
               "maxRows": 20,
               "rowLabels": ["A", "B"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(d) Passive income"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "estateOrTrustDeductionOrLoss", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Estates and Trusts"},
                          "end":   {"kind": "literal", "pattern": "Total estate and trust"}},
               "maxRows": 20,
               "rowLabels": ["A", "B"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(e) Deduction or loss"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "estateOrTrustOtherIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Estates and Trusts"},
                          "end":   {"kind": "literal", "pattern": "Total estate and trust"}},
               "maxRows": 20,
               "rowLabels": ["A", "B"]},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(f) Other income from"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "estateAndTrustTotal", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total estate and trust income or (loss)"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},

    {"name": "remicName", "dataType": "STRING", "required": true,
     "normalizer": "entityName", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Real Estate Mortgage Investment Conduits"},
                          "end":   {"kind": "literal", "pattern": "Combine columns (d) and (e) only"}},
               "maxRows": 10},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(a) Name"},
        "value": {"pattern": "@ENTITY@",
                  "occurrence": 0}}]},
    {"name": "remicExcessInclusion", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Real Estate Mortgage Investment Conduits"},
                          "end":   {"kind": "literal", "pattern": "Combine columns (d) and (e) only"}},
               "maxRows": 10},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(c) Excess inclusion from"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "remicIncome", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "group": {"kind": "ROW",
               "region": {"start": {"kind": "literal", "pattern": "Income or Loss From Real Estate Mortgage Investment Conduits"},
                          "end":   {"kind": "literal", "pattern": "Combine columns (d) and (e) only"}},
               "maxRows": 10},
     "extractors": [
       {"method": "ROW_CELL", "strength": 0.9,
        "columnHeader": {"kind": "literal", "pattern": "(e) Income from"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0}}]},
    {"name": "remicTotal", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Combine columns (d) and (e) only"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]},

    {"name": "totalIncomeOrLoss", "dataType": "MONEY", "required": true,
     "normalizer": "money", "sensitive": false,
     "extractors": [
       {"method": "ANCHOR_LABEL", "strength": 0.9,
        "label": {"kind": "literal", "pattern": "Total income or (loss). Combine lines 26, 32, 37, 39, and 40"},
        "value": {"pattern": "@MONEY@",
                  "occurrence": 0, "scope": "LINE_RIGHT"}}]}
  ]
}',
       -- Four alternatives, in the order the leftmost-match rule needs them: parenthesised
       -- (whitespace and "$" INSIDE), leading minus, trailing minus (matched so it can FAIL
       -- normalization rather than shift `occurrence` onto the next number), and last the
       -- unsigned amount, which a negative lookbehind keeps from being reached past a sign
       -- glyph none of the first three could consume.
       '@MONEY@',
       '(?<![\\d,.])(?:\\(\\s*\\$?\\s*(?:@AMT@)\\s*\\)|-\\s*\\$?\\s*(?:@AMT@)|\\$?(?:@AMT@)-|(?<![-(]\\s{0,3})\\$?(?:@AMT@))(?![\\d,.])'),
     -- A comma group or exact cents, never a bare integer: the form is dense with line
     -- numbers sitting on the very lines the rungs read.
     '@AMT@',
     '\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?|\\d+\\.\\d{2}'),
     -- Whole-token boundaries on BOTH sides, so a name the pattern cannot fully cover comes
     -- back as nothing rather than as its tail; a first token that must contain a LETTER, so a
     -- drifted cell cannot hand back the EIN column as a name; letters of any script, digits,
     -- ampersand, period, comma, apostrophe and hyphen, which is what these columns print.
     '@ENTITY@',
     '(?<!\\S)[\\p{L}\\p{N}]*[\\p{L}][\\p{L}\\p{N}&.,''-]*(?: [\\p{L}\\p{N}&][\\p{L}\\p{N}&.,''-]*){0,11}(?!\\S)'),
   -- The identity block's caption is "Name(s) shown on return" — the FORM says the value
   -- may be two people. One person, then OPTIONALLY one conjunction ("and" or "&") and a
   -- second whole person, so a joint return's name is captured WHOLE instead of losing the
   -- second spouse at 0.9. A person is a first name, up to TWO middle tokens — each an
   -- initial ("Q.") or a SPELLED word ("Quinn"), because real returns print both — and an
   -- optional surname. A middle slot that admitted only initials read a spelled middle as
   -- the SURNAME and stopped: "First Middle" persisted at 0.9 with the true surname
   -- dropped, and nothing looked wrong to a reviewer.
   --
   -- What bounds the capture, now that "Your" (the SSN caption shares the printed line and
   -- its first word is shaped exactly like a name word) fits the widened slots: the word
   -- AFTER it. A caption's first word drags its own lowercase text behind it ("social
   -- security number"), while a true final surname is followed by the caption's capital,
   -- the SSN digits, or nothing. The final lookahead refuses to END the match where a
   -- lowercase word follows — except "and", the conjunction the pattern itself consumes —
   -- so a greedy attempt to swallow "Your" backtracks off it, and the letter boundaries on
   -- BOTH ends still forbid starting or stopping inside a word.
   '@PERSON@',
   '(?<![A-Za-z])[A-Z][a-z]+(?: (?:[A-Z]\\.?|[A-Z][a-z]+)){0,2}(?: [A-Z][a-z]+)?(?: (?:and|&) [A-Z][a-z]+(?: (?:[A-Z]\\.?|[A-Z][a-z]+)){0,2}(?: [A-Z][a-z]+)?)?(?![A-Za-z])(?! (?!and\\b)[a-z])')::jsonb);

-- (§5 complete: schedule_e@1.0.0 seeded inside this ONE dance; the FORCE restore below
-- closes it. Corrections always ship as new schema versions, never as edits — every
-- extracted_field points at the schema row that produced it through schema_id.)
ALTER TABLE extraction_schema FORCE ROW LEVEL SECURITY;
