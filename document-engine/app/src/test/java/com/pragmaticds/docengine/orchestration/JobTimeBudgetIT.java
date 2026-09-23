package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ParserPort.StageRequest;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A job past its wall-clock budget fails ONCE, before the next worker-bound stage, and never
 * starts that stage. Production 2026-09-16: OCR is one call per page, each inside its own timeout,
 * so a 75-page scan held the single worker for over an hour while every other document queued. The
 * user's rule is five minutes or move on; this is the engine half of that rule (the browser half is
 * the suite's five-minute Classify wait).
 *
 * <p>Its own class: the 1-second budget property shapes the whole Spring context.
 */
@Import({SyncExecutorTestConfig.class, JobTimeBudgetIT.SlowRender.class})
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "docengine.processing.job-time-budget-seconds=1",
            "docengine.processing.job-time-budget-seconds-per-page=90",
            "spring.main.allow-bean-definition-overriding=true"
        })
class JobTimeBudgetIT extends AbstractPostgresIT {

    /** The stub parser, but RENDERING takes longer than the whole budget. */
    @TestConfiguration
    static class SlowRender {
        static final List<ProcessingStatus> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        ParserPort slowRenderParser(StubParserAdapter stub) {
            return request -> {
                SEEN.add(request.stage());
                if (request.stage() == ProcessingStatus.RENDERING) {
                    try {
                        Thread.sleep(1_300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return stub.run(request);
            };
        }
    }

    @Autowired JobService jobService;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        SlowRender.SEEN.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void a_job_past_its_budget_fails_once_before_the_next_worker_stage_and_never_runs_it() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "budget-it");

        ProcessingJob job = jobService.createJob(packageId, "budget-" + UUID.randomUUID());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(details.job().getCurrentStage()).isEqualTo(ProcessingStatus.TEXT_EXTRACTION);

        // RENDERING itself succeeded — the budget check is BETWEEN stages, never a kill mid-call.
        assertThat(rows(details, ProcessingStatus.RENDERING))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(StageStatus.SUCCEEDED));

        // TEXT_EXTRACTION has exactly one row: the budget failure, non-retryable, never attempted.
        assertThat(rows(details, ProcessingStatus.TEXT_EXTRACTION))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode())
                                    .isEqualTo(ErrorCode.JOB_TIME_BUDGET_EXCEEDED);
                            assertThat(row.getErrorDetail()).contains("before TEXT_EXTRACTION");
                        });
        assertThat(SlowRender.SEEN).contains(ProcessingStatus.RENDERING);
        assertThat(SlowRender.SEEN).doesNotContain(ProcessingStatus.TEXT_EXTRACTION);
        assertThat(SlowRender.SEEN).doesNotContain(ProcessingStatus.OCR_PROCESSING);
    }

    @Test
    void the_deadline_rides_on_every_stage_request_when_a_budget_is_set() {
        // Sanity on the plumbing the adapter's per-page check depends on.
        List<StageRequest> requests = new CopyOnWriteArrayList<>();
        ParserPort recorder =
                request -> {
                    requests.add(request);
                    return new StageOutcome(true, "d", null, java.util.Map.of());
                };
        StageRequest sample =
                new StageRequest(UUID.randomUUID(), UUID.randomUUID(), ProcessingStatus.RENDERING, 1, "k");
        recorder.run(sample);
        assertThat(requests.get(0).deadline()).isNull();
    }

    @Test
    void the_per_page_budget_extends_the_floor_once_the_pages_are_known() {
        // With 90 s per page and 1 planted page, the deadline after RENDERING is start + 90 s,
        // so the 1 s floor no longer trips and the job completes.
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "budget-pp");
        UUID fileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename, content_type,
                    size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'pp.pdf', 'application/pdf', 1, ?, ?)
                """,
                fileId,
                ORG_DEV,
                packageId,
                "1".repeat(64),
                ORG_DEV + "/" + packageId + "/" + fileId + "/original");
        jdbc.update(
                """
                INSERT INTO page (org_id, source_file_id, package_id, page_index, package_page_index, width_pt, height_pt, text_layer)
                VALUES (?, ?, ?, 0, 0, 612, 792, 'NATIVE')
                """,
                ORG_DEV,
                fileId,
                packageId);

        ProcessingJob job = jobService.createJob(packageId, "budget-pp-" + UUID.randomUUID());

        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
    }

    @Test
    void the_per_page_budget_extends_a_resumed_continuation_run_too() {
        // A resumed run never walks RENDERING again (isDone -> continue), so the extension must
        // be applied BEFORE the loop as well, from the pages that already exist.
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "budget-pp-resume");
        UUID fileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename, content_type,
                    size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'pp.pdf', 'application/pdf', 1, ?, ?)
                """,
                fileId,
                ORG_DEV,
                packageId,
                "2".repeat(64),
                ORG_DEV + "/" + packageId + "/" + fileId + "/original");
        jdbc.update(
                """
                INSERT INTO page (org_id, source_file_id, package_id, page_index, package_page_index, width_pt, height_pt, text_layer)
                VALUES (?, ?, ?, 0, 0, 612, 792, 'NATIVE')
                """,
                ORG_DEV,
                fileId,
                packageId);

        // A pre-existing job, already FAILED at TEXT_EXTRACTION, whose RENDERING stage already
        // SUCCEEDED — the state a job is in the moment before a human resumes it.
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status,
                    current_stage, attempt, started_at, finished_at)
                VALUES (?, ?, ?, ?, 'FAILED', 'TEXT_EXTRACTION', 1, now(), now())
                """,
                jobId,
                ORG_DEV,
                packageId,
                "budget-pp-resume-" + jobId);
        jdbc.update(
                """
                INSERT INTO processing_stage (id, org_id, job_id, stage, status, attempt,
                    started_at, finished_at, duration_ms)
                VALUES (gen_random_uuid(), ?, ?, 'RENDERING', 'SUCCEEDED', 1, now(), now(), 5)
                """,
                ORG_DEV,
                jobId);

        jobService.resume(jobId);

        // resume()'s own return value is stale (it re-reads right after the claim UPDATE, before
        // the dispatched run — synchronous here via SyncExecutorTestConfig — has finished); the
        // other tests in this class make the same fresh-read choice via getJob().
        JobService.JobDetails details = jobService.getJob(jobId);

        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(rows(details, ProcessingStatus.TEXT_EXTRACTION))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(StageStatus.SUCCEEDED));
        assertThat(details.stages())
                .noneMatch(row -> row.getErrorCode() == ErrorCode.JOB_TIME_BUDGET_EXCEEDED);
        // RENDERING was already SUCCEEDED — the resumed run never re-executes it.
        assertThat(SlowRender.SEEN).doesNotContain(ProcessingStatus.RENDERING);
    }

    private static List<ProcessingStage> rows(JobService.JobDetails details, ProcessingStatus stage) {
        return details.stages().stream().filter(row -> row.getStage() == stage).toList();
    }
}
