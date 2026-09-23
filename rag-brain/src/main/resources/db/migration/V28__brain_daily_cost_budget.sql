-- V28: per-brain daily LLM spend cap (design: per-brain spend-cap circuit breaker).
-- Opt-in and OFF by default: a null budget means "use the global default", and a
-- budget of 0 (or an unset global default) means unlimited. DB-backed daily usage
-- so the cap is enforced correctly across multiple app instances.

-- Per-brain override of the global daily budget. Nullable: null => fall back to
-- ragbrain.rag.cost.daily-budget-usd.
ALTER TABLE brains ADD COLUMN daily_cost_budget_usd NUMERIC(10,4);

-- One row per (brain, day). Accumulated by SpendGuardService.recordSpend via an
-- UPSERT after every successful paid model call.
CREATE TABLE brain_daily_usage (
    brain_id           UUID NOT NULL REFERENCES brains (id) ON DELETE CASCADE,
    usage_date         DATE NOT NULL,
    request_count      INT NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT NOT NULL DEFAULT 0,
    completion_tokens  BIGINT NOT NULL DEFAULT 0,
    cost_estimate_usd  NUMERIC(12,6) NOT NULL DEFAULT 0,
    PRIMARY KEY (brain_id, usage_date)
);
