package com.pragmaticds.docengine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V1 schema and — more importantly — proof that Postgres RLS actually isolates tenants.
 *
 * <p>Risk R2 in docs/IMPLEMENTATION_PLAN.md: RLS only engages when the application connects as a
 * non-owner role. If the app connects as the schema owner, RLS is silently bypassed and the only
 * thing protecting tenant isolation is the application layer. That failure is invisible — every
 * query still returns "correct-looking" results. So this test connects as the non-owner
 * {@code docengine_app} role rather than trusting the policy exists.
 */
@Testcontainers
class V1MigrationIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** A connection acting as the non-owner application role, scoped to one org. */
    private Connection appConnection(UUID orgId) throws Exception {
        Connection connection = ownerConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE docengine_app");
            statement.execute("SET app.current_org = '" + orgId + "'");
        }
        return connection;
    }

    private List<String> query(Connection connection, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        }
        return rows;
    }

    @Test
    void enables_pgvector_so_spec6_rag_indexing_needs_no_extension_migration() throws Exception {
        try (Connection connection = ownerConnection()) {
            assertThat(query(connection, "SELECT extname FROM pg_extension WHERE extname = 'vector'"))
                    .containsExactly("vector");
        }
    }

    @Test
    void creates_the_tenancy_tables() throws Exception {
        try (Connection connection = ownerConnection()) {
            assertThat(query(connection,
                            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"))
                    .contains("api_key", "app_user", "loan", "tenant");
        }
    }

    @Test
    void forces_row_level_security_on_every_tenant_scoped_table() throws Exception {
        try (Connection connection = ownerConnection()) {
            // tenant, app_user and loan stay ENABLE + FORCE. api_key is the deliberate exception:
            // V22 drops FORCE (keeping ENABLE + the api_key_isolation policy) so the OWNER, and the
            // api_key_authenticate SECURITY DEFINER function it owns, can resolve a key by hash for
            // the service-auth bootstrap before any tenant is bound. The app still connects as the
            // non-owner docengine_app, which FORCE never affected — its isolation is unchanged, as
            // rls_hides_another_tenants_rows_from_the_application_role proves for loan.
            assertThat(query(connection,
                            """
                            SELECT relname FROM pg_class
                            WHERE relname IN ('tenant','app_user','api_key','loan')
                              AND relrowsecurity AND relforcerowsecurity
                            ORDER BY relname
                            """))
                    .as("api_key drops FORCE at V22; the other tenant tables keep it")
                    .containsExactly("app_user", "loan", "tenant");

            // api_key is still RLS-ENABLED with its isolation policy — only FORCE is gone.
            assertThat(query(connection,
                            """
                            SELECT relname FROM pg_class
                            WHERE relname = 'api_key' AND relrowsecurity AND NOT relforcerowsecurity
                            """))
                    .containsExactly("api_key");
            assertThat(query(connection,
                            "SELECT polname FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid"
                                    + " WHERE c.relname = 'api_key'"))
                    .containsExactly("api_key_isolation");
        }
    }

    @Test
    void the_application_role_is_not_the_schema_owner() throws Exception {
        try (Connection connection = ownerConnection()) {
            assertThat(query(connection,
                            """
                            SELECT rolname FROM pg_roles
                            WHERE rolname = 'docengine_app'
                              AND NOT rolsuper AND NOT rolbypassrls
                            """))
                    .containsExactly("docengine_app");
        }
    }

    @Test
    void rls_hides_another_tenants_rows_from_the_application_role() throws Exception {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES ('" + ORG_A + "', 'Org A', 'ACTIVE')");
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES ('" + ORG_B + "', 'Org B', 'ACTIVE')");
            statement.execute(
                    "INSERT INTO loan (id, org_id, loan_number) VALUES (gen_random_uuid(), '"
                            + ORG_A + "', 'LOAN-A')");
            statement.execute(
                    "INSERT INTO loan (id, org_id, loan_number) VALUES (gen_random_uuid(), '"
                            + ORG_B + "', 'LOAN-B')");
        }

        try (Connection connection = appConnection(ORG_A)) {
            assertThat(query(connection, "SELECT loan_number FROM loan")).containsExactly("LOAN-A");
        }
        try (Connection connection = appConnection(ORG_B)) {
            assertThat(query(connection, "SELECT loan_number FROM loan")).containsExactly("LOAN-B");
        }
    }

    @Test
    void rls_fails_closed_when_no_org_is_set() throws Exception {
        try (Connection connection = ownerConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE docengine_app");
            }
            assertThat(query(connection, "SELECT loan_number FROM loan")).isEmpty();
        }
    }

    @Test
    void rls_rejects_a_write_outside_the_current_org() throws Exception {
        try (Connection connection = appConnection(ORG_A);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "INSERT INTO loan (id, org_id, loan_number) VALUES (gen_random_uuid(), '"
                                                    + ORG_B + "', 'SMUGGLED')"))
                    .hasMessageContaining("row-level security");
        }
    }
}
