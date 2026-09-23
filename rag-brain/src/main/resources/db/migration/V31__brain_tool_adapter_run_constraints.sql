ALTER TABLE brain_tool_adapter_runs
    ADD CONSTRAINT chk_brain_tool_adapter_runs_duration
        CHECK (duration_ms >= 0),
    ADD CONSTRAINT chk_brain_tool_adapter_runs_mode
        CHECK (mode IN ('READ', 'WRITE', 'NAVIGATE')),
    ADD CONSTRAINT chk_brain_tool_adapter_runs_status
        CHECK (status IN ('SUCCEEDED', 'FAILED', 'CONFIRMATION_REQUIRED', 'STUBBED')),
    ADD CONSTRAINT chk_brain_tool_adapter_runs_http_method
        CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    ADD CONSTRAINT chk_brain_tool_adapter_runs_http_status_code
        CHECK (http_status_code IS NULL OR http_status_code BETWEEN 100 AND 599);
