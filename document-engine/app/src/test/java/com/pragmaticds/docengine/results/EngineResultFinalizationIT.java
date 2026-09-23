package com.pragmaticds.docengine.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort;
import com.pragmaticds.docengine.orchestration.EngineResultFinalizerPort.FinalizationResult;
import com.pragmaticds.docengine.orchestration.StageRunner;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Database/storage acceptance for idempotent immutable result materialization. */
class EngineResultFinalizationIT extends AbstractPostgresIT {

    @Autowired private EngineResultFinalizerPort finalizer;
    @Autowired private StageRunner stageRunner;
    @MockitoSpyBean private BlobStoragePort blobs;
    @Autowired private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        clearInvocations(blobs);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void persistsReloadableRevisionOneThenRevisionTwoWithImmediatePredecessor() {
        Fixture fixture = seedFixture(ORG_DEV, false);

        FinalizationResult first =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);
        jdbc.update(
                "UPDATE processing_job SET parse_generation = 2, attempt = 2 WHERE id = ?",
                fixture.jobId());
        FinalizationResult second =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 2, 2);

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT id, parse_generation, materialized_job_attempt, revision,
                               supersedes_result_id, envelope_sha256, envelope_size_bytes, created_at
                          FROM engine_result
                         WHERE package_id = ?
                         ORDER BY revision
                        """,
                        fixture.packageId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("parse_generation")).isEqualTo(1);
        assertThat(rows.get(0).get("materialized_job_attempt")).isEqualTo(1);
        assertThat(rows.get(0).get("revision")).isEqualTo(1);
        assertThat(rows.get(0).get("supersedes_result_id")).isNull();
        assertThat(rows.get(0).get("created_at")).isInstanceOf(Timestamp.class);
        assertThat(rows.get(1).get("parse_generation")).isEqualTo(2);
        assertThat(rows.get(1).get("materialized_job_attempt")).isEqualTo(2);
        assertThat(rows.get(1).get("revision")).isEqualTo(2);
        assertThat(rows.get(1).get("supersedes_result_id"))
                .isEqualTo(rows.get(0).get("id"));
        assertThat(rows.get(1).get("created_at")).isInstanceOf(Timestamp.class);
        assertThat(rows.get(0).get("envelope_sha256").toString())
                .isEqualTo(first.envelopeSha256());
        assertThat(rows.get(1).get("envelope_sha256").toString())
                .isEqualTo(second.envelopeSha256());
        assertThat(blobs.get(key(first))).hasSize((int) first.envelopeSizeBytes());
        assertThat(blobs.get(key(second))).hasSize((int) second.envelopeSizeBytes());
    }

    @Test
    void concurrentSameGenerationCallsReturnOneDescriptorAndOneArtifact() throws Exception {
        Fixture fixture = seedFixture(ORG_DEV, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FinalizationResult> first =
                    executor.submit(() -> concurrentFinalize(fixture, ready, start));
            Future<FinalizationResult> second =
                    executor.submit(() -> concurrentFinalize(fixture, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            FinalizationResult firstResult = first.get(30, TimeUnit.SECONDS);
            FinalizationResult secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                    Integer.class,
                                    fixture.packageId()))
                    .isEqualTo(1);
            assertThat(blobs.get(key(firstResult)))
                    .hasSize((int) firstResult.envelopeSizeBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void foreignAndTombstonedPackagesReturnTheSameOpaqueNotFoundAndCreateNothing() {
        Fixture foreign = seedFixture(ORG_OTHER, false);
        Fixture tombstoned = seedFixture(ORG_DEV, true);

        assertDomain(
                () -> finalizer.finalizeResult(foreign.jobId(), foreign.packageId(), 1, 1),
                ErrorCode.NOT_FOUND,
                404);
        assertDomain(
                () ->
                        finalizer.finalizeResult(
                                tombstoned.jobId(), tombstoned.packageId(), 1, 1),
                ErrorCode.NOT_FOUND,
                404);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id IN (?, ?)",
                                Integer.class,
                                foreign.packageId(),
                                tombstoned.packageId()))
                .isZero();
    }

    @Test
    void changedMachineRowsConflictWithoutChangingDescriptorOrPublishedBytes() {
        Fixture fixture = seedFixture(ORG_DEV, false);
        seedSource(fixture, 0, "a".repeat(64));
        FinalizationResult first =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);
        byte[] published = blobs.get(key(first));
        String descriptorDigest =
                jdbc.queryForObject(
                        "SELECT envelope_sha256 FROM engine_result WHERE package_id = ?",
                        String.class,
                        fixture.packageId());
        seedSource(fixture, 1, "b".repeat(64));

        assertDomain(
                () -> finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1),
                ErrorCode.ENGINE_RESULT_CONFLICT,
                409);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT envelope_sha256 FROM engine_result WHERE package_id = ?",
                                String.class,
                                fixture.packageId()))
                .isEqualTo(descriptorDigest);
        assertThat(blobs.get(key(first))).isEqualTo(published);
    }

    @Test
    void missingAndWrongExistingBlobsFailCorruptWithoutRepair() {
        Fixture missing = seedFixture(ORG_DEV, false);
        FinalizationResult missingResult =
                finalizer.finalizeResult(missing.jobId(), missing.packageId(), 1, 1);
        blobs.delete(key(missingResult));

        assertDomain(
                () -> finalizer.finalizeResult(missing.jobId(), missing.packageId(), 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500);
        assertThat(blobs.exists(key(missingResult))).isFalse();

        Fixture wrong = seedFixture(ORG_DEV, false);
        FinalizationResult wrongResult =
                finalizer.finalizeResult(wrong.jobId(), wrong.packageId(), 1, 1);
        byte[] wrongBytes = "wrong-machine-result".getBytes(StandardCharsets.UTF_8);
        blobs.put(key(wrongResult), wrongBytes);

        assertDomain(
                () -> finalizer.finalizeResult(wrong.jobId(), wrong.packageId(), 1, 1),
                ErrorCode.ENGINE_RESULT_CORRUPT,
                500);
        assertThat(blobs.get(key(wrongResult))).isEqualTo(wrongBytes);
    }

    @Test
    void rollbackAfterPublicationLeavesNoDescriptorAndRetryReclaimsTheArtifact() {
        Fixture fixture = seedFixture(ORG_DEV, false);
        AtomicReference<FinalizationResult> published = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        ignored -> {
                                            published.set(
                                                    finalizer.finalizeResult(
                                                            fixture.jobId(),
                                                            fixture.packageId(),
                                                            1,
                                                            1));
                                            throw new RollbackMarker();
                                        }))
                .isInstanceOf(RollbackMarker.class);

        assertThat(published.get()).isNotNull();
        assertThat(blobs.exists(key(published.get()))).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isZero();

        FinalizationResult retried =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);
        assertThat(retried).isEqualTo(published.get());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isEqualTo(1);
        assertThat(blobs.get(key(retried))).hasSize((int) retried.envelopeSizeBytes());
    }

    @Test
    void classificationEvidenceWithUnknownRawTextFailsBeforeArtifactPublication() {
        Fixture fixture = seedFixture(ORG_DEV, false);
        UUID sourceId = seedSource(fixture, 0, "a".repeat(64));
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, 0, 0, 612, 792, 0, 'NATIVE')
                """,
                pageId,
                ORG_DEV,
                sourceId,
                fixture.packageId());
        jdbc.update(
                """
                INSERT INTO classification_result
                    (id, org_id, subject_type, subject_id, document_type_code, confidence,
                     method, rule_pack_version, evidence, is_current)
                VALUES (?, ?, 'PAGE', ?, 'PAYSTUB', 0.9, 'RULE_ANCHOR', '1.0.0',
                        ?::jsonb, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                "{\"anchors\":[],\"scores\":[],\"rawDocumentText\":\"borrower text\"}");

        assertDomain(
                () -> finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1),
                ErrorCode.ENGINE_RESULT_NOT_READY,
                409);
        // The refusal names the violated invariant (member names only, never stored values):
        // an anonymous MACHINE_SNAPSHOT_UNAVAILABLE cost a multi-hour production investigation.
        assertThatThrownBy(
                        () -> finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.params())
                                    .containsEntry("reason", "MACHINE_SNAPSHOT_UNAVAILABLE");
                            assertThat(String.valueOf(failure.params().get("detail")))
                                    .contains("rawDocumentText")
                                    .doesNotContain("borrower text");
                        });

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isZero();
        verify(blobs, never()).putImmutable(anyString(), any(byte[].class), anyString());
    }

    /**
     * Regression for the 2026-09-07 production failure: a tax return whose pages co-qualified under
     * the TAX_RETURN and SCHEDULE_C packs carried the Phase B4 {@code coQualifyingTypes} member in
     * its classification evidence, and FINALIZING refused every attempt with
     * MACHINE_SNAPSHOT_UNAVAILABLE. Single-document packages never co-qualify, so nothing else
     * exercised the member. The signal must finalize AND survive into the published envelope.
     */
    @Test
    void classificationEvidenceWithCoQualifyingTypesFinalizesAndPublishesTheSignal()
            throws Exception {
        Fixture fixture = seedFixture(ORG_DEV, false);
        UUID sourceId = seedSource(fixture, 0, "b".repeat(64));
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, 0, 0, 612, 792, 0, 'NATIVE')
                """,
                pageId,
                ORG_DEV,
                sourceId,
                fixture.packageId());
        jdbc.update(
                """
                INSERT INTO classification_result
                    (id, org_id, subject_type, subject_id, document_type_code, confidence,
                     method, rule_pack_version, evidence, is_current)
                VALUES (?, ?, 'PAGE', ?, 'TAX_RETURN', 0.9, 'RULE_ANCHOR', '1.0.0',
                        ?::jsonb, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                "{\"anchors\":[],\"coQualifyingTypes\":[\"SCHEDULE_C\",\"TAX_RETURN\"],"
                        + "\"scores\":[]}");

        FinalizationResult result =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isEqualTo(1);
        JsonNode envelope = new ObjectMapper().readTree(blobs.get(key(result)));
        JsonNode published = envelope.at("/pages/0/classification/evidence/coQualifyingTypes");
        assertThat(published.isArray()).isTrue();
        assertThat(published).hasSize(2);
        assertThat(List.of(published.get(0).asText(), published.get(1).asText()))
                .containsExactly("SCHEDULE_C", "TAX_RETURN");
    }

    /**
     * Regression for the 2026-09-10 production failure: with AI page classification enabled the
     * model retyped pages the packs had left UNKNOWN, and every package holding one of those rows
     * failed FINALIZING with MACHINE_SNAPSHOT_UNAVAILABLE — the snapshot contract knew one evidence
     * shape and the model writes another. No test finalized an LLM-classified page, so the shape
     * that the classifier had been writing since PR #51 was never loaded once.
     *
     * <p>Uses the byte-exact document {@code AiPageClassificationService.evidenceJson} writes, so
     * this pins the producer's shape and not a convenient retyping of it.
     */
    @Test
    void llmClassifiedPageFinalizesAndPublishesTheModelsOwnEvidence() throws Exception {
        Fixture fixture = seedFixture(ORG_DEV, false);
        UUID pageId = seedClassifiedPage(fixture, "LLM", null, LLM_EVIDENCE);

        FinalizationResult result =
                finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isEqualTo(1);
        JsonNode envelope = new ObjectMapper().readTree(blobs.get(key(result)));
        JsonNode classification = envelope.at("/pages/0/classification");
        assertThat(envelope.at("/pages/0/id").asText()).isEqualTo(pageId.toString());
        assertThat(classification.path("method").asText()).isEqualTo("LLM");
        assertThat(classification.path("rulePackVersion").isNull()).isTrue();

        JsonNode evidence = classification.path("evidence");
        assertThat(evidence.path("source").asText()).isEqualTo("AI");
        assertThat(evidence.path("model").asText()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(evidence.path("promptVersion").asText()).isEqualTo("page-classification-1");
        assertThat(evidence.at("/matchedSpanIds/0").asLong()).isEqualTo(101L);
        assertThat(evidence.at("/matchedSpanIds/1").asLong()).isEqualTo(102L);
        assertThat(evidence.at("/offsets/start").asInt()).isEqualTo(10);
        assertThat(evidence.at("/offsets/end").asInt()).isEqualTo(40);
        assertThat(evidence.at("/deterministicRunnerUp/type").asText()).isEqualTo("PAYSTUB");
        assertThat(evidence.at("/deterministicRunnerUp/score").decimalValue())
                .isEqualByComparingTo("0.55");
        // The packs never ran an anchor over this page; the envelope must not claim they did.
        assertThat(evidence.has("anchors")).isFalse();
        assertThat(evidence.has("scores")).isFalse();
    }

    /**
     * Evidence written under one method is not readable under the other. Each method keeps an EXACT
     * member set, so a swap is caught at FINALIZING rather than published as if the other producer
     * had verified it — and the refusal says WHICH contract was applied.
     */
    @Test
    void evidenceWrittenUnderTheWrongMethodStillFailsLoudly() {
        Fixture anchorShapeAsLlm = seedFixture(ORG_DEV, false);
        seedClassifiedPage(anchorShapeAsLlm, "LLM", null, "{\"anchors\":[],\"scores\":[]}");

        assertThatThrownBy(
                        () ->
                                finalizer.finalizeResult(
                                        anchorShapeAsLlm.jobId(), anchorShapeAsLlm.packageId(),
                                        1, 1))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.params())
                                    .containsEntry("reason", "MACHINE_SNAPSHOT_UNAVAILABLE");
                            assertThat(String.valueOf(failure.params().get("detail")))
                                    .contains("LLM classification evidence")
                                    .contains("matchedSpanIds")
                                    .contains("anchors");
                        });

        Fixture llmShapeAsAnchor = seedFixture(ORG_DEV, false);
        seedClassifiedPage(llmShapeAsAnchor, "RULE_ANCHOR", "1.0.0", LLM_EVIDENCE);

        assertThatThrownBy(
                        () ->
                                finalizer.finalizeResult(
                                        llmShapeAsAnchor.jobId(), llmShapeAsAnchor.packageId(),
                                        1, 1))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure ->
                                assertThat(String.valueOf(failure.params().get("detail")))
                                        .contains("RULE_ANCHOR classification evidence"));

        verify(blobs, never()).putImmutable(anyString(), any(byte[].class), anyString());
    }

    /**
     * The model's evidence is still an EXACT contract: a stray member, a missing one, a span id
     * that is not a span id, or page text smuggled into an identifier is refused — by name, never
     * by value.
     */
    @Test
    void malformedLlmEvidenceIsRefusedByMemberNameAndNeverByStoredValue() {
        Fixture fixture = seedFixture(ORG_DEV, false);
        UUID pageId = seedClassifiedPage(fixture, "LLM", null, LLM_EVIDENCE);
        List<String> malformed =
                List.of(
                        LLM_EVIDENCE.replace(
                                "\"source\":\"AI\"",
                                "\"source\":\"AI\",\"quotedText\":\"borrower text\""),
                        LLM_EVIDENCE.replace("\"promptVersion\":\"page-classification-1\",", ""),
                        LLM_EVIDENCE.replace("\"matchedSpanIds\":[101,102]", "\"matchedSpanIds\":0"),
                        LLM_EVIDENCE.replace("\"matchedSpanIds\":[101,102]",
                                "\"matchedSpanIds\":[\"borrower text\"]"),
                        LLM_EVIDENCE.replace("\"start\":10,\"end\":40", "\"start\":40,\"end\":10"),
                        LLM_EVIDENCE.replace(
                                "\"model\":\"gemini-2.5-flash-lite\"",
                                "\"model\":\"borrower text on the page\""),
                        LLM_EVIDENCE.replace("\"type\":\"PAYSTUB\"", "\"type\":\"borrower text\""),
                        LLM_EVIDENCE.replace(
                                "{\"type\":\"PAYSTUB\",\"score\":0.55}",
                                "{\"type\":\"PAYSTUB\",\"score\":0.55,\"quote\":\"borrower text\"}"));

        for (String evidence : malformed) {
            jdbc.update(
                    "UPDATE classification_result SET evidence = ?::jsonb WHERE subject_id = ?",
                    evidence,
                    pageId);

            assertThatThrownBy(
                            () ->
                                    finalizer.finalizeResult(
                                            fixture.jobId(), fixture.packageId(), 1, 1))
                    .as("malformed LLM evidence: %s", evidence)
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            failure -> {
                                assertThat(failure.params())
                                        .containsEntry("reason", "MACHINE_SNAPSHOT_UNAVAILABLE");
                                assertThat(String.valueOf(failure.params().get("detail")))
                                        .doesNotContain("borrower");
                            });
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM engine_result WHERE package_id = ?",
                                Integer.class,
                                fixture.packageId()))
                .isZero();
        verify(blobs, never()).putImmutable(anyString(), any(byte[].class), anyString());
    }

    /**
     * Byte-for-byte the document {@code AiPageClassificationService.evidenceJson} writes: member
     * order as produced, so a drift in the producer's shape surfaces here.
     */
    private static final String LLM_EVIDENCE =
            "{\"source\":\"AI\",\"model\":\"gemini-2.5-flash-lite\","
                    + "\"promptVersion\":\"page-classification-1\","
                    + "\"matchedSpanIds\":[101,102],\"offsets\":{\"start\":10,\"end\":40},"
                    + "\"deterministicRunnerUp\":{\"type\":\"PAYSTUB\",\"score\":0.55}}";

    /**
     * The content digest is unique per call: these ITs share one cumulative database, and a
     * hard-coded filler sha silently changes what another suite's duplicate probe finds.
     */
    private UUID seedClassifiedPage(
            Fixture fixture, String method, String rulePackVersion, String evidence) {
        UUID sourceId =
                seedSource(fixture, 0, UUID.randomUUID().toString().replace("-", "").repeat(2));
        UUID pageId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO page
                    (id, org_id, source_file_id, package_id, page_index, package_page_index,
                     width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, 0, 0, 612, 792, 0, 'NATIVE')
                """,
                pageId,
                ORG_DEV,
                sourceId,
                fixture.packageId());
        jdbc.update(
                """
                INSERT INTO classification_result
                    (id, org_id, subject_type, subject_id, document_type_code, confidence,
                     method, rule_pack_version, evidence, is_current)
                VALUES (?, ?, 'PAGE', ?, 'PAYSTUB', 0.9, ?, ?, ?::jsonb, true)
                """,
                UUID.randomUUID(),
                ORG_DEV,
                pageId,
                method,
                rulePackVersion,
                evidence);
        return pageId;
    }

    @Test
    void pipelineFinalizationCommitsMatchingDescriptorAndSuccessfulStage() {
        UUID packageId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'pipeline-finalization')",
                packageId,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO processing_job
                    (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)
                VALUES (?, ?, ?, ?, 'UPLOADED', 1, 1)
                """,
                jobId,
                ORG_DEV,
                packageId,
                "pipeline-finalization-" + jobId);

        stageRunner.run(jobId);

        Map<String, Object> descriptor =
                jdbc.queryForMap(
                        """
                        SELECT revision, parse_generation, envelope_sha256
                          FROM engine_result
                         WHERE processing_job_id = ?
                        """,
                        jobId);
        assertThat(descriptor)
                .containsEntry("revision", 1)
                .containsEntry("parse_generation", 1);
        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT status, attempt, output_digest
                                  FROM processing_stage
                                 WHERE job_id = ? AND stage = 'FINALIZING'
                                """,
                                jobId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt", 1)
                .containsEntry("output_digest", descriptor.get("envelope_sha256"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM processing_job WHERE id = ?",
                                String.class,
                                jobId))
                .isEqualTo("HUMAN_REVIEW_REQUIRED");
    }

    private FinalizationResult concurrentFinalize(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        TenantContext.set(ORG_DEV);
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return finalizer.finalizeResult(fixture.jobId(), fixture.packageId(), 1, 1);
        } finally {
            TenantContext.clear();
        }
    }

    private Fixture seedFixture(UUID orgId, boolean tombstoned) {
        UUID packageId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO document_package (id, org_id, name, deleted_at, purge_after)
                VALUES (?, ?, 'synthetic-finalization', ?, ?)
                """,
                packageId,
                orgId,
                tombstoned ? Timestamp.from(Instant.now()) : null,
                tombstoned ? Timestamp.from(Instant.now().plusSeconds(86_400)) : null);
        jdbc.update(
                """
                INSERT INTO processing_job
                    (id, org_id, package_id, idempotency_key, status, attempt, parse_generation)
                VALUES (?, ?, ?, ?, 'EXTRACTING', 1, 1)
                """,
                jobId,
                orgId,
                packageId,
                "finalization-" + jobId);
        return new Fixture(packageId, jobId, orgId);
    }

    private UUID seedSource(Fixture fixture, int ordinal, String digest) {
        UUID sourceId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file
                    (id, org_id, package_id, ordinal, original_filename, content_type,
                     size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, ?, 'synthetic.pdf', 'application/pdf', 1, ?, ?)
                """,
                sourceId,
                fixture.orgId(),
                fixture.packageId(),
                ordinal,
                digest,
                "synthetic-source-" + sourceId);
        return sourceId;
    }

    private static String key(FinalizationResult result) {
        return "org/"
                + ORG_DEV
                + "/engine-results/sha256/"
                + result.envelopeSha256()
                + ".json";
    }

    private static void assertDomain(Runnable call, ErrorCode code, int status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.httpStatus()).isEqualTo(status);
                            assertThat(failure.getMessage())
                                    .doesNotContain("synthetic-finalization", "synthetic.pdf");
                        });
    }

    private record Fixture(UUID packageId, UUID jobId, UUID orgId) {}

    private static final class RollbackMarker extends RuntimeException {}
}
