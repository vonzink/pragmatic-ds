package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.SyncExecutorTestConfig;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A worker that accepts the connection and then goes quiet must surface as WORKER_TIMEOUT, once and
 * not retried — its own class because the 1-second read timeout property shapes the whole Spring
 * context.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true",
            "docengine.processing.adapter=worker",
            "docengine.worker.shared-secret=it-worker-secret",
            "docengine.worker.timeout-seconds=1"
        })
class WorkerTimeoutIT extends AbstractPostgresIT {

    private static final MockWebServer WORKER = new MockWebServer();

    static {
        try {
            WORKER.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("docengine.worker.base-url", () -> WORKER.url("/").toString());
    }

    @Autowired JobService jobService;
    @Autowired BlobStoragePort storage;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void worker_read_timeout_fails_the_stage_with_worker_timeout() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "timeout-it");
        UUID fileId = UUID.randomUUID();
        byte[] pdf = "%PDF-slow".getBytes(StandardCharsets.UTF_8);
        String key = ORG_DEV + "/" + packageId + "/" + fileId + "/original";
        storage.put(key, pdf);
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'slow.pdf', 'application/pdf', ?, ?, ?)
                """,
                fileId,
                ORG_DEV,
                packageId,
                pdf.length,
                Digests.sha256Hex(pdf),
                key);

        for (int i = 0; i < 3; i++) {
            WORKER.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        }
        int requestsBefore = WORKER.getRequestCount();

        ProcessingJob job = jobService.createJob(packageId, "worker-timeout-" + UUID.randomUUID());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        List<ProcessingStage> renderRows =
                details.stages().stream()
                        .filter(row -> row.getStage() == ProcessingStatus.RENDERING)
                        .toList();
        // ONE attempt. A worker call that ran out its whole timeout is not a blip another identical
        // call fixes: in production (2026-09-16) three 10-minute attempts per stage on an 83-page
        // scan only tripled the time before the same failure. WORKER_UNAVAILABLE stays retryable.
        assertThat(renderRows).hasSize(1);
        assertThat(WORKER.getRequestCount() - requestsBefore).isEqualTo(1);
        assertThat(renderRows)
                .allSatisfy(
                        row -> assertThat(row.getErrorCode()).isEqualTo(ErrorCode.WORKER_TIMEOUT));
    }
}
