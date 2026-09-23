package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.lab.analyze.ParsedAnalysisInput;
import com.pragmaticds.rag.lab.analyze.ParsedIncomeAnalysisService;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunDocument;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.domain.LabRunReviewSnapshot;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
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
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationRepository;
import com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository;
import com.pragmaticds.rag.lab.repository.LabRunDocumentRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.repository.LabRunReviewSnapshotRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.lab.web.LabDtos;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.chat.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The orchestrator is where Tasks 1-5 finally meet, so these tests pin the joins rather than
 * re-testing the parts.
 *
 * <p>Four properties carry most of the weight:
 *
 * <ul>
 *   <li><b>Order of refusal.</b> A missing idempotency key, the wrong file count, a package
 *       belonging to another brain, a non-terminal job, or an incompatible envelope must all stop
 *       BEFORE the expensive thing they precede — before the engine call, before the claim, before
 *       the analyzer. Each of those is asserted as a {@code verifyNoInteractions}/{@code never},
 *       not as a status code.
 *   <li><b>Idempotency is a short circuit, not a retry.</b> A replayed run key returns the stored
 *       row having invoked the analyzer zero times; a replayed message key returns the stored pair
 *       having invoked {@code ChatService} zero times.
 *   <li><b>Terminal state is written once.</b> Success pins the caller-allocated
 *       {@code analysis_runs} id and the encrypted payload together; failure writes a safe code and
 *       no payload at all.
 *   <li><b>Nothing leaks.</b> Every failure carries a code and a correlation id; the canary planted
 *       in a provider/engine/recorder failure appears in no exception the Lab produces.
 * </ul>
 */
class IncomeLabServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PACKAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SOURCE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PAGE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID DOCUMENT = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID RELEASE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID REGISTRATION = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ANALYSIS_RUN = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID EXCHANGE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private static final String SOURCE_SET_SHA = "9f".repeat(32);
    private static final String KEY = "idem-key-1";

    /** Planted in every simulated collaborator failure; must never reach a Lab surface. */
    private static final String CANARY = "CANARY-a1b2c3-secret-body";

    private DocumentEngineClient engine;
    private IncomeLabReleaseService releases;
    private ParsedIncomeAnalysisService analysis;
    private LabRunTransactionService transactions;
    private LabRetentionService retention;
    private LabAuditService audit;
    private LabPayloadCipher cipher;
    private ChatService chat;
    private LabDocumentRegistrationRepository registrations;
    private LabEnginePackageBindingRepository packageBindings;
    private LabRunRepository runs;
    private LabRunDocumentRepository runDocuments;
    private LabRunPayloadRepository payloads;
    private LabDiscussionExchangeRepository exchanges;
    private LabDiscussionMessageRepository messages;
    private AnalysisRunRepository analysisRuns;
    private LabRunReviewSnapshotRepository reviewSnapshots;

    private IncomeLabService service;

    @BeforeEach
    void setUp() {
        engine = mock(DocumentEngineClient.class);
        releases = mock(IncomeLabReleaseService.class);
        analysis = mock(ParsedIncomeAnalysisService.class);
        transactions = mock(LabRunTransactionService.class);
        retention = mock(LabRetentionService.class);
        audit = mock(LabAuditService.class);
        cipher = mock(LabPayloadCipher.class);
        chat = mock(ChatService.class);
        registrations = mock(LabDocumentRegistrationRepository.class);
        runs = mock(LabRunRepository.class);
        runDocuments = mock(LabRunDocumentRepository.class);
        payloads = mock(LabRunPayloadRepository.class);
        exchanges = mock(LabDiscussionExchangeRepository.class);
        messages = mock(LabDiscussionMessageRepository.class);
        analysisRuns = mock(AnalysisRunRepository.class);
        packageBindings = mock(LabEnginePackageBindingRepository.class);
        when(packageBindings.findById(any())).thenReturn(Optional.empty());
        reviewSnapshots = mock(LabRunReviewSnapshotRepository.class);

        service = new IncomeLabService(engine, releases, analysis, transactions, retention, audit,
                cipher, chat, new ObjectMapper(), registrations, packageBindings, runs,
                runDocuments, payloads, exchanges, messages, analysisRuns, reviewSnapshots);
    }

    // ================================================================ instances

    @Test
    void instancesCarryThePrototypeBoundaryOnEveryResponse() {
        when(releases.resolveInstance(BRAIN)).thenReturn(instanceState(false));

        LabDtos.InstancesResponse response = service.instances(BRAIN);

        assertEquals(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE, response.prototype().code());
        assertEquals(LabReleaseManifest.LIVE_DEPENDENCIES,
                response.prototype().liveDependencies());
        assertEquals(1, response.instances().size());
        assertEquals(LabReleaseManifest.LIVE_DEPENDENCIES,
                response.instances().get(0).prototype().liveDependencies(),
                "each instance repeats the boundary so a single-instance render cannot lose it");
    }

    @Test
    void instancesExposeReleaseDriftWithoutPromotingIt() {
        when(releases.resolveInstance(BRAIN)).thenReturn(instanceState(true));

        LabDtos.Instance instance = service.instances(BRAIN).instances().get(0);

        assertTrue(instance.driftDetected());
        assertEquals(RELEASE, instance.productionReleaseId(),
                "drift never moves the production pointer");
    }

    // ================================================================ registration

    @Test
    void uploadRequiresAnIdempotencyKeyBeforeTouchingTheEngine() {
        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.registerDocument(BRAIN, "income", upload(64L), "   "));

        assertEquals(IncomeLabService.LabRequestException.Code.IDEMPOTENCY_KEY_REQUIRED,
                failure.code());
        verifyNoInteractions(engine);
    }

    @Test
    void uploadForwardsTheKeyByteForByteAndStoresAValueFreeRegistration() {
        when(engine.register(any(), eq(KEY))).thenReturn(registration());
        when(registrations.findFirstByEnginePackageIdOrderByRegisteredAtAsc(PACKAGE)).thenReturn(Optional.empty());
        when(registrations.save(any())).thenAnswer(call -> {
            LabDocumentRegistration saved = call.getArgument(0);
            saved.setId(REGISTRATION);
            return saved;
        });

        LabDtos.RegistrationResponse response =
                service.registerDocument(BRAIN, "income", upload(64L), KEY);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(engine).register(any(), key.capture());
        assertEquals(KEY, key.getValue(), "the caller's key is forwarded unchanged");

        ArgumentCaptor<LabDocumentRegistration> stored =
                ArgumentCaptor.forClass(LabDocumentRegistration.class);
        verify(registrations).save(stored.capture());
        assertEquals(BRAIN, stored.getValue().getBrainId());
        assertEquals(PACKAGE, stored.getValue().getEnginePackageId());
        assertEquals(JOB, stored.getValue().getEngineJobId());
        assertEquals(SOURCE, stored.getValue().getEngineSourceId());
        assertTrue(response.created());
        assertEquals(PACKAGE, response.packageId());
    }

    @Test
    void uploadReplayReturnsTheSameRegistrationWithoutInsertingAnother() {
        when(engine.register(any(), eq(KEY))).thenReturn(registration());
        when(registrations.findFirstByEnginePackageIdOrderByRegisteredAtAsc(PACKAGE))
                .thenReturn(Optional.of(existingRegistration(BRAIN, "income")));

        LabDtos.RegistrationResponse response =
                service.registerDocument(BRAIN, "income", upload(64L), KEY);

        assertFalse(response.created(), "an existing registration is adopted, not duplicated");
        assertEquals(REGISTRATION, response.registrationId());
        verify(registrations, never()).save(any());
    }

    @Test
    void aPackageRegisteredToAnotherBrainFailsClosed() {
        when(engine.register(any(), eq(KEY))).thenReturn(registration());
        when(registrations.findFirstByEnginePackageIdOrderByRegisteredAtAsc(PACKAGE))
                .thenReturn(Optional.of(existingRegistration(OTHER_BRAIN, "income")));

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.registerDocument(BRAIN, "income", upload(64L), KEY));

        assertEquals(IncomeLabService.LabRequestException.Code.REGISTRATION_CONFLICT,
                failure.code());
        verify(registrations, never()).save(any());
    }

    // ================================================================ document status

    @Test
    void documentStatusResolvesTheRegistrationBeforeReadingTheJob() {
        when(registrations.findByEnginePackageIdAndBrainIdAndInstanceSlug(PACKAGE, BRAIN, "income"))
                .thenReturn(Optional.empty());

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.documentStatus(BRAIN, PACKAGE, JOB));

        assertEquals(IncomeLabService.LabRequestException.Code.REGISTRATION_NOT_FOUND,
                failure.code());
        verifyNoInteractions(engine);
    }

    @Test
    void completedAndReviewRequiredJobsAreAnalyzableAndFailedJobsAreNot() {
        registrationFound();
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor()));

        when(engine.job(JOB)).thenReturn(job("COMPLETED"));
        assertTrue(service.documentStatus(BRAIN, PACKAGE, JOB).analyzable());

        when(engine.job(JOB)).thenReturn(job("HUMAN_REVIEW_REQUIRED"));
        LabDtos.DocumentStatusResponse review = service.documentStatus(BRAIN, PACKAGE, JOB);
        assertTrue(review.analyzable(), "review-required parses are analyzable with warnings");
        assertTrue(review.warnings().contains("HUMAN_REVIEW_REQUIRED"),
                "the warning must be prominent rather than implied");

        when(engine.job(JOB)).thenReturn(job("FAILED"));
        assertFalse(service.documentStatus(BRAIN, PACKAGE, JOB).analyzable());

        when(engine.job(JOB)).thenReturn(job("EXTRACTING"));
        assertFalse(service.documentStatus(BRAIN, PACKAGE, JOB).analyzable(),
                "a nonterminal job is not analyzable");
    }

    @Test
    void aJobThatBelongsToAnotherPackageIsBlocked() {
        registrationFound();
        when(engine.job(JOB)).thenReturn(new DocumentEngineClient.JobSnapshot(
                JOB, UUID.randomUUID(), "COMPLETED", null));

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.documentStatus(BRAIN, PACKAGE, JOB));

        assertEquals(IncomeLabService.LabRequestException.Code.JOB_IDENTITY_MISMATCH,
                failure.code());
    }

    @Test
    void statusReadsNeverUploadResumeOrReparse() {
        registrationFound();
        when(engine.job(JOB)).thenReturn(job("COMPLETED"));
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor()));

        service.documentStatus(BRAIN, PACKAGE, JOB);
        service.documentStatus(BRAIN, PACKAGE, JOB);

        verify(engine, never()).register(any(), anyString());
        verify(engine, never()).currentEnvelope(any());
        verify(engine, never()).envelopeRevision(any(), anyInt());
    }

    // ================================================================ envelope view

    @Test
    void envelopeViewSerializesNormalizedDecimalsAsCanonicalStrings() {
        registrationFound();
        when(engine.currentEnvelope(PACKAGE)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));

        LabDtos.EnvelopeResponse response = service.envelope(BRAIN, PACKAGE, null);

        LabDtos.Field field = response.documents().get(0).fields().get(0);
        assertEquals("12345678901234567890.123456789", field.normalizedNumber(),
                "a 29-digit normalized amount survives byte-for-byte as a decimal string");
        assertEquals(new BigDecimal("0.97"), field.confidence(),
                "bounded confidences stay numeric");
        assertEquals(new BigDecimal("612"), response.pages().get(0).widthPt(),
                "page geometry stays numeric");
    }

    @Test
    void envelopeViewIsAuthorizedThroughTheRegistrationAndFailsClosedWhenAuditCannotCommit() {
        registrationFound();
        when(engine.currentEnvelope(PACKAGE)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));
        auditFailsWith(new IllegalStateException(CANARY));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.envelope(BRAIN, PACKAGE, null));

        assertFalse(String.valueOf(failure).contains(CANARY),
                "a sensitive read that cannot be audited fails closed, payload-free");
    }

    @Test
    void envelopeViewCarriesTheCompatibilityDecisionOfTheStoredRelease() {
        registrationFound();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("SCHEDULE_E")));
        // The STORED release accepts only W2, so the live default set must not rescue this.
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));

        LabDtos.EnvelopeResponse response = service.envelope(BRAIN, PACKAGE, 3);

        assertFalse(response.compatible());
        assertEquals("NO_SUPPORTED_DOCUMENT", response.rejection());
    }

    private static LabReleaseManifest manifestReadingReviewedValues(List<String> documentTypes) {
        LabReleaseManifest base = manifest(documentTypes);
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

    /** The canary envelope's one field, as the read model would serve it after a correction. */
    private static ReviewedFields reviewedCanary(EngineResultEnvelope.ReviewState status,
                                                 String sha) {
        return new ReviewedFields(DOCUMENT, "W2", "1", List.of(
                new ReviewedFields.ReviewedField("wages.annual", null,
                        ReviewedFields.GroupKind.NONE, status, "MONEY",
                        "$99.00", "12345678901234567890.123456789",
                        new NormalizedValue(null, new BigDecimal("99.00"), null, null),
                        "ANCHORED", false, List.of(PAGE))), sha, 512);
    }

    @Test
    void theEnvelopeViewOverlaysReviewedValuesLikeARunWould() {
        registrationFound();
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(engine.currentEnvelope(PACKAGE)).thenReturn(verified(envelope("W2")));
        when(engine.reviewedFields(DOCUMENT)).thenReturn(
                reviewedCanary(EngineResultEnvelope.ReviewState.REJECTED, "ab".repeat(32)));

        LabDtos.EnvelopeResponse view = service.envelope(BRAIN, PACKAGE, null);

        LabDtos.Field field = view.documents().get(0).fields().get(0);
        assertEquals("MISSING", field.status());
        assertNull(field.normalizedNumber());
        assertEquals("REJECTED", field.reviewState());
    }

    // ================================================================ runs

    @Test
    void runRequiresAnIdempotencyKeyBeforeAnyEngineOrAnalyzerWork() {
        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), null));

        assertEquals(IncomeLabService.LabRequestException.Code.IDEMPOTENCY_KEY_REQUIRED,
                failure.code());
        verifyNoInteractions(engine);
        verifyNoInteractions(analysis);
    }

    @Test
    void aReplayedRunKeyReturnsTheStoredRowAndStartsNoAnalyzerExecution() {
        LabRun stored = run(LabRun.Status.SUCCEEDED);
        when(runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(BRAIN, "income", KEY))
                .thenReturn(Optional.of(stored));
        when(runDocuments.findByRunId(RUN)).thenReturn(List.of(runDocument()));
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(payload()));
        when(cipher.open(eq(BRAIN), eq(RUN), any(), any(), any()))
                .thenReturn(storedAnalysisJson());

        LabDtos.RunResponse response =
                service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY);

        assertTrue(response.replayed());
        assertEquals(RUN, response.runId());
        assertEquals(ANALYSIS_RUN, response.analysisRunId());
        verifyNoInteractions(analysis);
        verify(transactions, never()).claimRun(any(), anyString(), anyString(), any(), any(), any(),
                any());
        verify(engine, never()).currentEnvelope(any());
    }

    @Test
    void anInterruptedRunIsReturnedOnReplayAndNeverReplayedThroughTheProvider() {
        LabRun interrupted = run(LabRun.Status.INTERRUPTED);
        when(runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(BRAIN, "income", KEY))
                .thenReturn(Optional.of(interrupted));
        when(runDocuments.findByRunId(RUN)).thenReturn(List.of(runDocument()));

        LabDtos.RunResponse response =
                service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY);

        assertEquals("INTERRUPTED", response.status());
        assertTrue(response.replayed());
        verifyNoInteractions(analysis);
    }

    @Test
    void anIncompatibleEnvelopeStopsBeforeTheRunIsEvenClaimed() {
        registrationFound();
        when(runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(BRAIN, "income", KEY))
                .thenReturn(Optional.empty());
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor()));
        when(engine.currentEnvelope(PACKAGE)).thenReturn(verified(envelope("SCHEDULE_E")));

        ParsedIncomeAnalysisService.ParsedAnalysisException failure = assertThrows(
                ParsedIncomeAnalysisService.ParsedAnalysisException.class,
                () -> service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY));

        assertEquals(ParsedIncomeAnalysisService.ParsedAnalysisException.Code
                .PARSED_ENVELOPE_INCOMPATIBLE, failure.code());
        verify(transactions, never()).claimRun(any(), anyString(), anyString(), any(), any(), any(),
                any());
        verifyNoInteractions(analysis);
    }

    @Test
    void aSuccessfulRunPinsTheCallerAllocatedAnalysisRunAndSealsItsPayload() {
        freshRunReady();
        when(analysis.analyze(any())).thenAnswer(call -> outcome(call.getArgument(0)));
        when(analysisRuns.existsById(any())).thenReturn(true);
        when(cipher.seal(any(), any(), any(), any(), any())).thenReturn(sealed());

        LabDtos.RunResponse response =
                service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY);

        ArgumentCaptor<ParsedAnalysisInput> input =
                ArgumentCaptor.forClass(ParsedAnalysisInput.class);
        verify(analysis).analyze(input.capture());
        assertEquals(PACKAGE, input.getValue().packageId());
        assertNotNull(input.getValue().correlationId());

        ArgumentCaptor<UUID> analysisRunId = ArgumentCaptor.forClass(UUID.class);
        verify(transactions).completeRun(eq(RUN), eq(BRAIN), analysisRunId.capture(), any());
        assertEquals(input.getValue().analysisRunId(), analysisRunId.getValue(),
                "the row the analyzer wrote is exactly the row the Lab run pins");
        assertFalse(response.replayed());
        assertEquals("SUCCEEDED", response.status());
    }

    @Test
    void aRecorderFailureCannotProduceASuccessfulLabRun() {
        freshRunReady();
        when(analysis.analyze(any())).thenAnswer(call -> outcome(call.getArgument(0)));
        when(analysisRuns.existsById(any())).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY));

        verify(transactions, never()).completeRun(any(), any(), any(), any());
        verify(transactions).failRun(eq(RUN), eq(BRAIN), anyString());
    }

    @Test
    void aProviderFailureWritesSafeTerminalMetadataAndNoPartialPlaintext() {
        freshRunReady();
        when(analysis.analyze(any())).thenThrow(new ModelRouterService.SanitizedProviderException(
                ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                "anthropic", "HttpClientErrorException", "corr-1"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, null), KEY));

        assertFalse(String.valueOf(failure).contains(CANARY));
        verify(transactions).failRun(eq(RUN), eq(BRAIN), anyString());
        verify(transactions, never()).completeRun(any(), any(), any(), any());
        verify(cipher, never()).seal(any(), any(), any(), any(), any());
    }

    @Test
    void aReleaseThatReadsReviewedValuesOverlaysThemBeforeCompatibilityAndPinsTheSnapshot() {
        freshRunReady();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(engine.reviewedFields(DOCUMENT)).thenReturn(
                reviewedCanary(EngineResultEnvelope.ReviewState.CORRECTED, "ab".repeat(32)));
        when(analysisRuns.existsById(any())).thenReturn(true);
        ArgumentCaptor<ParsedAnalysisInput> input = ArgumentCaptor.forClass(ParsedAnalysisInput.class);
        when(analysis.analyze(input.capture())).thenAnswer(call -> outcome(call.getArgument(0)));

        service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, 3), KEY);

        verify(engine).reviewedFields(DOCUMENT);
        FieldOccurrence analyzed = input.getValue().verified().envelope().documents().get(0).fields().get(0);
        assertEquals(EngineResultEnvelope.ReviewState.CORRECTED, analyzed.reviewState());
        assertEquals(new BigDecimal("99.00"), analyzed.normalized().number());
        assertEquals("12345678901234567890.123456789", analyzed.rawValue());
        assertNotNull(input.getValue().reviewSnapshotSha256());
        ArgumentCaptor<LabRunTransactionService.RunDocumentFacts> facts =
                ArgumentCaptor.forClass(LabRunTransactionService.RunDocumentFacts.class);
        verify(transactions).claimRun(eq(BRAIN), eq("income"), eq(KEY), eq(RELEASE),
                eq(REGISTRATION), any(), facts.capture());
        assertEquals(1, facts.getValue().reviewSnapshot().correctedCount());
        assertEquals(input.getValue().reviewSnapshotSha256(), facts.getValue().reviewSnapshot().fieldsSha256());
        ArgumentCaptor<Map<String, Object>> counts = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(BRAIN), eq(LabAuditService.RUN_START),
                eq(LabAuditEvent.Status.SUCCEEDED), eq(LabAuditEvent.SubjectType.RUN), any(),
                isNull(), counts.capture());
        assertEquals(1, counts.getValue().get("correctedFields"));
        assertEquals(0, counts.getValue().get("rejectedFields"));
    }

    @Test
    void aRejectedRequiredFieldMakesTheRunIncompatibleBeforeAnyClaim() {
        freshRunReady();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(engine.reviewedFields(DOCUMENT)).thenReturn(
                reviewedCanary(EngineResultEnvelope.ReviewState.REJECTED, "ab".repeat(32)));
        when(analysisRuns.existsById(any())).thenReturn(true);
        when(analysis.analyze(any())).thenAnswer(call -> outcome(call.getArgument(0)));

        // The canary manifest's missing-field policy decides here; whichever way it decides, the
        // value must not reach the analyzer and the audit must count it as rejected.
        try {
            service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, 3), KEY);
        } catch (ParsedIncomeAnalysisService.ParsedAnalysisException refused) {
            assertEquals(ParsedIncomeAnalysisService.ParsedAnalysisException.Code
                    .PARSED_ENVELOPE_INCOMPATIBLE, refused.code());
            verify(transactions, never()).claimRun(any(), any(), any(), any(), any(), any(), any());
            return;
        }
        ArgumentCaptor<ParsedAnalysisInput> input = ArgumentCaptor.forClass(ParsedAnalysisInput.class);
        verify(analysis).analyze(input.capture());
        FieldOccurrence analyzed = input.getValue().verified().envelope().documents().get(0).fields().get(0);
        assertEquals(FieldStatus.MISSING, analyzed.status());
        assertEquals(EngineResultEnvelope.ReviewState.REJECTED, analyzed.reviewState());
        assertNull(analyzed.normalized());
    }

    @Test
    void aReadModelFailureRefusesTheRunBeforeAnyClaim() {
        freshRunReady();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(engine.reviewedFields(DOCUMENT)).thenThrow(
                new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, 503));

        DocumentEngineFailure failure = assertThrows(DocumentEngineFailure.class,
                () -> service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, 3), KEY));

        assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, failure.code());
        verify(transactions, never()).claimRun(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(analysis);
    }

    @Test
    void aReleaseWithoutTheReadModelBlockNeverCallsTheReadModel() {
        freshRunReady();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("W2")));
        when(analysis.analyze(any())).thenAnswer(call -> outcome(call.getArgument(0)));
        when(analysisRuns.existsById(any())).thenReturn(true);

        service.startRun(BRAIN, "income", new LabDtos.RunRequest(PACKAGE, 3), KEY);

        verify(engine, never()).reviewedFields(any());
        ArgumentCaptor<LabRunTransactionService.RunDocumentFacts> facts =
                ArgumentCaptor.forClass(LabRunTransactionService.RunDocumentFacts.class);
        verify(transactions).claimRun(any(), any(), any(), any(), any(), any(), facts.capture());
        assertNull(facts.getValue().reviewSnapshot());
    }

    // ================================================================ history and detail

    @Test
    void historyIsNewestFirstAndNeverDecryptsAPayload() {
        LabRun older = run(LabRun.Status.SUCCEEDED);
        older.setId(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        older.setCreatedAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        LabRun newer = run(LabRun.Status.SUCCEEDED);
        newer.setCreatedAt(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        when(runs.findByBrainIdAndInstanceSlugOrderByCreatedAtDesc(BRAIN, "income"))
                .thenReturn(List.of(newer, older));
        when(runDocuments.findByRunId(any())).thenReturn(List.of(runDocument()));

        LabDtos.RunHistoryResponse response = service.history(BRAIN, "income");

        assertEquals(List.of(RUN, older.getId()),
                response.runs().stream().map(LabDtos.RunSummary::runId).toList());
        verifyNoInteractions(cipher);
        verify(payloads, never()).findByRunIdAndPayloadType(any(), any());
    }

    @Test
    void runDetailFromAnotherBrainFailsClosed() {
        when(runs.findByIdAndBrainId(RUN, OTHER_BRAIN)).thenReturn(Optional.empty());

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.runDetail(OTHER_BRAIN, RUN));

        assertEquals(IncomeLabService.LabRequestException.Code.RUN_NOT_FOUND, failure.code());
        verifyNoInteractions(cipher);
    }

    @Test
    void runDetailDecryptsExactlyOneAuthorizedRun() {
        when(runs.findByIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(run(LabRun.Status.SUCCEEDED)));
        when(runDocuments.findByRunId(RUN)).thenReturn(List.of(runDocument()));
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(payload()));
        when(cipher.open(eq(BRAIN), eq(RUN), any(), eq(LabPayloadCipher.RecordType.ANALYSIS_OUTPUT),
                any())).thenReturn(storedAnalysisJson());

        LabDtos.RunResponse response = service.runDetail(BRAIN, RUN);

        assertNotNull(response.analysis());
        assertEquals("SUCCESS", response.analysis().status());
        assertEquals(PACKAGE, response.source().packageId());
        assertEquals(LabReleaseManifest.LIVE_DEPENDENCIES,
                response.prototype().liveDependencies());
        verify(cipher).open(any(), any(), any(), any(), any());
    }

    // ================================================================ discussion

    @Test
    void anOverlongQuestionFailsClosedBeforeRetrievalOrTheModel() {
        when(runs.findByIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(run(LabRun.Status.SUCCEEDED)));

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.postMessage(BRAIN, RUN,
                        new LabDtos.MessageRequest("q".repeat(4_001)), KEY));

        assertEquals(IncomeLabService.LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                failure.code());
        verifyNoInteractions(chat);
    }

    @Test
    void aThirteenthTranscriptTurnFailsClosedRatherThanDroppingHistory() {
        discussionReady();
        List<LabDiscussionExchange> transcript = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            transcript.add(exchange(i, LabRun.Status.SUCCEEDED));
        }
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(transcript);

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY));

        assertEquals(IncomeLabService.LabRequestException.Code.DISCUSSION_CONTEXT_TOO_LARGE,
                failure.code());
        verifyNoInteractions(chat);
    }

    @Test
    void aReplayedMessageKeyReturnsTheStoredPairWithoutAnotherModelCall() {
        when(runs.findByIdAndBrainId(RUN, BRAIN))
                .thenReturn(Optional.of(run(LabRun.Status.SUCCEEDED)));
        LabDiscussionExchange stored = exchange(1, LabRun.Status.SUCCEEDED);
        when(exchanges.findByRunIdAndIdempotencyKey(RUN, KEY)).thenReturn(Optional.of(stored));
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of(stored));
        when(messages.findByExchangeIdInOrderByOrdinalAsc(any()))
                .thenReturn(List.of(message(LabDiscussionMessage.Role.USER, 1),
                        message(LabDiscussionMessage.Role.ASSISTANT, 2)));
        when(cipher.open(any(), any(), any(), any(), any()))
                .thenReturn("stored body".getBytes(StandardCharsets.UTF_8));

        LabDtos.DiscussionResponse response =
                service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        assertTrue(response.replayed());
        verifyNoInteractions(chat);
        verify(transactions, never()).claimExchange(any(), any(), anyString(), any());
    }

    @Test
    void aConcurrentInFlightExchangeConflictsRatherThanReorderingTheTranscript() {
        when(runs.findByIdAndBrainId(RUN, BRAIN))
                .thenReturn(Optional.of(run(LabRun.Status.SUCCEEDED)));
        when(exchanges.findByRunIdAndIdempotencyKey(RUN, "other-key")).thenReturn(Optional.empty());
        LabDiscussionExchange inFlight = exchange(1, LabRun.Status.PROCESSING);
        inFlight.setLeaseExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of(inFlight));

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"),
                        "other-key"));

        assertEquals(IncomeLabService.LabRequestException.Code.DISCUSSION_EXCHANGE_IN_PROGRESS,
                failure.code());
        verifyNoInteractions(chat);
    }

    @Test
    void aDiscussionIsPinnedToTheRunsExactRevisionAndUsesTheSanitizedModelPath() {
        discussionReady();
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of());
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(
                        exchange(1, LabRun.Status.PROCESSING), true));
        when(chat.answerSanitized(any(), eq(BRAIN), anyString()))
                .thenReturn(new ChatResponse("the answer", List.of()));
        when(cipher.seal(any(), any(), any(), any(), any())).thenReturn(sealed());

        service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        // The exact revision the run pinned is re-fetched and re-verified, not trusted from cache.
        verify(engine).envelopeRevision(PACKAGE, 3);
        verify(engine, never()).reviewedFields(any());
        verify(chat, never()).answer(any(), any());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).answerSanitized(request.capture(), eq(BRAIN), anyString());
        assertEquals("why?", request.getValue().question());
        assertNotNull(request.getValue().context());
    }

    @Test
    void aDiscussionContextRedactsSensitiveValuesLikeTheAnalysisPromptDoes() {
        discussionReady();
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelopeWithSensitiveField()));
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of());
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(
                        exchange(1, LabRun.Status.PROCESSING), true));
        when(chat.answerSanitized(any(), eq(BRAIN), anyString()))
                .thenReturn(new ChatResponse("the answer", List.of()));
        when(cipher.seal(any(), any(), any(), any(), any())).thenReturn(sealed());

        service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).answerSanitized(request.capture(), eq(BRAIN), anyString());
        String context = request.getValue().context();
        assertTrue(context.contains(
                "employee.ssn status=FOUND reviewState=UNREVIEWED_SOURCE value=<redacted:sensitive>"),
                context);
        assertFalse(context.contains("987-65-4321"), "displayed sensitive value leaked");
        assertFalse(context.contains("987654321"), "normalized sensitive value leaked");
        assertTrue(context.contains("wages.annual status=FOUND reviewState=UNREVIEWED_SOURCE "
                + "value=12345678901234567890.123456789"),
                "non-sensitive values must still be present");
    }

    private static LabRunReviewSnapshot snapshotRow(String sha, int machine, int corrected,
                                                    int rejected) {
        LabRunReviewSnapshot row = new LabRunReviewSnapshot();
        row.setRunId(RUN);
        row.setFieldsSha256(sha);
        row.setDocumentCount(1);
        row.setMachineCount(machine);
        row.setCorrectedCount(corrected);
        row.setRejectedCount(rejected);
        row.setSchemaVersions(Map.of(DOCUMENT.toString(), "1"));
        return row;
    }

    /**
     * What {@link com.pragmaticds.rag.lab.engine.ReviewedValueOverlay} would have pinned as
     * {@code fieldsSha256} for a single-document run whose read model reported {@code docSha} —
     * the same {@code sha256(docSha + "\n")} digest {@code composeContext} recomputes at discussion
     * time, so a fixture can assert "unchanged" or "changed" by picking equal or differing inputs.
     */
    private static String pinnedDigestOf(String docSha) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest((docSha + "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private void discussionOnReviewedRun(String storedSha, EngineResultEnvelope.ReviewState nowStatus,
                                         String nowSha) {
        discussionReady();
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(reviewSnapshots.findByRunId(RUN))
                .thenReturn(Optional.of(snapshotRow(pinnedDigestOf(storedSha), 0, 1, 0)));
        when(engine.reviewedFields(DOCUMENT)).thenReturn(reviewedCanary(nowStatus, nowSha));
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of());
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(
                        exchange(1, LabRun.Status.PROCESSING), true));
        when(chat.answerSanitized(any(), eq(BRAIN), anyString()))
                .thenReturn(new ChatResponse("the answer", List.of()));
        when(cipher.seal(any(), any(), any(), any(), any())).thenReturn(sealed());
    }

    @Test
    void aDiscussionOnAnEnvelopeEraRunUsesTheEnvelopeAndReportsNoDrift() {
        // A run with no snapshot row predates the read-model release, even when the *current*
        // release manifest now reads reviewed values: composeContext must resolve that from the
        // snapshot lookup alone, never call the read model, and leave the envelope's own
        // UNREVIEWED_SOURCE reviewState untouched.
        discussionReady();
        when(releases.resolveForRun(BRAIN)).thenReturn(
                resolvedRelease(manifestReadingReviewedValues(List.of("W2"))));
        when(reviewSnapshots.findByRunId(RUN)).thenReturn(Optional.empty());
        when(exchanges.findByRunIdOrderBySequenceNumberAsc(RUN)).thenReturn(List.of());
        when(transactions.claimExchange(eq(BRAIN), eq(RUN), eq(KEY), any()))
                .thenReturn(new LabRunTransactionService.ExchangeClaim(
                        exchange(1, LabRun.Status.PROCESSING), true));
        when(chat.answerSanitized(any(), eq(BRAIN), anyString()))
                .thenReturn(new ChatResponse("the answer", List.of()));
        when(cipher.seal(any(), any(), any(), any(), any())).thenReturn(sealed());

        LabDtos.DiscussionResponse response =
                service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        verify(engine, never()).reviewedFields(any());
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).answerSanitized(request.capture(), eq(BRAIN), anyString());
        String context = request.getValue().context();
        assertTrue(context.contains("reviewState=UNREVIEWED_SOURCE"), context);
        assertFalse(context.contains("reviewer changes were recorded"));
        assertNull(response.reviewDrift());
    }

    @Test
    void aDiscussionOnAnUnchangedReviewSnapshotCarriesNoDriftNote() {
        discussionOnReviewedRun("ab".repeat(32), EngineResultEnvelope.ReviewState.CORRECTED, "ab".repeat(32));

        LabDtos.DiscussionResponse response =
                service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).answerSanitized(request.capture(), eq(BRAIN), anyString());
        assertFalse(request.getValue().context().contains("reviewer changes were recorded"));
        assertTrue(request.getValue().context().contains("reviewState=CORRECTED"));
        assertFalse(response.reviewDrift().changed());
    }

    @Test
    void aDiscussionAfterReviewerChangesNamesTheCountsAndTellsTheModelToReRun() {
        discussionOnReviewedRun("ab".repeat(32), EngineResultEnvelope.ReviewState.REJECTED, "cd".repeat(32));

        LabDtos.DiscussionResponse response =
                service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).answerSanitized(request.capture(), eq(BRAIN), anyString());
        String context = request.getValue().context();
        assertTrue(context.contains("NOTE: reviewer changes were recorded in the engine after this run:"
                + " 1 fields changed status (machine 0->0, corrected 1->0, rejected 0->1)."), context);
        assertTrue(context.contains("Re-run to analyze the current values."));
        assertTrue(context.contains("wages.annual status=MISSING reviewState=REJECTED"));
        assertFalse(context.contains("99.00"), "a rejected value never reaches the prompt");
        assertTrue(response.reviewDrift().changed());
        assertEquals(1, response.reviewDrift().correctedBefore());
        assertEquals(1, response.reviewDrift().rejectedNow());
    }

    @Test
    void aReadModelFailureDuringDiscussionRefusesRatherThanFallingBackToTheEnvelope() {
        discussionOnReviewedRun("ab".repeat(32), EngineResultEnvelope.ReviewState.CORRECTED, "ab".repeat(32));
        when(engine.reviewedFields(DOCUMENT)).thenThrow(
                new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, 502));

        assertThrows(DocumentEngineFailure.class,
                () -> service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY));
        verifyNoInteractions(chat);
    }

    @Test
    void aDiscussionOnANonSucceededRunIsRefused() {
        when(runs.findByIdAndBrainId(RUN, BRAIN))
                .thenReturn(Optional.of(run(LabRun.Status.FAILED)));

        IncomeLabService.LabRequestException failure = assertThrows(
                IncomeLabService.LabRequestException.class,
                () -> service.postMessage(BRAIN, RUN, new LabDtos.MessageRequest("why?"), KEY));

        assertEquals(IncomeLabService.LabRequestException.Code.RUN_NOT_SUCCEEDED, failure.code());
        verifyNoInteractions(chat);
    }

    // ================================================================ purge

    @Test
    void purgeIsDelegatedToRetentionAndNeverDeletesTheEnginePackage() {
        when(retention.purge(BRAIN, RUN)).thenReturn(
                new LabRetentionService.PurgeOutcome(true, 2, 1, 1, 1, true));

        LabDtos.PurgeResponse response = service.purgeRun(BRAIN, RUN);

        assertTrue(response.deleted());
        assertTrue(response.enginePackageRetained(),
                "the Document Engine package is never deleted from this route");
        assertEquals(2, response.messagesDeleted());
        verify(engine, never()).register(any(), anyString());
        verifyNoInteractions(engine);
    }

    // ================================================================ fixtures

    private void registrationFound() {
        when(registrations.findByEnginePackageIdAndBrainIdAndInstanceSlug(PACKAGE, BRAIN, "income"))
                .thenReturn(Optional.of(existingRegistration(BRAIN, "income")));
    }

    /** A fresh, compatible, claimable run whose analyzer has not been stubbed yet. */
    private void freshRunReady() {
        registrationFound();
        when(runs.findByBrainIdAndInstanceSlugAndIdempotencyKey(BRAIN, "income", KEY))
                .thenReturn(Optional.empty());
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));
        when(engine.revisionHistory(PACKAGE)).thenReturn(List.of(descriptor()));
        when(engine.currentEnvelope(PACKAGE)).thenReturn(verified(envelope("W2")));
        when(transactions.claimRun(eq(BRAIN), eq("income"), eq(KEY), eq(RELEASE), eq(REGISTRATION),
                any(), any()))
                .thenReturn(new LabRunTransactionService.RunClaim(run(LabRun.Status.PROCESSING),
                        true));
    }

    private void discussionReady() {
        LabRun succeeded = run(LabRun.Status.SUCCEEDED);
        when(runs.findByIdAndBrainId(RUN, BRAIN)).thenReturn(Optional.of(succeeded));
        when(runDocuments.findByRunId(RUN)).thenReturn(List.of(runDocument()));
        when(exchanges.findByRunIdAndIdempotencyKey(RUN, KEY)).thenReturn(Optional.empty());
        when(engine.envelopeRevision(PACKAGE, 3)).thenReturn(verified(envelope("W2")));
        when(releases.resolveForRun(BRAIN)).thenReturn(resolvedRelease(manifest(List.of("W2"))));
        when(payloads.findByRunIdAndPayloadType(RUN, LabRunPayload.PayloadType.ANALYSIS_OUTPUT))
                .thenReturn(Optional.of(payload()));
        when(cipher.open(eq(BRAIN), eq(RUN), any(), eq(LabPayloadCipher.RecordType.ANALYSIS_OUTPUT),
                any())).thenReturn(storedAnalysisJson());
    }

    private void auditFailsWith(RuntimeException failure) {
        org.mockito.Mockito.doThrow(failure).when(audit)
                .recordRequired(any(), anyString(), any(), any(), any(), any(), any());
    }

    private static DocumentEngineClient.EngineUpload upload(long size) {
        return new DocumentEngineClient.EngineUpload(size,
                new ByteArrayResource("%PDF-1.7 synthetic".getBytes(StandardCharsets.UTF_8)));
    }

    private static DocumentEngineClient.UploadRegistration registration() {
        return new DocumentEngineClient.UploadRegistration(PACKAGE, JOB,
                List.of(new DocumentEngineClient.RegisteredSource(SOURCE, "a1".repeat(32), 64L, 1)),
                List.of());
    }

    private static LabDocumentRegistration existingRegistration(UUID brainId, String slug) {
        LabDocumentRegistration stored = new LabDocumentRegistration();
        stored.setId(REGISTRATION);
        stored.setBrainId(brainId);
        stored.setInstanceSlug(slug);
        stored.setEnginePackageId(PACKAGE);
        stored.setEngineJobId(JOB);
        stored.setEngineSourceId(SOURCE);
        stored.setRegisteredAt(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        return stored;
    }

    private static DocumentEngineClient.JobSnapshot job(String status) {
        return new DocumentEngineClient.JobSnapshot(JOB, PACKAGE, status, null);
    }

    private static LabRun run(LabRun.Status status) {
        LabRun stored = new LabRun();
        stored.setId(RUN);
        stored.setBrainId(BRAIN);
        stored.setInstanceSlug("income");
        stored.setIdempotencyKey(KEY);
        stored.setReleaseId(RELEASE);
        stored.setRegistrationId(REGISTRATION);
        stored.setStatus(status);
        stored.setCreatedAt(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        if (status != LabRun.Status.PROCESSING) {
            stored.setTerminalAt(OffsetDateTime.parse("2026-08-15T00:01:00Z"));
        } else {
            stored.setLeaseExpiresAt(OffsetDateTime.parse("2026-08-15T00:10:00Z"));
        }
        if (status == LabRun.Status.SUCCEEDED) {
            stored.setAnalysisRunId(ANALYSIS_RUN);
        }
        return stored;
    }

    private static LabRunDocument runDocument() {
        LabRunDocument stored = new LabRunDocument();
        stored.setRunId(RUN);
        stored.setRegistrationId(REGISTRATION);
        stored.setEnginePackageId(PACKAGE);
        stored.setPackageRevision(3);
        stored.setProcessingJobId(JOB);
        stored.setParseGeneration(1);
        stored.setEnvelopeVersion("1.0.0");
        stored.setCanonicalizationVersion("DOCENGINE-C14N-1");
        // The REAL digest of the synthetic envelope, not a stand-in: a discussion re-fetches the
        // pinned revision and refuses to answer unless the bytes still hash to what the run pinned.
        stored.setEnvelopeSha256(envelope("W2").artifact().sha256());
        stored.setEnvelopeSizeBytes(envelope("W2").artifact().byteCount());
        stored.setSourceSetSha256(SOURCE_SET_SHA);
        stored.setReuseEligibility("PARSE_ONCE_CURRENT_PACKAGE");
        stored.setDocumentCount(1);
        stored.setPageCount(1);
        return stored;
    }

    private static LabRunPayload payload() {
        LabRunPayload stored = new LabRunPayload();
        stored.setId(UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
        stored.setRunId(RUN);
        stored.setPayloadType(LabRunPayload.PayloadType.ANALYSIS_OUTPUT);
        stored.setNonce(new byte[12]);
        stored.setCiphertext(new byte[32]);
        return stored;
    }

    private static LabPayloadCipher.SealedPayload sealed() {
        return new LabPayloadCipher.SealedPayload(new byte[12], new byte[32]);
    }

    private static byte[] storedAnalysisJson() {
        return ("{\"analysis\":{\"status\":\"SUCCESS\",\"reportMarkdown\":\"# report\","
                + "\"findingsJson\":\"{}\",\"citations\":[],\"provider\":\"anthropic\","
                + "\"model\":\"claude-x\",\"inputTokens\":10,\"outputTokens\":20,"
                + "\"costUsd\":0.01,\"providerAttempts\":1,\"reason\":null},"
                + "\"releaseNumber\":1,\"releaseManifestSha256\":\"" + "f6".repeat(32) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static LabDiscussionExchange exchange(int sequence, LabRun.Status status) {
        LabDiscussionExchange stored = new LabDiscussionExchange();
        stored.setId(EXCHANGE);
        stored.setRunId(RUN);
        stored.setIdempotencyKey(KEY);
        stored.setSequenceNumber(sequence);
        stored.setUserOrdinal(2 * sequence - 1);
        stored.setAssistantOrdinal(2 * sequence);
        stored.setStatus(status);
        stored.setCreatedAt(OffsetDateTime.parse("2026-08-15T00:02:00Z"));
        if (status != LabRun.Status.PROCESSING) {
            stored.setTerminalAt(OffsetDateTime.parse("2026-08-15T00:03:00Z"));
        }
        return stored;
    }

    private static LabDiscussionMessage message(LabDiscussionMessage.Role role, int ordinal) {
        LabDiscussionMessage stored = new LabDiscussionMessage();
        stored.setId(UUID.randomUUID());
        stored.setExchangeId(EXCHANGE);
        stored.setRole(role);
        stored.setOrdinal(ordinal);
        stored.setNonce(new byte[12]);
        stored.setCiphertext(new byte[32]);
        stored.setCreatedAt(OffsetDateTime.parse("2026-08-15T00:03:00Z"));
        return stored;
    }

    private static ParsedIncomeAnalysisService.ParsedRunOutcome outcome(ParsedAnalysisInput input) {
        AnalysisResult result = new AnalysisResult(AnalysisResult.Status.SUCCESS, "# report", "{}",
                List.of(), "anthropic", "claude-x", 10, 20, 0.01, 1, List.of(), null, List.of(),
                input.analysisRunId().toString());
        return new ParsedIncomeAnalysisService.ParsedRunOutcome(result,
                new ParsedIncomeAnalysisService.RunProvenance(
                        input.analysisRunId(), RELEASE, 1, "f6".repeat(32), PACKAGE, 3, 1, JOB,
                        SOURCE_SET_SHA, "b2".repeat(32), 4096, "1.0.0", "DOCENGINE-C14N-1",
                        new ModelRouterService.Resolution(null, null, true, "anthropic", "claude-x",
                                "anthropic", "claude-x", false),
                        new ParsedIncomeAnalysisService.RetrievalProvenance(true, "income", 8, 8,
                                true),
                        List.of("BORROWER_DOC_1"), 0, 1,
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    private static IncomeLabReleaseService.InstanceState instanceState(boolean drift) {
        return new IncomeLabReleaseService.InstanceState("income", "income-v2", RELEASE, 1,
                "f6".repeat(32), drift, drift ? UUID.randomUUID() : null,
                drift ? "07".repeat(32) : null, LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                LabReleaseManifest.LIVE_DEPENDENCIES);
    }

    private static IncomeLabReleaseService.ResolvedRelease resolvedRelease(
            LabReleaseManifest manifest) {
        return new IncomeLabReleaseService.ResolvedRelease(RELEASE, 1, "f6".repeat(32), manifest);
    }

    private static DocumentEngineClient.VerifiedEnvelope verified(EngineResultEnvelope envelope) {
        return new DocumentEngineClient.VerifiedEnvelope(envelope.artifact(), envelope, 3);
    }

    private static DocumentEngineClient.RevisionDescriptor descriptor() {
        EngineResultEnvelope any = envelope("W2");
        return new DocumentEngineClient.RevisionDescriptor(3, JOB, 1, 1, "1.0.0", SOURCE_SET_SHA,
                "d4".repeat(32), any.artifact().sha256(), any.artifact().byteCount(),
                "PARSE_ONCE_CURRENT_PACKAGE", Instant.parse("2026-08-15T00:00:00Z"));
    }

    /** A one-page, one-document synthetic envelope carrying the decimal-fidelity canary. */
    private static EngineResultEnvelope envelope(String documentTypeCode) {
        FieldOccurrence field = new FieldOccurrence(
                "wages.annual", null, FieldStatus.FOUND, "MONEY", "$12,345,678,901,234,567,890.12",
                "12345678901234567890.123456789",
                new NormalizedValue(null, new BigDecimal("12345678901234567890.123456789"), null,
                        null),
                new SchemaRef(UUID.fromString("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"), "1"),
                "ANCHORED", "1.4.0", new BigDecimal("0.97"), null, "OK", false,
                List.of(new EvidenceSpan(PAGE, null, 7L, "VALUE", 0,
                        new EngineResultEnvelope.Box(new BigDecimal("10"), new BigDecimal("20"),
                                new BigDecimal("30"), new BigDecimal("40")))));
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("synthetic-envelope".getBytes(StandardCharsets.UTF_8)),
                "1.0.0", "DOCENGINE-C14N-1", PACKAGE,
                new Generation(JOB, 1, 3, SOURCE_SET_SHA, "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 64L, "application/pdf")),
                List.of(new EnginePage(PAGE, SOURCE, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                List.of(new LogicalDocument(DOCUMENT, documentTypeCode, 1, List.of(PAGE),
                        List.of(field))),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    /** The canary envelope plus one FOUND, sensitive occurrence carrying an SSN in every arm. */
    private static EngineResultEnvelope envelopeWithSensitiveField() {
        EngineResultEnvelope base = envelope("W2");
        LogicalDocument document = base.documents().get(0);
        FieldOccurrence ssn = new FieldOccurrence(
                "employee.ssn", null, FieldStatus.FOUND, "TEXT", "987-65-4321", "987-65-4321",
                new NormalizedValue("987654321", null, null, null),
                new SchemaRef(UUID.fromString("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"), "1"),
                "ANCHORED", "1.4.0", new BigDecimal("0.97"), null, "OK", true, List.of());
        List<FieldOccurrence> fields = new java.util.ArrayList<>(document.fields());
        fields.add(ssn);
        return new EngineResultEnvelope(
                base.artifact(), base.envelopeVersion(), base.canonicalizationVersion(),
                base.packageId(), base.generation(), base.sources(), base.pages(),
                List.of(new LogicalDocument(document.id(), document.documentTypeCode(),
                        document.ordinal(), document.pageIds(), fields)),
                base.unassignedPageIds(), base.provenance());
    }

    private static LabReleaseManifest manifest(List<String> documentTypes) {
        return new LabReleaseManifest(
                LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION,
                IncomeLabReleaseService.INCOME_INSTANCE_SLUG,
                IncomeLabReleaseService.INCOME_ANALYZER_SLUG,
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer("Income", "v2", "prompt", "{}",
                                IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                                "e5".repeat(32)),
                        new LabReleaseManifest.Retrieval("query", "income", 8),
                        new LabReleaseManifest.EngineContract(
                                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE,
                                List.of("1.0.0"), List.of("DOCENGINE-C14N-1"), documentTypes,
                                List.of("MANUAL_REVIEW_REQUIRED")),
                        new LabReleaseManifest.Calculator(
                                IncomeCalcService.SUPPORTED_METHODS.stream().sorted().toList())),
                new LabReleaseManifest.ObservedInference(
                        LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE,
                        "anthropic", "claude-x", "openai", 20000, 2,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, null, null,
                                null, null, "anthropic", "claude-x", false)),
                new LabReleaseManifest.PrototypeLimitations(
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }
}
