package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.platform.security.Role;
import com.pragmaticds.docengine.platform.tenancy.DevTenantFilter;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local/test authentication WITHOUT an IdP: binds a full {@link AuthPrincipal} (and, from it,
 * {@code TenantContext}) so the existing IT suite keeps working untouched, while letting RBAC and
 * cross-tenant tests drive the matrix via headers.
 *
 * <p>Folds in what {@code DevTenantFilter} used to do — the two must never both bind an org — but
 * now binds a PRINCIPAL, not just a tenant. Defaults (no headers): org = {@code DEV_ORG}, a fixed
 * dev user, role = {@code docengine.dev.role} (default {@code ADMIN}, so every existing upload/read
 * IT stays authorized). Overridable per request:
 *
 * <ul>
 *   <li>{@code X-Dev-Org} — act as another org (cross-tenant tests)
 *   <li>{@code X-Dev-Role} — act with another role (RBAC matrix tests)
 *   <li>{@code X-Dev-User} — act as another user id
 * </ul>
 *
 * <p>⚠️ Bound to the {@code local}/{@code test} profiles only. It trusts request headers with no
 * token — it must NEVER be active in a deployed environment. The non-local profile uses the JWT
 * resource-server chain instead.
 */
public class DevAuthFilter extends OncePerRequestFilter {

    public static final UUID DEV_ORG = DevTenantFilter.DEV_ORG;
    /** A stable dev user id so audit rows and principal identity are deterministic in tests. */
    public static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final Role defaultRole;

    public DevAuthFilter(Role defaultRole) {
        this.defaultRole = defaultRole;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID orgId = header(request, "X-Dev-Org", DEV_ORG, UUID::fromString);
        UUID userId = header(request, "X-Dev-User", DEV_USER, UUID::fromString);
        Role role = header(request, "X-Dev-Role", defaultRole, Role::valueOf);

        AuthPrincipal principal =
                AuthPrincipal.user(orgId, userId, "dev:" + userId, role);
        try {
            TenantContext.set(orgId);
            AuthContext.set(principal);
            var authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            "N/A",
                            List.of(new SimpleGrantedAuthority(role.authority())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            AuthContext.clear();
            TenantContext.clear();
        }
    }

    private static <T> T header(
            HttpServletRequest request,
            String name,
            T fallback,
            java.util.function.Function<String, T> parse) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return parse.apply(value.trim());
    }
}
