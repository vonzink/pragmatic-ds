-- V24: index the citation-trail foreign keys.
-- ai_answer_sources.document_id and chunk_id are ON DELETE SET NULL but had no
-- index, so deleting/reindexing a document fired a per-chunk sequential scan of
-- the whole (chat-volume-sized) citation table. These indexes turn each SET NULL
-- into an index lookup. Denormalized snapshot columns preserve the citation
-- display after the source row is gone.
CREATE INDEX IF NOT EXISTS idx_answer_sources_chunk ON ai_answer_sources (chunk_id);
CREATE INDEX IF NOT EXISTS idx_answer_sources_document ON ai_answer_sources (document_id);
