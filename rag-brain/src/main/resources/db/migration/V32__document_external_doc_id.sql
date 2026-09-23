-- V32: the corpus-declared stable document identifier (frontmatter
-- `document_id:`, e.g. suite_page_pipeline_board). Nullable: null means the
-- source file declared none — every pre-existing row and every non-markdown
-- upload. Ingestion reads it the same way it already reads `visibility:`.
--
-- Why persist it: it is the id the corpus retrieval cases assert against, so it
-- is the only key that lets a retrieval eval join a retrieved chunk back to the
-- document that was supposed to answer. File names drift on upload (pages/x.md
-- flattens to page-x.md) and titles are prose, so neither is a reliable join.
--
-- Deliberately NOT unique: the same logical doc can legitimately exist in more
-- than one brain, and a partial/duplicate id must never fail an ingest.
ALTER TABLE brain_documents ADD COLUMN external_doc_id VARCHAR(200);

-- Eval + admin lookups filter by brain then id.
CREATE INDEX idx_brain_documents_external_doc_id
    ON brain_documents (brain_id, external_doc_id)
    WHERE external_doc_id IS NOT NULL;
