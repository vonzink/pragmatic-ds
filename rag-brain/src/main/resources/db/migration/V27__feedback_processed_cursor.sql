-- V27: idempotency cursor for the learning job.
--
-- The scheduled learning job (SourceWeightLearningService.adjust) previously
-- re-read the SAME feedback every run (a 30-day createdAt window with no
-- consumed-marker), so one vote burst ratcheted a weight to its clamp bound over
-- successive runs and re-emitted a PENDING review event forever (a rejected
-- proposal reappeared next hour). This adds a per-row consumed marker so each
-- feedback row is aggregated by the job exactly once.
--
-- Metadata-only add: nullable column, no backfill. Existing rows stay NULL
-- (unprocessed) and are consumed on the next run, which is the intended
-- one-time catch-up; the bounded per-run delta + clamp keep that safe.

ALTER TABLE rag_answer_feedback ADD COLUMN processed_at TIMESTAMPTZ;

-- The job selects unprocessed rows per brain: WHERE brain_id = ? AND processed_at IS NULL.
CREATE INDEX idx_answer_feedback_brain_processed
    ON rag_answer_feedback (brain_id, processed_at);
