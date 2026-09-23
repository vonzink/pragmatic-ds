package com.pragmaticds.docengine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Database contract for V19 — parse-once reuse: the nullable
 * {@code processing_job.behavior_fingerprint} column (lowercase-hex-64 CHECK) and the
 * {@code engine_result_org_source_set_idx} probe index. Additive only: V1..V18 checksums are
 * captured at target 18 and must be byte-identical after the full migrate.
 */
@Testcontainers
class V19MigrationIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID PACKAGE = UUID.fromString("50000000-0000-0000-0000-000000000002");

    private static final Map<String, Integer> CHECKSUMS_AT_18 = new HashMap<>();

    @BeforeAll
    static void migrate() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("18"))
                .load()
                .migrate();
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement();
                ResultSet history =
                        statement.executeQuery(
                                "SELECT version, checksum FROM flyway_schema_history"
                                        + " WHERE version IS NOT NULL")) {
            while (history.next()) {
                CHECKSUMS_AT_18.put(history.getString(1), history.getInt(2));
            }
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name) VALUES ('" + ORG + "', 'V19 Org')");
            statement.execute(
                    "INSERT INTO document_package (id, org_id, name) VALUES ('" + PACKAGE
                            + "', '" + ORG + "', 'V19 Package')");
        }
    }

    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void v1ThroughV18ChecksumsAreUnchangedAndV19Applied() throws Exception {
        assertThat(CHECKSUMS_AT_18).containsKeys("1", "18");
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement();
                ResultSet history =
                        statement.executeQuery(
                                "SELECT version, checksum, success FROM flyway_schema_history"
                                        + " WHERE version IS NOT NULL")) {
            Map<String, Integer> after = new HashMap<>();
            while (history.next()) {
                assertThat(history.getBoolean(3)).as("migration %s", history.getString(1)).isTrue();
                after.put(history.getString(1), history.getInt(2));
            }
            assertThat(after).containsAllEntriesOf(CHECKSUMS_AT_18);
            assertThat(after).containsKey("19");
        }
    }

    @Test
    void behaviorFingerprintColumnIsNullableChar64() throws Exception {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement();
                ResultSet column =
                        statement.executeQuery(
                                """
                                SELECT data_type, character_maximum_length, is_nullable
                                  FROM information_schema.columns
                                 WHERE table_name = 'processing_job'
                                   AND column_name = 'behavior_fingerprint'
                                """)) {
            assertThat(column.next()).as("behavior_fingerprint column exists").isTrue();
            assertThat(column.getString(1)).isEqualTo("character");
            assertThat(column.getInt(2)).isEqualTo(64);
            assertThat(column.getString(3)).isEqualTo("YES");
        }
    }

    @Test
    void behaviorFingerprintAcceptsNullAndHexAndRejectsEverythingElse() throws Exception {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                            + " behavior_fingerprint) VALUES (gen_random_uuid(), '" + ORG + "', '"
                            + PACKAGE + "', 'v19-null', 'UPLOADED', NULL)");
            statement.execute(
                    "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                            + " behavior_fingerprint) VALUES (gen_random_uuid(), '" + ORG + "', '"
                            + PACKAGE + "', 'v19-hex', 'UPLOADED', '" + "a".repeat(64) + "')");
        }
        assertThatThrownBy(
                        () -> {
                            try (Connection connection = ownerConnection();
                                    Statement statement = connection.createStatement()) {
                                statement.execute(
                                        "INSERT INTO processing_job (id, org_id, package_id,"
                                            + " idempotency_key, status, behavior_fingerprint)"
                                            + " VALUES (gen_random_uuid(), '" + ORG + "', '"
                                            + PACKAGE + "', 'v19-bad', 'UPLOADED', '"
                                            + "Z".repeat(64) + "')");
                            }
                        })
                .hasMessageContaining("processing_job_behavior_fingerprint_format_check");
    }

    @Test
    void engineResultOrgSourceSetProbeIndexExists() throws Exception {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement();
                ResultSet index =
                        statement.executeQuery(
                                """
                                SELECT indexdef FROM pg_indexes
                                 WHERE tablename = 'engine_result'
                                   AND indexname = 'engine_result_org_source_set_idx'
                                """)) {
            assertThat(index.next()).as("engine_result_org_source_set_idx exists").isTrue();
            assertThat(index.getString(1)).contains("org_id", "source_set_sha256");
        }
    }
}
