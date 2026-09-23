package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.List;
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
 * The {@code X-DocEngine-Api-Key} service-auth path, end to end on the non-local JWT chain (profile
 * {@code securityit}, so the dev filter is off, the api-key filter and bearer resource server are
 * both on). Spec 6 §6a.2.
 *
 * <p>Proves: a live key authenticates as ITS org and is authorized (a nonexistent id then 404s, i.e.
 * it got past auth+authz), can read its own package, and CANNOT read another org's package (404
 * existence-hiding — the api-key principal drives tenancy exactly like a JWT one); unknown, revoked,
 * and expired keys are each an opaque 401; a key whose scopes name no role is 401; and {@code
 * last_used_at} is stamped on a successful call.
 *
 * <p>Also proves the {@code SCOPE_ENGINE_RESULT_READ} least-privilege path: a key scoped
 * {@code READONLY,ENGINE_RESULT_READ} reaches all five raw-text reads and is refused every write;
 * the same key one scope short is refused those reads; an ADMIN key is unchanged; and the read scope
 * WITHOUT a role scope still fails to authenticate at the filter. Those four together are the claim
 * — that the scope opened exactly one door and no other.
 *
 * <p>The stored hash is computed with the very {@link ApiKeyHasher} the filter uses, under a salt
 * this test configures — the same contract prod runs. The owner assertion is {@code warn} because
 * the test container connects as a superuser (as in {@link JwtSecurityIT}).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "docengine.security.owner-assertion=warn",
            "docengine.apikey.salt=apikey-it-salt",
            "spring.datasource.hikari.maximum-pool-size=2"
        })
@AutoConfigureMockMvc
@ActiveProfiles("securityit")
@Import(ApiKeyAuthIT.TestJwtDecoderConfig.class)
class ApiKeyAuthIT {

    /** Must equal the {@code docengine.apikey.salt} property above — filter and test hash alike. */
    static final String SALT = "apikey-it-salt";
    private static final String HEADER = ApiKeyAuthFilter.API_KEY_HEADER;

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG_A = UUID.fromString("0a0a0a0a-0000-0000-0000-000000000001");
    private static final UUID ORG_B = UUID.fromString("0b0b0b0b-0000-0000-0000-000000000002");

    /** A JWT decoder must exist for the resource server to wire, even though these tests use keys. */
    @TestConfiguration
    static class TestJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            RSAKey jwk = new RSAKeyGenerator(2048).keyID("it-key").generate();
            return NimbusJwtDecoder.withPublicKey(jwk.toRSAPublicKey()).build();
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

    private final ApiKeyHasher hasher = new ApiKeyHasher(SALT);

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        seedTenant(ORG_A);
        seedTenant(ORG_B);
    }

    private void seedTenant(UUID org) {
        jdbc.update(
                "INSERT INTO tenant (id, name, status) VALUES (?, 'Org', 'ACTIVE') ON CONFLICT (id)"
                    + " DO NOTHING",
                org);
    }

    /** Insert a key (hash computed the way the filter computes it) and return the raw key. */
    private String insertKey(UUID org, String scopesLiteral, String expiresSql, String revokedSql) {
        String rawKey = "pds_live_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO api_key (org_id, name, key_hash, scopes, expires_at, revoked_at)"
                        + " VALUES (?, 'suite', ?, ?::text[], "
                        + expiresSql
                        + ", "
                        + revokedSql
                        + ")",
                org,
                hasher.hash(rawKey),
                scopesLiteral);
        return rawKey;
    }

    private UUID insertPackage(UUID org) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'pkg')", id, org);
        return id;
    }

    @Test
    void a_live_key_authenticates_as_its_org_and_is_authorized() throws Exception {
        String key = insertKey(ORG_A, "{READONLY}", "NULL", "NULL");
        // Past auth and authz (READONLY may GET /v1/**): a nonexistent id is 404, not 401/403.
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_key_reads_its_own_org_but_not_another_orgs_package() throws Exception {
        UUID packageA = insertPackage(ORG_A);
        String keyA = insertKey(ORG_A, "{READONLY}", "NULL", "NULL");
        String keyB = insertKey(ORG_B, "{READONLY}", "NULL", "NULL");

        // Org A's key sees org A's package.
        mockMvc.perform(get("/v1/packages/{id}", packageA).header(HEADER, keyA))
                .andExpect(status().isOk());
        // Org B's key is denied the very same id — an opaque 404, not a 403 that would confirm it.
        mockMvc.perform(get("/v1/packages/{id}", packageA).header(HEADER, keyB))
                .andExpect(status().isNotFound());
    }

    @Test
    void no_api_key_header_falls_through_to_the_bearer_path_and_is_401() throws Exception {
        // No key and no bearer token: the api-key filter passes through, the resource server 401s.
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void an_unknown_key_is_401() throws Exception {
        mockMvc.perform(
                        get("/v1/packages/{id}", UUID.randomUUID())
                                .header(HEADER, "pds_live_never_issued"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_revoked_key_is_401() throws Exception {
        String key = insertKey(ORG_A, "{READONLY}", "NULL", "now()");
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void an_expired_key_is_401() throws Exception {
        String key = insertKey(ORG_A, "{READONLY}", "now() - interval '1 hour'", "NULL");
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_key_whose_scopes_name_no_role_is_401() throws Exception {
        String key = insertKey(ORG_A, "{documents:read}", "NULL", "NULL");
        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_processor_scope_key_may_read_but_an_admin_only_raw_read_is_403() throws Exception {
        String key = insertKey(ORG_A, "{PROCESSOR}", "NULL", "NULL");
        UUID id = UUID.randomUUID();
        // PROCESSOR is included in the broad GET rule → past authz, controller 404s.
        mockMvc.perform(get("/v1/packages/{id}", id).header(HEADER, key))
                .andExpect(status().isNotFound());
        // The raw engine-result read is ADMIN-only → 403, exactly as for a PROCESSOR human.
        mockMvc.perform(get("/v1/packages/{id}/engine-result", id).header(HEADER, key))
                .andExpect(status().isForbidden());
    }

    // ── SCOPE_ENGINE_RESULT_READ: a read-only machine consumer ──────────────────────────────
    //
    // The raw-text matchers accept ROLE_ADMIN **or** SCOPE_ENGINE_RESULT_READ so a service that
    // only GETs parsed envelopes no longer needs an ADMIN key, which would also open every
    // /v1/** write. What has to be true is a conjunction, so each half is asserted:
    // the scoped key REACHES the raw reads, and it reaches NOTHING ELSE new.
    //
    // On absent ids these assertions read 404-vs-403, which is the same discriminator
    // RawContentAdminBoundaryIT is built on and is stronger than a 200 alone: 403 means the
    // matcher refused, 404 means it authorized and the controller found nothing. A test that only
    // checked "the scoped key gets 200" would still pass if the gate had disappeared entirely.
    // One genuine 200 is asserted too, on a page that really exists, so the pair proves both
    // "authorized" and "actually serves the body".

    @Test
    void a_scoped_key_reads_raw_content_and_is_still_refused_every_write() throws Exception {
        String key = insertKey(ORG_A, "{READONLY,ENGINE_RESULT_READ}", "NULL", "NULL");
        UUID absent = UUID.randomUUID();

        // Past the raw-text matcher: the controller's own absence answer, not the matcher's 403.
        for (String path :
                List.of(
                        "/v1/packages/{id}/engine-result",
                        "/v1/packages/{id}/engine-results/{revision}",
                        "/v1/pages/{id}/spans",
                        "/v1/pages/{id}/structure",
                        "/v1/documents/{id}/body.md")) {
            mockMvc.perform(get(path, absent, 1).header(HEADER, key))
                    .andExpect(status().isNotFound());
        }

        // ...and the scope buys nothing on the write side. POST /v1/packages is PROCESSOR+, the
        // three review writes are REVIEWER+, DELETE is ADMIN: a READONLY key is refused all four
        // before and after this change, and SCOPE_ENGINE_RESULT_READ appears on none of them.
        mockMvc.perform(post("/v1/packages").header(HEADER, key)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/v1/fields/{id}", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/documents/{id}/review", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/documents/{id}/classification", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/packages/{id}/regroup", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/pages/{id}/verdict", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/packages/{id}", absent).header(HEADER, key))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_scoped_key_really_serves_the_raw_body_of_a_page_that_exists() throws Exception {
        UUID pageId = insertPage(ORG_A);
        String scoped = insertKey(ORG_A, "{READONLY,ENGINE_RESULT_READ}", "NULL", "NULL");
        String readonly = insertKey(ORG_A, "{READONLY}", "NULL", "NULL");

        // Not merely authorized — the span window is actually returned.
        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).header(HEADER, scoped))
                .andExpect(status().isOk());
        // The very same id, one scope short, is refused by the matcher.
        mockMvc.perform(get("/v1/pages/{id}/spans", pageId).header(HEADER, readonly))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_readonly_key_without_the_scope_is_still_refused_raw_content() throws Exception {
        String key = insertKey(ORG_A, "{READONLY}", "NULL", "NULL");
        UUID absent = UUID.randomUUID();

        for (String path :
                List.of(
                        "/v1/packages/{id}/engine-result",
                        "/v1/packages/{id}/engine-results/{revision}",
                        "/v1/pages/{id}/spans",
                        "/v1/pages/{id}/structure",
                        "/v1/documents/{id}/body.md")) {
            mockMvc.perform(get(path, absent, 1).header(HEADER, key))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void an_admin_key_is_unchanged_by_the_new_scope() throws Exception {
        String key = insertKey(ORG_A, "{ADMIN}", "NULL", "NULL");
        UUID absent = UUID.randomUUID();

        // Still reaches every raw read on ROLE_ADMIN alone, carrying no ENGINE_RESULT_READ scope.
        for (String path :
                List.of(
                        "/v1/packages/{id}/engine-result",
                        "/v1/packages/{id}/engine-results/{revision}",
                        "/v1/pages/{id}/spans",
                        "/v1/pages/{id}/structure",
                        "/v1/documents/{id}/body.md")) {
            mockMvc.perform(get(path, absent, 1).header(HEADER, key))
                    .andExpect(status().isNotFound());
        }
        // And still reaches the writes: a 403 here would mean hasAnyAuthority had narrowed ADMIN.
        mockMvc.perform(delete("/v1/packages/{id}", absent).header(HEADER, key))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_read_scope_alone_does_not_authenticate() throws Exception {
        // No scope names a Role, so the filter refuses to bind at all — 401 at the filter, NOT a
        // 403 from the matcher. The scope authority must never become a second way to be
        // authenticated: a key holding only this one would otherwise bind and reach exactly the
        // four raw-text routes, which is precisely the surface this change is supposed to gate.
        String key = insertKey(ORG_A, "{ENGINE_RESULT_READ}", "NULL", "NULL");
        mockMvc.perform(
                        get("/v1/packages/{id}/engine-result", UUID.randomUUID())
                                .header(HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    /** A package → source_file → page chain: the least a real {@code /spans} 200 needs. */
    private UUID insertPage(UUID org) {
        UUID packageId = insertPackage(org);
        UUID sourceFileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'fixture.pdf', 'application/pdf', 10, ?, 'unused')
                """,
                sourceFileId,
                org,
                packageId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                    package_page_index, width_pt, height_pt, rotation, text_layer, is_blank)
                VALUES (?, ?, ?, ?, 0, 0, 612.0, 792.0, 0, 'NATIVE', false)
                """,
                pageId,
                org,
                sourceFileId,
                packageId);
        return pageId;
    }

    @Test
    void a_successful_call_stamps_last_used_at() throws Exception {
        String key = insertKey(ORG_A, "{READONLY}", "NULL", "NULL");
        String hash = hasher.hash(key);
        // Scoped to THIS key's hash — the class shares one container, so a count over the org would
        // race the other tests that also authenticate ORG_A keys.
        assertThat(lastUsedAt(hash)).as("unused before the call").isNull();

        mockMvc.perform(get("/v1/packages/{id}", UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isNotFound());

        // The stamp is an RLS-scoped UPDATE under the now-bound org; read it back as the owner.
        assertThat(lastUsedAt(hash)).as("stamped after the call").isNotNull();
    }

    private java.time.Instant lastUsedAt(String keyHash) {
        java.sql.Timestamp ts =
                jdbc.queryForObject(
                        "SELECT last_used_at FROM api_key WHERE key_hash = ?",
                        java.sql.Timestamp.class,
                        keyHash);
        return ts == null ? null : ts.toInstant();
    }
}
