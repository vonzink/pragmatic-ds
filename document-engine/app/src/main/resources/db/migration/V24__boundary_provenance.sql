-- V24 — Phase B of the Reducto-parity roadmap
-- (docs/superpowers/plans/2026-08-22-reducto-parity-roadmap.md). Additive: one column, one CHECK,
-- one targeted backfill. No table, no policy, no seed — so no NO FORCE dance and no census move.
--
-- WHY: a document's page boundary can be decided four different ways, and today the row does not
-- say which. A reviewer looking at a two-page bank statement cannot tell whether the machine PROVED
-- the cut (a pack anchor declaring a form header), merely INFERRED it (the page type changed), or
-- whether a human placed it. That distinction is about to matter much more than it does now:
-- boundary extraction (Phase D) will add cuts a model proposed, and the standing precedence rule is
-- human > pack anchor > type change > AI. A queue that cannot sort by how a boundary was decided
-- cannot put the weakest ones in front of a reviewer first.
--
-- THE VOCABULARY, strongest evidence first:
--   HUMAN         a reviewer placed or reshaped this document. Outranks everything; the machine
--                 never re-guesses what a human decided (Spec 2 D4).
--   RULE          a page matched an anchor a pack declared "startsDocument": true — POSITIVE proof
--                 of a printed form header (Spec 5a D6).
--   PACKAGE_START this document opens the package. Not a split at all, and worth saying so: on a
--                 single-document upload every document is PACKAGE_START, which is a different
--                 fact from "we inferred a boundary here".
--   TYPE_CHANGE   the page type changed between two typed pages. Real evidence, weaker than an
--                 anchor: it cannot see a boundary between two documents of the SAME type.
--   AI            reserved for Phase D. No producer writes it yet; it is declared here so the
--                 CHECK does not need a second DROP-then-ADD when that lands.
--
-- NULLABLE, and deliberately not defaulted. A row written before this migration carries no record
-- of why its boundary fell where it did, and inventing one would be exactly the confident-wrong
-- answer this engine refuses everywhere else: NULL reads as "recorded before provenance existed",
-- which is true, where 'TYPE_CHANGE' for all of them would be a fabricated audit trail.
ALTER TABLE logical_document ADD COLUMN boundary_provenance text;

ALTER TABLE logical_document ADD CONSTRAINT logical_document_boundary_provenance_check
    CHECK (boundary_provenance IS NULL
           OR boundary_provenance IN ('HUMAN', 'RULE', 'PACKAGE_START', 'TYPE_CHANGE', 'AI'));

-- The ONE backfill that is a deduction rather than a guess. `classification_confidence IS NULL` is
-- written by exactly two paths, both human: LogicalDocument.humanRegroup() nulls it when a reviewer
-- reshapes a document, and RegroupService constructs a reviewer-created document with a null
-- confidence. The splitter can never produce one — group() substitutes BigDecimal.ZERO for a page
-- with no confidence, so every machine-made document has a number. So a null confidence today
-- PROVES a human made this document, and the row may say so.
--
-- Everything else keeps NULL provenance. A pre-V24 machine document could have been cut by an
-- anchor or by a type change and the row does not know which; a re-split (SPLITTING is idempotent —
-- it deletes and recreates a package's documents) will stamp it correctly on its next run.
UPDATE logical_document
   SET boundary_provenance = 'HUMAN'
 WHERE classification_confidence IS NULL;

COMMENT ON COLUMN logical_document.boundary_provenance IS
    'How this document''s starting boundary was decided: HUMAN | RULE | PACKAGE_START | '
    'TYPE_CHANGE | AI. NULL for rows written before V24. Precedence, strongest first: '
    'HUMAN > RULE > TYPE_CHANGE > AI (PACKAGE_START is structural, not inferred).';
