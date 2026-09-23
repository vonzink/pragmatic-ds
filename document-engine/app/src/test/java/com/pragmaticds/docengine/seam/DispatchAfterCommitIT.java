package com.pragmaticds.docengine.seam;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review finding (confirmed, critical): when createJob runs inside a caller's open transaction —
 * exactly what the production upload seam does — the pipeline must not be dispatched until that
 * transaction COMMITS. A dispatch racing an uncommitted insert reads nothing (READ COMMITTED),
 * throws NOT_FOUND into a swallowing catch block, and the job is stranded in UPLOADED with no API
 * path to ever run it.
 *
 * <p>Deterministic: the transaction is held open well past dispatch, so the async runner is
 * GUARANTEED to lose the race unless dispatch is correctly deferred to after-commit.
 */
class DispatchAfterCommitIT extends AbstractPostgresIT {

    @Autowired JobService jobService;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID insertPackage() {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource)
                .update(
                        "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'seam-test')",
                        id,
                        ORG_DEV);
        return id;
    }

    @Test
    void job_created_inside_a_transaction_still_runs_after_commit() throws Exception {
        UUID packageId = insertPackage();
        String key = "dispatch-after-commit-" + UUID.randomUUID();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID jobId =
                tx.execute(
                        status -> {
                            ProcessingJob job = jobService.createJob(packageId, key);
                            // Hold the transaction open long enough that an eager dispatch has
                            // certainly already tried (and failed) to read the job row.
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return job.getId();
                        });

        // Now the transaction has committed. A correct implementation dispatches here.
        ProcessingStatus status = null;
        for (int i = 0; i < 40; i++) {
            status = jobService.getJob(jobId).job().getStatus();
            if (status == ProcessingStatus.HUMAN_REVIEW_REQUIRED || status == ProcessingStatus.FAILED) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(status)
                .withFailMessage(
                        "job %s ended in %s — dispatched before commit and stranded", jobId, status)
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
    }
}
