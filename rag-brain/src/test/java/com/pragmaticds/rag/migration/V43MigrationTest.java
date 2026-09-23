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
 * Migration contract for a registration's opaque subject scope.
 *
 * <p>The scope is what a finding's {@code subjectKey} is hashed against, so a loan officer's
 * waiver on one upload can match the same problem on the next. Two properties matter, and they
 * pull in opposite directions — which is why the database decides them rather than a service.
 *
 * <p><b>UPDATE is refused.</b> A queued run re-resolves its registration at dispatch. A scope that
 * could be edited in place would let a run queued for one loan be reported against another, and
 * would silently move every waiver taken against it. The service refuses a differing write, but
 * the trigger is what makes that true even when the service is wrong.
 *
 * <p><b>DELETE is NOT refused</b>, the same deliberate asymmetry V42 makes. A per-loan correlator
 * that no retention sweep can ever reach is a worse property than one that cannot be edited.
 *
 * <p>Unlike its loan-facts sibling this table stores plaintext: a subject scope is a token the
 * caller derives from a loan id and this process contractually cannot reverse, so there is nothing
 * for a cipher to protect. What is pinned instead is that the text stays a bounded, single-line,
 * non-blank identifier — the same discipline V40 applies to {@code tenant_id}, so a caller's value
 * can never forge a second line in a log or an export that prints it.
 */
@Testcontainers
class V43MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void oneRegistrationCarriesOneSubjectScope() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);

            insertScope(schema, registration, "scope-a");

            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_registration_subject_scope "
                            + "WHERE registration_id = ?", registration));
            // registration_id is the primary key: a second scope for one registration is
            // impossible rather than merely discouraged.
            assertRejected(() -> insertScope(schema, registration, "scope-b"));
        } finally {
            dropSchema(schema);
        }
    }

    /** Immutability is what keeps a queued run bound to the loan it was queued for. */
    @Test
    void aStoredScopeCannotBeEdited() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);
            insertScope(schema, registration, "scope-a");

            assertRejected(() -> execute(schema,
                    "UPDATE lab_registration_subject_scope SET subject_scope = ? "
                            + "WHERE registration_id = ?", "scope-b", registration));

            assertEquals("scope-a", stringValue(schema,
                    "SELECT subject_scope FROM lab_registration_subject_scope "
                            + "WHERE registration_id = ?", registration));
        } finally {
            dropSchema(schema);
        }
    }

    /** The deliberate asymmetry: a per-loan correlator must stay reachable by a purge. */
    @Test
    void aStoredScopeCanStillBeDeleted() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);
            insertScope(schema, registration, "scope-a");

            execute(schema, "DELETE FROM lab_registration_subject_scope "
                    + "WHERE registration_id = ?", registration);

            assertEquals(0, intValue(schema,
                    "SELECT count(*) FROM lab_registration_subject_scope "
                            + "WHERE registration_id = ?", registration));

            // ON DELETE RESTRICT: a purge has to remove the scope first rather than orphan it.
            insertScope(schema, registration, "scope-a");
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_document_registration WHERE id = ?", registration));
        } finally {
            dropSchema(schema);
        }
    }

    /** A blank or control-bearing scope is not storable at all. */
    @Test
    void aScopeMustBeBoundedSingleLineText() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);

            assertRejected(() -> insertScope(schema, registration, "   "));
            assertRejected(() -> insertScope(schema, registration, "scope\na"));
        } finally {
            dropSchema(schema);
        }
    }

    // ================================================================ fixtures

    /** One UPLOAD_ONE registration plus the two rows its foreign keys require. */
    private UUID registration(String schema) throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_instance (brain_id, slug, display_name, purpose) "
                        + "VALUES (?, 'assets', 'Assets', 'Evaluate parsed asset documents')",
                TestBrains.DEFAULT_ID);
        execute(schema, "INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?)", packageId, TestBrains.DEFAULT_ID);
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + " engine_source_id, registration_mode) "
                        + "VALUES (?, ?, 'assets', ?, ?, ?, 'UPLOAD_ONE')",
                registrationId, TestBrains.DEFAULT_ID, packageId,
                UUID.randomUUID(), UUID.randomUUID());
        return registrationId;
    }

    private void insertScope(String schema, UUID registrationId, String scope) throws Exception {
        execute(schema, "INSERT INTO lab_registration_subject_scope "
                        + "(registration_id, brain_id, subject_scope) VALUES (?, ?, ?)",
                registrationId, TestBrains.DEFAULT_ID, scope);
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
        return "v43_" + UUID.randomUUID().toString().replace("-", "");
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
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private String stringValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
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
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }
}
