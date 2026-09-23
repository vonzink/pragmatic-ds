package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
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

/** A cancel requested while RENDERING runs is honoured before TEXT_EXTRACTION: one FAILED row, JOB_CANCELLED. */
@Import({SyncExecutorTestConfig.class, JobCancelIT.CancelDuringRender.class})
@TestPropertySource(properties = {"docengine.processing.retry-backoff-ms=0", "spring.main.allow-bean-definition-overriding=true"})
class JobCancelIT extends AbstractPostgresIT {

    @TestConfiguration
    static class CancelDuringRender {
        static volatile ProcessingJobRepository jobs;
        static volatile JobService jobService;
        static volatile UUID orgId;

        /** When true, a second "click" calls jobService.cancel(...) itself, not just the repo. */
        static volatile boolean alsoServiceCancelOnRender = false;

        static volatile JobService.JobDetails secondCancelResult;

        @Bean
        @Primary
        ParserPort cancellingParser(StubParserAdapter stub) {
            return request -> {
                if (request.stage() == ProcessingStatus.RENDERING) {
                    // The user clicks cancel while the render is running.
                    jobs.requestCancel(request.jobId(), orgId);
                    if (alsoServiceCancelOnRender) {
                        // A second click on the cancel icon, landed while the pipeline is still
                        // running (the flag is already set) — must not blow up as a 409.
                        secondCancelResult = jobService.cancel(request.jobId());
                    }
                }
                return stub.run(request);
            };
        }
    }

    @Autowired JobService jobService;
    @Autowired ProcessingJobRepository jobs;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        CancelDuringRender.jobs = jobs;
        CancelDuringRender.jobService = jobService;
        CancelDuringRender.orgId = ORG_DEV;
        CancelDuringRender.alsoServiceCancelOnRender = false;
        CancelDuringRender.secondCancelResult = null;
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void a_cancel_requested_mid_run_fails_the_job_at_the_next_checkpoint() {
        UUID packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)", packageId, ORG_DEV, "cancel-it");

        ProcessingJob job = jobService.createJob(packageId, "cancel-" + UUID.randomUUID());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(details.job().getCurrentStage()).isEqualTo(ProcessingStatus.TEXT_EXTRACTION);
        assertThat(details.stages().stream().filter(s -> s.getStage() == ProcessingStatus.TEXT_EXTRACTION).toList())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                    assertThat(row.getErrorCode()).isEqualTo(ErrorCode.JOB_CANCELLED);
                });
    }

    @Test
    void cancelling_a_finished_job_is_a_conflict() {
        UUID packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)", packageId, ORG_DEV, "cancel-done");
        CancelDuringRender.orgId = ORG_OTHER; // makes the mid-run cancel a no-op (0 rows) for this job
        ProcessingJob job = jobService.createJob(packageId, "cancel-done-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobService.cancel(job.getId()))
                .isInstanceOf(com.pragmaticds.docengine.platform.error.DomainException.class);
    }

    @Test
    void a_second_cancel_click_while_the_job_is_still_running_is_idempotent_not_a_conflict() {
        UUID packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)", packageId, ORG_DEV, "cancel-twice");
        CancelDuringRender.alsoServiceCancelOnRender = true;

        // The first click sets the flag (inside the port, mid-RENDERING); the second click, also
        // mid-RENDERING, lands on an already-cancelled-but-still-running job and must come back
        // with the job's current details rather than throwing the terminal-job 409.
        ProcessingJob job = jobService.createJob(packageId, "cancel-twice-" + UUID.randomUUID());

        assertThat(CancelDuringRender.secondCancelResult).isNotNull();
        assertThat(CancelDuringRender.secondCancelResult.job().getStatus())
                .isNotIn(
                        ProcessingStatus.COMPLETED,
                        ProcessingStatus.FAILED,
                        ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        // The run still ends up cancelled at its next checkpoint, exactly as the single-click case.
        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(details.stages().stream().filter(s -> s.getStage() == ProcessingStatus.TEXT_EXTRACTION).toList())
                .singleElement()
                .satisfies(row -> assertThat(row.getErrorCode()).isEqualTo(ErrorCode.JOB_CANCELLED));
    }

    @Test
    void a_cancelled_job_can_be_resumed_once_the_flag_is_cleared_by_the_claim() {
        UUID packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)", packageId, ORG_DEV, "cancel-resume");

        ProcessingJob job = jobService.createJob(packageId, "cancel-resume-" + UUID.randomUUID());

        JobService.JobDetails failed = jobService.getJob(job.getId());
        assertThat(failed.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.job().getCurrentStage()).isEqualTo(ProcessingStatus.TEXT_EXTRACTION);
        assertThat(failed.stages().stream().filter(s -> s.getStage() == ProcessingStatus.TEXT_EXTRACTION).toList())
                .singleElement()
                .satisfies(row -> assertThat(row.getErrorCode()).isEqualTo(ErrorCode.JOB_CANCELLED));

        // Switch the mock's target org so a resumed run's RENDERING — already SUCCEEDED, so it is
        // skipped, not re-run — cannot trigger another mid-run cancel for this job either way.
        CancelDuringRender.orgId = ORG_OTHER;

        jobService.resume(job.getId());

        JobService.JobDetails resumed = jobService.getJob(job.getId());
        assertThat(resumed.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
    }
}
