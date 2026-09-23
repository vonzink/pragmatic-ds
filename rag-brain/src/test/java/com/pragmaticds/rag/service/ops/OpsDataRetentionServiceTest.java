package com.pragmaticds.rag.service.ops;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.TestRunGroups;
import com.pragmaticds.rag.config.ClusterJobLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OpsDataRetentionServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    DataSource dataSource;

    @Test
    void prunesRowsOlderThanTheWindowInBatchesAndKeepsRecentOnes() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OffsetDateTime now = OffsetDateTime.now();
        insertAudit(jdbc, now.minusDays(200));
        insertAudit(jdbc, now.minusDays(150));
        insertAudit(jdbc, now.minusDays(120));
        insertAudit(jdbc, now.minusDays(10));   // recent — must survive
        insertAudit(jdbc, now.minusDays(1));     // recent — must survive

        // batchSize 2 forces the batching loop to iterate more than once.
        OpsDataRetentionService service = new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 0, 2);

        long deleted = service.pruneTable("ai_audit_logs", now.minusDays(90));

        assertEquals(3, deleted);
        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM ai_audit_logs WHERE brain_id = ?", Long.class, TestBrains.DEFAULT_ID);
        assertEquals(2L, remaining);
    }

    @Test
    void prunesAnalysisRunsTable() {
        // analysis_runs (the analyze-pipeline run manifest) fits the same append-only,
        // created_at-pruned pattern as the other operational tables and must be swept
        // the same way — it can hold borrower data under persist-findings=true.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OffsetDateTime now = OffsetDateTime.now();
        insertAnalysisRun(jdbc, now.minusDays(120));
        insertAnalysisRun(jdbc, now.minusDays(10));   // recent — must survive

        OpsDataRetentionService service = new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 0, 2);

        long deleted = service.pruneTable("analysis_runs", now.minusDays(90));

        assertEquals(1, deleted);
        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM analysis_runs WHERE brain_id = ?", Long.class, TestBrains.DEFAULT_ID);
        assertEquals(1L, remaining);
    }

    @Test
    void anAnalyzerManifestHeldByARetainedLabRunIsExcludedFromPruning() {
        // The Income Lab pins the analyzer's own analysis_runs row rather than inventing a
        // parallel identity, so pruning that row out from under a retained Lab run would leave
        // the Lab run pointing at nothing (and, with the FK, would fail outright).
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OffsetDateTime now = OffsetDateTime.now();
        UUID held = insertAnalysisRun(jdbc, now.minusDays(200));
        UUID unheld = insertAnalysisRun(jdbc, now.minusDays(200));
        insertSucceededLabRun(jdbc, held);

        long deleted = new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 0, 5000)
                .pruneTable("analysis_runs", now.minusDays(90));

        assertEquals(1, deleted, "only the unreferenced manifest may be pruned");
        assertEquals(1L, countAnalysisRun(jdbc, held), "a Lab-held manifest must survive pruning");
        assertEquals(0L, countAnalysisRun(jdbc, unheld), "an unreferenced manifest keeps existing behavior");
    }

    @Test
    void theHoldReleasesOnceTheLabRunIsPurged() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OffsetDateTime now = OffsetDateTime.now();
        UUID analysisRun = insertAnalysisRun(jdbc, now.minusDays(200));
        UUID labRun = insertSucceededLabRun(jdbc, analysisRun);
        OpsDataRetentionService service =
                new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 0, 5000);
        assertEquals(0, service.pruneTable("analysis_runs", now.minusDays(90)));

        // Lab purge removes the Lab run (FK-safe order); the manifest is then ordinary again.
        jdbc.update("DELETE FROM lab_run WHERE id = ?", labRun);

        assertEquals(1, service.pruneTable("analysis_runs", now.minusDays(90)));
        assertEquals(0L, countAnalysisRun(jdbc, analysisRun));
    }

    @Test
    void onlyAnalysisRunsGainsAHoldAndEveryOtherTableKeepsItsExactSql() {
        // Byte-identical SQL for every other operational table: the Lab must not change how
        // audit logs, traces, connector events, or tool-adapter runs are pruned.
        OpsDataRetentionService service = new OpsDataRetentionService(
                new JdbcTemplate(dataSource), new ClusterJobLock(new JdbcTemplate(dataSource)), 0, 5000);

        for (String table : List.of(
                "ai_audit_logs", "rag_traces", "brain_connector_events", "brain_tool_adapter_runs")) {
            assertEquals(
                    "DELETE FROM " + table + " WHERE ctid IN ("
                            + "SELECT ctid FROM " + table + " WHERE created_at < ? "
                            + "ORDER BY created_at LIMIT 5000)",
                    service.pruneSql(table),
                    table + " must keep its exact pre-Lab prune statement");
        }

        String analysisRuns = service.pruneSql("analysis_runs");
        assertTrue(analysisRuns.contains("NOT EXISTS"), analysisRuns);
        assertTrue(analysisRuns.contains("lab_run"), analysisRuns);
        assertTrue(analysisRuns.contains("analysis_run_id"), analysisRuns);
    }

    @Test
    void instanceRunHistoryLeavesOnlyThroughItsOwnRetentionNeverThroughOps() {
        // Ops retention prunes append-only operational tables by age. Instance run history —
        // groups, runs, usage, reservations — has its own policy (InstanceRetentionService)
        // with its own holds and deletion order; an ops sweep reaching into it would purge
        // encrypted history on the wrong policy's clock. This pins that the full ops sweep,
        // window wide open, leaves an ancient lab run and its group exactly where they are.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID analysis = insertAnalysisRun(jdbc, OffsetDateTime.now().minusDays(400));
        UUID run = insertSucceededLabRun(jdbc, analysis);
        OpsDataRetentionService service =
                new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 30, 100);

        service.pruneAllTables();

        assertEquals(1L, countAnalysisRun(jdbc, analysis));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run WHERE id = ?", Long.class, run));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run_group WHERE id = ?", Long.class, run));
    }

    @Test
    void disabledRetentionPrunesNothing() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertAudit(jdbc, OffsetDateTime.now().minusDays(500));

        // days = 0 -> prune() is a no-op.
        new OpsDataRetentionService(jdbc, new ClusterJobLock(jdbc), 0, 5000).prune();

        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM ai_audit_logs WHERE brain_id = ?", Long.class, TestBrains.DEFAULT_ID);
        assertEquals(1L, remaining);
    }

    private static void insertAudit(JdbcTemplate jdbc, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO ai_audit_logs (id, brain_id, user_question, created_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), TestBrains.DEFAULT_ID, "q", Timestamp.from(createdAt.toInstant()));
    }

    private static UUID insertAnalysisRun(JdbcTemplate jdbc, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO analysis_runs (id, brain_id, analyzer_slug, envelope_version, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, TestBrains.DEFAULT_ID, "income", "v2", "SUCCESS",
                Timestamp.from(createdAt.toInstant()));
        return id;
    }

    private static long countAnalysisRun(JdbcTemplate jdbc, UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM analysis_runs WHERE id = ?", Long.class, id);
        return count == null ? 0L : count;
    }

    /** A terminal Lab run holding the exact analyzer manifest row, plus the rows it requires. */
    private static UUID insertSucceededLabRun(JdbcTemplate jdbc, UUID analysisRunId) {
        jdbc.update("INSERT INTO lab_instance (brain_id, slug, display_name, purpose) "
                        + "VALUES (?, 'income', 'Income', 'Retention fixture') "
                        + "ON CONFLICT (brain_id, slug) DO NOTHING", TestBrains.DEFAULT_ID);
        UUID release = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_instance_release (id, brain_id, instance_slug, release_number, "
                        + "provenance_mode, manifest, manifest_sha256) "
                        + "VALUES (?, ?, 'income', 1, 'PRODUCTION', '{}'::jsonb, ?)",
                release, TestBrains.DEFAULT_ID, "c".repeat(64));
        UUID registration = UUID.randomUUID();
        // V38 requires the brain to own the package before a registration may reference it.
        UUID enginePackage = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                        + "VALUES (?, ?)",
                enginePackage, TestBrains.DEFAULT_ID);
        jdbc.update("INSERT INTO lab_document_registration (id, brain_id, instance_slug, "
                        + "engine_package_id, engine_job_id, engine_source_id) "
                        + "VALUES (?, ?, 'income', ?, ?, ?)",
                registration, TestBrains.DEFAULT_ID, enginePackage, UUID.randomUUID(),
                UUID.randomUUID());
        UUID run = UUID.randomUUID();
        TestRunGroups.insertSoloGroup(jdbc, run, TestBrains.DEFAULT_ID);
        jdbc.update("INSERT INTO lab_run (id, brain_id, instance_slug, idempotency_key, release_id, "
                        + "registration_id, status, analysis_run_id, terminal_at, "
                        + TestRunGroups.membershipColumns() + ") "
                        + "VALUES (?, ?, 'income', ?, ?, ?, 'SUCCEEDED', ?, now(), "
                        + TestRunGroups.membershipValues(run) + ")",
                run, TestBrains.DEFAULT_ID, "retention-" + run, release, registration, analysisRunId);
        return run;
    }
}
