-- V45: per-brain answer-confidence floor. Nullable: null => no gate (the
-- pre-V45 behaviour). Distinct from retrieval_confidence_threshold (V29), which
-- gates the top chunk score BEFORE the model runs: this one gates the model's
-- own self-reported confidence AFTER the answer comes back, which is the number
-- the dashboard shows on every answer. Without it a brain served answers the
-- model rated 0.30 as "grounded" because nothing ever compared that number.
ALTER TABLE brain_profiles ADD COLUMN answer_confidence_floor DOUBLE PRECISION;
