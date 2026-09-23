package com.pragmaticds.docengine.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The subset of an {@code api_key} row that authentication needs, as returned by the {@code
 * api_key_authenticate} SECURITY DEFINER lookup (V22). Never carries {@code key_hash} — the filter
 * already holds the hash it looked up by, and the row must not echo secret material.
 *
 * <p>{@code expiresAt} and {@code revokedAt} are the RAW lifecycle columns, not a verdict: the
 * filter judges expiry/revocation itself so every rejection reason collapses to one opaque 401.
 *
 * @param id the {@code api_key} row id (used to stamp {@code last_used_at})
 * @param orgId the tenant the key belongs to — the trust anchor bound to {@code TenantContext}
 * @param scopes granted scope strings; mapped to engine roles by the filter
 * @param expiresAt expiry instant, or null for a non-expiring key
 * @param revokedAt revocation instant, or null if the key is not revoked
 */
public record ApiKeyRecord(
        UUID id, UUID orgId, List<String> scopes, Instant expiresAt, Instant revokedAt) {}
