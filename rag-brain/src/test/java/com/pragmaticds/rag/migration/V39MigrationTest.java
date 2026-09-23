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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration contract for grouped runs, versioned pricing, usage, and promotion history.
 *
 * <p><b>Numbered 39, not 38.</b> The Phase 4 plan reserved V38 for this work on the assumption
 * that V37 was the latest applied migration. Phase 3 shipped V38 first, and applied migrations are
 * immutable history, so this took the next free number exactly as that plan's own constraint says
 * to.
 *
 * <p>The historical-data tests start from V38, seed real prototype runs in every status V34
 * allows, and only then apply V39. That ordering is the point rather than a formality: the
 * backfill has to update rows that V34's guard trigger refuses to touch, and on an empty database
 * that statement is a silent no-op. Only a database that already holds terminal runs — production
 * — can tell you whether the migration actually works.
 */
@Testcontainers
class V39MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String INCOME = "income";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final UUID LEGACY_PRICING =
            UUID.fromString("00000000-0000-4000-8000-00000000f001");

    @Test
    void backfillsEveryHistoricalRunIntoItsOwnGroupWithoutRewritingRunHistory() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "38");
            UUID succeeded = insertLegacyRun(schema, "SUCCEEDED");
            UUID failed = insertLegacyRun(schema, "FAILED");
            UUID interrupted = insertLegacyRun(schema, "INTERRUPTED");
            UUID processing = insertLegacyRun(schema, "PROCESSING");

            migrate(schema, null);

            // Group id IS run id, so no run, release, registration, or analysis id moves.
            for (UUID run : List.of(succeeded, failed, interrupted, processing)) {
                assertEquals(run, uuidValue(schema,
                        "SELECT run_group_id FROM lab_run WHERE id = ?", run));
                assertEquals(0, intValue(schema,
                        "SELECT member_index FROM lab_run WHERE id = ?", run));
                assertEquals("INDEPENDENT", stringValue(schema,
                        "SELECT mode FROM lab_run_group WHERE id = ?", run));
                assertEquals("legacy:" + run, stringValue(schema,
                        "SELECT idempotency_key FROM lab_run_group WHERE id = ?", run));
                // Priced against the empty legacy version rather than a fabricated price: a run
                // that predates versioned pricing has no price, and saying so is the honest answer.
                assertEquals(LEGACY_PRICING, uuidValue(schema,
                        "SELECT pricing_version_id FROM lab_run WHERE id = ?", run));
            }

            assertEquals("SUCCEEDED", groupStatus(schema, succeeded));
            assertEquals("FAILED", groupStatus(schema, failed));
            assertEquals("FAILED", groupStatus(schema, interrupted));
            assertEquals("PROCESSING", groupStatus(schema, processing));

            // The successful run keeps the analyzer row it claimed before the migration.
            assertEquals(1, intValue(schema,
                    "SELECT count(*) FROM lab_run WHERE id = ? AND analysis_run_id IS NOT NULL",
                    succeeded));
            assertEquals(0, intValue(schema,
                    "SELECT count(*) FROM lab_model_catalog_entry"),
                    "the legacy pricing version prices nothing, deliberately");
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void theRunGuardIsRestoredAfterTheBackfillSuspendsIt() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, "38");
            UUID succeeded = insertLegacyRun(schema, "SUCCEEDED");

            migrate(schema, null);

            // The backfill disables trg_lab_run_guard to update terminal rows. If it failed to
            // re-enable it, every terminal run in the database would silently become mutable.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET failure_code = 'tampered' WHERE id = ?", succeeded));
            assertTrue(booleanValue(schema,
                    "SELECT tgenabled = 'O' FROM pg_trigger WHERE tgname = 'trg_lab_run_guard'"),
                    "the guard must be enabled again once the backfill has run");
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void aGroupDeclaresWhatItVariesOrDeclaresNothing() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);

            // An independent group varies nothing and so names nothing.
            assertRejected(() -> insertGroup(schema, "INDEPENDENT", "MODEL", null, "k1"));
            // A comparison must pin the digest of everything it holds equal. Both halves are
            // probed because a CHECK is satisfied by NULL as well as by TRUE, so an arm that
            // compares against NULL without an IS NOT NULL guard is a hole, not a rule.
            assertRejected(() -> insertGroup(schema, "COMPARISON", "MODEL", null, "k2"));
            assertRejected(() -> insertGroup(schema, "COMPARISON", null, HASH_B, "k3"));
            insertGroup(schema, "COMPARISON", "MODEL", HASH_B, "k4");
            insertGroup(schema, "INDEPENDENT", null, null, "k5");

            assertEquals(2, intValue(schema, "SELECT count(*) FROM lab_run_group"));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void oneRunReachesOneTerminalStateAndCannotBeRevived() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID run = insertQueuedMember(schema);

            // A queued member holds no lease and has claimed no analyzer row.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET lease_expires_at = now() + interval '5 minutes' "
                            + "WHERE id = ?", run));
            // Skipping PROCESSING would mean a run that was never dispatched claiming an answer.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET status = 'SUCCEEDED', terminal_at = now() WHERE id = ?",
                    run));

            execute(schema, "UPDATE lab_run SET status = 'CANCELLED', terminal_at = now() "
                    + "WHERE id = ?", run);

            // An expired lease is a no-replay boundary, so nothing may leave a terminal state.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET status = 'PROCESSING', terminal_at = NULL, "
                            + "lease_expires_at = now() + interval '5 minutes' WHERE id = ?", run));
            assertEquals("CANCELLED", stringValue(schema,
                    "SELECT status FROM lab_run WHERE id = ?", run));
            assertNull(uuidValue(schema, "SELECT analysis_run_id FROM lab_run WHERE id = ?", run));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void aRunCannotJoinAnotherBrainsGroupOrGroundOnItsSnapshot() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID other = insertBrain(schema, "other-brain");
            UUID foreignGroup = insertGroupFor(schema, other, "k-foreign");
            UUID foreignSnapshot = insertSnapshot(schema, other, HASH_B);
            UUID run = insertQueuedMember(schema);

            // Composite foreign keys, so cross-brain membership and cross-brain grounding are
            // unrepresentable rather than merely forbidden in service code.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET run_group_id = ? WHERE id = ?", foreignGroup, run));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_run SET corpus_snapshot_id = ? WHERE id = ?", foreignSnapshot, run));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void anEstimateNeverMovesAndUsageIsReportedExactlyOnce() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID run = insertQueuedMember(schema);
            UUID usage = insertUsage(schema, run);

            // Absent provider usage is never converted into a reported zero.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_model_usage SET usage_quality = 'UNAVAILABLE', "
                            + "actual_total_tokens = 0, actual_cost_usd = 0, reported_at = now() "
                            + "WHERE id = ?", usage));
            // Nor is a report accepted without the numbers that make it a report.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_model_usage SET usage_quality = 'REPORTED' WHERE id = ?", usage));
            // An estimate that could be rewritten after the fact makes every budget decision
            // unauditable, so it is immutable even in the one legal transition.
            assertRejected(() -> execute(schema,
                    "UPDATE lab_model_usage SET usage_quality = 'REPORTED', "
                            + "actual_total_tokens = 1500, actual_cost_usd = 0.2, "
                            + "reported_at = now(), expected_cost_usd_max = 99 WHERE id = ?",
                    usage));

            execute(schema, "UPDATE lab_model_usage SET usage_quality = 'REPORTED', "
                    + "actual_input_tokens = 1200, actual_output_tokens = 300, "
                    + "actual_total_tokens = 1500, actual_cost_usd = 0.215000, "
                    + "reported_at = now() WHERE id = ?", usage);
            assertRejected(() -> execute(schema,
                    "UPDATE lab_model_usage SET actual_total_tokens = 1600 WHERE id = ?", usage));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void aReservationIsConsumedOrReleasedOnceAndNeverBoth() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            UUID run = insertQueuedMember(schema);
            UUID reservation = UUID.randomUUID();
            execute(schema, "INSERT INTO lab_spend_reservation "
                            + "(id, run_id, brain_id, reserved_max_usd) VALUES (?, ?, ?, 0.40)",
                    reservation, run, TestBrains.DEFAULT_ID);

            execute(schema, "UPDATE lab_spend_reservation SET status = 'CONSUMED', "
                    + "terminal_at = now() WHERE id = ?", reservation);
            assertRejected(() -> execute(schema,
                    "UPDATE lab_spend_reservation SET status = 'RELEASED' WHERE id = ?",
                    reservation));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void pointerHistoryIsAppendOnlyAndCarriesNoSmuggledStructure() throws Exception {
        String schema = newSchema();
        try {
            // Stop at V38 and plant a live pointer FIRST, so the version assertion below is about
            // a pointer that predates this migration. Querying a table the test never populated
            // asserted nothing at all — it just threw on an empty result set.
            migrate(schema, "38");
            UUID preExisting = insertRelease(schema);
            execute(schema, "INSERT INTO lab_instance_pointer "
                            + "(brain_id, instance_slug, production_release_id) "
                            + "VALUES (?, ?, ?)",
                    TestBrains.DEFAULT_ID, INCOME, preExisting);

            migrate(schema, null);
            UUID release = insertRelease(schema);

            // A newline in an audit export is a way to fake a second record.
            assertRejected(() -> insertPointerEvent(schema, "PROMOTE", null, release, 1,
                    "ops@example.com", "line one\ninjected"));
            // A rollback always names what it moved away from; only a first promotion cannot.
            assertRejected(() -> insertPointerEvent(schema, "ROLLBACK", null, release, 1,
                    "ops@example.com", "revert"));

            insertPointerEvent(schema, "PROMOTE", null, release, 1, "ops@example.com", "ship it");

            // One event per pointer version, and no event is ever rewritten or removed.
            assertRejected(() -> insertPointerEvent(schema, "PROMOTE", release, release, 1,
                    "ops@example.com", "again"));
            assertRejected(() -> execute(schema,
                    "UPDATE lab_instance_pointer_event SET change_reason = 'rewritten'"));
            assertRejected(() -> execute(schema, "DELETE FROM lab_instance_pointer_event"));

            assertEquals(0, intValue(schema,
                    "SELECT pointer_version FROM lab_instance_pointer "
                            + "WHERE brain_id = ? AND instance_slug = ?",
                    TestBrains.DEFAULT_ID, INCOME),
                    "the migration adds the CAS column at zero for a pointer that already existed");
            assertEquals(preExisting, uuidValue(schema,
                    "SELECT production_release_id FROM lab_instance_pointer "
                            + "WHERE brain_id = ? AND instance_slug = ?",
                    TestBrains.DEFAULT_ID, INCOME),
                    "and moves no pointer while doing it");
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void everyNewTableHoldsMetadataAndHasNowhereToPutContent() throws Exception {
        String schema = newSchema();
        try {
            migrate(schema, null);
            // The bounded administrative prose on the pointer event is the only free text added
            // by this migration; nothing else may hold a prompt, an answer, evidence, a provider
            // body, a filename, a URL, or a credential.
            for (String table : List.of("lab_run_group", "lab_model_catalog_version",
                    "lab_model_catalog_entry", "lab_model_usage", "lab_spend_reservation",
                    "lab_release_evaluation", "lab_instance_pointer_event",
                    "lab_discussion_model_usage")) {
                assertEquals(List.of(), columnsOfType(schema, table, "text", "jsonb", "bytea"),
                        table + " gained a free-form column a value could hide in");
            }
            // The pointer event's two bounded administrative strings are the only prose added,
            // and both are constrained to non-blank, control-character-free text.
            assertEquals(List.of("action", "actor_id", "change_reason", "instance_slug"),
                    columnsOfType(schema, "lab_instance_pointer_event", "character varying"));
        } finally {
            dropSchema(schema);
        }
    }

    // ================================================================ fixtures

    private String groupStatus(String schema, UUID groupId) throws Exception {
        return stringValue(schema, "SELECT status FROM lab_run_group WHERE id = ?", groupId);
    }

    /** A V38-era run in one of the four statuses V34 allowed, written before groups existed. */
    private UUID insertLegacyRun(String schema, String status) throws Exception {
        UUID registration = insertRegistration(schema);
        UUID release = insertRelease(schema);
        UUID run = UUID.randomUUID();
        UUID analysis = null;
        if ("SUCCEEDED".equals(status)) {
            analysis = UUID.randomUUID();
            execute(schema, "INSERT INTO analysis_runs "
                            + "(id, brain_id, analyzer_slug, envelope_version, status) "
                            + "VALUES (?, ?, ?, '1.0.0', 'SUCCESS')",
                    analysis, TestBrains.DEFAULT_ID, INCOME);
        }
        boolean processing = "PROCESSING".equals(status);
        execute(schema, "INSERT INTO lab_run "
                        + "(id, brain_id, instance_slug, idempotency_key, release_id, "
                        + "registration_id, status, lease_expires_at, terminal_at, analysis_run_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, "
                        + (processing ? "now() + interval '5 minutes', NULL, " : "NULL, now(), ")
                        + "?)",
                run, TestBrains.DEFAULT_ID, INCOME, "key-" + shortId(run), release, registration,
                status, analysis);
        return run;
    }

    /** A post-V39 queued member of a real group. */
    private UUID insertQueuedMember(String schema) throws Exception {
        UUID group = insertGroupFor(schema, TestBrains.DEFAULT_ID, "k-" + shortId(UUID.randomUUID()));
        UUID run = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_run "
                        + "(id, brain_id, instance_slug, idempotency_key, release_id, "
                        + "registration_id, status, run_group_id, member_index, pricing_version_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, 0, ?)",
                run, TestBrains.DEFAULT_ID, INCOME, "group:" + group + ":0",
                insertRelease(schema), insertRegistration(schema), group, LEGACY_PRICING);
        return run;
    }

    private UUID insertUsage(String schema, UUID run) throws Exception {
        UUID usage = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_model_usage "
                        + "(id, run_id, brain_id, pricing_version_id, provider, model, "
                        + "expected_input_min, expected_input_max, expected_output_min, "
                        + "expected_output_max, expected_cost_usd_min, expected_cost_usd_max, "
                        + "estimate_quality) "
                        + "VALUES (?, ?, ?, ?, 'anthropic', 'claude-x', 1000, 2000, 100, 400, "
                        + "0.10, 0.40, 'ESTIMATED_RANGE')",
                usage, run, TestBrains.DEFAULT_ID, LEGACY_PRICING);
        return usage;
    }

    private void insertGroup(String schema, String mode, String dimension, String basis, String key)
            throws Exception {
        execute(schema, "INSERT INTO lab_run_group "
                        + "(brain_id, mode, comparison_dimension, comparison_basis_sha256, "
                        + "idempotency_key, request_sha256, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED')",
                TestBrains.DEFAULT_ID, mode, dimension, basis, key, HASH_A);
    }

    private UUID insertGroupFor(String schema, UUID brainId, String key) throws Exception {
        UUID group = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_run_group "
                        + "(id, brain_id, mode, idempotency_key, request_sha256, status) "
                        + "VALUES (?, ?, 'INDEPENDENT', ?, ?, 'QUEUED')",
                group, brainId, key, HASH_A);
        return group;
    }

    private void insertPointerEvent(String schema, String action, UUID from, UUID to, int version,
                                    String actor, String reason) throws Exception {
        execute(schema, "INSERT INTO lab_instance_pointer_event "
                        + "(brain_id, instance_slug, action, from_release_id, to_release_id, "
                        + "pointer_version, actor_id, change_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                TestBrains.DEFAULT_ID, INCOME, action, from, to, version, actor, reason);
    }

    private UUID insertBrain(String schema, String slug) throws Exception {
        UUID brain = UUID.randomUUID();
        execute(schema, "INSERT INTO brains (id, slug, display_name) VALUES (?, ?, ?)",
                brain, slug, slug);
        return brain;
    }

    private UUID insertSnapshot(String schema, UUID brainId, String manifestSha) throws Exception {
        UUID snapshot = UUID.randomUUID();
        execute(schema, "INSERT INTO brain_corpus_snapshot "
                        + "(id, brain_id, manifest, manifest_sha256) "
                        + "VALUES (?, ?, '{}'::jsonb, ?)",
                snapshot, brainId, manifestSha);
        return snapshot;
    }

    /**
     * A release with a digest unique to it.
     *
     * <p>{@code uq_lab_release_manifest} is UNIQUE (brain_id, instance_slug, manifest_sha256), so
     * a shared constant here made the second release of any test a duplicate-key failure — which
     * is what happened to every test that seeds more than one historical run.
     */
    private UUID insertRelease(String schema) throws Exception {
        ensureInstance(schema, TestBrains.DEFAULT_ID, INCOME);
        UUID release = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, "
                        + "manifest_sha256, manifest) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCTION', ?, '{}'::jsonb)",
                release, TestBrains.DEFAULT_ID, INCOME, releaseNumber(schema),
                digestOf(release));
        return release;
    }

    /** 64 hex characters derived from an id, so every fixture release digests differently. */
    private static String digestOf(UUID id) {
        String hex = id.toString().replace("-", "");
        return (hex + hex).substring(0, 64);
    }

    private UUID insertRegistration(String schema) throws Exception {
        ensureInstance(schema, TestBrains.DEFAULT_ID, INCOME);
        UUID pkg = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?)", pkg, TestBrains.DEFAULT_ID);
        UUID registration = UUID.randomUUID();
        execute(schema, "INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + "engine_source_id, registration_mode) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'UPLOAD_ONE')",
                registration, TestBrains.DEFAULT_ID, INCOME, pkg, UUID.randomUUID(),
                UUID.randomUUID());
        return registration;
    }

    private void ensureInstance(String schema, UUID brainId, String slug) throws Exception {
        execute(schema, "INSERT INTO lab_instance (brain_id, slug, display_name, purpose) "
                        + "VALUES (?, ?, ?, '') ON CONFLICT (brain_id, slug) DO NOTHING",
                brainId, slug, slug);
    }

    private int releaseNumber(String schema) throws Exception {
        return intValue(schema, "SELECT coalesce(max(release_number), 0) + 1 "
                + "FROM lab_instance_release WHERE brain_id = ? AND instance_slug = ?",
                TestBrains.DEFAULT_ID, INCOME);
    }

    private List<String> columnsOfType(String schema, String table, String... types)
            throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = ? AND table_name = ? "
                             + "AND data_type = ANY (?) ORDER BY column_name")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setArray(3, connection.createArrayOf("text", types));
            try (ResultSet result = statement.executeQuery()) {
                List<String> columns = new java.util.ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return List.copyOf(columns);
            }
        }
    }

    // ================================================================ plumbing

    private String newSchema() {
        return "v39_" + UUID.randomUUID().toString().replace("-", "");
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

    private boolean booleanValue(String schema, String sql, Object... values) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
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
            if (values[index] == null) {
                // Explicit rather than setObject(i, null): several of these fixtures bind a null
                // UUID, and leaving the type for the driver to infer is how that becomes a
                // "could not determine data type" failure instead of a NULL.
                statement.setNull(index + 1, java.sql.Types.OTHER);
            } else {
                statement.setObject(index + 1, values[index]);
            }
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
