-- V29: per-brain retrieval confidence floor. Nullable: null => fall back to the
-- global runtime setting (retrieval.confidence-threshold) and then the env default
-- (RETRIEVAL_CONFIDENCE_THRESHOLD, 0.35). Lets a small/calibrated corpus stop
-- over-escalating without changing the global default for every brain.
ALTER TABLE brain_profiles ADD COLUMN retrieval_confidence_threshold DOUBLE PRECISION;
