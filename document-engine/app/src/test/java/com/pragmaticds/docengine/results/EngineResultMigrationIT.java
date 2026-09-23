package com.pragmaticds.docengine.results;

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
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Database contract for the append-only V15/V16 machine-result descriptor. */
@Testcontainers
class EngineResultMigrationIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    private static final UUID ORG_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PACKAGE_A =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PACKAGE_B =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID PACKAGE_B_WRITE =
            UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID PACKAGE_A_RLS =
            UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID PACKAGE_A_KEY =
            UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID PACKAGE_B_EXISTING =
            UUID.fromString("20000000-0000-0000-0000-000000000006");
    private static final UUID JOB_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID JOB_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID JOB_B_WRITE =
            UUID.fromString("30000000-0000-0000-0000-000000000005");
    private static final UUID JOB_A_RLS =
            UUID.fromString("30000000-0000-0000-0000-000000000006");
    private static final UUID JOB_A_KEY =
            UUID.fromString("30000000-0000-0000-0000-000000000007");
    private static final UUID JOB_B_EXISTING =
            UUID.fromString("30000000-0000-0000-0000-000000000008");
    private static final UUID LEGACY_JOB =
            UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID RESULT_B_EXISTING =
            UUID.fromString("40000000-0000-0000-0000-000000000099");

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String DIGEST_E = "e".repeat(64);
    private static final String DIGEST_F = "f".repeat(64);
    private static final String DIGEST_1 = "1".repeat(64);
    private static final String DIGEST_2 = "2".repeat(64);
    private static final String DIGEST_3 = "3".repeat(64);
    private static final String MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    @BeforeAll
    static void migrateFromV14() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("14"))
                .load()
                .migrate();

        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name) VALUES ('" + ORG_A + "', 'Org A'), ('"
                            + ORG_B + "', 'Org B')");
            statement.execute(
                    "INSERT INTO document_package (id, org_id, name) VALUES ('" + PACKAGE_A
                            + "', '" + ORG_A + "', 'Package A'), ('" + PACKAGE_B + "', '" + ORG_B
                            + "', 'Package B'), ('" + PACKAGE_B_WRITE + "', '" + ORG_B
                            + "', 'Package B write'), ('" + PACKAGE_A_RLS + "', '" + ORG_A
                            + "', 'Package A RLS'), ('" + PACKAGE_A_KEY + "', '" + ORG_A
                            + "', 'Package A key binding'), ('" + PACKAGE_B_EXISTING + "', '" + ORG_B
                            + "', 'Package B existing result')");
            statement.execute(
                    "INSERT INTO processing_job (id, org_id, package_id, idempotency_key) VALUES ('"
                            + JOB_A + "', '" + ORG_A + "', '" + PACKAGE_A + "', 'job-a'), ('"
                            + JOB_B + "', '" + ORG_B + "', '" + PACKAGE_B + "', 'job-b'), ('"
                            + JOB_B_WRITE + "', '" + ORG_B + "', '" + PACKAGE_B_WRITE
                            + "', 'job-b-write'), ('" + JOB_A_RLS + "', '" + ORG_A + "', '"
                            + PACKAGE_A_RLS + "', 'job-a-rls'), ('" + JOB_A_KEY + "', '" + ORG_A
                            + "', '" + PACKAGE_A_KEY + "', 'job-a-key-binding'), ('"
                            + JOB_B_EXISTING + "', '" + ORG_B + "', '" + PACKAGE_B_EXISTING
                            + "', 'job-b-existing-result'), ('" + LEGACY_JOB + "', '" + ORG_A
                            + "', '" + PACKAGE_A + "', 'legacy-job')");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate();

        // V16 must validate existing V15 descriptors as well as future inserts. Seed a valid
        // foreign-tenant row at V15, then migrate it through the new binding constraint.
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    resultInsert(
                            RESULT_B_EXISTING,
                            ORG_B,
                            PACKAGE_B_EXISTING,
                            JOB_B_EXISTING,
                            1,
                            1,
                            null,
                            DIGEST_A,
                            DIGEST_B,
                            DIGEST_D,
                            1));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appConnection(UUID orgId) throws Exception {
        Connection connection = ownerConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE docengine_app");
            if (orgId != null) {
                statement.execute("SET app.current_org = '" + orgId + "'");
            }
        }
        return connection;
    }

    private static List<String> query(Connection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private static String resultInsert(
            UUID id,
            UUID orgId,
            UUID packageId,
            UUID jobId,
            int generation,
            int revision,
            UUID predecessor,
            String sourceDigest,
            String provenanceDigest,
            String envelopeDigest,
            long size) {
        return resultInsertWithKey(
                id,
                orgId,
                packageId,
                jobId,
                generation,
                revision,
                predecessor,
                sourceDigest,
                provenanceDigest,
                envelopeDigest,
                size,
                "org/"
                        + orgId
                        + "/engine-results/sha256/"
                        + envelopeDigest
                        + ".json");
    }

    private static String resultInsertWithKey(
            UUID id,
            UUID orgId,
            UUID packageId,
            UUID jobId,
            int generation,
            int revision,
            UUID predecessor,
            String sourceDigest,
            String provenanceDigest,
            String envelopeDigest,
            long size,
            String storageKey) {
        return "INSERT INTO engine_result (id, org_id, package_id, processing_job_id,"
                + " parse_generation, materialized_job_attempt, revision, supersedes_result_id,"
                + " envelope_schema_version, canonicalization_version, canonical_media_type,"
                + " source_set_sha256, provenance_sha256, envelope_storage_key, envelope_sha256,"
                + " envelope_size_bytes, reuse_eligibility) VALUES ('"
                + id + "', '" + orgId + "', '" + packageId + "', '" + jobId + "', "
                + generation + ", 1, " + revision + ", "
                + (predecessor == null ? "NULL" : "'" + predecessor + "'")
                + ", '1.0.0', 'DOCENGINE-C14N-1', '" + MEDIA_TYPE + "', '" + sourceDigest
                + "', '" + provenanceDigest + "', '" + storageKey + "', '"
                + envelopeDigest + "', " + size + ", 'PARSE_ONCE_CURRENT_PACKAGE')";
    }

    @Test
    void storage_key_binding_validates_existing_v15_rows() throws Exception {
        try (Connection owner = ownerConnection()) {
            assertThat(query(
                            owner,
                            "SELECT envelope_storage_key FROM engine_result WHERE id = '"
                                    + RESULT_B_EXISTING
                                    + "'"))
                    .containsExactly(
                            "org/"
                                    + ORG_B
                                    + "/engine-results/sha256/"
                                    + DIGEST_D
                                    + ".json");
            assertThat(query(
                            owner,
                            "SELECT convalidated::text FROM pg_constraint"
                                    + " WHERE conrelid = 'engine_result'::regclass"
                                    + " AND conname = 'engine_result_storage_key_binding_check'"))
                    .containsExactly("true");
        }
    }

    @Test
    void application_role_rejects_malformed_or_foreign_result_storage_keys() throws Exception {
        UUID malformed = UUID.fromString("40000000-0000-0000-0000-000000000100");
        UUID foreign = UUID.fromString("40000000-0000-0000-0000-000000000101");
        try (Connection connection = appConnection(ORG_A);
                Statement statement = connection.createStatement()) {
            try {
                assertThatThrownBy(
                                () ->
                                        statement.execute(
                                                resultInsertWithKey(
                                                        malformed,
                                                        ORG_A,
                                                        PACKAGE_A_KEY,
                                                        JOB_A_KEY,
                                                        1,
                                                        1,
                                                        null,
                                                        DIGEST_A,
                                                        DIGEST_B,
                                                        DIGEST_C,
                                                        1,
                                                        "org/test/engine-results/malformed.json")))
                        .hasMessageContaining("engine_result_storage_key_binding_check");

                assertThatThrownBy(
                                () ->
                                        statement.execute(
                                                resultInsertWithKey(
                                                        foreign,
                                                        ORG_A,
                                                        PACKAGE_A_KEY,
                                                        JOB_A_KEY,
                                                        1,
                                                        1,
                                                        null,
                                                        DIGEST_A,
                                                        DIGEST_B,
                                                        DIGEST_C,
                                                        1,
                                                        "org/"
                                                                + ORG_B
                                                                + "/engine-results/sha256/"
                                                                + DIGEST_C
                                                                + ".json")))
                        .hasMessageContaining("engine_result_storage_key_binding_check");
            } finally {
                // On RED, the missing constraint permits these rows. Keep the shared fixture clean
                // so the failure reports the missing invariant rather than poisoning later tests.
                statement.executeUpdate(
                        "DELETE FROM engine_result WHERE id IN ('"
                                + malformed
                                + "', '"
                                + foreign
                                + "')");
            }
        }
    }

    @Test
    void parse_generation_defaults_existing_and_new_jobs_to_one_and_rejects_nonpositive_values()
            throws Exception {
        UUID newJob = UUID.fromString("30000000-0000-0000-0000-000000000004");
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            assertThat(query(
                            connection,
                            "SELECT parse_generation::text FROM processing_job WHERE id = '"
                                    + LEGACY_JOB + "'"))
                    .containsExactly("1");

            statement.execute(
                    "INSERT INTO processing_job (id, org_id, package_id, idempotency_key) VALUES ('"
                            + newJob + "', '" + ORG_A + "', '" + PACKAGE_A + "', 'new-job')");
            assertThat(query(
                            connection,
                            "SELECT parse_generation::text FROM processing_job WHERE id = '"
                                    + newJob + "'"))
                    .containsExactly("1");

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "UPDATE processing_job SET parse_generation = 0 WHERE id = '"
                                                    + newJob + "'"))
                    .hasMessageContaining("processing_job_parse_generation_check");
        }
    }

    @Test
    void processing_status_accepts_finalizing_but_still_rejects_unknown_values() throws Exception {
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "UPDATE processing_job SET status = 'FINALIZING' WHERE id = '" + LEGACY_JOB + "'");
            assertThat(query(
                            connection,
                            "SELECT status FROM processing_job WHERE id = '" + LEGACY_JOB + "'"))
                    .containsExactly("FINALIZING");

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "UPDATE processing_job SET status = 'NOT_A_STAGE' WHERE id = '"
                                                    + LEGACY_JOB + "'"))
                    .hasMessageContaining("processing_job_status_check");
        }
    }

    @Test
    void engine_result_has_the_complete_checked_immutable_descriptor_shape() throws Exception {
        try (Connection connection = ownerConnection()) {
            assertThat(query(
                            connection,
                            "SELECT column_name FROM information_schema.columns"
                                    + " WHERE table_schema = 'public' AND table_name = 'engine_result'"
                                    + " ORDER BY ordinal_position"))
                    .containsExactly(
                            "id",
                            "org_id",
                            "package_id",
                            "processing_job_id",
                            "parse_generation",
                            "materialized_job_attempt",
                            "revision",
                            "supersedes_result_id",
                            "envelope_schema_version",
                            "canonicalization_version",
                            "canonical_media_type",
                            "source_set_sha256",
                            "provenance_sha256",
                            "envelope_storage_key",
                            "envelope_sha256",
                            "envelope_size_bytes",
                            "reuse_eligibility",
                            "created_at");

            assertThat(query(
                            connection,
                            "SELECT conname FROM pg_constraint WHERE conrelid = 'engine_result'::regclass"
                                    + " ORDER BY conname"))
                    .contains(
                            "engine_result_org_job_generation_key",
                            "engine_result_org_package_revision_key",
                            "engine_result_storage_key_key",
                            "engine_result_positive_values_check",
                            "engine_result_digest_format_check",
                            "engine_result_media_type_check",
                            "engine_result_revision_chain_shape_check",
                            "engine_result_package_fk",
                            "engine_result_job_fk",
                            "engine_result_predecessor_fk");

            assertThat(query(
                            connection,
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                    + " WHERE conname = 'engine_result_positive_values_check'"))
                    .singleElement()
                    .satisfies(
                            definition ->
                                    assertThat(definition)
                                            .contains(
                                                    "parse_generation > 0",
                                                    "materialized_job_attempt > 0",
                                                    "revision > 0",
                                                    "envelope_size_bytes > 0"));
            assertThat(query(
                            connection,
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                    + " WHERE conname = 'engine_result_predecessor_fk'"))
                    .singleElement()
                    .satisfies(
                            definition ->
                                    assertThat(definition)
                                            .contains(
                                                    "FOREIGN KEY (org_id, package_id, supersedes_result_id)",
                                                    "REFERENCES engine_result(org_id, package_id, id)"));

            assertThat(query(
                            connection,
                            "SELECT indexname || ':' || indexdef FROM pg_indexes"
                                    + " WHERE schemaname = 'public' AND tablename = 'engine_result'"))
                    .anyMatch(
                            definition ->
                                    definition.startsWith("engine_result_one_root_per_package:")
                                            && definition.contains("CREATE UNIQUE INDEX")
                                            && definition.contains("supersedes_result_id IS NULL"))
                    .anyMatch(
                            definition ->
                                    definition.startsWith("engine_result_one_successor_per_predecessor:")
                                            && definition.contains("CREATE UNIQUE INDEX")
                                            && definition.contains("supersedes_result_id IS NOT NULL"));
        }
    }

    @Test
    void descriptor_checks_reject_nonpositive_values_invalid_digests_and_wrong_media_type()
            throws Exception {
        UUID base = UUID.fromString("40000000-0000-0000-0000-000000000001");
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    base,
                                                    ORG_A,
                                                    PACKAGE_A,
                                                    JOB_A,
                                                    0,
                                                    1,
                                                    null,
                                                    DIGEST_A,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    1)))
                    .hasMessageContaining("engine_result_positive_values_check");

            String invalidDigest = DIGEST_A.substring(0, 63) + "G";
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    base,
                                                    ORG_A,
                                                    PACKAGE_A,
                                                    JOB_A,
                                                    1,
                                                    1,
                                                    null,
                                                    invalidDigest,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    1)))
                    .hasMessageContaining("engine_result_digest_format_check");

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                            base,
                                                            ORG_A,
                                                            PACKAGE_A,
                                                            JOB_A,
                                                            1,
                                                            1,
                                                            null,
                                                            DIGEST_A,
                                                            DIGEST_B,
                                                            DIGEST_C,
                                                            1)
                                                    .replace(MEDIA_TYPE, "application/json")))
                    .hasMessageContaining("engine_result_media_type_check");
        }
    }

    @Test
    void direct_sql_cannot_skip_a_revision_or_name_any_predecessor_other_than_n_minus_one()
            throws Exception {
        UUID root = UUID.fromString("41000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("41000000-0000-0000-0000-000000000002");
        UUID skipped = UUID.fromString("41000000-0000-0000-0000-000000000003");
        UUID wrongPredecessor = UUID.fromString("41000000-0000-0000-0000-000000000004");
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    resultInsert(
                            root, ORG_A, PACKAGE_A, JOB_A, 1, 1, null, DIGEST_A, DIGEST_B, DIGEST_E, 1));

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    skipped,
                                                    ORG_A,
                                                    PACKAGE_A,
                                                    JOB_A,
                                                    2,
                                                    3,
                                                    root,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    DIGEST_F,
                                                    2)))
                    .hasMessageContaining("engine_result_revision_chain");

            statement.execute(
                    resultInsert(
                            second,
                            ORG_A,
                            PACKAGE_A,
                            JOB_A,
                            2,
                            2,
                            root,
                            DIGEST_B,
                            DIGEST_C,
                            DIGEST_F,
                            2));

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    wrongPredecessor,
                                                    ORG_A,
                                                    PACKAGE_A,
                                                    JOB_A,
                                                    3,
                                                    3,
                                                    root,
                                                    DIGEST_C,
                                                    DIGEST_A,
                                                    DIGEST_B,
                                                    3)))
                    .hasMessageContaining("engine_result_revision_chain");
        }
    }

    @Test
    void composite_foreign_keys_reject_cross_tenant_package_and_job_links()
            throws Exception {
        UUID crossPackage = UUID.fromString("42000000-0000-0000-0000-000000000001");
        UUID crossJob = UUID.fromString("42000000-0000-0000-0000-000000000002");
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    crossPackage,
                                                    ORG_B,
                                                    PACKAGE_A,
                                                    JOB_B,
                                                    1,
                                                    1,
                                                    null,
                                                    DIGEST_A,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    1)))
                    .hasMessageContaining("engine_result_package_fk");

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    crossJob,
                                                    ORG_A,
                                                    PACKAGE_A,
                                                    JOB_B,
                                                    1,
                                                    1,
                                                    null,
                                                    DIGEST_A,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    1)))
                    .hasMessageContaining("engine_result_job_fk");
        }
    }

    @Test
    void engine_result_forces_rls_with_separate_select_insert_delete_and_no_update_path()
            throws Exception {
        try (Connection connection = ownerConnection()) {
            assertThat(query(
                            connection,
                            "SELECT relrowsecurity::text || ':' || relforcerowsecurity::text"
                                    + " FROM pg_class WHERE oid = 'engine_result'::regclass"))
                    .containsExactly("true:true");
            assertThat(query(
                            connection,
                            "SELECT cmd FROM pg_policies WHERE schemaname = 'public'"
                                    + " AND tablename = 'engine_result' ORDER BY cmd"))
                    .containsExactly("DELETE", "INSERT", "SELECT");
            assertThat(query(
                            connection,
                            "SELECT has_table_privilege('docengine_app', 'engine_result', 'UPDATE')::text"))
                    .containsExactly("false");
        }
    }

    @Test
    void application_role_is_tenant_bound_fail_closed_and_may_insert_select_delete_but_not_update()
            throws Exception {
        UUID own = UUID.fromString("43000000-0000-0000-0000-000000000001");
        UUID ownSuccessor = UUID.fromString("43000000-0000-0000-0000-000000000004");
        UUID foreign = UUID.fromString("43000000-0000-0000-0000-000000000002");
        try (Connection owner = ownerConnection();
                Statement statement = owner.createStatement()) {
            statement.execute(
                    resultInsert(
                            foreign,
                            ORG_B,
                            PACKAGE_B,
                            JOB_B,
                            1,
                            1,
                            null,
                            DIGEST_A,
                            DIGEST_B,
                            DIGEST_1,
                            1));
        }

        try (Connection connection = appConnection(ORG_A);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    resultInsert(
                            own,
                            ORG_A,
                            PACKAGE_A_RLS,
                            JOB_A_RLS,
                            1,
                            1,
                            null,
                            DIGEST_A,
                            DIGEST_B,
                            DIGEST_2,
                            1));
            statement.execute(
                    resultInsert(
                            ownSuccessor,
                            ORG_A,
                            PACKAGE_A_RLS,
                            JOB_A_RLS,
                            2,
                            2,
                            own,
                            DIGEST_B,
                            DIGEST_C,
                            DIGEST_3,
                            2));
            assertThat(query(
                            connection,
                            "SELECT id::text FROM engine_result WHERE package_id = '"
                                    + PACKAGE_A_RLS
                                    + "' ORDER BY revision"))
                    .containsExactly(own.toString(), ownSuccessor.toString());

            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    UUID.fromString(
                                                            "43000000-0000-0000-0000-000000000003"),
                                                    ORG_B,
                                                    PACKAGE_B_WRITE,
                                                    JOB_B_WRITE,
                                                    1,
                                                    1,
                                                    null,
                                                    DIGEST_B,
                                                    DIGEST_C,
                                                    DIGEST_A,
                                                    2)))
                    .hasMessageContaining("row-level security");

            assertThatThrownBy(
                            () ->
                                    statement.executeUpdate(
                                            "UPDATE engine_result SET envelope_size_bytes = 2 WHERE id = '"
                                                    + own + "'"))
                    .hasMessageContaining("permission denied");
        }

        try (Connection unbound = appConnection(null);
                Statement statement = unbound.createStatement()) {
            assertThat(query(unbound, "SELECT id::text FROM engine_result")).isEmpty();
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            resultInsert(
                                                    UUID.fromString(
                                                            "43000000-0000-0000-0000-000000000005"),
                                                    ORG_B,
                                                    PACKAGE_B_WRITE,
                                                    JOB_B_WRITE,
                                                    1,
                                                    1,
                                                    null,
                                                    DIGEST_C,
                                                    DIGEST_A,
                                                    DIGEST_B,
                                                    3)))
                    .hasMessageContaining("row-level security");
            assertThat(statement.executeUpdate("DELETE FROM engine_result WHERE id = '" + own + "'"))
                    .isZero();
        }

        try (Connection connection = appConnection(ORG_A);
                Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(
                            "DELETE FROM engine_result WHERE id = '" + ownSuccessor + "'"))
                    .isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM engine_result WHERE id = '" + own + "'"))
                    .isEqualTo(1);
        }
    }
}
