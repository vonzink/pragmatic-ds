-- V8: remove the transitional brain_id column DEFAULT added in V7. Every writer
-- now sets brain_id explicitly (Phase 3a), so the safety net is no longer needed
-- and dropping it makes any future writer that omits brain_id fail loudly.
ALTER TABLE brain_documents       ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE brain_document_chunks ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_conversations      ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_messages           ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_answer_sources     ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_audit_logs         ALTER COLUMN brain_id DROP DEFAULT;

-- NOTE: brain_id stays NOT NULL. rule_revisions is intentionally NOT brain-scoped
-- in this milestone — co-resident brains share one owner-editable rules layer;
-- per-brain rule editing (a brain_id on rule_revisions) is deferred.
