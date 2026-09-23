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
 * Migration contract for a registration's encrypted loan facts.
 *
 * <p>The two properties worth pinning here pull in opposite directions, which is exactly why the
 * database rather than a service decides them.
 *
 * <p><b>UPDATE is refused.</b> A queued run re-resolves its registration at dispatch, so facts
 * that could be edited in place would let two members of one comparison group run against
 * different income figures depending only on when each was picked up. The service turns an
 * identical re-write into a no-op and a differing one into a refusal, but the trigger is what
 * makes that true even if the service is wrong.
 *
 * <p><b>DELETE is NOT refused</b>, unlike most Lab tables. These are borrower financial figures,
 * and a row beyond the reach of any future retention sweep is a worse property than one that
 * cannot be edited. This test pins the asymmetry so a later migration cannot quietly "tidy" it
 * into matching its siblings.
 *
 * <p>Everything else asserted here is a shape constraint that keeps unauthenticated bytes out:
 * a nonce of the wrong width or a ciphertext too short to carry a GCM tag is not storable at all.
 */
@Testcontainers
class V42MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String SHA = "ab".repeat(32);

    @Test
    void oneRegistrationCarriesOneSealedSetOfLoanFacts() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);

            insertFacts(schema, registration, SHA);

            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_registration_loan_facts WHERE registration_id = ?",
                    registration));
            // registration_id is the primary key: a second set for one registration is impossible
            // rather than merely discouraged.
            assertRejected(() -> insertFacts(schema, registration, "cd".repeat(32)));
        } finally {
            dropSchema(schema);
        }
    }

    /** Content immutability is the property that keeps a queued run reproducible. */
    @Test
    void storedFactsCannotBeEdited() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);
            insertFacts(schema, registration, SHA);

            assertRejected(() -> execute(schema,
                    "UPDATE lab_registration_loan_facts SET facts_sha256 = ? "
                            + "WHERE registration_id = ?", "cd".repeat(32), registration));

            assertEquals(SHA, stringValue(schema,
                    "SELECT facts_sha256 FROM lab_registration_loan_facts "
                            + "WHERE registration_id = ?", registration));
        } finally {
            dropSchema(schema);
        }
    }

    /**
     * The deliberate asymmetry: borrower figures must stay reachable by a retention sweep.
     *
     * <p>lab_run_payload makes the same split for the same reason. A row that can never be
     * removed would put this table permanently outside any purge.
     */
    @Test
    void storedFactsCanStillBeDeleted() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);
            insertFacts(schema, registration, SHA);

            execute(schema, "DELETE FROM lab_registration_loan_facts WHERE registration_id = ?",
                    registration);

            assertEquals(0, intValue(schema,
                    "SELECT count(*) FROM lab_registration_loan_facts"));
        } finally {
            dropSchema(schema);
        }
    }

    /** Bytes that cannot be an AES-256-GCM record are not storable, so none can accumulate. */
    @Test
    void unauthenticatableBytesAreRefusedByTheSchemaItself() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);

            // A nonce of the wrong width — GCM's 12 bytes is a schema fact, not a convention.
            assertRejected(() -> insertFacts(schema, registration, SHA, new byte[11], new byte[32],
                    "AES-256-GCM"));
            // A "ciphertext" shorter than a bare 128-bit tag cannot have been authenticated.
            assertRejected(() -> insertFacts(schema, registration, SHA, new byte[12], new byte[15],
                    "AES-256-GCM"));
            // Any other algorithm, including a stronger-sounding one.
            assertRejected(() -> insertFacts(schema, registration, SHA, new byte[12], new byte[32],
                    "AES-512-GCM"));
            // The digest is over the canonical plaintext and is always lowercase hex.
            assertRejected(() -> insertFacts(schema, registration, "NOT-A-DIGEST"));
            assertRejected(() -> insertFacts(schema, registration, "AB".repeat(32)));
        } finally {
            dropSchema(schema);
        }
    }

    /** Facts belong to a registration that exists; an orphan set is refused on insert. */
    @Test
    void factsCannotOutliveOrPrecedeTheirRegistration() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema);
            UUID registration = registration(schema);
            insertFacts(schema, registration, SHA);

            assertRejected(() -> insertFacts(schema, UUID.randomUUID(), SHA));
            // And the registration cannot be removed out from under them: ON DELETE RESTRICT,
            // so a purge has to remove the facts first rather than orphaning them.
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_document_registration WHERE id = ?", registration));
        } finally {
            dropSchema(schema);
        }
    }

    // ================================================================ fixtures

    /**
     * One UPLOAD_ONE registration plus the two rows its foreign keys require.
     *
     * <p>The instance row is not optional scaffolding. V35 gave every Lab table a composite
     * {@code (brain_id, instance_slug)} reference to {@code lab_instance} and backfilled the
     * registry from whatever history the database already held — which on a schema migrated from
     * empty is nothing at all. So an instance that exists in production has to be created here.
     */
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

    private void insertFacts(String schema, UUID registrationId, String digest) throws Exception {
        insertFacts(schema, registrationId, digest, new byte[12], new byte[32], "AES-256-GCM");
    }

    private void insertFacts(String schema, UUID registrationId, String digest,
                             byte[] nonce, byte[] ciphertext, String algorithm) throws Exception {
        execute(schema, "INSERT INTO lab_registration_loan_facts "
                        + "(registration_id, brain_id, facts_sha256, cipher_algorithm, "
                        + " nonce, ciphertext) VALUES (?, ?, ?, ?, ?, ?)",
                registrationId, TestBrains.DEFAULT_ID, digest, algorithm, nonce, ciphertext);
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
        return "v42_" + UUID.randomUUID().toString().replace("-", "");
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

    private String stringValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
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
