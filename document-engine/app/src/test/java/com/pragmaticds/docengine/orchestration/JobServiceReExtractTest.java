package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The re-extract claim must NULL {@code behavior_fingerprint} alongside its existing
 * attempt/generation bump: a regrouped generation re-ran only EXTRACTING under possibly-newer
 * loaders, so the stored fingerprint no longer describes what produced the rows — a
 * mixed-behavior artifact is never a reuse source. NULL never matches a probe, so nulling here is
 * what makes {@code regroupedPriorNeverReuses} structural rather than aspirational.
 */
class JobServiceReExtractTest extends AbstractPostgresIT {

    private static final String FINGERPRINT = "f".repeat(64);

    @Autowired private ProcessingJobRepository jobs;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        com.pragmaticds.docengine.platform.tenancy.TenantContext.clear();
    }

    /** The claims are @Modifying(flush…) — they run inside JobService transactions in production. */
    private int inTransaction(java.util.function.IntSupplier claim) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> claim.getAsInt());
    }

    @Test
    void claimForReExtractClearsBehaviorFingerprint() {
        UUID packageId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'reuse-regen')",
                packageId,
                ORG_DEV);
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                        + " attempt, parse_generation, behavior_fingerprint)"
                        + " VALUES (?, ?, ?, ?, 'HUMAN_REVIEW_REQUIRED', 1, 1, ?)",
                jobId,
                ORG_DEV,
                packageId,
                "regen-" + jobId,
                FINGERPRINT);

        int claimed = inTransaction(() -> jobs.claimForReExtract(jobId, ORG_DEV, 1, 1));

        assertThat(claimed).isEqualTo(1);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT status, attempt, parse_generation, behavior_fingerprint"
                                + " FROM processing_job WHERE id = ?",
                        jobId);
        assertThat(row.get("status")).isEqualTo("UPLOADED");
        assertThat(row.get("attempt")).isEqualTo(2);
        assertThat(row.get("parse_generation")).isEqualTo(2);
        assertThat(row.get("behavior_fingerprint"))
                .as("a regrouped generation is never a reuse source")
                .isNull();
    }

    /**
     * A resume is a mixed-behavior artifact, exactly like a regroup.
     *
     * <p>This test previously pinned the OPPOSITE — "resume continues the same parse under the same
     * admitted view, so the stamp stays" — and that was wrong. A resume re-runs only from the
     * failed stage forward: the stages that already SUCCEEDED keep the output the OLD worker,
     * OLD packs and OLD schemas produced, while the replayed stages run under whatever is current
     * hours or days later. The finished rows are a blend of two behaviors, and no single
     * fingerprint can honestly describe a blend. The regroup claim already NULLs for precisely
     * this reason; resume deserves the same treatment.
     */
    @Test
    void resumeClaimClearsTheFingerprintBecauseAResumedRunIsMixedBehavior() {
        UUID packageId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'reuse-resume')",
                packageId,
                ORG_DEV);
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,"
                        + " attempt, parse_generation, behavior_fingerprint)"
                        + " VALUES (?, ?, ?, ?, 'FAILED', 1, 1, ?)",
                jobId,
                ORG_DEV,
                packageId,
                "resume-" + jobId,
                FINGERPRINT);

        assertThat(inTransaction(() -> jobs.claimForResume(jobId, ORG_DEV))).isEqualTo(1);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT behavior_fingerprint FROM processing_job WHERE id = ?",
                                String.class,
                                jobId))
                .as("a resumed generation is never a reuse source")
                .isNull();
    }
}
