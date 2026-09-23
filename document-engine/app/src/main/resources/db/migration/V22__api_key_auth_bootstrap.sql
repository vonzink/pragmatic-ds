-- V22 — service-authentication bootstrap for the api_key table (Spec 6 §6a.2).
-- Seed-free and additive: V1 through V21 remain immutable migration history.
--
-- THE PROBLEM. The api_key table (V1) is FORCE ROW LEVEL SECURITY with policy
-- api_key_isolation USING (org_id = current_org()). That is correct for the
-- app's OWN tenant-scoped access to keys — but it makes the table unusable for
-- AUTHENTICATION, which is a chicken-and-egg: to authenticate an inbound
-- X-DocEngine-Api-Key header we must look the key up BY ITS HASH to LEARN which
-- org it belongs to, and only then can we stamp app.current_org. At lookup time
-- no tenant is bound, current_org() is NULL, and a FORCE-RLS'd api_key returns
-- ZERO rows for EVERY connection — the owner included. Auth can never begin.
--
-- THE FIX, in two parts:
--   1. NO FORCE (keep ENABLE + the policy). FORCE only ever affected the TABLE
--      OWNER connection; the app connects as the non-owner docengine_app role,
--      so dropping FORCE leaves app-layer tenant isolation on api_key completely
--      unchanged — docengine_app is still fully governed by api_key_isolation.
--      What NO FORCE buys is that the OWNER (the Flyway/migration role, which
--      also owns the SECURITY DEFINER function below) once again bypasses RLS on
--      this one table.
--   2. A SECURITY DEFINER lookup function owned by that same owner. It executes
--      with the owner's privileges, so — because of step 1 — it bypasses RLS and
--      can resolve a key on an unstamped connection. This is the ONE place the
--      resolve-org-from-the-key bootstrap is allowed to see across the RLS
--      boundary, and it is deliberately narrow.
--
-- WHY THIS IS SAFE. Knowledge of the EXACT hash — which is derived from the
-- secret key the caller presents — IS the authorization; the org is resolved
-- FROM the key, not asserted by the caller. The function takes a single exact
-- hash and can only ever return the row for that one hash: it has no wildcard,
-- no range, no "list all keys" shape, so it cannot be used to enumerate keys or
-- browse another tenant's key material. This mirrors host-app's partner_api_key
-- no-RLS auth-bootstrap table, where the org is likewise learned from the key.
--
-- WHAT STAYS RLS-SCOPED. Only the resolve-by-hash SELECT bypasses RLS. Every
-- OTHER access to api_key — including the last_used_at stamp on use — is a normal
-- RLS-governed statement run by docengine_app AFTER the org is known and
-- app.current_org is stamped, so it is covered by api_key_isolation like any
-- other tenant write. The stamp is deliberately NOT done inside this function.

ALTER TABLE api_key NO FORCE ROW LEVEL SECURITY;

-- Resolve a single api_key row by its exact key_hash, bypassing RLS.
--
-- SECURITY DEFINER + owned by the migration/table owner = runs with the owner's
-- privileges. Combined with the NO FORCE above, the owner bypasses RLS on
-- api_key, so this returns the row even on an unstamped (no-tenant) connection —
-- exactly what the auth filter needs before it can learn the org. STABLE: no
-- writes, result depends only on the argument and table contents within a
-- statement. search_path is pinned to a trusted, minimal value so a caller
-- cannot shadow `api_key` with an object on their own search_path — the standard
-- hardening for any SECURITY DEFINER function.
--
-- The function returns the raw lifecycle columns (expires_at, revoked_at) rather
-- than pre-judging them: the application decides expiry/revocation so that the
-- reason a key is rejected never leaks back to the caller (all failures collapse
-- to one opaque 401). It never returns key_hash.
CREATE FUNCTION api_key_authenticate(p_key_hash text)
    RETURNS TABLE (
        id         uuid,
        org_id     uuid,
        scopes     text[],
        expires_at timestamptz,
        revoked_at timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
        SELECT id, org_id, scopes, expires_at, revoked_at
        FROM api_key
        WHERE key_hash = p_key_hash
    $$;

-- Least privilege: no one may execute this by default; only the application role
-- (which fabricates the API_KEY principal) is granted execute. Revoking from
-- PUBLIC first is the standard belt-and-braces for a SECURITY DEFINER function.
REVOKE ALL ON FUNCTION api_key_authenticate(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION api_key_authenticate(text) TO docengine_app;
