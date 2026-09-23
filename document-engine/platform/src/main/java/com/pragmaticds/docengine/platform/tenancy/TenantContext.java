package com.pragmaticds.docengine.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holder for the current request's organization — the value Hibernate's {@code @TenantId} stamps on
 * writes and filters on reads.
 *
 * <p>Fail-closed: {@link #require()} throws when no org is bound. Code that "just works" without a
 * tenant is code that is leaking across tenants.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID orgId) {
        CURRENT.set(orgId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID orgId = CURRENT.get();
        if (orgId == null) {
            throw new IllegalStateException("no tenant bound to this thread");
        }
        return orgId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
