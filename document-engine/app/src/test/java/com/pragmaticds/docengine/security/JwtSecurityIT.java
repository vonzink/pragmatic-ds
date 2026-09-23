package com.pragmaticds.docengine.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The non-local JWT resource-server chain, end to end, under a profile that is neither {@code
 * local} nor {@code test} (so the dev filter is off and the JWT chain is on). No live IdP: a test
 * RSA key backs a {@link JwtDecoder} bean and mints matching tokens.
 *
 * <p>Proves: unauthenticated {@code /v1} is 401; health and OpenAPI docs are public; CORS preflight
 * is permitted; a valid token resolves the seeded {@code app_user} to a principal and is authorized
 * (a nonexistent id then resolves to 404, i.e. it got past auth and authz).
 *
 * <p>The boot-time owner assertion is set to {@code warn} because the test container connects as a
 * superuser; the assertion's enforce path is proven separately (RlsRuntimeIT).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "docengine.security.owner-assertion=warn",
            "spring.datasource.hikari.maximum-pool-size=2"
        })
@AutoConfigureMockMvc
@ActiveProfiles("securityit")
@Import(JwtSecurityIT.TestJwtDecoderConfig.class)
class JwtSecurityIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SUBJECT = "auth0|integration-user";
    private static final String READONLY_SUBJECT = "auth0|readonly-result-user";

    /** One RSA key shared by the decoder bean and the token minter. */
    static final RSAKey RSA_JWK;

    static {
        try {
            RSA_JWK = new RSAKeyGenerator(2048).keyID("it-key").generate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "docengine.storage.local-root",
                () -> System.getProperty("user.dir") + "/build/test-blobs");
    }

    @TestConfiguration
    static class TestJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(RSA_JWK.toRSAPublicKey()).build();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void seedUser() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "INSERT INTO tenant (id, name, status) VALUES (?, 'Org', 'ACTIVE') ON CONFLICT (id)"
                    + " DO NOTHING",
                ORG);
        jdbc.update(
                "INSERT INTO app_user (org_id, external_subject, email, role, status) VALUES (?, ?,"
                    + " 'u@example.com', 'ADMIN', 'ACTIVE') ON CONFLICT DO NOTHING",
                ORG,
                SUBJECT);
        jdbc.update(
                "INSERT INTO app_user (org_id, external_subject, email, role, status) VALUES (?, ?,"
                    + " 'readonly-result@example.com', 'READONLY', 'ACTIVE') ON CONFLICT DO NOTHING",
                ORG,
                READONLY_SUBJECT);
    }

    private String mintToken(String subject, String orgIdClaim) throws Exception {
        JWTClaimsSet.Builder claims =
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000));
        if (orgIdClaim != null) {
            claims.claim("org_id", orgIdClaim);
        }
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_JWK.getKeyID()).build(),
                        claims.build());
        jwt.sign(new RSASSASigner(RSA_JWK.toRSAPrivateKey()));
        return jwt.serialize();
    }

    @Test
    void health_is_public() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void openapi_docs_are_public() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void an_unauthenticated_v1_request_is_401() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_garbage_bearer_token_is_401() throws Exception {
        mockMvc.perform(
                        get("/v1/packages/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_token_with_no_org_id_claim_is_401() throws Exception {
        String token = mintToken(SUBJECT, null);
        mockMvc.perform(
                        get("/v1/packages/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_token_for_an_unprovisioned_subject_is_401() throws Exception {
        String token = mintToken("auth0|ghost", ORG.toString());
        mockMvc.perform(
                        get("/v1/packages/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_valid_token_resolves_a_principal_and_is_authorized() throws Exception {
        String token = mintToken(SUBJECT, ORG.toString());
        // Past auth and authz (ADMIN), a nonexistent package is 404 — not 401/403.
        mockMvc.perform(
                        get("/v1/packages/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void jwt_role_allows_result_metadata_but_raw_result_matchers_are_admin_only() throws Exception {
        UUID packageId = UUID.randomUUID();
        String readonly = mintToken(READONLY_SUBJECT, ORG.toString());
        String admin = mintToken(SUBJECT, ORG.toString());

        mockMvc.perform(
                        get("/v1/packages/{id}/engine-results", packageId)
                                .header("Authorization", "Bearer " + readonly))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-result", packageId)
                                .header("Authorization", "Bearer " + readonly))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-results/{revision}", packageId, 1)
                                .header("Authorization", "Bearer " + readonly))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/v1/packages/{id}/engine-result", packageId)
                                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-results/{revision}", packageId, 1)
                                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void result_content_requires_a_well_formed_bearer_token() throws Exception {
        UUID packageId = UUID.randomUUID();
        mockMvc.perform(get("/v1/packages/{id}/engine-result", packageId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-results/{revision}", packageId, 1)
                                .header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void options_is_permitted_by_the_security_allowlist_without_a_token() throws Exception {
        // The security matrix permits OPTIONS so a CORS preflight is never 401'd for lacking a
        // bearer token. A real cross-origin preflight additionally needs a per-deployment CORS
        // policy (there is none in the prod-shaped profile, by design — it belongs with the
        // deployment, not baked in), so this asserts the security allowlist, not a CORS policy.
        mockMvc.perform(options("/v1/packages/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
    }
}
