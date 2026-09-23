package com.pragmaticds.rag.service.ops;

import com.pragmaticds.rag.config.ClusterJobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Opt-in retention for the append-only operational tables (audit logs, RAG traces,
 * connector events, tool-adapter runs, analyze run manifests), which otherwise grow
 * unbounded — every /ask writes prompt-sized audit + trace rows, and every /analyze
 * writes an analysis_runs row. Disabled by default
 * ({@code ragbrain.rag.retention.days <= 0}) because {@code ai_audit_logs} is a
 * compliance trail; operators set a window per their retention policy. When
 * enabled, a daily job prunes rows older than the window in bounded batches so a
 * single delete can't hold long locks.
 */
@Service
public class OpsDataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OpsDataRetentionService.class);

    /** Fixed allow-list — never user input, so the table name is safe to inline in SQL. */
    private static final List<String> TABLES = List.of(
            "ai_audit_logs", "rag_traces", "brain_connector_events", "brain_tool_adapter_runs",
            "analysis_runs");

    private final JdbcTemplate jdbc;
    private final ClusterJobLock clusterJobLock;
    private final int retentionDays;
    private final int batchSize;

    /** Advisory-lock key so only one instance prunes per tick (multi-instance safe). */
    private static final long RETENTION_JOB_LOCK_KEY = 4_701_010_002L;

    public OpsDataRetentionService(JdbcTemplate jdbc,
                                   ClusterJobLock clusterJobLock,
                                   @Value("${ragbrain.rag.retention.days:0}") int retentionDays,
                                   @Value("${ragbrain.rag.retention.batch-size:5000}") int batchSize) {
        this.jdbc = jdbc;
        this.clusterJobLock = clusterJobLock;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(cron = "${ragbrain.rag.retention.cron:0 30 3 * * *}")
    public void prune() {
        if (retentionDays <= 0) {
            return; // retention disabled — keep everything
        }
        // Run once cluster-wide: on multiple replicas only the lock winner prunes.
        clusterJobLock.runIfLeader(RETENTION_JOB_LOCK_KEY, "ops-retention", this::pruneAllTables);
    }

    /** Prunes every retention table; runs under the cluster lock. */
    void pruneAllTables() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        for (String table : TABLES) {
            try {
                long deleted = pruneTable(table, cutoff);
                if (deleted > 0) {
                    log.info("Retention pruned {} rows from {} older than {} days", deleted, table, retentionDays);
                }
            } catch (RuntimeException e) {
                // One table's failure must not stop the others or crash the scheduler.
                log.error("Retention prune failed for {}: {}", table, e.getMessage());
            }
        }
    }

    /**
     * Per-table retention holds, keyed by table. A hold is an extra {@code AND} predicate that
     * excludes rows another feature still needs; a table with no entry keeps its exact pre-existing
     * statement, so its behavior is unchanged byte for byte.
     *
     * <p>{@code analysis_runs} is held by the Income Lab: a Lab run pins the analyzer's own
     * {@code analysis_runs.id} rather than inventing a parallel identity, so pruning a referenced
     * manifest would strand — and, through the FK, fail — a retained Lab run. Unreferenced rows keep
     * the existing behavior. The predicate is fixed source text, never user input.
     */
    private static final Map<String, String> RETENTION_HOLDS = Map.of(
            "analysis_runs",
            " AND NOT EXISTS (SELECT 1 FROM lab_run "
                    + "WHERE lab_run.analysis_run_id = analysis_runs.id)");

    /**
     * The batched delete for one table.
     *
     * <p>The hold belongs to the INNER selection, not just the outer delete: a batch that filled
     * its limit with held rows would delete nothing, end the loop, and silently leave older
     * prunable rows behind.
     */
    String pruneSql(String table) {
        return "DELETE FROM " + table + " WHERE ctid IN ("
                + "SELECT ctid FROM " + table + " WHERE created_at < ?"
                + RETENTION_HOLDS.getOrDefault(table, "")
                + " ORDER BY created_at LIMIT " + batchSize + ")";
    }

    /** Deletes rows older than {@code cutoff} in batches; returns the total removed. */
    long pruneTable(String table, OffsetDateTime cutoff) {
        String sql = pruneSql(table);
        Timestamp cutoffTs = Timestamp.from(cutoff.toInstant());
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, cutoffTs);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
