-- V23: track the env-config fingerprint the default brain was last reconciled to.
-- DefaultBrainSeeder only re-applies env config when this fingerprint changes, so
-- restarts no longer silently revert admin edits made through the dashboard.
-- Nullable, no default -> metadata-only add, safe on a non-empty table.
ALTER TABLE brains ADD COLUMN env_config_fingerprint VARCHAR(64);
