package com.pragmaticds.docengine.platform.security;

import com.pragmaticds.docengine.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The {@code app_user} row (V1__extensions_and_tenancy.sql), mapping the columns the authentication
 * paths need to build an {@link AuthPrincipal}.
 *
 * <p>Historically read-only: users were provisioned out-of-band by the IdP integration. The
 * delegated service path adds ONE write — {@link #provisioned} — because requiring a DBA to insert a
 * row before a loan officer can correct a typo is permission management in a second place, and a
 * second place is where permissions go stale. Creation only: a delegated call never updates an
 * existing user, so a role set deliberately in the engine is never silently overwritten by traffic.
 *
 * <p>Extends {@link TenantScopedEntity}, so {@code org_id} is a {@code @TenantId} column: the
 * lookup that resolves a user runs INSIDE the org taken from the verified JWT {@code org_id}
 * claim (see JwtAuthPrincipalConverter). It never spans tenants — it is bound to exactly the
 * claim's org before the query runs.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends TenantScopedEntity {

    @Column(name = "external_subject", nullable = false, updatable = false)
    private String externalSubject;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "status", nullable = false)
    private String status;

    protected AppUser() {}

    /**
     * A user provisioned on first sight, for the delegated service path.
     *
     * <p><b>The role is a parameter, and the caller must take it from ENGINE configuration — never
     * from the request.</b> That is the whole safety property of auto-provisioning: the calling
     * service asserts WHO is acting, the engine decides WHAT they may do. A service that could name
     * both would be able to mint an ADMIN, which is privilege escalation wearing an identity claim.
     *
     * <p>{@code org_id} is not a parameter at all — {@link TenantScopedEntity} fills it from the
     * bound {@code TenantContext}, which on the delegated path is the api-key's own org. A caller
     * therefore cannot create a user in someone else's tenant even by trying.
     */
    public static AppUser provisioned(
            String externalSubject, String email, String displayName, Role role) {
        AppUser user = new AppUser();
        user.externalSubject = externalSubject;
        user.email = email;
        user.displayName = displayName;
        user.role = role.name();
        user.status = "ACTIVE";
        return user;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The raw {@code role} text; {@link #roleEnum()} parses it against the known {@link Role} set. */
    public String getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /**
     * The parsed role, or empty if the stored text is not one of the known roles. Parsing here
     * rather than throwing keeps an unexpected value from becoming a 500 — the caller decides how
     * to fail (fail-closed, in the converter).
     */
    public java.util.Optional<Role> roleEnum() {
        try {
            return java.util.Optional.of(Role.valueOf(role));
        } catch (IllegalArgumentException | NullPointerException e) {
            return java.util.Optional.empty();
        }
    }
}
