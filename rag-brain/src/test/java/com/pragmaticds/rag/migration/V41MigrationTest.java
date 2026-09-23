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

/**
 * Migration contract for instance-plane retention.
 *
 * <p>V41 makes exactly one change — {@code GROUP} joins the audit subject values so a group purge
 * can leave a tombstone under its own identity — and this test pins both that change and the
 * refusals V41 deliberately does <em>not</em> relax. The refusals are the retention policy's
 * floor: whatever a purge deletes, the database itself keeps refusing to delete idempotency
 * records, catalog history, and audit rows, so no service bug can quietly widen a purge into
 * erasing evidence.
 */
@Testcontainers
class V41MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** The catalog version V39 seeds for legacy runs; exists in every migrated schema. */
    private static final String LEGACY_CATALOG_VERSION = "00000000-0000-4000-8000-00000000f001";

    @Test
    void aGroupPurgeTombstoneIsNowAValidAuditSubject() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);

            insertAuditEvent(schema, "GROUP");

            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_audit_event WHERE subject_type = 'GROUP'"));
            // The widening is exact: a value outside the enumerated set is still refused.
            assertRejected(() -> insertAuditEvent(schema, "SOMETHING_ELSE"));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void everyPreexistingSubjectValueRemainsValid() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            for (String subject : new String[] {"INSTANCE", "RELEASE", "REGISTRATION",
                    "ENVELOPE", "RUN", "EXCHANGE", "COLLECTION", "SNAPSHOT"}) {
                insertAuditEvent(schema, subject);
            }
            assertEquals(8, intValue(schema,
                    "SELECT count(*) FROM lab_audit_event WHERE subject_type <> 'GROUP'"));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void theImmutableHistoryStillRefusesDeletion() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);

            // Audit rows: a purge appends a tombstone, never removes the record.
            insertAuditEvent(schema, "GROUP");
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_audit_event WHERE subject_type = 'GROUP'"));

            // Idempotency records guard operations whose results are retained immutably;
            // expiring a key while its result lives would let a stale retry duplicate it.
            execute(schema, "INSERT INTO lab_idempotency_record "
                            + "(brain_id, operation, idempotency_key, request_sha256, "
                            + " result_kind, result_id) VALUES (?, 'RELEASE_CREATE', 'key-1', ?, "
                            + "'RELEASE', ?)",
                    TestBrains.DEFAULT_ID, "a".repeat(64), UUID.randomUUID());
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_idempotency_record WHERE idempotency_key = 'key-1'"));

            // Catalog history prices finished runs; V39's seeded legacy version must survive
            // any purge. (lab_release_evaluation carries the same refuse-DELETE trigger from
            // the same migration; one representative per mechanism is asserted here.)
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_model_catalog_version WHERE id = ?::uuid",
                    LEGACY_CATALOG_VERSION));
        } finally {
            dropSchema(schema);
        }
    }

    // ================================================================ inserts

    private void insertAuditEvent(String schema, String subjectType) throws Exception {
        execute(schema, "INSERT INTO lab_audit_event "
                        + "(brain_id, action, status, subject_type, subject_id, actor) "
                        + "VALUES (?, 'GROUP_PURGE', 'SUCCEEDED', ?, ?, 'test-actor')",
                TestBrains.DEFAULT_ID, subjectType, UUID.randomUUID());
    }

    private void assertRejected(SqlStatement statement) {
        assertThrows(Exception.class, statement::run);
    }

    @FunctionalInterface
    private interface SqlStatement {
        void run() throws Exception;
    }

    // ================================================================ plumbing

    private String newSchema() {
        return "v41_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void migrate(String schema) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void execute(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private int intValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }
}
