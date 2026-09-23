package com.pragmaticds.rag;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Membership columns for a test that inserts {@code lab_run} with raw SQL.
 *
 * <p>V39 made {@code run_group_id}, {@code member_index}, and {@code pricing_version_id} NOT NULL:
 * every run belongs to exactly one group, and a solo run is a one-member group. Fixtures written
 * before that column existed insert a run with no group and now violate the constraint.
 *
 * <p>What this builds is exactly what V39's own backfill builds for a historical run — a
 * one-member INDEPENDENT group whose id is the run's own id, at the legacy-unpriced catalog
 * version — so a test fixture and a migrated production row have the same shape. That matters:
 * a fixture that invented some other shape would let a test pass against a row the database will
 * never actually contain.
 *
 * <p>The alternative was relaxing the constraint to keep the old fixtures compiling. That would
 * have moved a real invariant out of the schema and into the hope that every insert path
 * remembers it, which is the opposite of how the rest of these tables are built.
 */
public final class TestRunGroups {
    private TestRunGroups() {}

    /**
     * The catalog version V39 seeds for runs that predate pricing.
     *
     * <p>It is deliberately empty — zero entries — so nothing can read a price out of it. A
     * historical run's cost is unknown, and pointing it at a version carrying invented rates
     * would make that unknown look like a measurement.
     */
    public static final UUID LEGACY_PRICING_VERSION =
            UUID.fromString("00000000-0000-4000-8000-00000000f001");

    /**
     * Inserts the one-member group a run needs, keyed by the run's own id.
     *
     * <p>Call this before inserting the run, then pass the same id as {@code run_group_id}. The
     * digest is a real SHA-256 of the generated key rather than a placeholder, because
     * {@code chk_lab_run_group_sha} rejects anything that is not 64 hex characters — and a fixture
     * that fed it a fake would be testing the constraint rather than the behaviour.
     */
    public static void insertSoloGroup(JdbcTemplate jdbc, UUID runId, UUID brainId) {
        jdbc.update("INSERT INTO lab_run_group (id, brain_id, mode, idempotency_key, "
                        + "request_sha256, status) "
                        + "VALUES (?, ?, 'INDEPENDENT', ?, "
                        + "encode(sha256(?::bytea), 'hex'), 'PROCESSING')",
                runId, brainId, "legacy:" + runId, "legacy:" + runId);
    }

    /** The three membership columns, as a SQL column-list fragment. */
    public static String membershipColumns() {
        return "run_group_id, member_index, pricing_version_id";
    }

    /** The matching values fragment: the run's own id, slot zero, and the legacy price version. */
    public static String membershipValues(UUID runId) {
        return "'" + runId + "', 0, '" + LEGACY_PRICING_VERSION + "'";
    }
}
