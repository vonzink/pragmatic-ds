package com.pragmaticds.docengine.platform.security;

import java.util.Optional;

/**
 * Holder for the current request's authenticated principal — the security twin of
 * {@code TenantContext}.
 *
 * <p>A ThreadLocal is used rather than Spring's {@code SecurityContextHolder} so feature modules
 * that must not depend on the servlet security stack can still read the caller. The auth layer
 * populates BOTH this and {@code TenantContext} from the same {@link AuthPrincipal#orgId()}, and
 * clears both in a finally, so they are always consistent for the life of a request.
 *
 * <p>Fail-closed: {@link #require()} throws when nothing is bound. Code that reads a principal
 * "successfully" on an unauthenticated thread is code that would act without an identity.
 */
public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> CURRENT = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(AuthPrincipal principal) {
        CURRENT.set(principal);
    }

    public static Optional<AuthPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static AuthPrincipal require() {
        AuthPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException("no principal bound to this thread");
        }
        return principal;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
