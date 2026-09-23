-- Live operational knobs (spec §5). Missing keys fall back to env defaults,
-- so an empty table behaves exactly like the pre-settings system.
CREATE TABLE brain_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(100) NOT NULL
);
