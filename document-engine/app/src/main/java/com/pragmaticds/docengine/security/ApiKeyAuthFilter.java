package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.security.ActorType;
import com.pragmaticds.docengine.platform.security.AppUser;
import com.pragmaticds.docengine.platform.security.AppUserRepository;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.AuthPrincipal;
import com.pragmaticds.docengine.platform.security.Role;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service authentication for the non-local profiles: the LOS (and other machine
 * consumers) authenticate to the engine with an API key in {@code X-DocEngine-Api-Key}, ALONGSIDE
 * the OIDC bearer path that authenticates human operators (Spec 6 §6a.2).
 *
 * <p>Wired into {@code jwtSecurityChain} only, OUTSIDE {@link ContextClearingFilter} and BEFORE the
 * bearer-token filter. It binds the same {@link AuthPrincipal} shape the JWT path builds — {@code
 * TenantContext} first, then {@code AuthContext} and the Spring {@code SecurityContext} — so RBAC,
 * {@code @TenantId}, the RLS GUC stamp, and audit all work downstream unchanged. Teardown is not
 * this filter's job: {@link ContextClearingFilter} clears both contexts in a {@code finally} on
 * every outcome.
 *
 * <h2>Behavior</h2>
 *
 * <ul>
 *   <li>No {@code X-DocEngine-Api-Key} header → pass through untouched; a human operator's bearer
 *       token is handled by the filter after this one.
 *   <li>Header present, but no salt configured → 401 (fail-closed; see {@link ApiKeyHasher}).
 *   <li>Header present → hash it, resolve by hash. Unknown, revoked, or expired → an identical
 *       opaque 401 that never says which; the presented key is never logged.
 *   <li>Resolved and live, but no scope maps to a {@link Role} → 401. A principal that can do
 *       nothing is not authenticated; letting it in would only 403 on every route.
 *   <li>Header present AND {@code X-DocEngine-Acting-User} present → the DELEGATED path: the call is
 *       made on behalf of a named human. Requires the key to hold {@code ACT_AS_USER}; binds that
 *       person's own userId and role, never the key's scopes. See {@link #delegate}.
 *   <li>Otherwise: bind the principal (ActorType {@link ActorType#API_KEY}, the key's scopes), grant
 *       {@code ROLE_*} for every scope that names a Role AND {@code SCOPE_<name>} for every scope it
 *       carries, stamp {@code last_used_at} best-effort, and continue. The scope authorities are how
 *       a key can hold a capability narrower than any role — see {@link #withScopeAuthorities}. The
 *       api-key path is terminal — the SecurityContext is already set, so the bearer filter finds an
 *       authenticated request and does nothing even if a token was also presented.
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** The header the LOS presents its key in. */
    public static final String API_KEY_HEADER = "X-DocEngine-Api-Key";

    /**
     * The header naming the HUMAN a delegated call is made on behalf of, carrying that person's
     * {@code app_user.external_subject}. Present only on the service-to-service path.
     */
    public static final String ACTING_USER_HEADER = "X-DocEngine-Acting-User";

    /**
     * The scope a key must hold before it may name an acting user. Deliberately NOT a {@link Role}
     * name, so it grants no authority of its own — it only unlocks delegation, and the authority
     * still comes from the named human's own role.
     */
    public static final String SCOPE_ACT_AS_USER = "ACT_AS_USER";

    /** The acting person's email — required only when a first-sight user is being provisioned. */
    public static final String ACTING_EMAIL_HEADER = "X-DocEngine-Acting-Email";

    /** The acting person's display name — optional, used only at provisioning time. */
    public static final String ACTING_NAME_HEADER = "X-DocEngine-Acting-Name";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final ApiKeyHasher hasher;
    private final ApiKeyRepository repository;
    private final AppUserRepository users;

    /**
     * The role a first-sight delegated user is created with, or null to require pre-provisioning.
     * Configuration, deliberately — see {@link AppUser#provisioned}.
     */
    private final Role autoProvisionRole;

    private final Clock clock;

    public ApiKeyAuthFilter(
            ApiKeyHasher hasher,
            ApiKeyRepository repository,
            AppUserRepository users,
            Role autoProvisionRole) {
        this(hasher, repository, users, autoProvisionRole, Clock.systemUTC());
    }

    ApiKeyAuthFilter(
            ApiKeyHasher hasher,
            ApiKeyRepository repository,
            AppUserRepository users,
            Role autoProvisionRole,
            Clock clock) {
        this.hasher = hasher;
        this.repository = repository;
        this.users = users;
        this.autoProvisionRole = autoProvisionRole;
        this.clock = clock;
    }

    /** A header's trimmed value, or null when absent or blank. */
    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(API_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            // No key presented — let the bearer/JWT path decide. Do NOT touch any context.
            chain.doFilter(request, response);
            return;
        }

        // A key was presented but service auth is not configured: refuse rather than fall through
        // to the bearer path (which would 401 anyway) — an unsalted hash must never authenticate.
        if (!hasher.isConfigured()) {
            unauthorized(response);
            return;
        }

        Optional<ApiKeyRecord> resolved = repository.resolveByHash(hasher.hash(presented.trim()));
        if (resolved.isEmpty() || !isLive(resolved.get())) {
            // Unknown, revoked, or expired: one opaque answer. Never reveal which, never log the key.
            unauthorized(response);
            return;
        }
        ApiKeyRecord key = resolved.get();

        String actingUser = request.getHeader(ACTING_USER_HEADER);
        if (actingUser != null && !actingUser.isBlank()) {
            delegate(key, actingUser.trim(), request, response, chain);
            return;
        }

        List<GrantedAuthority> roles = rolesFromScopes(key.scopes());
        if (roles.isEmpty()) {
            // A key whose scopes name no engine role can authorize nothing — reject rather than bind
            // a principal that 403s on every route. This check reads ROLES ONLY, deliberately, and
            // must keep doing so: a key holding SCOPE_ENGINE_RESULT_READ and nothing else would
            // otherwise bind on the strength of a scope authority and reach exactly one matcher,
            // which is a second, undocumented way to be authenticated.
            unauthorized(response);
            return;
        }
        List<GrantedAuthority> authorities = withScopeAuthorities(roles, key.scopes());

        Set<String> scopes = new LinkedHashSet<>(key.scopes());
        AuthPrincipal principal =
                new AuthPrincipal(
                        key.orgId(), null, "apikey:" + key.id(), null, ActorType.API_KEY, scopes);

        // TenantContext FIRST — so the last_used_at stamp below (and everything downstream) runs
        // under the key's org, with the RLS GUC stamped to it.
        TenantContext.set(key.orgId());
        AuthContext.set(principal);
        var authentication = new UsernamePasswordAuthenticationToken(principal, "N/A", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        stampLastUsed(key);

        chain.doFilter(request, response);
    }

    /**
     * A call the LOS makes ON BEHALF OF a named human — the console's field-correction path.
     *
     * <p><b>The trust boundary, stated plainly.</b> The engine cannot verify the person; it verifies
     * the SERVICE and takes the service's word for WHO. That is a real delegation of trust and it is
     * bounded three ways, each of which must hold:
     *
     * <ol>
     *   <li>the key must carry {@link #SCOPE_ACT_AS_USER} — impersonation is a granted capability,
     *       not something every key can do;
     *   <li>the named subject must resolve to an ACTIVE {@code app_user} <b>in the key's own org</b>,
     *       so one tenant's service can never name another tenant's person;
     *   <li>authority comes from THAT PERSON'S role, never from the key's scopes. A key scoped
     *       {@code PROCESSOR} still yields a REVIEWER principal when it names a reviewer, and yields
     *       nothing a READONLY user could not do when it names one.
     * </ol>
     *
     * <p>Point 3 is why this is safer than the obvious alternative of minting the LOS a REVIEWER-scoped
     * key: that would let every LOS request act as a reviewer, with {@code decided_by} naming a
     * machine. Here the key stays least-privileged and every correction is attributable to a person.
     *
     * <p>The principal is {@link ActorType#USER} because a human genuinely made the decision, and
     * {@code audit_event.actor_type} is constrained to USER/SYSTEM/API_KEY. Which service relayed it
     * belongs in the audit detail, not in the actor taxonomy.
     *
     * <p>Fail-closed and NEVER fall back: a key without the scope is 403, an unknown/inactive/
     * unroleable subject is an opaque 401. Falling back to the key's own identity would silently turn
     * a refused impersonation into an anonymous machine write, which is precisely the outcome
     * {@code FieldCorrectionService} refuses by requiring a userId.
     */
    private void delegate(
            ApiKeyRecord key,
            String actingSubject,
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {
        if (!key.scopes().contains(SCOPE_ACT_AS_USER)) {
            forbidden(response);
            return;
        }
        // The tenant must be bound before the lookup: app_user is @TenantId-filtered and RLS-governed,
        // so an unstamped connection sees no rows. Every failure path below clears it again — this
        // filter short-circuits without calling the chain, so ContextClearingFilter would not run.
        TenantContext.set(key.orgId());
        AppUser actor;
        try {
            actor = users.findByOrgIdAndExternalSubject(key.orgId(), actingSubject).orElse(null);
        } catch (RuntimeException lookupFailed) {
            TenantContext.clear();
            throw lookupFailed;
        }
        if (actor == null && autoProvisionRole != null) {
            // First sight of a person the calling service already authenticated. Creating the row
            // here is the alternative to maintaining the same permission in a second system, which
            // is where permissions go stale. The role is OURS (configuration), never the caller's.
            String email = header(request, ACTING_EMAIL_HEADER);
            if (email == null) {
                // app_user.email is NOT NULL and a synthesized address would be a lie in the audit
                // trail. Refuse rather than invent an identity detail.
                TenantContext.clear();
                unauthorized(response);
                return;
            }
            try {
                actor =
                        users.save(
                                AppUser.provisioned(
                                        actingSubject,
                                        email,
                                        header(request, ACTING_NAME_HEADER),
                                        autoProvisionRole));
                log.info(
                        "provisioned an app_user on first delegated use role={} org={}",
                        autoProvisionRole,
                        key.orgId());
            } catch (RuntimeException raced) {
                // Two concurrent first calls: the unique (org_id, external_subject) index wins and
                // one insert fails. Re-read rather than fail the request the loser was making.
                actor = users.findByOrgIdAndExternalSubject(key.orgId(), actingSubject).orElse(null);
            }
        }
        if (actor == null || !actor.isActive() || actor.roleEnum().isEmpty()) {
            // One opaque answer for all three: never reveal whether a subject exists.
            TenantContext.clear();
            unauthorized(response);
            return;
        }
        Role role = actor.roleEnum().orElseThrow();
        AuthPrincipal principal =
                AuthPrincipal.user(key.orgId(), actor.getId(), actingSubject, role);
        AuthContext.set(principal);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                "N/A",
                                List.of(new SimpleGrantedAuthority(role.authority()))));
        stampLastUsed(key);
        chain.doFilter(request, response);
    }

    private boolean isLive(ApiKeyRecord key) {
        if (key.revokedAt() != null) {
            return false;
        }
        Instant expiresAt = key.expiresAt();
        return expiresAt == null || expiresAt.isAfter(clock.instant());
    }

    /**
     * The key's role authorities PLUS one {@code SCOPE_<name>} authority per scope it carries.
     *
     * <p>This exists so a machine consumer can be granted a capability NARROWER than any role. The
     * concrete case: a service that only ever GETs parsed envelopes needed {@code ROLE_ADMIN} to
     * pass the raw-text matchers, and ADMIN also opens every {@code /v1/**} write — a real
     * over-grant. A scope authority lets one matcher name one capability without moving the key up
     * a role.
     *
     * <p><b>The scope name is granted VERBATIM</b> (trimmed, never case-folded), so
     * {@code ENGINE_RESULT_READ} yields {@code SCOPE_ENGINE_RESULT_READ} and nothing else does. An
     * exact match is the point: a matcher naming a scope must be impossible to satisfy by accident,
     * and case-folding would quietly make {@code engine_result_read} equivalent.
     *
     * <p>A scope authority is ADDITIVE and never subtracts: every role authority the key already had
     * is kept, so an ADMIN key behaves exactly as it did. It also confers nothing on its own —
     * authority arrives only where a matcher names that exact scope, and the empty-roles rejection
     * upstream still refuses a key that holds scopes but no role.
     *
     * <p>{@link #SCOPE_ACT_AS_USER} is granted here like any other scope. That is deliberate and
     * inert: no matcher names it, and delegation is gated by reading {@code key.scopes()} directly
     * in {@link #delegate}, not by an authority. A delegated principal never receives these
     * authorities at all — it carries the named person's role, which is the whole point of that path.
     */
    private static List<GrantedAuthority> withScopeAuthorities(
            List<GrantedAuthority> roles, List<String> scopes) {
        // Insertion-ordered and de-duplicated: a key may legitimately repeat a scope, and a
        // duplicate authority is noise in every log line and test assertion that prints them.
        Set<String> names = new LinkedHashSet<>();
        for (GrantedAuthority role : roles) {
            names.add(role.getAuthority());
        }
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            names.add("SCOPE_" + scope.trim());
        }
        List<GrantedAuthority> authorities = new ArrayList<>(names.size());
        for (String name : names) {
            authorities.add(new SimpleGrantedAuthority(name));
        }
        return List.copyOf(authorities);
    }

    /** One {@code ROLE_*} authority for every scope that names a {@link Role}; unknown scopes drop. */
    private static List<GrantedAuthority> rolesFromScopes(List<String> scopes) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            try {
                Role role = Role.valueOf(scope.trim());
                authorities.add(new SimpleGrantedAuthority(role.authority()));
            } catch (IllegalArgumentException ignored) {
                // A scope that is not an engine role grants no role authority. Not an error: keys may
                // carry scopes this service does not model.
            }
        }
        return authorities;
    }

    private void stampLastUsed(ApiKeyRecord key) {
        try {
            repository.touchLastUsed(key.id());
        } catch (RuntimeException e) {
            // Best-effort telemetry, never a reason to fail an authenticated request. The class name
            // (no key, no hash) is enough to notice a systemic problem.
            log.debug("api-key last_used_at stamp failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * The key is authentic but not permitted to act as a person — distinct from 401 on purpose. The
     * caller proved who IT is; what it may not do is speak for someone else, and a 401 would send an
     * integrator hunting a credential problem that does not exist.
     */
    private static void forbidden(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"FORBIDDEN\",\"status\":403}");
    }

    /** A generic 401 with no hint about why. Body carries no key, hash, org, or reason detail. */
    private static void unauthorized(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"UNAUTHENTICATED\",\"status\":401}");
    }
}
