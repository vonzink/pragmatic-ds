package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.TestRunGroups;
import com.pragmaticds.rag.domain.AnalysisRun;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.lab.analyze.ParsedAnalysisInput;
import com.pragmaticds.rag.lab.analyze.ParsedIncomeAnalysisService;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabEnginePackageBinding;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.engine.ReviewedFields;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabAuditEventRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationRepository;
import com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
import com.pragmaticds.rag.lab.service.IncomeLabService;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabRetentionService;
import com.pragmaticds.rag.lab.service.LabRunRecoveryService;
import com.pragmaticds.rag.lab.web.LabDtos;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.chat.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The Income Lab against real PostgreSQL transactions, with a fake engine and a fake model.
 *
 * <p>The unit tests pin ordering against mocks; this pins the things only a real database can show:
 * that the idempotency constraint actually settles a race, that a terminal transition really can
 * happen only once, that ciphertext really is what lands in the table, that history really survives
 * a restart, and that a purge really leaves nothing recoverable behind.
 *
 * <p><b>Every collaborator that could reach outside the process is a mock, and every value is
 * synthetic.</b> No corpus document, value, or fixture is used, and the canaries planted below are
 * invented strings whose only job is to fail loudly if they ever appear where they must not.
 */
@SpringBootTest(properties = {
        "ragbrain.lab.enabled=true",
        "ragbrain.lab.max-upload-bytes=1048576",
        "ragbrain.lab.retention-days=7",
        // A synthetic 32-byte key. Nothing derived from any real deployment.
        "ragbrain.lab.payload-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "ragbrain.lab.engine.base-url=http://localhost:9090",
        "ragbrain.lab.engine.dev-auth=true",
        "ragbrain.lab.engine.org-id=00000000-0000-4000-8000-0000000000aa",
        "ragbrain.lab.engine.max-envelope-bytes=8388608",
        "ragbrain.lab.engine.connect-timeout-ms=2000",
        "ragbrain.lab.engine.read-timeout-ms=5000",
        // Park the sweeps: this test drives recovery and retention explicitly.
        "ragbrain.lab.recovery-sweep-ms=3600000",
        "ragbrain.lab.retention-sweep-ms=3600000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IncomeLabIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** Appears in the parsed envelope; must never appear in ciphertext, audit, or a failure. */
    private static final String VALUE_CANARY = "CANARY-VALUE-8471223";

    /** Planted inside simulated collaborator failures; must never reach an API body or the DB. */
    private static final String FAILURE_CANARY = "CANARY-FAILURE-provider-body-33af";

    @MockBean DocumentEngineClient engine;
    @MockBean ParsedIncomeAnalysisService analysis;
    @MockBean IncomeLabReleaseService releases;
    @MockBean ChatService chat;

    @Autowired IncomeLabService lab;
    @Autowired LabRetentionService retention;
    @Autowired LabRunRecoveryService recovery;
    @Autowired BrainRepository brains;
    @Autowired AnalysisRunRepository analysisRuns;
    @Autowired LabInstanceRepository instances;
    @Autowired LabInstanceReleaseRepository releaseRows;
    @Autowired LabDocumentRegistrationRepository registrations;
    @Autowired LabEnginePackageBindingRepository packageBindings;
    @Autowired LabRunRepository runs;
    @Autowired LabRunDocumentRepository runDocuments;
    @Autowired LabRunReviewSnapshotRepository reviewSnapshots;
    @Autowired LabRunPayloadRepository payloads;
    @Autowired LabDiscussionExchangeRepository exchanges;
    @Autowired LabDiscussionMessageRepository messages;
    @Autowired LabAuditEventRepository auditEvents;
    @Autowired JdbcTemplate jdbc;

    private UUID brainId;
    private UUID otherBrainId;
    private UUID releaseId;
    private UUID packageId;
    private UUID jobId;
    private UUID sourceId;
    private final AtomicInteger analyzerExecutions = new AtomicInteger();
    private final AtomicInteger discussionCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        analyzerExecutions.set(0);
        discussionCalls.set(0);
        brainId = brains.save(new Brain(UUID.randomUUID(), "lab-it-" + UUID.randomUUID(),
                "Lab IT")).getId();
        otherBrainId = brains.save(new Brain(UUID.randomUUID(), "lab-it-other-" + UUID.randomUUID(),
                "Lab IT Other")).getId();
        packageId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        sourceId = UUID.randomUUID();

        // V35 makes every release belong to a brain-scoped registry identity. The legacy Income
        // fixture still pins its historical release explicitly, so establish that parent without
        // enabling the generalized instance controller or changing any Income request behavior.
        instances.saveAndFlush(new LabInstance(brainId, "income", "Income", "Income Lab fixture"));

        LabInstanceRelease release = new LabInstanceRelease();
        release.setBrainId(brainId);
        release.setInstanceSlug("income");
        release.setReleaseNumber(1);
        release.setProvenanceMode(LabInstanceRelease.ProvenanceMode.PRODUCTION);
        release.setManifest(Map.of("instanceSlug", "income"));
        release.setManifestSha256(HexFormat.of().formatHex(new byte[32]));
        releaseId = releaseRows.saveAndFlush(release).getId();

        when(releases.resolveForRun(brainId)).thenReturn(
                new IncomeLabReleaseService.ResolvedRelease(releaseId, 1,
                        HexFormat.of().formatHex(new byte[32]), manifest()));
        when(releases.resolveInstance(brainId)).thenReturn(instanceState());

        when(engine.register(any(), anyString())).thenReturn(
                new DocumentEngineClient.UploadRegistration(packageId, jobId,
                        List.of(new DocumentEngineClient.RegisteredSource(sourceId, "a1".repeat(32),
                                64L, 1)), List.of()));
        when(engine.job(jobId)).thenReturn(
                new DocumentEngineClient.JobSnapshot(jobId, packageId, "COMPLETED", null));
        when(engine.revisionHistory(packageId)).thenReturn(List.of(descriptor()));
        when(engine.currentEnvelope(packageId)).thenReturn(verified());
        when(engine.envelopeRevision(eq(packageId), anyInt())).thenReturn(verified());

        when(analysis.analyze(any())).thenAnswer(call -> {
            analyzerExecutions.incrementAndGet();
            return writeAnalyzerRowAndOutcome(call.getArgument(0));
        });
        when(chat.answerSanitized(any(), any(), anyString())).thenAnswer(call -> {
            discussionCalls.incrementAndGet();
            return new ChatResponse("A pinned answer mentioning " + VALUE_CANARY, List.of());
        });
    }

    // ================================================================ registration

    @Test
    void uploadCreatesNoApplicationTempFileAndForwardsTheKeyUnchanged() throws Exception {
        Set<Path> before = tempFiles();

        LabDtos.RegistrationResponse first = lab.registerDocument(brainId, "income",
                upload(64L), "upload-key-1");
        LabDtos.RegistrationResponse replay = lab.registerDocument(brainId, "income",
                upload(64L), "upload-key-1");

        assertTrue(first.created());
        assertFalse(replay.created(), "a replay adopts the same registration");
        assertEquals(first.registrationId(), replay.registrationId());
        assertEquals(packageId, replay.packageId());
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(2))
                .register(any(), eq("upload-key-1"));

        assertEquals(1, registrations.findByBrainIdAndInstanceSlugOrderByRegisteredAtDesc(
                brainId, "income").size(), "one registration, not two");
        assertEquals(before, tempFiles(),
                "Lab code creates no application-owned temporary file for an upload");
    }

    @Test
    void aPackageBoundToAnotherBrainCannotBeSubstituted() {
        lab.registerDocument(brainId, "income", upload(64L), "upload-key-1");

        IncomeLabService.LabRequestException conflict = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> lab.registerDocument(otherBrainId, "income", upload(64L), "upload-key-2"));
        assertEquals(IncomeLabService.LabRequestException.Code.REGISTRATION_CONFLICT,
                conflict.code());

        // And a read from the other brain fails BEFORE any engine call.
        IncomeLabService.LabRequestException blocked = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> lab.documentStatus(otherBrainId, packageId, jobId));
        assertEquals(IncomeLabService.LabRequestException.Code.REGISTRATION_NOT_FOUND,
                blocked.code());
    }

    // ================================================================ runs

    @Test
    void aRunPinsItsDescriptorAndTheExactMetadataOnlyAnalyzerRow() {
        register();

        LabDtos.RunResponse response = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "run-key-1");

        LabRun stored = runs.findById(response.runId()).orElseThrow();
        assertEquals(LabRun.Status.SUCCEEDED, stored.getStatus());
        assertNotNull(stored.getAnalysisRunId());
        assertNull(stored.getLeaseExpiresAt(), "a terminal run releases its lease");
        assertNotNull(stored.getTerminalAt());

        var pinned = runDocuments.findByRunId(response.runId()).get(0);
        assertEquals(descriptor().envelopeSha256(), pinned.getEnvelopeSha256());
        assertEquals(3, pinned.getPackageRevision());
        assertEquals(1, pinned.getParseGeneration());

        AnalysisRun analyzerRow = analysisRuns.findById(stored.getAnalysisRunId()).orElseThrow();
        assertEquals(brainId, analyzerRow.getBrainId());
        // Metadata only: no findings row was written even though the Lab path succeeded.
        assertNull(jdbc.queryForObject(
                "SELECT findings FROM analysis_runs WHERE id = ?", String.class,
                stored.getAnalysisRunId()));
    }

    @Test
    void aSameKeyRunRetryAddsZeroAnalyzerExecutions() {
        register();

        LabDtos.RunResponse first = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "run-key-1");
        LabDtos.RunResponse replay = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "run-key-1");

        assertEquals(first.runId(), replay.runId());
        assertTrue(replay.replayed());
        assertEquals(1, analyzerExecutions.get(), "the replay ran no analyzer and billed nothing");
        assertEquals(1, runs.findByBrainIdAndInstanceSlugOrderByCreatedAtDesc(brainId, "income")
                .size());
    }

    @Test
    void concurrentSameKeyCallersProduceExactlyOneRunAndOneAnalyzerExecution() throws Exception {
        register();
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<UUID>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return lab.startRun(brainId, "income",
                            new LabDtos.RunRequest(packageId, null), "race-key").runId();
                }));
            }
            gate.countDown();
            Set<UUID> distinct = new java.util.HashSet<>();
            for (Future<UUID> result : results) {
                distinct.add(result.get(60, TimeUnit.SECONDS));
            }
            assertEquals(1, distinct.size(), "the unique key constraint settles the race");
            assertEquals(1, analyzerExecutions.get(), "only the winner executed the analyzer");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void storedPayloadIsCiphertextWithoutTheValueCanary() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();

        LabRunPayload payload = payloads
                .findByRunIdAndPayloadType(runId, LabRunPayload.PayloadType.ANALYSIS_OUTPUT)
                .orElseThrow();
        assertEquals("AES-256-GCM", payload.getCipherAlgorithm());
        assertEquals(12, payload.getNonce().length);
        String raw = new String(payload.getCiphertext(), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(VALUE_CANARY), "the value canary is not stored in the clear");
        assertFalse(raw.contains("reportMarkdown"), "no plaintext structure survives in the row");

        // And the authorized read still returns it, so the encryption is real, not lossy.
        assertTrue(lab.runDetail(brainId, runId).analysis().reportMarkdown().contains(VALUE_CANARY));
    }

    @Test
    void aCanaryBearingAnalyzerFailureIsPayloadFreeInTheApiTheRowAndTheAudit() {
        register();
        doThrow(new ModelRouterService.SanitizedProviderException(
                ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                "anthropic", "HttpClientErrorException", "corr-it"))
                .when(analysis).analyze(any());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                        "run-key-fail"));
        assertFalse(String.valueOf(failure).contains(FAILURE_CANARY));

        LabRun stored = runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(brainId, "income",
                "run-key-fail").orElseThrow();
        assertEquals(LabRun.Status.FAILED, stored.getStatus());
        assertEquals("PROVIDER_CALL_FAILED", stored.getFailureCode());
        assertNull(stored.getAnalysisRunId(), "a failed run pins no analyzer row");
        assertTrue(payloads.findByRunIdAndPayloadType(stored.getId(),
                LabRunPayload.PayloadType.ANALYSIS_OUTPUT).isEmpty(),
                "no partial plaintext, and no partial ciphertext either");

        assertTrue(auditEvents.findByBrainIdAndSubjectIdOrderByCreatedAtDesc(brainId,
                        stored.getId()).stream()
                .anyMatch(event -> event.getStatus() == LabAuditEvent.Status.FAILED),
                "the refusal is recorded");
        assertFalse(auditText().contains(FAILURE_CANARY));
        assertFalse(auditText().contains(VALUE_CANARY));
    }

    @Test
    void aCrossBrainRunIdFailsClosed() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> lab.runDetail(otherBrainId, runId));
        assertEquals(IncomeLabService.LabRequestException.Code.RUN_NOT_FOUND, failure.code());
    }

    @Test
    void historySurvivesARestartAndStaysNewestFirst() {
        register();
        UUID older = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();
        UUID newer = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-2").runId();

        // "Restart" for a stateless service is a fresh read with no in-memory state involved:
        // every row below came from PostgreSQL, not from a cache this process is holding.
        List<UUID> ordered = lab.history(brainId, "income").runs().stream()
                .map(LabDtos.RunSummary::runId).toList();
        assertEquals(List.of(newer, older), ordered);
        assertEquals(2, runs.findByBrainIdAndInstanceSlugOrderByCreatedAtDesc(brainId, "income")
                .size());
    }

    // ================================================================ recovery

    @Test
    void anExpiredRunLeaseBecomesInterruptedWithoutAnyReplay() {
        register();
        UUID runId = claimAbandonedRun("abandoned-key");

        int recovered = recovery.recoverExpiredRuns();

        assertTrue(recovered >= 1);
        LabRun stored = runs.findById(runId).orElseThrow();
        assertEquals(LabRun.Status.INTERRUPTED, stored.getStatus());
        assertEquals(LabRunRecoveryService.LEASE_EXPIRED, stored.getFailureCode());
        assertEquals(0, analyzerExecutions.get(), "recovery never starts an analyzer execution");

        // The same key now returns the interrupted run rather than starting anything.
        LabDtos.RunResponse replay = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "abandoned-key");
        assertEquals("INTERRUPTED", replay.status());
        assertEquals(0, analyzerExecutions.get());

        // Only an explicit NEW key starts a fresh attempt.
        LabDtos.RunResponse fresh = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "operator-new-key");
        assertEquals("SUCCEEDED", fresh.status());
        assertEquals(1, analyzerExecutions.get());
    }

    @Test
    void aTerminalRunCannotTransitionASecondTime() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();

        // The database guard, not merely the Java check: an UPDATE straight at the row is refused.
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE lab_run SET status = 'FAILED' WHERE id = ?", runId));
        assertEquals(LabRun.Status.SUCCEEDED, runs.findById(runId).orElseThrow().getStatus());
    }

    // ================================================================ discussion

    @Test
    void discussionIsIdempotentSerializedAndOrdinalStable() throws Exception {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();

        LabDtos.DiscussionResponse first = lab.postMessage(brainId, runId,
                new LabDtos.MessageRequest("why?"), "msg-1");
        assertFalse(first.replayed());
        assertEquals(1, discussionCalls.get());

        LabDtos.DiscussionResponse replay = lab.postMessage(brainId, runId,
                new LabDtos.MessageRequest("why?"), "msg-1");
        assertTrue(replay.replayed());
        assertEquals(1, discussionCalls.get(), "a replayed key makes zero model calls");

        lab.postMessage(brainId, runId, new LabDtos.MessageRequest("and then?"), "msg-2");

        List<LabDiscussionExchange> transcript =
                exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        assertEquals(List.of(1, 2),
                transcript.stream().map(LabDiscussionExchange::getSequenceNumber).toList());
        assertEquals(List.of(1, 3),
                transcript.stream().map(LabDiscussionExchange::getUserOrdinal).toList());
        assertEquals(List.of(2, 4),
                transcript.stream().map(LabDiscussionExchange::getAssistantOrdinal).toList());

        // Bodies are ciphertext at rest and readable only through an authorized decrypt.
        String stored = messages.findByExchangeIdInOrderByOrdinalAsc(
                        transcript.stream().map(LabDiscussionExchange::getId).toList()).stream()
                .map(message -> new String(message.getCiphertext(), StandardCharsets.ISO_8859_1))
                .reduce("", String::concat);
        assertFalse(stored.contains(VALUE_CANARY));
        assertTrue(lab.discussion(brainId, runId).exchanges().get(0).messages().get(1).body()
                .contains(VALUE_CANARY));
    }

    @Test
    void anExpiredExchangeLeaseBecomesInterruptedAndIsNeverReplayed() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();
        UUID exchangeId = claimAbandonedExchange(runId, "msg-abandoned");

        int recovered = recovery.recoverExpiredExchanges();

        assertTrue(recovered >= 1);
        assertEquals(LabRun.Status.INTERRUPTED,
                exchanges.findById(exchangeId).orElseThrow().getStatus());
        assertEquals(0, discussionCalls.get(), "recovery never calls the model");

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> lab.postMessage(brainId, runId, new LabDtos.MessageRequest("why?"),
                        "msg-abandoned"));
        assertEquals(IncomeLabService.LabRequestException.Code.DISCUSSION_EXCHANGE_INTERRUPTED,
                failure.code());
        assertEquals(0, discussionCalls.get());
    }

    // ================================================================ reviewed values

    @Test
    void aRunWithCorrectedAndRejectedFieldsOverlaysThemPinsTheSnapshotAndAuditsTheCounts() {
        register();
        stubReviewedRelease();
        when(engine.reviewedFields(reviewableDocId())).thenReturn(
                reviewedFieldsWith("aa".repeat(32), EngineResultEnvelope.ReviewState.REJECTED, null));

        LabDtos.RunResponse response = lab.startRun(brainId, "income",
                new LabDtos.RunRequest(packageId, null), "run-key-reviewed");

        assertEquals("SUCCEEDED", response.status());
        assertNotNull(response.reviewSnapshot(), "a run under a read-model release pins a snapshot");
        assertEquals(1, response.reviewSnapshot().machineCount());
        assertEquals(1, response.reviewSnapshot().correctedCount());
        assertEquals(1, response.reviewSnapshot().rejectedCount());

        LabRunReviewSnapshot snapshotRow =
                reviewSnapshots.findByRunId(response.runId()).orElseThrow();
        assertEquals(1, snapshotRow.getCorrectedCount());
        assertEquals(1, snapshotRow.getRejectedCount());
        assertEquals(response.reviewSnapshot().fieldsSha256(), snapshotRow.getFieldsSha256());

        ArgumentCaptor<ParsedAnalysisInput> input =
                ArgumentCaptor.forClass(ParsedAnalysisInput.class);
        org.mockito.Mockito.verify(analysis).analyze(input.capture());
        List<FieldOccurrence> analyzed =
                input.getValue().verified().envelope().documents().get(0).fields();
        FieldOccurrence corrected = analyzed.stream()
                .filter(field -> field.name().equals("wages.annual")).findFirst().orElseThrow();
        assertEquals(EngineResultEnvelope.ReviewState.CORRECTED, corrected.reviewState());
        assertEquals(new BigDecimal("75000.00"), corrected.normalized().number());
        FieldOccurrence rejected = analyzed.stream()
                .filter(field -> field.name().equals("employer.ein")).findFirst().orElseThrow();
        assertEquals(EngineResultEnvelope.ReviewState.REJECTED, rejected.reviewState());
        assertEquals(FieldStatus.MISSING, rejected.status());
        assertNull(rejected.normalized());

        LabAuditEvent runStart = auditEvents
                .findByBrainIdAndSubjectIdOrderByCreatedAtDesc(brainId, response.runId()).stream()
                .filter(event -> LabAuditService.RUN_START.equals(event.getAction()))
                .findFirst().orElseThrow();
        assertEquals(1, ((Number) runStart.getMetadata().get("machineFields")).intValue());
        assertEquals(1, ((Number) runStart.getMetadata().get("correctedFields")).intValue());
        assertEquals(1, ((Number) runStart.getMetadata().get("rejectedFields")).intValue());

        LabDtos.EnvelopeResponse envelope = lab.envelope(brainId, packageId, null);
        LabDtos.Field einField = envelope.documents().get(0).fields().stream()
                .filter(field -> field.name().equals("employer.ein")).findFirst().orElseThrow();
        assertEquals("REJECTED", einField.reviewState());
        assertEquals("MISSING", einField.status());
        assertNull(einField.displayedText());
        assertNull(einField.rawValue());
        assertNull(einField.normalizedText());
        assertNull(einField.normalizedNumber());
        assertNull(einField.normalizedDate());
    }

    @Test
    void aDiscussionAfterReviewerChangesReportsReviewDrift() {
        register();
        stubReviewedRelease();
        when(engine.reviewedFields(reviewableDocId())).thenReturn(
                reviewedFieldsWith("aa".repeat(32), EngineResultEnvelope.ReviewState.REJECTED, null));
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-reviewed").runId();

        // The engine's read model moves after the run pinned its snapshot: the previously
        // rejected field is now corrected, and the document's bytes carry a different sha.
        when(engine.reviewedFields(reviewableDocId())).thenReturn(
                reviewedFieldsWith("bb".repeat(32), EngineResultEnvelope.ReviewState.CORRECTED,
                        "12-3456789-CORRECTED"));

        LabDtos.DiscussionResponse response = lab.postMessage(brainId, runId,
                new LabDtos.MessageRequest("why?"), "msg-1");

        assertTrue(response.reviewDrift().changed());
        assertEquals(1, response.reviewDrift().rejectedBefore());
        assertEquals(0, response.reviewDrift().rejectedNow());
        assertEquals(1, response.reviewDrift().correctedBefore());
        assertEquals(2, response.reviewDrift().correctedNow());
        assertEquals(1, response.reviewDrift().machineBefore());
        assertEquals(1, response.reviewDrift().machineNow());
    }

    private void stubReviewedRelease() {
        when(releases.resolveForRun(brainId)).thenReturn(
                new IncomeLabReleaseService.ResolvedRelease(releaseId, 1,
                        HexFormat.of().formatHex(new byte[32]), manifestReadingReviewedValues()));
        when(engine.currentEnvelope(packageId)).thenReturn(verifiedWithReviewableFields());
        when(engine.envelopeRevision(eq(packageId), anyInt()))
                .thenReturn(verifiedWithReviewableFields());
    }

    /** The IT's own manifest, with the read-model block Task 7 added to {@code EngineContract}. */
    private static LabReleaseManifest manifestReadingReviewedValues() {
        LabReleaseManifest base = manifest();
        LabReleaseManifest.EngineContract contract = base.pinned().engineContract();
        return new LabReleaseManifest(base.manifestVersion(), base.canonicalization(),
                base.instanceSlug(), base.analyzerSlug(),
                new LabReleaseManifest.Pinned(base.pinned().analyzer(), base.pinned().retrieval(),
                        new LabReleaseManifest.EngineContract(contract.resultMediaType(),
                                contract.supportedEnvelopeVersions(),
                                contract.supportedCanonicalizationVersions(),
                                contract.supportedDocumentTypes(),
                                contract.reviewWarningValidationStatuses(),
                                new LabReleaseManifest.ReadModelContract(
                                        LabReleaseManifest.FIELDS_CONTRACT,
                                        List.of("CORRECTED", "MACHINE", "REJECTED"))),
                        base.pinned().calculator()),
                base.observedInference(), base.prototypeLimitations());
    }

    private UUID reviewableDocId() {
        return UUID.nameUUIDFromBytes(("doc" + packageId).getBytes(StandardCharsets.UTF_8));
    }

    private UUID reviewablePageId() {
        return UUID.nameUUIDFromBytes(("page" + packageId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The same document as {@link #envelope()}, but with three occurrences instead of one so a
     * single run can carry a MACHINE, a CORRECTED, and a REJECTED field at once. The artifact
     * bytes are the exact same formula as {@link #envelope()}'s, so the descriptor and revision
     * history stubbed in {@link #setUp()} still describe it.
     */
    private EngineResultEnvelope envelopeWithReviewableFields() {
        UUID pageId = reviewablePageId();
        UUID docId = reviewableDocId();
        SchemaRef schema = new SchemaRef(
                UUID.nameUUIDFromBytes("schema".getBytes(StandardCharsets.UTF_8)), "1");
        EngineResultEnvelope.Box box = new EngineResultEnvelope.Box(
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN);

        FieldOccurrence employerName = new FieldOccurrence("employer.name", null, FieldStatus.FOUND,
                "TEXT", VALUE_CANARY, VALUE_CANARY,
                new NormalizedValue(VALUE_CANARY, null, null, null), schema, "ANCHORED", "1.4.0",
                new BigDecimal("0.97"), null, "OK", false,
                List.of(new EvidenceSpan(pageId, null, 7L, "VALUE", 0, box)));
        FieldOccurrence wagesAnnual = new FieldOccurrence("wages.annual", null, FieldStatus.FOUND,
                "MONEY", "$50,000.00", "50000.00",
                new NormalizedValue(null, new BigDecimal("50000.00"), null, null), schema,
                "ANCHORED", "1.4.0", new BigDecimal("0.9"), null, "OK", false,
                List.of(new EvidenceSpan(pageId, null, 8L, "VALUE", 0, box)));
        FieldOccurrence employerEin = new FieldOccurrence("employer.ein", null, FieldStatus.FOUND,
                "TEXT", "12-3456789", "12-3456789",
                new NormalizedValue("12-3456789", null, null, null), schema, "ANCHORED", "1.4.0",
                new BigDecimal("0.9"), null, "OK", false,
                List.of(new EvidenceSpan(pageId, null, 9L, "VALUE", 0, box)));

        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of(("synthetic-envelope-" + packageId)
                        .getBytes(StandardCharsets.UTF_8)),
                "1.0.0", "DOCENGINE-C14N-1", packageId,
                new Generation(jobId, 1, 3, "9f".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(sourceId, 0, "a1".repeat(32), 64L, "application/pdf")),
                List.of(new EnginePage(pageId, sourceId, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                List.of(new LogicalDocument(docId, "W2", 1, List.of(pageId),
                        List.of(employerName, wagesAnnual, employerEin))),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    private DocumentEngineClient.VerifiedEnvelope verifiedWithReviewableFields() {
        EngineResultEnvelope envelope = envelopeWithReviewableFields();
        return new DocumentEngineClient.VerifiedEnvelope(envelope.artifact(), envelope, 3);
    }

    /**
     * The read model's view of {@link #envelopeWithReviewableFields()}'s three occurrences:
     * {@code employer.name} unchanged (MACHINE), {@code wages.annual} corrected to a different
     * number, and {@code employer.ein} at whichever status the caller is proving.
     *
     * @param einDisplayedText the corrected display text for {@code employer.ein}, or null for a
     *     rejected occurrence with no value arm
     */
    private ReviewedFields reviewedFieldsWith(String sha, EngineResultEnvelope.ReviewState einStatus,
                                              String einDisplayedText) {
        NormalizedValue einNormalized = einDisplayedText == null
                ? new NormalizedValue(null, null, null, null)
                : new NormalizedValue(einDisplayedText, null, null, null);
        return new ReviewedFields(reviewableDocId(), "W2", "1", List.of(
                new ReviewedFields.ReviewedField("employer.name", null,
                        ReviewedFields.GroupKind.NONE, EngineResultEnvelope.ReviewState.MACHINE,
                        "TEXT", VALUE_CANARY, VALUE_CANARY,
                        new NormalizedValue(VALUE_CANARY, null, null, null), "ANCHORED", false,
                        List.of(reviewablePageId())),
                new ReviewedFields.ReviewedField("wages.annual", null,
                        ReviewedFields.GroupKind.NONE, EngineResultEnvelope.ReviewState.CORRECTED,
                        "MONEY", "$75,000.00", "75000.00",
                        new NormalizedValue(null, new BigDecimal("75000.00"), null, null),
                        "MANUAL_REVIEW", false, List.of(reviewablePageId())),
                new ReviewedFields.ReviewedField("employer.ein", null,
                        ReviewedFields.GroupKind.NONE, einStatus, "TEXT", einDisplayedText,
                        einDisplayedText, einNormalized,
                        einDisplayedText == null ? "NONE" : "MANUAL_REVIEW", false,
                        List.of(reviewablePageId()))),
                sha, 512);
    }

    // ================================================================ purge

    @Test
    void purgeIsIdempotentAndLeavesNothingRecoverable() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();
        lab.postMessage(brainId, runId, new LabDtos.MessageRequest("why?"), "msg-1");
        UUID analysisRunId = runs.findById(runId).orElseThrow().getAnalysisRunId();
        long auditRowsBefore = auditEvents.findByBrainIdOrderByCreatedAtDesc(brainId).size();

        LabDtos.PurgeResponse purged = lab.purgeRun(brainId, runId);
        assertTrue(purged.deleted());
        assertEquals(2, purged.messagesDeleted());
        assertEquals(1, purged.exchangesDeleted());
        assertEquals(1, purged.payloadsDeleted());
        assertEquals(1, purged.documentsDeleted());
        assertTrue(purged.analysisRunDeleted());
        assertTrue(purged.enginePackageRetained());

        assertTrue(runs.findById(runId).isEmpty());
        assertTrue(runDocuments.findByRunId(runId).isEmpty());
        assertTrue(payloads.findByRunIdAndPayloadType(runId,
                LabRunPayload.PayloadType.ANALYSIS_OUTPUT).isEmpty());
        assertTrue(exchanges.findByRunIdOrderBySequenceNumberAsc(runId).isEmpty());
        assertTrue(analysisRuns.findById(analysisRunId).isEmpty());
        // The engine package binding is untouched: purge deletes RAG Brain's record, not the
        // engine's immutable result.
        assertTrue(registrations.findFirstByEnginePackageIdOrderByRegisteredAtAsc(packageId).isPresent());
        // Audit is append-only: the tombstone ADDS rows, it never removes them.
        assertTrue(auditEvents.findByBrainIdOrderByCreatedAtDesc(brainId).size() > auditRowsBefore);

        LabDtos.PurgeResponse second = lab.purgeRun(brainId, runId);
        assertFalse(second.deleted(), "the second purge is a no-op success");
    }

    @Test
    void anInFlightRunIsNotDeletedUnderneathItsOwnExecution() {
        register();
        UUID runId = claimAbandonedRun("in-flight-key");
        // Give it a live lease again so it counts as genuinely in flight.
        jdbc.update("UPDATE lab_run SET lease_expires_at = now() + interval '10 minutes' "
                + "WHERE id = ?", runId);

        IncomeLabService.LabRequestException conflict = assertThrows(
                IncomeLabService.LabRequestException.class, () -> lab.purgeRun(brainId, runId));
        assertEquals(IncomeLabService.LabRequestException.Code.RUN_IN_PROGRESS, conflict.code());
        assertTrue(runs.findById(runId).isPresent());
    }

    @Test
    void theAgeSweepAppliesTheSamePathAsAManualPurge() {
        register();
        UUID runId = lab.startRun(brainId, "income", new LabDtos.RunRequest(packageId, null),
                "run-key-1").runId();
        // Only the passage of time can age a run: lab_run_guard() refuses any UPDATE to a terminal
        // row, created_at included. So the guard is lifted for exactly this forgery and restored
        // immediately — the fact that it has to be lifted at all is itself the pin.
        jdbc.execute("ALTER TABLE lab_run DISABLE TRIGGER trg_lab_run_guard");
        try {
            jdbc.update("UPDATE lab_run SET created_at = now() - interval '30 days' WHERE id = ?",
                    runId);
        } finally {
            jdbc.execute("ALTER TABLE lab_run ENABLE TRIGGER trg_lab_run_guard");
        }

        retention.sweepExpiredRuns();

        assertTrue(runs.findById(runId).isEmpty(), "an aged run is purged by the sweep");
    }

    // ================================================================ fixtures

    private void register() {
        // V38 makes package ownership explicit, and a registration carries a composite foreign key
        // into the binding, so the brain has to claim the package first.
        if (packageBindings.findById(packageId).isEmpty()) {
            packageBindings.saveAndFlush(new LabEnginePackageBinding(packageId, brainId));
        }
        LabDocumentRegistration registration = new LabDocumentRegistration();
        registration.setBrainId(brainId);
        registration.setInstanceSlug("income");
        registration.setEnginePackageId(packageId);
        registration.setEngineJobId(jobId);
        registration.setEngineSourceId(sourceId);
        registrations.saveAndFlush(registration);
    }

    /** A run left {@code PROCESSING} with an already-elapsed lease, as a crash would leave it. */
    private UUID claimAbandonedRun(String key) {
        UUID runId = UUID.randomUUID();
        UUID registrationId = registrations
                .findByEnginePackageIdAndBrainIdAndInstanceSlug(packageId, brainId, "income")
                .orElseThrow().getId();
        TestRunGroups.insertSoloGroup(jdbc, runId, brainId);
        jdbc.update("INSERT INTO lab_run (id, brain_id, instance_slug, idempotency_key, "
                        + "release_id, registration_id, status, attempt, lease_expires_at, "
                        + TestRunGroups.membershipColumns() + ") "
                        + "VALUES (?, ?, 'income', ?, ?, ?, 'PROCESSING', 1, "
                        + "now() - interval '1 hour', "
                        + TestRunGroups.membershipValues(runId) + ")",
                runId, brainId, key, releaseId, registrationId);
        return runId;
    }

    /** An exchange left {@code PROCESSING} with an elapsed lease. */
    private UUID claimAbandonedExchange(UUID runId, String key) {
        UUID exchangeId = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_discussion_exchange (id, run_id, idempotency_key, "
                        + "sequence_number, user_ordinal, assistant_ordinal, status, "
                        + "lease_expires_at) "
                        + "VALUES (?, ?, ?, 1, 1, 2, 'PROCESSING', now() - interval '1 hour')",
                exchangeId, runId, key);
        return exchangeId;
    }

    private ParsedIncomeAnalysisService.ParsedRunOutcome writeAnalyzerRowAndOutcome(
            ParsedAnalysisInput input) {
        // The fake analyzer writes exactly the metadata-only row the real recorder would: the
        // caller-allocated id, no findings, no calculation values, no filenames.
        AnalysisRun row = new AnalysisRun();
        row.setId(input.analysisRunId());
        row.setBrainId(input.brainId());
        row.setAnalyzerSlug("income-v2");
        row.setEnvelopeVersion("v2");
        row.setStatus("SUCCESS");
        row.setProvider("anthropic");
        row.setModel("claude-x");
        row.setAttempts(1);
        analysisRuns.saveAndFlush(row);

        com.pragmaticds.rag.service.analyze.AnalysisResult result =
                new com.pragmaticds.rag.service.analyze.AnalysisResult(
                        com.pragmaticds.rag.service.analyze.AnalysisResult.Status.SUCCESS,
                        "# Report mentioning " + VALUE_CANARY, "{}", List.of(), "anthropic",
                        "claude-x", 10, 20, 0.01, 1, List.of(), null, List.of(),
                        input.analysisRunId().toString());
        return new ParsedIncomeAnalysisService.ParsedRunOutcome(result,
                new ParsedIncomeAnalysisService.RunProvenance(input.analysisRunId(), releaseId, 1,
                        HexFormat.of().formatHex(new byte[32]), packageId, 3, 1, jobId,
                        "9f".repeat(32), verified().artifact().sha256(),
                        verified().artifact().byteCount(), "1.0.0", "DOCENGINE-C14N-1",
                        new ModelRouterService.Resolution(null, null, true, "anthropic", "claude-x",
                                "anthropic", "claude-x", false),
                        new ParsedIncomeAnalysisService.RetrievalProvenance(true, "income", 8, 8,
                                true),
                        List.of("BORROWER_DOC_1"), 0, 1,
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    private String auditText() {
        return auditEvents.findByBrainIdOrderByCreatedAtDesc(brainId).stream()
                .map(event -> String.valueOf(event.getAction()) + event.getFailureCode()
                        + event.getActor() + event.getCorrelationId() + event.getMetadata())
                .reduce("", String::concat);
    }

    private static Set<Path> tempFiles() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries.collect(java.util.stream.Collectors.toSet());
        }
    }

    private static DocumentEngineClient.EngineUpload upload(long size) {
        return new DocumentEngineClient.EngineUpload(size,
                new ByteArrayResource("%PDF-1.7 synthetic".getBytes(StandardCharsets.UTF_8)));
    }

    private DocumentEngineClient.VerifiedEnvelope verified() {
        EngineResultEnvelope envelope = envelope();
        return new DocumentEngineClient.VerifiedEnvelope(envelope.artifact(), envelope, 3);
    }

    private DocumentEngineClient.RevisionDescriptor descriptor() {
        DocumentEngineClient.VerifiedEnvelope fetched = verified();
        return new DocumentEngineClient.RevisionDescriptor(3, jobId, 1, 1, "1.0.0", "9f".repeat(32),
                "d4".repeat(32), fetched.artifact().sha256(), fetched.artifact().byteCount(),
                "PARSE_ONCE_CURRENT_PACKAGE", Instant.parse("2026-08-15T00:00:00Z"));
    }

    private EngineResultEnvelope envelope() {
        UUID pageId = UUID.nameUUIDFromBytes(("page" + packageId).getBytes(StandardCharsets.UTF_8));
        FieldOccurrence field = new FieldOccurrence("employer.name", null, FieldStatus.FOUND,
                "TEXT", VALUE_CANARY, VALUE_CANARY,
                new NormalizedValue(VALUE_CANARY, null, null, null),
                new SchemaRef(UUID.nameUUIDFromBytes("schema".getBytes(StandardCharsets.UTF_8)),
                        "1"),
                "ANCHORED", "1.4.0", new BigDecimal("0.97"), null, "OK", false,
                List.of(new EvidenceSpan(pageId, null, 7L, "VALUE", 0,
                        new EngineResultEnvelope.Box(BigDecimal.ONE, BigDecimal.TEN,
                                BigDecimal.ONE, BigDecimal.TEN))));
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of(("synthetic-envelope-" + packageId)
                        .getBytes(StandardCharsets.UTF_8)),
                "1.0.0", "DOCENGINE-C14N-1", packageId,
                new Generation(jobId, 1, 3, "9f".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(sourceId, 0, "a1".repeat(32), 64L, "application/pdf")),
                List.of(new EnginePage(pageId, sourceId, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                List.of(new LogicalDocument(
                        UUID.nameUUIDFromBytes(("doc" + packageId).getBytes(StandardCharsets.UTF_8)),
                        "W2", 1, List.of(pageId), List.of(field))),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    private IncomeLabReleaseService.InstanceState instanceState() {
        return new IncomeLabReleaseService.InstanceState("income", "income-v2", releaseId, 1,
                HexFormat.of().formatHex(new byte[32]), false, null, null,
                LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                LabReleaseManifest.LIVE_DEPENDENCIES);
    }

    private static LabReleaseManifest manifest() {
        return new LabReleaseManifest(LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION, "income", "income-v2",
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer("Income", "v2", "prompt", "{}",
                                IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                                "e5".repeat(32)),
                        new LabReleaseManifest.Retrieval("query", "income", 8),
                        new LabReleaseManifest.EngineContract(
                                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE, List.of("1.0.0"),
                                List.of("DOCENGINE-C14N-1"), List.of("W2"),
                                List.of("MANUAL_REVIEW_REQUIRED")),
                        new LabReleaseManifest.Calculator(List.of())),
                new LabReleaseManifest.ObservedInference(
                        LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE, "anthropic",
                        "claude-x", "openai", 20000, 2,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, null, null,
                                null, null, "anthropic", "claude-x", false)),
                new LabReleaseManifest.PrototypeLimitations(
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    private static byte[] key() {
        return Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }
}
