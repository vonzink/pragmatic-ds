-- Public website access must be explicitly published with an allowed-domain
-- list. Earlier profiles defaulted public_enabled to true; that made an empty
-- domain list effectively token-only. Existing empty-domain profiles are
-- unpublished until an admin saves domains again.
ALTER TABLE brain_profiles
    ALTER COLUMN public_enabled SET DEFAULT FALSE;

UPDATE brain_profiles
SET public_enabled = FALSE,
    updated_at = now()
WHERE public_enabled = TRUE
  AND allowed_domains = '[]'::jsonb;
