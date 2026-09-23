package com.pragmaticds.rag.migration;

import com.pragmaticds.rag.TestBrains;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration contract for the brain-scoped Lab instance registry.
 *
 * <p>The historical-data test starts from V34, seeds a real Income release/pointer/
 * registration/run graph, and only then applies V35. That proves the registry backfill is
 * additive: it establishes the missing instance identity without rewriting release history.
 */
@Testcontainers
class V35MigrationTest {

    private static final String INCOME = "income";
    private static final String SHA = "a".repeat(64);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void backfillsOneRegistryRowPerHistoricalBrainAndInstanceWithoutRewritingHistory()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "34");
            HistoricalRows defaultBrain = seedHistoricalRows(schema, TestBrains.DEFAULT_ID, INCOME);
            insertRegistration(schema, TestBrains.DEFAULT_ID, "registration-only");
            insertRun(schema, TestBrains.DEFAULT_ID, "run-only", defaultBrain.releaseId(),
                    defaultBrain.registrationId());
            UUID secondBrain = UUID.randomUUID();
            insertBrain(schema, secondBrain, "second-" + secondBrain.toString().substring(0, 8));
            HistoricalRows second = seedHistoricalRows(schema, secondBrain, INCOME);

            migrate(schema, null);

            assertEquals(1, instanceCount(schema, TestBrains.DEFAULT_ID, INCOME),
                    "release, pointer, registration, and run duplicates must backfill one row");
            assertEquals(1, instanceCount(schema, secondBrain, INCOME),
                    "two brains may independently own the same instance slug");
            assertEquals(1, instanceCount(schema, TestBrains.DEFAULT_ID, "registration-only"),
                    "a registration-only historical identity must retain its registry parent");
            assertEquals(1, instanceCount(schema, TestBrains.DEFAULT_ID, "run-only"),
                    "a run-only historical identity must retain its registry parent");
            assertEquals("Income", stringValue(schema,
                    "SELECT display_name FROM lab_instance WHERE brain_id = ? AND slug = ?",
                    TestBrains.DEFAULT_ID, INCOME));
            assertEquals("Evaluate parsed income documents", stringValue(schema,
                    "SELECT purpose FROM lab_instance WHERE brain_id = ? AND slug = ?",
                    TestBrains.DEFAULT_ID, INCOME));

            assertHistoricalRowsAreUnchanged(schema, defaultBrain);
            assertHistoricalRowsAreUnchanged(schema, second);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registryShapeStatesTimestampsAndCompositeForeignKeysProtectInstanceIdentity()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);

            assertEquals("uuid", stringValue(schema,
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = 'lab_instance' "
                            + "AND column_name = 'id'"));
            assertEquals(1, constraintCount(schema, "lab_instance", "PRIMARY KEY"));
            assertEquals(1, uniqueConstraintWithColumns(schema, "lab_instance", "brain_id", "slug"));
            assertEquals(32, intValue(schema,
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = 'lab_instance' "
                            + "AND column_name = 'slug'"));

            UUID brain = TestBrains.DEFAULT_ID;
            UUID id = UUID.randomUUID();
            insertInstance(schema, id, brain, "valid-instance", "ACTIVE");
            assertTrue(booleanValue(schema,
                    "SELECT created_at IS NOT NULL AND updated_at IS NOT NULL "
                            + "FROM lab_instance WHERE id = ?", id));
            assertRejected(() -> insertInstance(schema, UUID.randomUUID(), brain, "valid-instance", "ACTIVE"));
            assertRejected(() -> insertInstance(schema, UUID.randomUUID(), brain, "Bad Slug", "ACTIVE"));
            assertRejected(() -> insertInstance(schema, UUID.randomUUID(), brain, "disabled-test", "PAUSED"));
            assertRejected(() -> insertInstance(schema, UUID.randomUUID(), UUID.randomUUID(), "unknown-brain", "ACTIVE"));

            for (String table : new String[] {
                    "lab_instance_release", "lab_instance_pointer", "lab_document_registration", "lab_run"}) {
                assertEquals(1, compositeInstanceForeignKeyCount(schema, table),
                        table + " must reference lab_instance(brain_id, slug)");
            }

            assertRejected(() -> insertRegistration(schema, brain, "unregistered-instance"));

            UUID idempotency = UUID.randomUUID();
            execute(schema, "INSERT INTO lab_idempotency_record "
                    + "(id, brain_id, operation, idempotency_key, request_sha256, result_kind, result_id) "
                    + "VALUES (?, ?, 'CREATE_INSTANCE', 'key-1', ?, 'INSTANCE', ?)",
                    idempotency, brain, SHA, id);
            assertRejected(() -> execute(schema,
                    "UPDATE lab_idempotency_record SET result_kind = 'OTHER' WHERE id = ?", idempotency));
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_idempotency_record WHERE id = ?", idempotency));
        } finally {
            dropSchema(schema);
        }
    }

    private HistoricalRows seedHistoricalRows(String schema, UUID brainId, String slug) throws Exception {
        UUID releaseId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, manifest, manifest_sha256) "
                        + "VALUES (?, ?, ?, 1, 'PRODUCTION', '{\"analyzer\":\"income-v2\"}'::jsonb, ?)",
                releaseId, brainId, slug, SHA);
        execute(schema, "INSERT INTO lab_instance_pointer "
                        + "(brain_id, instance_slug, production_release_id) VALUES (?, ?, ?)",
                brainId, slug, releaseId);
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, engine_source_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                registrationId, brainId, slug, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute(schema, "INSERT INTO lab_run "
                        + "(id, brain_id, instance_slug, idempotency_key, release_id, registration_id, "
                        + "status, lease_expires_at) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', "
                        + "now() + interval '5 minutes')",
                runId, brainId, slug, "run-" + UUID.randomUUID(), releaseId, registrationId);
        return new HistoricalRows(brainId, slug, releaseId, registrationId, runId);
    }

    private void assertHistoricalRowsAreUnchanged(String schema, HistoricalRows rows) throws Exception {
        assertEquals(1, intValue(schema, "SELECT count(*) FROM lab_instance_release "
                + "WHERE id = ? AND manifest_sha256 = ? AND manifest = '{\"analyzer\":\"income-v2\"}'::jsonb",
                rows.releaseId(), SHA));
        assertEquals(rows.releaseId(), uuidValue(schema,
                "SELECT production_release_id FROM lab_instance_pointer "
                        + "WHERE brain_id = ? AND instance_slug = ?", rows.brainId(), rows.slug()));
        assertEquals(1, intValue(schema,
                "SELECT count(*) FROM lab_document_registration WHERE id = ?", rows.registrationId()));
        assertEquals(1, intValue(schema, "SELECT count(*) FROM lab_run WHERE id = ?", rows.runId()));
    }

    private void insertBrain(String schema, UUID id, String slug) throws Exception {
        execute(schema, "INSERT INTO brains (id, slug, display_name, is_default, is_active) "
                + "VALUES (?, ?, 'Second brain', false, true)", id, slug);
    }

    private void insertInstance(String schema, UUID id, UUID brainId, String slug, String state)
            throws Exception {
        execute(schema, "INSERT INTO lab_instance (id, brain_id, slug, display_name, state) "
                + "VALUES (?, ?, ?, 'Test instance', ?)", id, brainId, slug, state);
    }

    private void insertRegistration(String schema, UUID brainId, String slug) throws Exception {
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, engine_source_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), brainId, slug, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private void insertRun(String schema, UUID brainId, String slug, UUID releaseId, UUID registrationId)
            throws Exception {
        execute(schema, "INSERT INTO lab_run "
                        + "(id, brain_id, instance_slug, idempotency_key, release_id, registration_id, "
                        + "status, lease_expires_at) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', "
                        + "now() + interval '5 minutes')",
                UUID.randomUUID(), brainId, slug, "run-" + UUID.randomUUID(), releaseId, registrationId);
    }

    private int instanceCount(String schema, UUID brainId, String slug) throws Exception {
        return intValue(schema, "SELECT count(*) FROM lab_instance WHERE brain_id = ? AND slug = ?",
                brainId, slug);
    }

    private int constraintCount(String schema, String table, String type) throws Exception {
        return intValue(schema, "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = current_schema() AND table_name = ? AND constraint_type = ?", table, type);
    }

    private int uniqueConstraintWithColumns(String schema, String table, String... columns) throws Exception {
        String expected = "{" + String.join(",", columns) + "}";
        return intValue(schema, "SELECT count(*) FROM information_schema.table_constraints tc "
                        + "WHERE tc.table_schema = current_schema() AND tc.table_name = ? "
                        + "AND tc.constraint_type = 'UNIQUE' AND ARRAY(SELECT key.column_name::text "
                        + "FROM information_schema.key_column_usage key WHERE key.constraint_schema "
                        + "= tc.constraint_schema AND key.constraint_name = tc.constraint_name "
                        + "ORDER BY key.ordinal_position) = ?::text[]",
                table, expected);
    }

    private int compositeInstanceForeignKeyCount(String schema, String table) throws Exception {
        return intValue(schema, "SELECT count(*) FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.table_constraints fk "
                        + "ON fk.constraint_schema = rc.constraint_schema "
                        + "AND fk.constraint_name = rc.constraint_name "
                        + "WHERE fk.table_schema = current_schema() "
                        + "AND fk.table_name = ? AND EXISTS (SELECT 1 "
                        + "FROM information_schema.constraint_column_usage target "
                        + "WHERE target.constraint_schema = rc.unique_constraint_schema "
                        + "AND target.constraint_name = rc.unique_constraint_name "
                        + "AND target.table_name = 'lab_instance') AND ARRAY(SELECT key.column_name::text "
                        + "FROM information_schema.key_column_usage key WHERE key.constraint_schema "
                        + "= fk.constraint_schema AND key.constraint_name = fk.constraint_name "
                        + "ORDER BY key.ordinal_position) = ARRAY['brain_id', 'instance_slug']", table);
    }

    private void migrate(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private String newSchema() {
        return "v35_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void execute(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private String stringValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getString(1);
            }
        }
    }

    private int intValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getInt(1);
            }
        }
    }

    private boolean booleanValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getBoolean(1);
            }
        }
    }

    private UUID uuidValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "query must return one row: " + sql);
                return result.getObject(1, UUID.class);
            }
        }
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private void assertRejected(SqlStatement statement) {
        assertThrows(Exception.class, statement::run);
    }

    @FunctionalInterface
    private interface SqlStatement {
        void run() throws Exception;
    }

    private record HistoricalRows(UUID brainId, String slug, UUID releaseId, UUID registrationId,
                                  UUID runId) {}
}
