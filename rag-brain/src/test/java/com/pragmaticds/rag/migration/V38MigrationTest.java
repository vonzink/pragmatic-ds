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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Migration contract for generalized parsed-input registration.
 *
 * <p>V34 enforced cross-brain isolation by making engine package identity globally unique, which
 * also made reuse impossible. V38 moves that guarantee to an explicit package-to-brain binding so
 * one immutable parse can be selected by several instances in a brain while still never crossing
 * into another brain. The historical-data tests start from V37, seed real prototype rows, and only
 * then apply V38, proving the change is additive rather than a rewrite of registration history.
 */
@Testcontainers
class V38MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String INCOME = "income";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void backfillsPackageBindingsAndUploadSourcesWithoutRewritingRegistrationHistory()
            throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "37");
            UUID pkg = UUID.randomUUID();
            UUID job = UUID.randomUUID();
            UUID source = UUID.randomUUID();
            UUID registration = insertLegacyRegistration(
                    schema, TestBrains.DEFAULT_ID, INCOME, pkg, job, source);

            migrate(schema, null);

            // The historical row keeps every identity it was created with.
            assertEquals("UPLOAD_ONE", stringValue(schema,
                    "SELECT registration_mode FROM lab_document_registration WHERE id = ?",
                    registration));
            assertEquals(source, uuidValue(schema,
                    "SELECT engine_source_id FROM lab_document_registration WHERE id = ?",
                    registration));
            assertNull(stringValue(schema,
                    "SELECT source_set_sha256 FROM lab_document_registration WHERE id = ?",
                    registration));

            // Exactly one binding per historical package, pointing at its original brain.
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_engine_package_binding WHERE engine_package_id = ? "
                            + "AND brain_id = ?", pkg, TestBrains.DEFAULT_ID));

            // The upload's single source is carried forward at position 0. Its digest stays NULL
            // on purpose: no source digest for these rows exists anywhere in V1-V37, and inventing
            // one would forge a verification that never happened.
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_document_registration_source WHERE registration_id = ?",
                    registration));
            assertEquals(0, intValue(schema,
                    "SELECT source_position FROM lab_document_registration_source "
                            + "WHERE registration_id = ? AND engine_source_id = ?",
                    registration, source));
            assertNull(stringValue(schema,
                    "SELECT content_sha256 FROM lab_document_registration_source "
                            + "WHERE registration_id = ? AND engine_source_id = ?",
                    registration, source));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void onePackageServesManyInstancesInItsBrainAndNoInstanceInAnotherBrain() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID otherBrain = insertBrain(schema);
            UUID pkg = UUID.randomUUID();
            bindPackage(schema, pkg, TestBrains.DEFAULT_ID);

            // The same immutable revision, selected independently by two instances in one brain.
            insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 3, HASH_A);
            insertSelection(schema, TestBrains.DEFAULT_ID, "assets", pkg, 3, HASH_A);
            assertEquals(2, intValue(schema,
                    "SELECT count(*) FROM lab_document_registration WHERE engine_package_id = ?",
                    pkg));

            // A second brain cannot claim the package, and cannot register against it either.
            assertRejected(() -> bindPackage(schema, pkg, otherBrain));
            assertRejected(() -> insertSelection(schema, otherBrain, INCOME, pkg, 3, HASH_A));

            // Re-selecting the same revision for the same instance collides instead of duplicating.
            assertRejected(() -> insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 3, HASH_A));

            // A different revision of the same package is a different selection.
            insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 4, HASH_B);
            assertEquals(3, intValue(schema,
                    "SELECT count(*) FROM lab_document_registration WHERE engine_package_id = ?",
                    pkg));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registrationShapeKeepsTheTwoModesStructurallyExclusive() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID pkg = UUID.randomUUID();
            bindPackage(schema, pkg, TestBrains.DEFAULT_ID);
            ensureInstance(schema, TestBrains.DEFAULT_ID, INCOME);

            // EXISTING_PARSE must carry a real revision and a lowercase 64-hex source-set digest.
            assertRejected(() -> insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 0, HASH_A));
            assertRejected(() -> insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, -1, HASH_A));
            assertRejected(() -> insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 1, "ABC"));
            assertRejected(() -> insertSelection(
                    schema, TestBrains.DEFAULT_ID, INCOME, pkg, 1, HASH_A.toUpperCase()));
            assertRejected(() -> insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 1, null));

            // An existing parse may not also claim a single uploaded source.
            assertRejected(() -> execute(schema, "INSERT INTO lab_document_registration "
                            + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                            + "engine_source_id, registration_mode, selected_revision, source_set_sha256) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'EXISTING_PARSE', 1, ?)",
                    UUID.randomUUID(), TestBrains.DEFAULT_ID, INCOME, pkg, UUID.randomUUID(),
                    UUID.randomUUID(), HASH_A));

            // An upload may not claim a revision or a source set.
            assertRejected(() -> execute(schema, "INSERT INTO lab_document_registration "
                            + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                            + "engine_source_id, registration_mode, selected_revision) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'UPLOAD_ONE', 2)",
                    UUID.randomUUID(), TestBrains.DEFAULT_ID, INCOME, pkg, UUID.randomUUID(),
                    UUID.randomUUID()));

            // And an unknown mode is not a mode at all.
            assertRejected(() -> execute(schema, "INSERT INTO lab_document_registration "
                            + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                            + "engine_source_id, registration_mode) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'SOMETHING_ELSE')",
                    UUID.randomUUID(), TestBrains.DEFAULT_ID, INCOME, pkg, UUID.randomUUID(),
                    UUID.randomUUID()));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void registrationIdentityIsFrozenWhileTheReadTimestampStillMoves() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID pkg = UUID.randomUUID();
            bindPackage(schema, pkg, TestBrains.DEFAULT_ID);
            UUID registration = insertSelection(
                    schema, TestBrains.DEFAULT_ID, INCOME, pkg, 2, HASH_A);

            // The one field a read path is allowed to move.
            execute(schema, "UPDATE lab_document_registration SET last_read_at = now() WHERE id = ?",
                    registration);

            assertRejected(() -> execute(schema,
                    "UPDATE lab_document_registration SET selected_revision = 3 WHERE id = ?",
                    registration));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_document_registration SET source_set_sha256 = ? WHERE id = ?",
                    HASH_B, registration));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_document_registration SET registration_mode = 'UPLOAD_ONE' WHERE id = ?",
                    registration));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_document_registration SET instance_slug = 'assets' WHERE id = ?",
                    registration));

            // A binding is an identity decision too.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_engine_package_binding SET brain_id = ? WHERE engine_package_id = ?",
                    insertBrain(schema), pkg));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void selectedSourceRowsCarryIdentityOnlyAndNeverChange() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID pkg = UUID.randomUUID();
            bindPackage(schema, pkg, TestBrains.DEFAULT_ID);
            UUID registration = insertSelection(
                    schema, TestBrains.DEFAULT_ID, INCOME, pkg, 1, HASH_A);
            UUID source = UUID.randomUUID();
            insertSelectedSource(schema, registration, source, HASH_B, 0);

            // Identity, digest, and caller order only: nothing here can hold source content.
            assertEquals(
                    List.of("content_sha256", "engine_source_id", "registration_id",
                            "source_position"),
                    columnsOf(schema, "lab_document_registration_source"));

            assertRejected(() -> insertSelectedSource(schema, registration, source, HASH_A, 1));
            assertRejected(() -> insertSelectedSource(
                    schema, registration, UUID.randomUUID(), HASH_A, 0));
            assertRejected(() -> insertSelectedSource(
                    schema, registration, UUID.randomUUID(), "not-a-digest", 1));
            assertRejected(() -> insertSelectedSource(
                    schema, registration, UUID.randomUUID(), HASH_A, -1));

            assertRejected(() -> execute(schema,
                    "UPDATE lab_document_registration_source SET content_sha256 = ? "
                            + "WHERE registration_id = ? AND engine_source_id = ?",
                    HASH_A, registration, source));

            // Deleting a child row would shrink the resolved set with nothing on the parent to
            // contradict it, so removal is refused exactly like modification.
            assertRejected(() -> execute(schema,
                    "DELETE FROM lab_document_registration_source "
                            + "WHERE registration_id = ? AND engine_source_id = ?",
                    registration, source));
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_document_registration_source "
                            + "WHERE registration_id = ?", registration));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void runPayloadsAcceptExactlyAnalysisOutputAndRunProvenance() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID run = insertRun(schema);

            insertPayload(schema, run, "ANALYSIS_OUTPUT");
            insertPayload(schema, run, "RUN_PROVENANCE");
            assertEquals(2, intValue(schema,
                    "SELECT count(*) FROM lab_run_payload WHERE run_id = ?", run));

            // One of each per run, and nothing else is a payload type.
            assertRejected(() -> insertPayload(schema, run, "ANALYSIS_OUTPUT"));
            assertRejected(() -> insertPayload(schema, run, "PROMPT"));
            assertRejected(() -> insertPayload(schema, run, "RUN_PROVENANCE_V2"));
        } finally {
            dropSchema(schema);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private UUID insertBrain(String schema) throws Exception {
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO brains (id, slug, display_name, is_default, is_active) "
                + "VALUES (?, ?, 'Other brain', false, true)", id, "brain-" + shortId(id));
        return id;
    }

    /** V35 made the formerly free-form instance identity an explicit registry row. */
    private void ensureInstance(String schema, UUID brainId, String slug) throws Exception {
        execute(schema, "INSERT INTO lab_instance (brain_id, slug, display_name, purpose) "
                        + "VALUES (?, ?, ?, '') ON CONFLICT (brain_id, slug) DO NOTHING",
                brainId, slug, slug);
    }

    private void bindPackage(String schema, UUID packageId, UUID brainId) throws Exception {
        execute(schema, "INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?)", packageId, brainId);
    }

    /** A V37-era upload registration, written before the mode columns existed. */
    private UUID insertLegacyRegistration(String schema, UUID brainId, String slug, UUID packageId,
                                          UUID jobId, UUID sourceId) throws Exception {
        ensureInstance(schema, brainId, slug);
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, engine_source_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, brainId, slug, packageId, jobId, sourceId);
        return id;
    }

    private UUID insertSelection(String schema, UUID brainId, String slug, UUID packageId,
                                 int revision, String sourceSetSha) throws Exception {
        ensureInstance(schema, brainId, slug);
        UUID id = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + "registration_mode, selected_revision, source_set_sha256) "
                        + "VALUES (?, ?, ?, ?, ?, 'EXISTING_PARSE', ?, ?)",
                id, brainId, slug, packageId, UUID.randomUUID(), revision, sourceSetSha);
        return id;
    }

    private void insertSelectedSource(String schema, UUID registrationId, UUID sourceId,
                                      String contentSha, int position) throws Exception {
        execute(schema, "INSERT INTO lab_document_registration_source "
                        + "(registration_id, engine_source_id, content_sha256, source_position) "
                        + "VALUES (?, ?, ?, ?)",
                registrationId, sourceId, contentSha, position);
    }

    /** A minimal PROCESSING run, which is all a payload needs to exist. */
    private UUID insertRun(String schema) throws Exception {
        UUID pkg = UUID.randomUUID();
        bindPackage(schema, pkg, TestBrains.DEFAULT_ID);
        UUID registration = insertSelection(schema, TestBrains.DEFAULT_ID, INCOME, pkg, 1, HASH_A);
        UUID release = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, "
                        + "manifest_sha256, manifest) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCTION', ?, '{}'::jsonb)",
                release, TestBrains.DEFAULT_ID, INCOME, releaseNumber(schema), HASH_A);
        UUID run = UUID.randomUUID();
        // Its only caller migrates to latest, where V39 makes group membership mandatory. The
        // shape is the one V39's backfill gives a historical run: a one-member INDEPENDENT group
        // keyed by the run's own id, at the empty legacy-unpriced catalog version.
        execute(schema, "INSERT INTO lab_run_group "
                        + "(id, brain_id, mode, idempotency_key, request_sha256, status) "
                        + "VALUES (?, ?, 'INDEPENDENT', ?, encode(sha256(?::bytea), 'hex'), "
                        + "'PROCESSING')",
                run, TestBrains.DEFAULT_ID, "legacy:" + run, "legacy:" + run);
        execute(schema, "INSERT INTO lab_run "
                        + "(id, brain_id, instance_slug, idempotency_key, release_id, "
                        + "registration_id, status, lease_expires_at, "
                        + "run_group_id, member_index, pricing_version_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', now() + interval '5 minutes', "
                        + "?, 0, '00000000-0000-4000-8000-00000000f001')",
                run, TestBrains.DEFAULT_ID, INCOME, "key-" + shortId(run), release, registration,
                run);
        return run;
    }

    private int releaseNumber(String schema) throws Exception {
        return intValue(schema,
                "SELECT COALESCE(max(release_number), 0) + 1 FROM lab_instance_release "
                        + "WHERE brain_id = ? AND instance_slug = ?", TestBrains.DEFAULT_ID, INCOME);
    }

    private void insertPayload(String schema, UUID runId, String payloadType) throws Exception {
        execute(schema, "INSERT INTO lab_run_payload "
                        + "(id, run_id, payload_type, cipher_algorithm, nonce, ciphertext) "
                        + "VALUES (?, ?, ?, 'AES-256-GCM', "
                        + "decode('000000000000000000000000', 'hex'), "
                        + "decode('00000000000000000000000000000000', 'hex'))",
                UUID.randomUUID(), runId, payloadType);
    }

    // ---------------------------------------------------------------- harness

    private List<String> columnsOf(String schema, String table) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = current_schema() AND table_name = ? "
                             + "ORDER BY column_name")) {
            statement.setObject(1, table);
            try (ResultSet result = statement.executeQuery()) {
                List<String> columns = new java.util.ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return List.copyOf(columns);
            }
        }
    }

    private String newSchema() {
        return "v38_" + UUID.randomUUID().toString().replace("-", "");
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

    private UUID uuidValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
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

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface SqlStatement {
        void run() throws Exception;
    }
}
