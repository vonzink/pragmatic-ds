-- V25: standalone created_at indexes so the opt-in ops-data retention job can
-- prune old rows by an efficient index range scan instead of a sequential scan.
-- ai_audit_logs already has idx_audit_logs_created (V1); the other append-only
-- ops tables only had composite (brain_id/client_id, created_at) indexes, which
-- a global "created_at < cutoff" delete can't use effectively.
CREATE INDEX IF NOT EXISTS idx_rag_traces_created ON rag_traces (created_at);
CREATE INDEX IF NOT EXISTS idx_connector_events_created ON brain_connector_events (created_at);
CREATE INDEX IF NOT EXISTS idx_tool_adapter_runs_created ON brain_tool_adapter_runs (created_at);
