package com.pragmaticds.docengine.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase 1 tenant binding: every request runs as one fixed development organization.
 *
 * <p>⚠️ This is a PLACEHOLDER for the OIDC JWT {@code org_id} claim (Phase 7), the same shape as
 * host-app's {@code LocalDevSecurityConfig} — and carries the same warning: it must NEVER be
 * active in a deployed environment. The app module registers it only outside the {@code prod}
 * profile; Phase 7 replaces it with real principal resolution.
 */
public class DevTenantFilter extends OncePerRequestFilter {

    /** The org seeded by AbstractPostgresIT and local bootstrap. */
    public static final UUID DEV_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            TenantContext.set(DEV_ORG);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
