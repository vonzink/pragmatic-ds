CREATE TABLE brain_tool_adapters (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id              UUID          NOT NULL REFERENCES brains (id) ON DELETE CASCADE,
    tool_name             VARCHAR(120)  NOT NULL,
    enabled               BOOLEAN       NOT NULL DEFAULT FALSE,
    http_method           VARCHAR(12)   NOT NULL,
    url_template          VARCHAR(2000) NOT NULL,
    auth_mode             VARCHAR(40)   NOT NULL DEFAULT 'NONE',
    secret_ref            VARCHAR(160),
    api_key_header        VARCHAR(160),
    static_headers        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    request_body_template JSONB         NOT NULL DEFAULT '{}'::jsonb,
    timeout_ms            INTEGER       NOT NULL DEFAULT 5000,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(100)  NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by            VARCHAR(100)  NOT NULL,
    CONSTRAINT uq_brain_tool_adapters_brain_tool UNIQUE (brain_id, tool_name),
    CONSTRAINT chk_brain_tool_adapters_method CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT chk_brain_tool_adapters_auth CHECK (auth_mode IN ('NONE', 'BEARER_TOKEN', 'API_KEY_HEADER')),
    CONSTRAINT chk_brain_tool_adapters_timeout CHECK (timeout_ms >= 500 AND timeout_ms <= 30000)
);

CREATE INDEX idx_brain_tool_adapters_brain_enabled
    ON brain_tool_adapters (brain_id, enabled);
