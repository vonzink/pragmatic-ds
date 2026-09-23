-- V5 — Phase 3 page signals + one entity-mapping gap. Columns only: every table
-- touched here already carries org_id + FORCE RLS from V4, so no policy changes.

-- Duplicate-page detection (docs/IMPLEMENTATION_PLAN.md Phase 3): within a
-- package, pages sharing a non-null content_hash mark all but the FIRST
-- (package_page_index order) with the id of that first page. NULL = not a
-- duplicate. V4 already has content_hash / is_blank / blank_score; this is the
-- only signal column that was missing.
ALTER TABLE page ADD COLUMN duplicate_of_page_id uuid REFERENCES page (id);

-- layout_element was created schema-only in V4 without updated_at, but its
-- Phase 3 entity extends TenantScopedEntity, which maps the column on every
-- tenant entity — the same trade V2 documents for source_file and V4 for
-- parser_output: carrying the column beats a read-only remapping hack.
ALTER TABLE layout_element ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
