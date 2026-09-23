package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.platform.security.ActorType;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link ApiKeyAuthFilter} against a mocked hasher + lookup: the header-absent pass-through, every
 * fail-closed 401 (no salt, unknown, revoked, expired, no usable scope), and the happy path that
 * binds an {@link ActorType#API_KEY} principal with the right org and role authority and stamps
 * {@code last_used_at}. Also proves no raw key or hash ever reaches the response.
 */
class ApiKeyAuthFilterTest {

    private static final UUID ORG = UUID.fromString("0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a");
    private static final UUID KEY_ID = UUID.fromString("0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b");
    private static final String RAW_KEY = "pds_live_SUPER_SECRET_raw_key_value";
    private static final String HASH = "cafef00d".repeat(8);
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private final ApiKeyHasher hasher = mock(ApiKeyHasher.class);
    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final com.pragmaticds.docengine.platform.security.AppUserRepository users =
            mock(com.pragmaticds.docengine.platform.security.AppUserRepository.class);
    private final ApiKeyAuthFilter filter =
            new ApiKeyAuthFilter(
                    hasher, repository, users, null, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AuthContext.clear();
        TenantContext.clear();
    }

    private static MockHttpServletRequest withKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, RAW_KEY);
        return request;
    }

    private static ApiKeyRecord key(List<String> scopes, Instant expiresAt, Instant revokedAt) {
        return new ApiKeyRecord(KEY_ID, ORG, scopes, expiresAt, revokedAt);
    }

    @Test
    void an_absent_header_passes_through_untouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(); // no key header
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain proceeded to the bearer path").isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(AuthContext.current()).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void a_present_key_with_no_salt_configured_is_rejected_and_binds_nothing() throws Exception {
        when(hasher.isConfigured()).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void an_unknown_key_is_rejected() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void a_revoked_key_is_rejected() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), null, NOW.minusSeconds(60))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void an_expired_key_is_rejected() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), NOW.minusSeconds(1), null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void a_key_whose_scopes_map_to_no_role_is_rejected() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("documents:read", "SOMETHING_ELSE"), null, null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void a_key_with_empty_scopes_is_rejected() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH)).thenReturn(Optional.of(key(List.of(), null, null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void a_valid_key_binds_an_api_key_principal_with_the_right_org_and_role() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), NOW.plusSeconds(3600), null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(chain.getRequest()).as("request continued the chain").isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);

        var principal = AuthContext.require();
        assertThat(principal.actorType()).isEqualTo(ActorType.API_KEY);
        assertThat(principal.orgId()).isEqualTo(ORG);
        assertThat(principal.userId()).isNull();
        assertThat(principal.role()).isNull();
        assertThat(principal.subject()).isEqualTo("apikey:" + KEY_ID);
        assertThat(principal.scopes()).containsExactly("PROCESSOR");
        assertThat(TenantContext.current()).contains(ORG);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                // The role authority AND the scope authority for the same scope: SCOPE_* is
                // granted per scope so a matcher can name a capability narrower than any role.
                .containsExactlyInAnyOrder("ROLE_PROCESSOR", "SCOPE_PROCESSOR");
        // last_used_at is stamped for exactly this key id.
        verify(repository).touchLastUsed(KEY_ID);
    }

    /**
     * The two authority families, and the line between them. A scope that names a Role still
     * becomes a {@code ROLE_*} authority and a scope that does not still becomes none — that half
     * is unchanged and load-bearing, because {@code not-a-role} turning into {@code ROLE_not-a-role}
     * would put an unknown string into the role namespace the matrix is written in. What is new is
     * that EVERY scope, role-naming or not, also becomes a verbatim {@code SCOPE_*} authority.
     */
    @Test
    void role_authorities_come_only_from_role_scopes_but_every_scope_becomes_a_scope_authority()
            throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(
                        Optional.of(
                                key(List.of("READONLY", "not-a-role", "ADMIN"), null, null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        "ROLE_READONLY",
                        "ROLE_ADMIN",
                        "SCOPE_READONLY",
                        "SCOPE_not-a-role",
                        "SCOPE_ADMIN");
    }

    /**
     * The scope authority must never become a second way to AUTHENTICATE. A key holding only
     * {@code ENGINE_RESULT_READ} names no Role, so the filter refuses to bind it at all — if it
     * bound, the principal would carry {@code SCOPE_ENGINE_RESULT_READ} and reach exactly the five
     * raw-text matchers, which is the surface the scope exists to gate rather than to open.
     */
    @Test
    void a_read_scope_without_a_role_scope_still_refuses_to_bind() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("ENGINE_RESULT_READ"), null, null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).as("the chain never continued").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * The scope name is granted VERBATIM — trimmed of surrounding whitespace, never case-folded.
     * An exact match is the point: a matcher naming {@code SCOPE_ENGINE_RESULT_READ} must be
     * impossible to satisfy with {@code engine_result_read}.
     */
    @Test
    void a_scope_authority_is_verbatim_and_trimmed_never_case_folded() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(
                        Optional.of(
                                key(
                                        List.of("READONLY", "  ENGINE_RESULT_READ  ", "engine_result_read"),
                                        null,
                                        null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        "ROLE_READONLY",
                        "SCOPE_READONLY",
                        "SCOPE_ENGINE_RESULT_READ",
                        "SCOPE_engine_result_read");
    }

    @Test
    void a_non_expiring_unrevoked_key_is_live() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("READONLY"), null, null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AuthContext.require().orgId()).isEqualTo(ORG);
    }

    @Test
    void a_stamp_failure_does_not_fail_the_authenticated_request() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), null, null)));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(repository)
                .touchLastUsed(any());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        assertThat(chain.getRequest()).as("request still authenticated and continued").isNotNull();
        assertThat(AuthContext.require().orgId()).isEqualTo(ORG);
    }

    @Test
    void no_rejection_response_ever_echoes_the_raw_key_or_its_hash() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKey(), response, chain);

        String body = response.getContentAsString();
        assertThat(body).doesNotContain(RAW_KEY).doesNotContain(HASH);
        assertThat(body).contains("UNAUTHENTICATED");
    }

    // ── Delegation: a service call made on behalf of a named human ───────────────────────

    private static final String ACTING_SUBJECT = "cognito-sub-reviewer";
    private static final UUID ACTING_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private static MockHttpServletRequest withKeyActingAs(String subject) {
        MockHttpServletRequest request = withKey();
        request.addHeader(ApiKeyAuthFilter.ACTING_USER_HEADER, subject);
        return request;
    }

    private com.pragmaticds.docengine.platform.security.AppUser actor(String role, boolean active) {
        com.pragmaticds.docengine.platform.security.AppUser user =
                mock(com.pragmaticds.docengine.platform.security.AppUser.class);
        when(user.getId()).thenReturn(ACTING_USER_ID);
        when(user.isActive()).thenReturn(active);
        when(user.roleEnum())
                .thenReturn(
                        role == null
                                ? Optional.empty()
                                : Optional.of(
                                        com.pragmaticds.docengine.platform.security.Role.valueOf(role)));
        return user;
    }

    private void stubDelegation(List<String> scopes, String role, boolean active) {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(scopes, NOW.plusSeconds(3600), null)));
        // Build the actor BEFORE opening the lookup stubbing. actor() stubs a mock of its own, and
        // Mockito's stubbing state is global: a when(...) that starts while another when(...) is
        // still waiting for its thenReturn is an UnfinishedStubbingException, not a nested stub.
        Optional<com.pragmaticds.docengine.platform.security.AppUser> found = Optional.of(actor(role, active));
        when(users.findByOrgIdAndExternalSubject(ORG, ACTING_SUBJECT)).thenReturn(found);
    }

    /**
     * The point of the whole mechanism: a key scoped only PROCESSOR yields a REVIEWER principal when
     * it names a reviewer. Authority comes from the PERSON, never from the key — which is why the
     * LOS key can stay least-privileged instead of being minted REVIEWER.
     */
    @Test
    void a_delegated_call_binds_the_named_humans_own_identity_and_role() throws Exception {
        stubDelegation(List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER), "REVIEWER", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AuthContext.require().actorType())
                .as("a human made this decision, and the audit must say so")
                .isEqualTo(ActorType.USER);
        assertThat(AuthContext.require().userId())
                .as("decided_by must name the person, not the machine")
                .isEqualTo(ACTING_USER_ID);
        assertThat(AuthContext.require().orgId()).isEqualTo(ORG);
        assertThat(AuthContext.require().scopes()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_REVIEWER");
        assertThat(TenantContext.current()).contains(ORG);
        verify(repository).touchLastUsed(KEY_ID);
    }

    /** Impersonation is a granted capability. A key without the scope is authentic but refused. */
    @Test
    void a_key_without_the_act_as_user_scope_may_not_name_a_human() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), NOW.plusSeconds(3600), null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertThat(response.getStatus())
                .as("403, not 401 — the caller proved who IT is")
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
        assertThat(AuthContext.current()).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
    }

    /**
     * A refused impersonation must NEVER fall back to the key's own identity. That would turn a
     * rejected human claim into an anonymous machine write — exactly what FieldCorrectionService
     * refuses by requiring a userId.
     */
    @Test
    void an_unknown_subject_is_refused_rather_than_falling_back_to_the_key() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(
                        Optional.of(
                                key(
                                        List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER),
                                        NOW.plusSeconds(3600),
                                        null)));
        when(users.findByOrgIdAndExternalSubject(ORG, ACTING_SUBJECT)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    @Test
    void an_inactive_user_may_not_be_acted_as() throws Exception {
        stubDelegation(
                List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER), "REVIEWER", false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    /** A person whose stored role is not an engine role can authorize nothing. */
    @Test
    void a_user_with_an_unrecognized_role_is_refused() throws Exception {
        stubDelegation(List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER), null, true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
    }

    /**
     * Delegation never widens what the person may do: naming a READONLY user yields READONLY, so a
     * correction (REVIEWER+) still 403s downstream on the authorization matrix.
     */
    @Test
    void naming_a_readonly_user_yields_only_readonly_authority() throws Exception {
        stubDelegation(List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER), "READONLY", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_READONLY");
    }

    /** A blank header is not a delegation attempt — the ordinary machine path still runs. */
    @Test
    void a_blank_acting_user_header_falls_through_to_the_machine_path() throws Exception {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(Optional.of(key(List.of("PROCESSOR"), NOW.plusSeconds(3600), null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withKeyActingAs("   "), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AuthContext.require().actorType()).isEqualTo(ActorType.API_KEY);
        assertThat(AuthContext.require().userId()).isNull();
    }

    // ── Auto-provisioning: a person the calling service already authenticated, seen here first ──

    private ApiKeyAuthFilter provisioningFilter(com.pragmaticds.docengine.platform.security.Role role) {
        return new ApiKeyAuthFilter(
                hasher, repository, users, role, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubDelegatedKeyWithNoSuchUser() {
        stubHash();
        when(repository.resolveByHash(HASH))
                .thenReturn(
                        Optional.of(
                                key(
                                        List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER),
                                        NOW.plusSeconds(3600),
                                        null)));
        when(users.findByOrgIdAndExternalSubject(ORG, ACTING_SUBJECT)).thenReturn(Optional.empty());
    }

    /**
     * The reason this exists: nobody should maintain the same permission in two systems. A person
     * who can reach the Suite gets an engine identity the first time they act.
     */
    @Test
    void a_first_sight_user_is_created_with_the_configured_role() throws Exception {
        stubDelegatedKeyWithNoSuchUser();
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MockHttpServletRequest request = withKeyActingAs(ACTING_SUBJECT);
        request.addHeader(ApiKeyAuthFilter.ACTING_EMAIL_HEADER, "reviewer@example.com");
        request.addHeader(ApiKeyAuthFilter.ACTING_NAME_HEADER, "A Loan Officer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        provisioningFilter(com.pragmaticds.docengine.platform.security.Role.REVIEWER)
                .doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(AuthContext.require().actorType()).isEqualTo(ActorType.USER);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_REVIEWER");
    }

    /**
     * The role is the ENGINE's, not the caller's. Configured PROCESSOR means a first-sight user
     * gets PROCESSOR however the request is shaped — which is what stops a service that can assert
     * identity from also asserting privilege.
     */
    @Test
    void the_provisioned_role_comes_from_configuration_not_the_request() throws Exception {
        stubDelegatedKeyWithNoSuchUser();
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MockHttpServletRequest request = withKeyActingAs(ACTING_SUBJECT);
        request.addHeader(ApiKeyAuthFilter.ACTING_EMAIL_HEADER, "reviewer@example.com");
        request.addHeader("X-DocEngine-Acting-Role", "ADMIN"); // not a header the filter reads
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        provisioningFilter(com.pragmaticds.docengine.platform.security.Role.PROCESSOR)
                .doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_PROCESSOR");
    }

    /** app_user.email is NOT NULL, and a synthesized address would be a lie in the audit trail. */
    @Test
    void provisioning_without_an_email_is_refused_rather_than_invented() throws Exception {
        stubDelegatedKeyWithNoSuchUser();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        provisioningFilter(com.pragmaticds.docengine.platform.security.Role.REVIEWER)
                .doFilter(withKeyActingAs(ACTING_SUBJECT), response, chain);

        assertUnauthorizedAndUnbound(response, chain);
        verify(users, never()).save(any());
    }

    /** Provisioning off (the default) keeps the pre-existing behaviour: unknown subject, 401. */
    @Test
    void with_provisioning_off_an_unknown_subject_is_still_refused() throws Exception {
        stubDelegatedKeyWithNoSuchUser();
        MockHttpServletRequest request = withKeyActingAs(ACTING_SUBJECT);
        request.addHeader(ApiKeyAuthFilter.ACTING_EMAIL_HEADER, "reviewer@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain); // the shared filter: auto-provision null

        assertUnauthorizedAndUnbound(response, chain);
        verify(users, never()).save(any());
    }

    /**
     * Create-only. An existing user's role is whatever the engine says it is, and delegated traffic
     * never rewrites it — otherwise deliberately demoting someone would be undone by their next
     * click.
     */
    @Test
    void an_existing_users_role_is_never_rewritten_by_a_delegated_call() throws Exception {
        stubDelegation(
                List.of("PROCESSOR", ApiKeyAuthFilter.SCOPE_ACT_AS_USER), "READONLY", true);
        MockHttpServletRequest request = withKeyActingAs(ACTING_SUBJECT);
        request.addHeader(ApiKeyAuthFilter.ACTING_EMAIL_HEADER, "reviewer@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        provisioningFilter(com.pragmaticds.docengine.platform.security.Role.REVIEWER)
                .doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .as("the stored READONLY stands; configuration does not promote an existing user")
                .containsExactly("ROLE_READONLY");
        verify(users, never()).save(any());
    }

    private void stubHash() {
        when(hasher.isConfigured()).thenReturn(true);
        when(hasher.hash(RAW_KEY)).thenReturn(HASH);
    }

    private void assertUnauthorizedAndUnbound(MockHttpServletResponse response, MockFilterChain chain)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).as("the chain must NOT proceed past a rejected key").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(AuthContext.current()).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
        verify(repository, never()).touchLastUsed(any());
    }
}
