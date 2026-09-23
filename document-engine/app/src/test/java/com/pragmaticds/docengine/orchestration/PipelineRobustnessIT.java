package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Robustness properties surfaced by the Phase 1 review:
 *
 * <ul>
 *   <li>a crash INSIDE the stage machine (not a port failure) must still leave the job terminal —
 *       a job stuck forever in a non-terminal status is a silent failure;
 *   <li>resume must not re-invoke the port for port stages that already SUCCEEDED — previously
 *       only asserted for instant stages, which never touch the port, proving nothing;
 *   <li>two concurrent resumes must not double-walk the pipeline.
 * </ul>
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class PipelineRobustnessIT extends AbstractPostgresIT {

    @Autowired JobService jobService;
    @Autowired ObjectMapper objectMapper;
    @Autowired StubParserAdapter stub;
    @MockitoSpyBean ProcessingJobRepository jobsSpy;
    @MockitoSpyBean ProcessingStageRepository stageRepositorySpy;
    @MockitoSpyBean EngineResultFinalizerPort finalizerSpy;
    @MockitoSpyBean BlobStoragePort blobs;
    @MockitoSpyBean EngineResultRepository resultsSpy;
    @MockitoSpyBean(name = "processingExecutor") TaskExecutor executorSpy;
    @PersistenceContext EntityManager entityManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenantAndResetStub() {
        TenantContext.set(ORG_DEV);
        stub.reset();
        clearInvocations(finalizerSpy, blobs);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID insertPackage() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'robustness')",
                packageId,
                ORG_DEV);
        return packageId;
    }

    @Test
    void runner_internal_crash_still_leaves_the_job_terminal() {
        // A genuine internal crash: the stage-row save itself explodes once, for PARSING only.
        // This is NOT a port failure — callPort's outcome conversion never sees it.
        AtomicBoolean detonated = new AtomicBoolean(false);
        doAnswer(
                        invocation -> {
                            ProcessingStage row = invocation.getArgument(0);
                            if (row.getStage() == ProcessingStatus.PARSING
                                    && detonated.compareAndSet(false, true)) {
                                throw new IllegalStateException("simulated repository crash");
                            }
                            entityManager.persist(row);
                            return row;
                        })
                .when(stageRepositorySpy)
                .save(any(ProcessingStage.class));

        ProcessingJob job = jobService.createJob(insertPackage(), "crash-" + UUID.randomUUID());

        ProcessingStatus status = jobService.getJob(job.getId()).job().getStatus();
        assertThat(status.isTerminal())
                .withFailMessage(
                        "job ended %s — an internal crash left it non-terminal with no way forward",
                        status)
                .isTrue();
        assertThat(status).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void resume_does_not_reinvoke_the_port_for_already_succeeded_port_stages() {
        // Fail TEXT_EXTRACTION hard; RENDERING (a real port stage) succeeds in run 1.
        stub.failStage(ProcessingStatus.TEXT_EXTRACTION, Integer.MAX_VALUE);
        ProcessingJob job =
                jobService.createJob(insertPackage(), "resume-port-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(stub.invocations(job.getId(), ProcessingStatus.RENDERING)).isEqualTo(1);

        stub.clearFailures();
        jobService.resume(job.getId());

        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        // The property the plan actually demands: the SUCCEEDED port stage ran exactly once,
        // across both runs.
        assertThat(stub.invocations(job.getId(), ProcessingStatus.RENDERING)).isEqualTo(1);
    }

    @Test
    void a_non_retryable_stage_failure_is_recorded_once_without_spending_two_more_attempts() {
        ProcessingJob job =
                jobService.createJob(
                        insertPackage(), "fail-permanent:RENDERING:" + UUID.randomUUID());

        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(stub.invocations(job.getId(), ProcessingStatus.RENDERING)).isEqualTo(1);
        assertThat(rowsFor(job.getId(), ProcessingStatus.RENDERING))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED));
    }

    @Test
    void downstream_resume_increments_only_attempt_and_skips_successful_finalization() {
        AtomicBoolean detonated = new AtomicBoolean(false);
        doAnswer(
                        invocation -> {
                            ProcessingStage row = invocation.getArgument(0);
                            if (row.getStage() == ProcessingStatus.AI_REVIEW
                                    && detonated.compareAndSet(false, true)) {
                                throw new IllegalStateException("simulated downstream crash");
                            }
                            entityManager.persist(row);
                            return row;
                        })
                .when(stageRepositorySpy)
                .save(any(ProcessingStage.class));

        ProcessingJob job =
                jobService.createJob(insertPackage(), "downstream-resume-" + UUID.randomUUID());

        ProcessingJob failed = jobService.getJob(job.getId()).job();
        assertThat(failed.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.getCurrentStage()).isEqualTo(ProcessingStatus.AI_REVIEW);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getParseGeneration()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE processing_job_id = ?",
                                Integer.class,
                                job.getId()))
                .isEqualTo(1);
        verify(finalizerSpy, times(1))
                .finalizeResult(eq(job.getId()), eq(job.getPackageId()), eq(1), anyInt());

        jobService.resume(job.getId());

        ProcessingJob resumed = jobService.getJob(job.getId()).job();
        assertThat(resumed.getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(resumed.getAttempt()).isEqualTo(2);
        assertThat(resumed.getParseGeneration()).isEqualTo(1);
        verify(finalizerSpy, times(1))
                .finalizeResult(eq(job.getId()), eq(job.getPackageId()), eq(1), anyInt());
    }

    @Test
    void finalizerFailureRollsBackDescriptorAndResumeReclaimsBlobInSameGeneration() {
        UUID packageId = insertPackage();
        doThrow(
                        new DomainException(
                                ErrorCode.ENGINE_RESULT_NOT_READY,
                                409,
                                Map.of("reason", "SYNTHETIC_FINALIZER_FAILURE")))
                .when(resultsSpy)
                .saveAndFlush(any(EngineResult.class));

        ProcessingJob job =
                jobService.createJob(packageId, "pipeline-finalization-" + UUID.randomUUID());

        ProcessingJob failed = jobService.getJob(job.getId()).job();
        assertThat(failed.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.getCurrentStage()).isEqualTo(ProcessingStatus.FINALIZING);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getParseGeneration()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE processing_job_id = ?",
                                Integer.class,
                                job.getId()))
                .isZero();
        List<ProcessingStage> failedStages = rowsFor(job.getId(), ProcessingStatus.FINALIZING);
        assertThat(failedStages).hasSize(3);
        assertThat(failedStages)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode())
                                    .isEqualTo(ErrorCode.ENGINE_RESULT_NOT_READY);
                            assertThat(row.getErrorDetail())
                                    .contains("SYNTHETIC_FINALIZER_FAILURE")
                                    .doesNotContain("robustness");
                        });

        ArgumentCaptor<String> failedKeys = ArgumentCaptor.forClass(String.class);
        verify(blobs, times(3))
                .putImmutable(failedKeys.capture(), any(byte[].class), anyString());
        assertThat(failedKeys.getAllValues()).containsOnly(failedKeys.getValue());
        String publishedKey = failedKeys.getValue();
        assertThat(blobs.exists(publishedKey)).isTrue();

        reset(resultsSpy);
        clearInvocations(blobs);
        jobService.resume(job.getId());

        ProcessingJob resumed = jobService.getJob(job.getId()).job();
        assertThat(resumed.getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(resumed.getAttempt()).isEqualTo(2);
        assertThat(resumed.getParseGeneration()).isEqualTo(1);
        Map<String, Object> descriptor =
                jdbc.queryForMap(
                        """
                        SELECT revision, parse_generation, materialized_job_attempt, envelope_sha256
                          FROM engine_result
                         WHERE processing_job_id = ?
                        """,
                        job.getId());
        assertThat(descriptor)
                .containsEntry("revision", 1)
                .containsEntry("parse_generation", 1)
                .containsEntry("materialized_job_attempt", 2);
        List<ProcessingStage> retriedStages = rowsFor(job.getId(), ProcessingStatus.FINALIZING);
        assertThat(retriedStages).hasSize(4);
        ProcessingStage succeeded = retriedStages.get(3);
        assertThat(succeeded.getAttempt()).isEqualTo(4);
        assertThat(succeeded.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(succeeded.getOutputDigest()).isEqualTo(descriptor.get("envelope_sha256"));
        ArgumentCaptor<String> retryKey = ArgumentCaptor.forClass(String.class);
        verify(blobs).putImmutable(retryKey.capture(), any(byte[].class), anyString());
        assertThat(retryKey.getValue()).isEqualTo(publishedKey);
    }

    @Test
    void stageSaveFailureAfterSuccessfulFinalizationRetainsFailedTrailAndReclaimsBlobOnResume()
            throws Exception {
        AtomicBoolean detonated = new AtomicBoolean(false);
        doAnswer(
                        invocation -> {
                            ProcessingStage row = invocation.getArgument(0);
                            if (row.getStage() == ProcessingStatus.FINALIZING
                                    && row.getStatus() == StageStatus.SUCCEEDED
                                    && detonated.compareAndSet(false, true)) {
                                throw new IllegalStateException(
                                        "borrower payload must not enter the failure trail");
                            }
                            entityManager.persist(row);
                            return row;
                        })
                .when(stageRepositorySpy)
                .save(any(ProcessingStage.class));

        UUID packageId = insertPackage();
        ProcessingJob job =
                jobService.createJob(packageId, "stage-save-finalization-" + UUID.randomUUID());

        ProcessingJob failed = jobService.getJob(job.getId()).job();
        assertThat(failed.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.getCurrentStage()).isEqualTo(ProcessingStatus.FINALIZING);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getParseGeneration()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE processing_job_id = ?",
                                Integer.class,
                                job.getId()))
                .isZero();

        List<ProcessingStage> failedStages = rowsFor(job.getId(), ProcessingStatus.FINALIZING);
        assertThat(failedStages).hasSize(1);
        ProcessingStage failedStage = failedStages.getFirst();
        assertThat(failedStage.getAttempt()).isEqualTo(1);
        assertThat(failedStage.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(failedStage.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(objectMapper.readTree(failedStage.getErrorDetail()))
                .isEqualTo(
                        objectMapper.valueToTree(
                                Map.of("exception", "IllegalStateException", "attempt", 1)));
        assertThat(failedStage.getErrorDetail())
                .doesNotContain("borrower payload must not enter the failure trail");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> publishedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(blobs, times(1))
                .putImmutable(keys.capture(), publishedBytes.capture(), anyString());
        String publishedKey = keys.getValue();
        assertThat(blobs.exists(publishedKey)).isTrue();

        clearInvocations(blobs);
        jobService.resume(job.getId());

        ProcessingJob resumed = jobService.getJob(job.getId()).job();
        assertThat(resumed.getStatus()).isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);
        assertThat(resumed.getAttempt()).isEqualTo(2);
        assertThat(resumed.getParseGeneration()).isEqualTo(1);
        Map<String, Object> descriptor =
                jdbc.queryForMap(
                        """
                        SELECT revision, parse_generation, materialized_job_attempt, envelope_sha256
                          FROM engine_result
                         WHERE processing_job_id = ?
                        """,
                        job.getId());
        assertThat(descriptor)
                .containsEntry("revision", 1)
                .containsEntry("parse_generation", 1)
                .containsEntry("materialized_job_attempt", 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE processing_job_id = ?",
                                Integer.class,
                                job.getId()))
                .isEqualTo(1);

        List<ProcessingStage> resumedStages = rowsFor(job.getId(), ProcessingStatus.FINALIZING);
        assertThat(resumedStages).hasSize(2);
        ProcessingStage succeededStage = resumedStages.get(1);
        assertThat(succeededStage.getAttempt()).isEqualTo(2);
        assertThat(succeededStage.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(succeededStage.getOutputDigest()).isEqualTo(descriptor.get("envelope_sha256"));
        ArgumentCaptor<String> resumedKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> resumedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(blobs, times(1))
                .putImmutable(resumedKey.capture(), resumedBytes.capture(), anyString());
        assertThat(resumedKey.getValue()).isEqualTo(publishedKey);
        assertThat(resumedBytes.getValue()).isEqualTo(publishedBytes.getValue());
        verify(finalizerSpy, times(2))
                .finalizeResult(eq(job.getId()), eq(packageId), eq(1), anyInt());
    }

    @Test
    void unexpectedFinalizerFailureMapsToPayloadFreeInternalOutcome() {
        doThrow(new IllegalStateException("borrower payload must not escape"))
                .when(finalizerSpy)
                .finalizeResult(any(UUID.class), any(UUID.class), anyInt(), anyInt());

        ProcessingJob job =
                jobService.createJob(insertPackage(), "unexpected-finalizer-" + UUID.randomUUID());

        ProcessingJob failed = jobService.getJob(job.getId()).job();
        assertThat(failed.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.getCurrentStage()).isEqualTo(ProcessingStatus.FINALIZING);
        assertThat(rowsFor(job.getId(), ProcessingStatus.FINALIZING))
                .hasSize(3)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(StageStatus.FAILED);
                            assertThat(row.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
                            assertThat(row.getErrorDetail())
                                    .contains("IllegalStateException", "attempt")
                                    .doesNotContain("borrower payload must not escape");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE processing_job_id = ?",
                                Integer.class,
                                job.getId()))
                .isZero();
    }

    @Test
    void lostReextractClaimRollsBackStageCleanupAndDoesNotDispatch() {
        UUID packageId = insertPackage();
        ProcessingJob job =
                jobService.createJob(packageId, "lost-reextract-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.HUMAN_REVIEW_REQUIRED);

        List<UUID> downstreamStageIds =
                jdbc.queryForList(
                        """
                        SELECT id FROM processing_stage
                         WHERE job_id = ?
                           AND stage IN ('EXTRACTING', 'AI_EXTRACTION', 'FINALIZING', 'VALIDATING_DATA', 'AI_REVIEW')
                         ORDER BY stage
                        """,
                        UUID.class,
                        job.getId());
        assertThat(downstreamStageIds).hasSize(5);
        Map<String, Object> jobBefore =
                jdbc.queryForMap(
                        """
                        SELECT status, attempt, parse_generation
                          FROM processing_job
                         WHERE id = ?
                        """,
                        job.getId());
        List<Map<String, Object>> resultsBefore = engineResultsFor(packageId);
        assertThat(resultsBefore).hasSize(1);

        clearInvocations(stageRepositorySpy, jobsSpy, executorSpy);
        doReturn(0)
                .when(jobsSpy)
                .claimForReExtract(job.getId(), ORG_DEV, 1, 1);

        assertThatThrownBy(() -> jobService.reExtract(packageId))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(ErrorCode.CONFLICT);
                            assertThat(failure.params())
                                    .containsEntry("status", "HUMAN_REVIEW_REQUIRED");
                        });

        InOrder cleanupThenClaim = inOrder(stageRepositorySpy, jobsSpy);
        cleanupThenClaim.verify(stageRepositorySpy).deleteFromExtractingOnward(job.getId());
        cleanupThenClaim
                .verify(jobsSpy)
                .claimForReExtract(job.getId(), ORG_DEV, 1, 1);
        verify(executorSpy, never()).execute(any(Runnable.class));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM processing_stage WHERE id IN (?, ?, ?, ?, ?)",
                                Integer.class,
                                downstreamStageIds.get(0),
                                downstreamStageIds.get(1),
                                downstreamStageIds.get(2),
                                downstreamStageIds.get(3),
                                downstreamStageIds.get(4)))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT status, attempt, parse_generation
                                  FROM processing_job
                                 WHERE id = ?
                                """,
                                job.getId()))
                .isEqualTo(jobBefore);
        assertThat(engineResultsFor(packageId)).isEqualTo(resultsBefore);
    }

    private List<Map<String, Object>> engineResultsFor(UUID packageId) {
        return jdbc.queryForList(
                """
                SELECT id, org_id, package_id, processing_job_id, parse_generation,
                       materialized_job_attempt, revision, supersedes_result_id,
                       envelope_schema_version, canonicalization_version, canonical_media_type,
                       source_set_sha256, provenance_sha256, envelope_storage_key,
                       envelope_sha256, envelope_size_bytes, reuse_eligibility, created_at
                  FROM engine_result
                 WHERE package_id = ?
                 ORDER BY revision
                """,
                packageId);
    }

    private List<ProcessingStage> rowsFor(UUID jobId, ProcessingStatus stage) {
        return stageRepositorySpy.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(row -> row.getStage() == stage)
                .toList();
    }

    @Test
    void concurrent_resumes_do_not_double_walk_the_pipeline() throws Exception {
        stub.failStage(ProcessingStatus.RENDERING, Integer.MAX_VALUE);
        ProcessingJob job =
                jobService.createJob(insertPackage(), "concurrent-resume-" + UUID.randomUUID());
        assertThat(jobService.getJob(job.getId()).job().getStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        stub.clearFailures();
        int invocationsAfterFailedRun = stub.invocations(job.getId(), ProcessingStatus.RENDERING);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        Runnable attempt =
                () -> {
                    TenantContext.set(ORG_DEV);
                    try {
                        start.await();
                        jobService.resume(job.getId());
                        successes.incrementAndGet();
                    } catch (DomainException e) {
                        if (e.code() == ErrorCode.CONFLICT) {
                            conflicts.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        TenantContext.clear();
                    }
                };
        Thread first = new Thread(attempt);
        Thread second = new Thread(attempt);
        first.start();
        second.start();
        start.countDown();
        first.join(30_000);
        second.join(30_000);

        assertThat(successes.get()).withFailMessage("both resumes ran the pipeline").isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        // Exactly one additional successful walk: one new RENDERING invocation, not two.
        assertThat(stub.invocations(job.getId(), ProcessingStatus.RENDERING))
                .isEqualTo(invocationsAfterFailedRun + 1);
    }
}
