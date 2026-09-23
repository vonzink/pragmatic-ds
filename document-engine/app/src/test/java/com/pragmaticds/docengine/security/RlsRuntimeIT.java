package com.pragmaticds.docengine.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.platform.tenancy.TenantConnectionStampingDataSource;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The crux of Phase 7a: Postgres RLS actually engages at runtime, as a NON-OWNER role, and the
 * {@code app.current_org} GUC never leaks between pooled connections.
 *
 * <p>This is the prod topology, not the superuser test container: Flyway owns the schema, and the
 * "application" datasource connects as a login role granted {@code docengine_app} — neither a
 * superuser nor the table owner, so RLS is not bypassed. The datasource is wrapped exactly as the
 * app wires it ({@link TenantConnectionStampingDataSource}), and a raw JdbcTemplate query bypasses
 * Hibernate {@code @TenantId} so ONLY the database-level RLS is under test.
 *
 * <p>Also drives {@link DatasourceOwnershipCheck} — the boot-time assertion's pure logic — against
 * a superuser (must trip), a non-superuser owner (must trip), and the non-owner app role (passes).
 */
@Testcontainers
class RlsRuntimeIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG_DEV = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORG_OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** A login role granted docengine_app: not a superuser, not the schema owner. */
    private static final String APP_LOGIN = "rls_app_login";
    private static final String APP_PW = "rls";

    private static HikariDataSource nonOwnerPool;
    /** The app's datasource wrapper under test, over the non-owner pool. */
    private static DataSource appDataSource;

    @BeforeAll
    static void provision() throws Exception {
        // Flyway runs as the container superuser (the owner), building the schema, the RLS
        // policies, and the docengine_app role with its grants.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            // The non-owner application login role, exactly the deployment shape.
            s.execute(
                    "CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + APP_PW
                            + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("GRANT docengine_app TO " + APP_LOGIN);

            // Seed two orgs and one loan each, as the superuser (bypasses RLS on write).
            s.execute(
                    "INSERT INTO tenant (id, name, status) VALUES ('" + ORG_DEV
                            + "', 'Dev Org', 'ACTIVE') ON CONFLICT (id) DO NOTHING");
            s.execute(
                    "INSERT INTO tenant (id, name, status) VALUES ('" + ORG_OTHER
                            + "', 'Other Org', 'ACTIVE') ON CONFLICT (id) DO NOTHING");
            s.execute(
                    "INSERT INTO loan (org_id, loan_number) VALUES ('" + ORG_DEV + "', 'LN-DEV')");
            s.execute(
                    "INSERT INTO loan (org_id, loan_number) VALUES ('" + ORG_OTHER
                            + "', 'LN-OTHER')");
        }

        nonOwnerPool = new HikariDataSource();
        nonOwnerPool.setJdbcUrl(POSTGRES.getJdbcUrl());
        nonOwnerPool.setUsername(APP_LOGIN);
        nonOwnerPool.setPassword(APP_PW);
        // Pool size 1 forces the SAME physical connection to be reused across borrows, which is
        // what turns the leak test into a real proof rather than a coincidence.
        nonOwnerPool.setMaximumPoolSize(1);
        appDataSource = new TenantConnectionStampingDataSource(nonOwnerPool);
    }

    @AfterAll
    static void tearDown() {
        if (nonOwnerPool != null) {
            nonOwnerPool.close();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private List<String> loanNumbers() {
        return new JdbcTemplate(appDataSource)
                .queryForList("SELECT loan_number FROM loan ORDER BY loan_number", String.class);
    }

    @Test
    void rls_blocks_cross_tenant_reads_as_a_nonowner() {
        TenantContext.set(ORG_DEV);
        assertThat(loanNumbers())
                .as("bound to ORG_DEV, a raw query sees only ORG_DEV rows")
                .containsExactly("LN-DEV");

        TenantContext.set(ORG_OTHER);
        assertThat(loanNumbers())
                .as("bound to ORG_OTHER, the same raw query sees only ORG_OTHER rows")
                .containsExactly("LN-OTHER");
    }

    @Test
    void the_guc_does_not_leak_across_pooled_connections() {
        // Exercise org A, return the (single) connection to the pool.
        TenantContext.set(ORG_DEV);
        assertThat(loanNumbers()).containsExactly("LN-DEV");

        // Obtain the SAME physical connection again as org B: it must carry B's view, not A's.
        TenantContext.set(ORG_OTHER);
        assertThat(loanNumbers())
                .as("no leak: org B never sees org A's rows on a reused connection")
                .containsExactly("LN-OTHER");

        // And with nothing bound, the connection must fail closed — proving the GUC was reset to
        // empty on release, not left holding the previous tenant.
        TenantContext.clear();
        assertThat(loanNumbers())
                .as("unbound: the reused connection sees nothing, so the GUC did not leak")
                .isEmpty();
    }

    @Test
    void owner_assertion_trips_for_a_superuser_role() throws Exception {
        try (HikariDataSource superuser = new HikariDataSource()) {
            superuser.setJdbcUrl(POSTGRES.getJdbcUrl());
            superuser.setUsername(POSTGRES.getUsername());
            superuser.setPassword(POSTGRES.getPassword());
            superuser.setMaximumPoolSize(1);
            assertThat(DatasourceOwnershipCheck.inspect(superuser))
                    .as("a superuser datasource is the R2 misconfiguration and must be flagged")
                    .isNotEmpty();
        }
    }

    @Test
    void owner_assertion_trips_for_a_nonsuperuser_owner() throws Exception {
        // A plain owner (not superuser) still bypasses its own tables' RLS unless FORCE — and even
        // with FORCE it is a deployment smell. The check must flag the owner independently of the
        // superuser condition.
        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute(
                    "CREATE ROLE rls_owner LOGIN PASSWORD 'own' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("CREATE DATABASE rlsownertest OWNER rls_owner");
            s.execute("GRANT docengine_app TO rls_owner");
        }
        String ownerUrl = POSTGRES.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/rlsownertest$1");
        try (Connection admin =
                        DriverManager.getConnection(
                                ownerUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
        Flyway.configure()
                .dataSource(ownerUrl, "rls_owner", "own")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (HikariDataSource owner = new HikariDataSource()) {
            owner.setJdbcUrl(ownerUrl);
            owner.setUsername("rls_owner");
            owner.setPassword("own");
            owner.setMaximumPoolSize(1);
            assertThat(DatasourceOwnershipCheck.inspect(owner))
                    .as("the schema owner must be flagged even when it is not a superuser")
                    .isNotEmpty();
        }
    }

    @Test
    void owner_assertion_trips_for_a_bypassrls_role() throws Exception {
        // The one silent-bypass path FORCE cannot stop: a non-superuser, non-owner role carrying
        // the BYPASSRLS attribute bypasses RLS on every table regardless of FORCE. The R2 assertion
        // exists to catch silent bypass, so it must catch this too (Phase 7a review finding).
        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute(
                    "CREATE ROLE rls_bypass LOGIN PASSWORD 'byp' NOSUPERUSER NOCREATEDB"
                            + " NOCREATEROLE BYPASSRLS");
            s.execute("GRANT docengine_app TO rls_bypass");
        }
        try (HikariDataSource bypass = new HikariDataSource()) {
            bypass.setJdbcUrl(POSTGRES.getJdbcUrl());
            bypass.setUsername("rls_bypass");
            bypass.setPassword("byp");
            bypass.setMaximumPoolSize(1);
            assertThat(DatasourceOwnershipCheck.inspect(bypass))
                    .as("a BYPASSRLS role bypasses RLS regardless of FORCE and must be flagged")
                    .isNotEmpty();
        }
    }

    @Test
    void owner_assertion_passes_for_the_nonowner_app_role() {
        assertThat(DatasourceOwnershipCheck.inspect(appDataSource))
                .as("the non-owner docengine_app login role is the correct runtime role")
                .isEmpty();
    }
}
