package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guarantees the request-scoped contexts are torn down on the JWT path. The
 * {@code JwtAuthPrincipalConverter} sets {@code TenantContext}/{@code AuthContext} as a side effect
 * of authenticating; this filter sits OUTSIDE the bearer-token filter so its finally runs whether
 * authentication succeeds, fails, or authorization later denies — no principal or tenant ever
 * survives onto a pooled request thread.
 */
public class ContextClearingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
            TenantContext.clear();
        }
    }
}
