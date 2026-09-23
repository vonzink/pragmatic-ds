package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.platform.security.AppUser;
import com.pragmaticds.docengine.platform.security.AppUserRepository;
import com.pragmaticds.docengine.platform.security.AuthContext;
import com.pragmaticds.docengine.platform.security.Role;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * The JWT → {@link com.pragmaticds.docengine.platform.security.AuthPrincipal} mapping, unit-tested against
 * hand-built {@link Jwt}s (no live IdP): a valid token yields the right principal; a
 * missing/blank/malformed {@code org_id} and an unprovisioned {@code sub} are each rejected 401
 * (fail-closed).
 */
class JwtAuthPrincipalConverterTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final JwtAuthPrincipalConverter converter = new JwtAuthPrincipalConverter(users);

    @AfterEach
    void clear() {
        AuthContext.clear();
        TenantContext.clear();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    @Test
    void a_valid_token_yields_the_right_principal() {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER);
        when(user.isActive()).thenReturn(true);
        when(user.roleEnum()).thenReturn(Optional.of(Role.REVIEWER));
        when(users.findByOrgIdAndExternalSubject(ORG, "sub-123")).thenReturn(Optional.of(user));

        Jwt token = jwt().subject("sub-123").claim("org_id", ORG.toString()).build();
        AbstractAuthenticationToken authentication = converter.convert(token);

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_REVIEWER");
        assertThat(AuthContext.require().orgId()).isEqualTo(ORG);
        assertThat(AuthContext.require().userId()).isEqualTo(USER);
        assertThat(AuthContext.require().subject()).isEqualTo("sub-123");
        assertThat(AuthContext.require().role()).isEqualTo(Role.REVIEWER);
        // The tenant was bound from the claim, so the lookup ran inside the caller's org.
        assertThat(TenantContext.current()).contains(ORG);
    }

    @Test
    void a_missing_org_id_claim_is_rejected() {
        Jwt token = jwt().subject("sub-123").build();
        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void a_blank_org_id_claim_is_rejected() {
        Jwt token = jwt().subject("sub-123").claim("org_id", "  ").build();
        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void a_malformed_org_id_claim_is_rejected() {
        Jwt token = jwt().subject("sub-123").claim("org_id", "not-a-uuid").build();
        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void a_subject_with_no_provisioned_user_is_rejected() {
        when(users.findByOrgIdAndExternalSubject(ORG, "ghost")).thenReturn(Optional.empty());
        Jwt token = jwt().subject("ghost").claim("org_id", ORG.toString()).build();
        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
