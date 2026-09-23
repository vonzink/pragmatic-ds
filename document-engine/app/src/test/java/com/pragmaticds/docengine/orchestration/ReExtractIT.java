package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.extraction.AbstractExtractionIT;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort.FinalizationResult;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The re-extraction re-kick a regroup uses to refresh fields (Spec 2 Task 4 / design §8). After a
 * reviewer regroups, {@code reExtract} deletes the EXTRACTING + downstream stage rows, flips the
 * job HUMAN_REVIEW_REQUIRED -> UPLOADED, and dispatches: the runner skips the still-SUCCEEDED
 * RENDERING..SPLITTING and re-runs EXTRACTING (idempotent delete-then-recreate, package-wide), so
 * the job lands back at HUMAN_REVIEW_REQUIRED with the same fields.
 *
 * <p>The sync executor ({@link SyncExecutorTestConfig}) makes the afterCommit dispatch run inline,
 * so the whole re-kick completes before {@code reExtract} returns — same setup the orchestration
 * ITs use. The extraction fixtures + {@code runPipelineToExtraction} come from
 * {@link AbstractExtractionIT}, whose base already runs under {@code adapter=worker} with a
 * two-connection pool.
 */
@Import(SyncExecutorTestConfig.class)
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "spring.main.allow-bean-definition-overriding=true"
        })
class ReExtractIT extends AbstractExtractionIT {

    @Autowired JobService jobService;
    @Autowired EngineResultFinalizerPort finalizer;
    @Autowired ProcessingJobRepository jobs;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void re_extract_reruns_extraction_and_returns_to_awaiting_review() {
        UUID packageId = insertPackage("re-extract-it");
        insertFixturePages(packageId, "paystub_complete");
        insertFixtureLayout(packageId, "paystub_complete");
        runPipelineToExtraction(packageId); // fields + logical document now exist

        // runPipelineToExtraction drives the stages directly through parserPort and creates no
        // processing_job. reExtract replays the persisted pipeline, so seed the job + the stage
        // rows a completed Spec-1 run would have left: RENDERING..SPLITTING must be isDone so the
        // runner skips them (the worker adapter would otherwise HTTP-call a dead port), and
        // EXTRACTING is the stage the re-kick re-runs.
        seedCompletedJob(packageId);

        UUID jobId =
                jdbc.queryForObject(
                        "SELECT id FROM processing_job WHERE package_id = ?", UUID.class, packageId);
        FinalizationResult revisionOne = finalizer.finalizeResult(jobId, packageId, 1, 1);
        UUID oldFinalizing = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO processing_stage
                    (id, org_id, job_id, stage, status, attempt, output_digest)
                VALUES (?, ?, ?, 'FINALIZING', 'SUCCEEDED', 1, ?)
                """,
                oldFinalizing,
                ORG_DEV,
                jobId,
                revisionOne.envelopeSha256());
        List<UUID> staleStageIds =
                jdbc.queryForList(
                        """
                        SELECT id FROM processing_stage
                         WHERE job_id = ?
                           AND stage IN ('EXTRACTING', 'AI_EXTRACTION', 'FINALIZING', 'VALIDATING_DATA', 'AI_REVIEW')
                        """,
                        UUID.class,
                        jobId);
        assertThat(staleStageIds).hasSize(5);

        UUID documentId = onlyDocumentOf(packageId);
        long before = fieldCountOf(documentId);
        assertThat(before).isGreaterThan(0);

        jobService.reExtract(packageId); // synchronous under the sync executor

        // The EXTRACTING stage ran again (idempotent delete-recreate) and the job is terminal again.
        String status =
                jdbc.queryForObject(
                        "SELECT status FROM processing_job WHERE package_id = ?",
                        String.class,
                        packageId);
        assertThat(status).isEqualTo("HUMAN_REVIEW_REQUIRED");
        assertThat(fieldCountOf(documentId)).isEqualTo(before); // same fields re-extracted
        assertThat(
                        jdbc.queryForMap(
                                "SELECT attempt, parse_generation FROM processing_job WHERE id = ?",
                                jobId))
                .containsEntry("attempt", 2)
                .containsEntry("parse_generation", 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM processing_stage WHERE id IN (?, ?, ?, ?, ?)",
                                Integer.class,
                                staleStageIds.get(0),
                                staleStageIds.get(1),
                                staleStageIds.get(2),
                                staleStageIds.get(3),
                                staleStageIds.get(4)))
                .isZero();

        List<Map<String, Object>> results =
                jdbc.queryForList(
                        """
                        SELECT id, revision, parse_generation, supersedes_result_id, envelope_sha256
                          FROM engine_result
                         WHERE package_id = ?
                         ORDER BY revision
                        """,
                        packageId);
        assertThat(results).hasSize(2);
        assertThat(results.get(0))
                .containsEntry("revision", 1)
                .containsEntry("parse_generation", 1)
                .containsEntry("supersedes_result_id", null)
                .containsEntry("envelope_sha256", revisionOne.envelopeSha256());
        assertThat(results.get(1))
                .containsEntry("revision", 2)
                .containsEntry("parse_generation", 2)
                .containsEntry("supersedes_result_id", results.get(0).get("id"));
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT output_digest FROM processing_stage
                                 WHERE job_id = ? AND stage = 'FINALIZING' AND status = 'SUCCEEDED'
                                """,
                                String.class,
                                jobId))
                .isEqualTo(results.get(1).get("envelope_sha256"));
    }

    @Test
    void re_extract_claim_allows_one_concurrent_winner_and_rejects_stale_aba_observation()
            throws Exception {
        UUID packageId = insertPackage("re-extract-claim-it");
        seedCompletedJob(packageId);
        UUID jobId =
                jdbc.queryForObject(
                        "SELECT id FROM processing_job WHERE package_id = ?", UUID.class, packageId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable claim =
                () -> {
                    TenantContext.set(ORG_DEV);
                    try {
                        ready.countDown();
                        start.await();
                        Integer claimed =
                                new TransactionTemplate(transactionManager)
                                        .execute(
                                                ignored ->
                                                        jobs.claimForReExtract(
                                                                jobId, ORG_DEV, 1, 1));
                        winners.addAndGet(claimed == null ? 0 : claimed);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException unexpected) {
                        failure.compareAndSet(null, unexpected);
                    } finally {
                        TenantContext.clear();
                    }
                };
        Thread first = new Thread(claim);
        Thread second = new Thread(claim);
        first.start();
        second.start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        first.join(30_000);
        second.join(30_000);

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(winners.get()).isEqualTo(1);
        jdbc.update("UPDATE processing_job SET status = 'HUMAN_REVIEW_REQUIRED' WHERE id = ?", jobId);
        Integer staleClaim =
                new TransactionTemplate(transactionManager)
                        .execute(
                                ignored -> jobs.claimForReExtract(jobId, ORG_DEV, 1, 1));
        assertThat(staleClaim).isZero();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT attempt, parse_generation FROM processing_job WHERE id = ?",
                                jobId))
                .containsEntry("attempt", 2)
                .containsEntry("parse_generation", 2);
    }

    private long fieldCountOf(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM extracted_field WHERE logical_document_id = ?",
                Long.class,
                documentId);
    }
}
