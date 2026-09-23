package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The trap this proves closed: the processing executor's thread has no TenantContext of its own,
 * so JobService must capture the org BEFORE dispatch and bind it inside the runnable. If that
 * binding were missing, Hibernate would stamp the NO_TENANT sentinel (or fail) — so a pipeline
 * that finishes with every row under ORG_DEV is the proof. Runs against the REAL async executor;
 * no SyncExecutorTestConfig here, deliberately.
 */
@TestPropertySource(properties = "docengine.processing.retry-backoff-ms=0")
class AsyncTenantPropagationIT extends AbstractPostgresIT {

    @Autowired JobService jobService;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void async_run_stamps_every_row_with_the_captured_org() throws Exception {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "async-package");

        ProcessingJob job = jobService.createJob(packageId, "async-" + UUID.randomUUID());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status =
                    jdbc.queryForObject(
                            "SELECT status FROM processing_job WHERE id = ?",
                            String.class,
                            job.getId());
            if ("HUMAN_REVIEW_REQUIRED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(status).isEqualTo("HUMAN_REVIEW_REQUIRED");

        List<Map<String, Object>> stageRows =
                jdbc.queryForList(
                        "SELECT org_id FROM processing_stage WHERE job_id = ?", job.getId());
        assertThat(stageRows).hasSize(14);
        assertThat(stageRows)
                .allSatisfy(row -> assertThat(row.get("org_id")).isEqualTo(ORG_DEV));
        List<Map<String, Object>> resultRows =
                jdbc.queryForList(
                        "SELECT org_id FROM engine_result WHERE processing_job_id = ?",
                        job.getId());
        assertThat(resultRows).singleElement()
                .satisfies(row -> assertThat(row.get("org_id")).isEqualTo(ORG_DEV));
    }
}
