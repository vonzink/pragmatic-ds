package com.pragmaticds.docengine.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Resolves the {@code app_user} row for an authenticated subject.
 *
 * <p>The lookup carries {@code orgId} explicitly — the house rule (never a bare {@code findBy…}
 * that could cross tenants). At call time {@code TenantContext} is already bound to that same org
 * (taken from the JWT {@code org_id} claim), so {@code @TenantId} and the RLS GUC both scope the
 * query to it as well; the explicit {@code orgId} makes the intent legible and independent of the
 * ambient binding.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByOrgIdAndExternalSubject(UUID orgId, String externalSubject);
}
