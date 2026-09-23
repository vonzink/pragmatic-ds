package com.pragmaticds.docengine.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller for one request: who they are, which org they belong to, and what they
 * may do. Immutable by construction.
 *
 * <p>{@code orgId} is the single source of tenant truth — it drives {@code TenantContext}, which in
 * turn drives both Hibernate {@code @TenantId} filtering and the Postgres RLS GUC. Principal
 * binding and tenant binding therefore cannot disagree: there is one org, set once.
 *
 * @param orgId the caller's organization; never null (a request with no resolvable org is rejected)
 * @param userId the {@code app_user} row id; null for {@link ActorType#SYSTEM} and
 *     {@link ActorType#API_KEY}
 * @param subject the external identity (OIDC {@code sub}, api-key id, or a system tag); never null
 * @param role the human role; null for {@link ActorType#API_KEY} and {@link ActorType#SYSTEM}
 * @param actorType the kind of caller
 * @param scopes granted scopes (API keys); empty for humans and system tasks
 */
public record AuthPrincipal(
        UUID orgId,
        UUID userId,
        String subject,
        Role role,
        ActorType actorType,
        Set<String> scopes) {

    public AuthPrincipal {
        if (orgId == null) {
            throw new IllegalArgumentException("AuthPrincipal requires an orgId");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("AuthPrincipal requires a subject");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** A human user principal. */
    public static AuthPrincipal user(UUID orgId, UUID userId, String subject, Role role) {
        return new AuthPrincipal(orgId, userId, subject, role, ActorType.USER, Set.of());
    }

    public boolean hasRole(Role required) {
        return role != null && role.includes(required);
    }
}
