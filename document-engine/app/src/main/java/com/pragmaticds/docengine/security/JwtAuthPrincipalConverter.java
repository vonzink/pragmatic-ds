package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.security.AppUser;
import com.pragmaticds.docengine.platform.security.AppUserRepository;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.platform.security.Role;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a validated OIDC access token into an {@link AuthPrincipal} for the non-local profiles.
 *
 * <p>The JWT's {@code org_id} claim is the trust anchor: it is bound to {@code TenantContext}
 * BEFORE any database access, so the {@code app_user} lookup runs inside the caller's own tenant
 * (RLS GUC + {@code @TenantId}) rather than spanning tenants. Resolving the row yields the userId
 * and role. Every failure is fail-closed — a missing/blank/malformed {@code org_id}, a subject
 * with no provisioned user, an inactive user, or an unrecognized role all raise
 * {@link InvalidBearerTokenException} (401). No token value, claim value, or subject is placed in
 * an exception message — descriptions are generic.
 *
 * <p>Only active outside {@code local}/{@code test}: those profiles authenticate via
 * {@code DevAuthFilter}, not a real token.
 */
@Component
@Profile("!local & !test")
public class JwtAuthPrincipalConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserRepository users;

    public JwtAuthPrincipalConverter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID orgId = requireOrg(jwt.getClaimAsString("org_id"));
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("token has no subject");
        }

        // Bind the tenant from the verified claim before touching the database.
        TenantContext.set(orgId);

        AppUser user =
                users.findByOrgIdAndExternalSubject(orgId, subject)
                        .orElseThrow(() -> new InvalidBearerTokenException("principal not provisioned"));
        if (!user.isActive()) {
            throw new InvalidBearerTokenException("principal not active");
        }
        Role role =
                user.roleEnum()
                        .orElseThrow(() -> new InvalidBearerTokenException("principal role invalid"));

        AuthPrincipal principal = AuthPrincipal.user(orgId, user.getId(), subject, role);
        AuthContext.set(principal);
        return new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(role.authority())), subject);
    }

    private static UUID requireOrg(String claim) {
        if (claim == null || claim.isBlank()) {
            throw new InvalidBearerTokenException("token has no org_id claim");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("token has a malformed org_id claim");
        }
    }
}
