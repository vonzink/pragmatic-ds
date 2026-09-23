CREATE TABLE brain_tool_adapter_runs (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id         UUID        NOT NULL REFERENCES brains (id) ON DELETE CASCADE,
    tool_name        VARCHAR(120) NOT NULL,
    mode             VARCHAR(40) NOT NULL,
    target_host      VARCHAR(255),
    http_method      VARCHAR(12) NOT NULL,
    status           VARCHAR(40) NOT NULL,
    http_status_code INTEGER,
    duration_ms      BIGINT      NOT NULL,
    session_id       VARCHAR(255),
    user_id          VARCHAR(255),
    tenant_id        VARCHAR(255),
    error_type       VARCHAR(160),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tool_adapter_runs_brain_created
    ON brain_tool_adapter_runs (brain_id, created_at DESC);

CREATE INDEX idx_tool_adapter_runs_status_created
    ON brain_tool_adapter_runs (status, created_at DESC);
