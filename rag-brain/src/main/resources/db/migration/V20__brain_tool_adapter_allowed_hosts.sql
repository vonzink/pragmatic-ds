ALTER TABLE brain_tool_adapters
    ADD COLUMN allowed_hosts JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Existing enabled adapters predate host allowlists. Disable them until an admin sets allowed_hosts explicitly.
UPDATE brain_tool_adapters
SET enabled = false
WHERE enabled = true
  AND jsonb_array_length(allowed_hosts) = 0;

ALTER TABLE brain_tool_adapters
    ADD CONSTRAINT chk_brain_tool_adapters_allowed_hosts
    CHECK (
        CASE
            WHEN jsonb_typeof(allowed_hosts) = 'array'
                THEN enabled = false OR jsonb_array_length(allowed_hosts) > 0
            ELSE false
        END
    );
