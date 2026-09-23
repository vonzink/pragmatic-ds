package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives the whole Phase 1 stage machine through the service layer with a same-thread executor:
 * happy path, retry, hard failure, resume-from-first-failed-stage, and idempotent replay. Backoff
 * is zeroed so retries are instant; attempt semantics are asserted through the committed stage
 * rows, which is the artifact resume depends on.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class OrchestrationPipelineIT extends AbstractPostgresIT {

    private static final List<ProcessingStatus> PORT_STAGES =
            List.of(
                    ProcessingStatus.RENDERING,
                    ProcessingStatus.TEXT_EXTRACTION,
                    ProcessingStatus.OCR_PROCESSING,
                    ProcessingStatus.PARSING,
                    ProcessingStatus.CLASSIFYING,
                    ProcessingStatus.SPLITTING,
                    ProcessingStatus.EXTRACTING);

    @Autowired JobService jobService;
    @Autowired ProcessingStageRepository stageRepository;
    @Autowired StubParserAdapter stub;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenantAndResetStub() {
        TenantContext.set(ORG_DEV);
        stub.reset();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID insertPackage() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "it-package");
        return packageId;
    }

    private List<ProcessingStage> rowsFor(UUID jobId, ProcessingStatus stage) {
        return stageRepository.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(row -> row.getStage() == stage)
                .toList();
    }

    @Test
    void happy_path_runs_every_stage_and_parks_at_human_review() {
        ProcessingJob job = jobService.createJob(insertPackage(), "happy-" + UUID.randomUUID());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(details.job().getStartedAt()).isNotNull();
        assertThat(details.job().getFinishedAt()).isNotNull();

        List<ProcessingStage> validating = rowsFor(job.getId(), ProcessingStatus.VALIDATING);
        assertThat(validating).hasSize(1);
        assertThat(validating.get(0).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(validating.get(0).getDurationMs()).isNotNull();

        // NORMALIZING records SKIPPED, not SUCCEEDED: no normalization work exists until the
        // Phase 2 worker, and a stage row must never claim success for work that never ran.
        List<ProcessingStage> normalizing = rowsFor(job.getId(), ProcessingStatus.NORMALIZING);
        assertThat(normalizing).hasSize(1);
        assertThat(normalizing.get(0).getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(normalizing.get(0).getSkipReason()).isEqualTo("PHASE_2_NOT_IMPLEMENTED");
        for (ProcessingStatus stage : PORT_STAGES) {
            List<ProcessingStage> rows = rowsFor(job.getId(), stage);
            assertThat(rows).as("stage %s", stage).hasSize(1);
            ProcessingStage row = rows.get(0);
            assertThat(row.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
            assertThat(row.getAttempt()).isEqualTo(1);
            assertThat(row.getDurationMs()).isNotNull();
            assertThat(row.getOutputDigest()).as("digest for %s", stage).hasSize(64);
        }

        List<ProcessingStage> allStages =
                stageRepository.findByJobIdOrderByCreatedAtAsc(job.getId());
        assertThat(allStages)
                .extracting(row -> row.getStage().name())
                .containsExactly(
                        "VALIDATING",
                        "NORMALIZING",
                        "RENDERING",
                        "TEXT_EXTRACTION",
                        "OCR_PROCESSING",
                        "PARSING",
                        "CLASSIFYING",
                        "SPLITTING",
                        "BOUNDARY_EXTRACTION",
                        "EXTRACTING",
                        "AI_EXTRACTION",
                        "FINALIZING",
                        "VALIDATING_DATA",
                        "AI_REVIEW");
        ProcessingStage aiExtraction =
                allStages.stream()
                        .filter(row -> "AI_EXTRACTION".equals(row.getStage().name()))
                        .findFirst()
                        .orElseThrow();
        assertThat(aiExtraction.getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(aiExtraction.getSkipReason()).isEqualTo("AI_DISABLED");
        ProcessingStage boundaryExtraction =
                allStages.stream()
                        .filter(row -> "BOUNDARY_EXTRACTION".equals(row.getStage().name()))
                        .findFirst()
                        .orElseThrow();
        assertThat(boundaryExtraction.getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(boundaryExtraction.getSkipReason()).isEqualTo("BOUNDARY_EXTRACTION_DISABLED");
        ProcessingStage finalizing = rowsFor(job.getId(), ProcessingStatus.FINALIZING).get(0);
        assertThat(finalizing.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(finalizing.getOutputDigest()).hasSize(64);
        assertThat(stub.invocations(job.getId(), ProcessingStatus.FINALIZING)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT envelope_sha256 FROM engine_result WHERE processing_job_id = ? AND parse_generation = 1",
                                String.class,
                                job.getId()))
                .isEqualTo(finalizing.getOutputDigest());

        List<ProcessingStage> validatingData =
                rowsFor(job.getId(), ProcessingStatus.VALIDATING_DATA);
        assertThat(validatingData).hasSize(1);
        assertThat(validatingData.get(0).getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(validatingData.get(0).getSkipReason()).isEqualTo("SPEC_4_NOT_IMPLEMENTED");

        List<ProcessingStage> aiReview = rowsFor(job.getId(), ProcessingStatus.AI_REVIEW);
        assertThat(aiReview).hasSize(1);
        assertThat(aiReview.get(0).getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(aiReview.get(0).getSkipReason()).isEqualTo("SPEC_5_NOT_IMPLEMENTED");
    }

    @Test
    void hard_failure_exhausts_attempts_marks_job_failed_and_stops_the_pipeline() {
        ProcessingJob job =
                jobService.createJob(
                        insertPackage(), UUID.randomUUID() + "/fail-hard:RENDERING");

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(details.job().getCurrentStage()).isEqualTo(ProcessingStatus.RENDERING);
        assertThat(details.job().getFinishedAt()).isNotNull();

        List<ProcessingStage> renderRows = rowsFor(job.getId(), ProcessingStatus.RENDERING);
        assertThat(renderRows).hasSize(3);
        assertThat(renderRows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode()).isEqualTo(ErrorCode.RENDER_FAILED);
                            assertThat(row.getErrorDetail()).contains("RENDERING");
                        });
        assertThat(renderRows).extracting(ProcessingStage::getAttempt).containsExactly(1, 2, 3);

        // Earlier stages succeeded; nothing after the failed stage ever ran.
        assertThat(rowsFor(job.getId(), ProcessingStatus.VALIDATING).get(0).getStatus())
                .isEqualTo(StageStatus.SUCCEEDED);
        assertThat(rowsFor(job.getId(), ProcessingStatus.NORMALIZING).get(0).getStatus())
                .isEqualTo(StageStatus.SKIPPED);
        assertThat(rowsFor(job.getId(), ProcessingStatus.TEXT_EXTRACTION)).isEmpty();
        assertThat(rowsFor(job.getId(), ProcessingStatus.VALIDATING_DATA)).isEmpty();
    }

    @Test
    void transient_failure_retries_within_the_same_run() {
        ProcessingJob job =
                jobService.createJob(
                        insertPackage(), UUID.randomUUID() + "/fail-once:RENDERING");

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        List<ProcessingStage> renderRows = rowsFor(job.getId(), ProcessingStatus.RENDERING);
        assertThat(renderRows).hasSize(2);
        assertThat(renderRows.get(0).getAttempt()).isEqualTo(1);
        assertThat(renderRows.get(0).getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(renderRows.get(1).getAttempt()).isEqualTo(2);
        assertThat(renderRows.get(1).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void ai_extraction_failure_is_recorded_but_does_not_fail_the_pipeline() {
        stub.failStage(ProcessingStatus.AI_EXTRACTION, 3);

        ProcessingJob job =
                jobService.createJob(insertPackage(), "ai-failure-" + UUID.randomUUID());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        List<ProcessingStage> aiRows = rowsFor(job.getId(), ProcessingStatus.AI_EXTRACTION);
        assertThat(aiRows).hasSize(3);
        assertThat(aiRows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                        });
        assertThat(aiRows).extracting(ProcessingStage::getAttempt).containsExactly(1, 2, 3);
        assertThat(rowsFor(job.getId(), ProcessingStatus.FINALIZING).get(0).getStatus())
                .isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void resume_replays_from_the_first_failed_stage_without_rerunning_earlier_stages() {
        stub.failStage(ProcessingStatus.RENDERING, 3);
        ProcessingJob job = jobService.createJob(insertPackage(), "resume-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.FAILED);

        stub.clearFailures();
        jobService.resume(job.getId());

        JobService.JobDetails details = jobService.getJob(job.getId());
        assertThat(details.job().getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(details.job().getAttempt()).isEqualTo(2);

        // Stages before the failure keep their single original row — they were not re-run.
        assertThat(rowsFor(job.getId(), ProcessingStatus.VALIDATING)).hasSize(1);
        assertThat(rowsFor(job.getId(), ProcessingStatus.NORMALIZING)).hasSize(1);

        // Attempt numbering continues where the failed run stopped: 1..3 FAILED, 4 SUCCEEDED.
        List<ProcessingStage> renderRows = rowsFor(job.getId(), ProcessingStatus.RENDERING);
        assertThat(renderRows).hasSize(4);
        assertThat(renderRows).extracting(ProcessingStage::getAttempt).containsExactly(1, 2, 3, 4);
        assertThat(renderRows.get(3).getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stub.invocations(job.getId(), ProcessingStatus.RENDERING)).isEqualTo(4);
    }

    @Test
    void idempotent_replay_returns_the_existing_job_without_rerunning_anything() {
        String key = "idem-" + UUID.randomUUID();
        UUID packageId = insertPackage();

        ProcessingJob first = jobService.createJob(packageId, key);
        assertThat(jobService.getJob(first.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        int renderInvocations = stub.invocations(first.getId(), ProcessingStatus.RENDERING);

        ProcessingJob replay = jobService.createJob(packageId, key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(stub.invocations(first.getId(), ProcessingStatus.RENDERING))
                .isEqualTo(renderInvocations);
        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM processing_job WHERE org_id = ? AND idempotency_key = ?",
                        Integer.class,
                        ORG_DEV,
                        key);
        assertThat(rows).isEqualTo(1);
        assertThat(jobService.getJob(first.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
    }
}
