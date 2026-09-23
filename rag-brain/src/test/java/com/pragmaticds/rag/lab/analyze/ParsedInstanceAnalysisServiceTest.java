package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.RegistrationSubjectScopeService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabRegistrationLoanFactsRepository;
import com.pragmaticds.rag.lab.repository.LabRegistrationSubjectScopeRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The refusal ordering of one pinned run.
 *
 * <p>Every test here is about what does <em>not</em> happen. A pinned release is a promise that a
 * run either executed exactly what the release named or did not execute at all, and the only way
 * to keep that promise is for each layer to refuse before the next one spends anything. Retrieval,
 * tools, and the provider are the expensive, externally-visible ones; the assertions that matter
 * are the {@code verifyNoInteractions} calls, not the exception codes.
 */
class ParsedInstanceAnalysisServiceTest {
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RUN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SNAPSHOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RELEASE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PACKAGE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REGISTRATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID JOB = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SOURCE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID PAGE = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String INSTANCE = "income";
    private static final String SCHEMA_SHA = "c".repeat(64);
    private static final String QUERY = "income guidelines";

    private RetrievalService retrieval;
    private ParsedDocumentPromptRenderer renderer;
    private InstanceToolRegistry tools;
    private InstanceOutputSchemaRegistry schemas;
    private AnalysisService analysisService;
    private RegistrationLoanFactsService loanFacts;
    private ParsedInstanceAnalysisService service;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        renderer = mock(ParsedDocumentPromptRenderer.class);
        tools = mock(InstanceToolRegistry.class);
        schemas = mock(InstanceOutputSchemaRegistry.class);
        analysisService = mock(AnalysisService.class);
        loanFacts = mock(RegistrationLoanFactsService.class);
        service = new ParsedInstanceAnalysisService(
                retrieval, renderer, tools, schemas, analysisService, loanFacts,
                mock(RegistrationSubjectScopeService.class));
    }

    @Test
    void anIncompatibleParseCostsNothingAtAll() {
        ParsedDataCompatibilityService.CompatibilityDecision rejected =
                new ParsedDataCompatibilityService.CompatibilityDecision(false,
                        ParsedDataCompatibilityService.RejectionCode.REQUIRED_DOCUMENT_TYPE_MISSING,
                        List.of(), 0);

        ParsedInstanceAnalysisService.InstanceAnalysisException refused = assertThrows(
                ParsedInstanceAnalysisService.InstanceAnalysisException.class,
                () -> service.analyze(command(release(QUERY), rejected)));

        assertEquals(ParsedInstanceAnalysisService.InstanceAnalysisException.Code.PARSE_INCOMPATIBLE,
                refused.code());
        // A rejection is a refusal, not a warning. Nothing downstream may observe it — not the
        // schema allowlist, not retrieval, not a tool, and above all not a billed token.
        verifyNoInteractions(schemas, retrieval, renderer, tools, analysisService);
    }

    @Test
    void aReleaseThatPredatesV2CannotDescribeAPinnedRunAndExecutesNothing() {
        ResolvedInstanceRelease legacy = new ResolvedInstanceRelease(
                mock(LabInstance.class), storedRelease(),
                new DecodedInstanceManifest.V1Income(null), true);

        ParsedInstanceAnalysisService.InstanceAnalysisException refused = assertThrows(
                ParsedInstanceAnalysisService.InstanceAnalysisException.class,
                () -> service.analyze(command(legacy, compatible())));

        assertEquals(ParsedInstanceAnalysisService.InstanceAnalysisException.Code
                .INSTANCE_RELEASE_NOT_PINNABLE, refused.code());
        verifyNoInteractions(schemas, retrieval, renderer, tools, analysisService);
    }

    @Test
    void aSchemaThisBuildDoesNotShipStopsBeforeRetrievalAndBeforeAnyProvider() {
        when(schemas.requireText(anyString(), anyString())).thenThrow(
                new InstanceOutputSchemaRegistry.OutputSchemaException(
                        InstanceOutputSchemaRegistry.OutputSchemaException.Code
                                .OUTPUT_SCHEMA_NOT_ALLOWLISTED));

        assertThrows(InstanceOutputSchemaRegistry.OutputSchemaException.class,
                () -> service.analyze(command(release(QUERY), compatible())));

        // Resolving the schema IS the allowlist and digest check, so a release naming a schema
        // this build cannot serve must never reach a provider that would answer against it.
        verifyNoInteractions(retrieval, renderer, tools, analysisService);
    }

    @Test
    void aReleaseThatDeclaresRetrievalAndCannotRetrieveNeverReachesAToolOrAModel() {
        when(schemas.requireText(anyString(), eq(SCHEMA_SHA))).thenReturn("{}");
        when(retrieval.retrieveSnapshot(eq(QUERY), eq(BRAIN), eq(SNAPSHOT), any(), anyInt(),
                anyBoolean())).thenThrow(new IllegalStateException("snapshot unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.analyze(command(release(QUERY), compatible())));

        // An ungrounded answer recorded against a release that promised grounding is worse than
        // no answer, so the retrieval failure propagates rather than degrading to zero chunks.
        verifyNoInteractions(tools, analysisService);
    }

    @Test
    void aReleaseWithNoRetrievalQueryGroundsOnParsedFactsWithoutTouchingRetrieval() {
        happyPath();

        ParsedInstanceAnalysisService.InstanceRunOutcome outcome =
                service.analyze(command(release("   "), compatible()));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status());
        // Grounding on parsed facts alone is a legitimate release shape, not a degraded one.
        verifyNoInteractions(retrieval);
        assertTrue(outcome.provenance().retrieved().isEmpty(),
                "a release that retrieves nothing must seal no evidence it did not use");
    }

    @Test
    void theHappyPathRunsInTheDeclaredOrderAndSealsWhatProducedTheAnswer() {
        happyPath();
        RetrievedChunk chunk = chunk();
        when(retrieval.retrieveSnapshot(eq(QUERY), eq(BRAIN), eq(SNAPSHOT), any(), anyInt(),
                anyBoolean())).thenReturn(new RetrievalResult(List.of(chunk), 0.9, true));

        ParsedInstanceAnalysisService.InstanceRunOutcome outcome =
                service.analyze(command(release(QUERY), compatible()));

        InOrder order = inOrder(schemas, retrieval, renderer, tools, analysisService);
        order.verify(schemas).requireText(anyString(), eq(SCHEMA_SHA));
        order.verify(retrieval).retrieveSnapshot(eq(QUERY), eq(BRAIN), eq(SNAPSHOT), any(),
                anyInt(), anyBoolean());
        order.verify(renderer).render(any(EngineResultEnvelope.class),
                any(ParsedDataCompatibilityService.CompatibilityDecision.class));
        order.verify(tools).executePinned(any(), any());
        order.verify(analysisService).analyzePinned(eq(BRAIN), any(), any());

        // The provider is handed the exact chunks retrieval returned — it never fetches its own.
        ArgumentCaptor<AnalysisService.PinnedContract> pinned =
                ArgumentCaptor.forClass(AnalysisService.PinnedContract.class);
        org.mockito.Mockito.verify(analysisService)
                .analyzePinned(eq(BRAIN), any(), pinned.capture());
        assertEquals(List.of(chunk), pinned.getValue().chunks());
        assertEquals("anthropic", pinned.getValue().provider());
        assertEquals("claude-x", pinned.getValue().model());
        assertEquals(SNAPSHOT, pinned.getValue().corpusSnapshotId());

        // The release's standing direction AND this run's task, in that order. Sending only the
        // task prompt silently dropped systemPrompt from every run while the cost estimate and
        // the request digest both counted it.
        assertEquals("system\n\ntask", pinned.getValue().basePrompt());
        // And the release's declared temperature, not the lane default it never chose.
        assertEquals(new BigDecimal("0.250"), pinned.getValue().temperature());

        // Provenance must let someone reconstruct the answer, which means the evidence itself.
        assertEquals(1, outcome.provenance().retrieved().size());
        assertEquals(chunk.content(),
                outcome.provenance().retrieved().getFirst().content());
        assertEquals(RELEASE, outcome.provenance().releaseId());
        assertEquals(SNAPSHOT, outcome.provenance().corpusSnapshotId());
    }

    @Test
    void aFixtureParseWithNoRegistrationRunsWithNoScopeAndNoLoanFacts() {
        // An evaluation analyzes a committed fixture, which came from no registration. The real
        // lookup services refuse a null registration id, so the run must not ask them.
        service = new ParsedInstanceAnalysisService(
                retrieval, renderer, tools, schemas, analysisService,
                new RegistrationLoanFactsService(
                        mock(LabRegistrationLoanFactsRepository.class), mock(LabPayloadCipher.class)),
                new RegistrationSubjectScopeService(
                        mock(LabRegistrationSubjectScopeRepository.class)));
        happyPath();

        ParsedInstanceAnalysisService.InstanceRunOutcome outcome = service.analyze(
                new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                        BRAIN, INSTANCE, RUN, release("   "), parsedInput(compatible(), null),
                        snapshot(), 8, false, "eval-1"));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status());
    }

    // ================================================================ fixtures

    private void happyPath() {
        when(schemas.requireText(anyString(), eq(SCHEMA_SHA))).thenReturn("{}");
        when(renderer.render(any(EngineResultEnvelope.class),
                any(ParsedDataCompatibilityService.CompatibilityDecision.class)))
                .thenReturn(new ParsedDocumentPromptRenderer.Rendered("FACTS", List.of()));
        when(tools.executePinned(any(), any())).thenReturn(List.of());
        when(analysisService.analyzePinned(eq(BRAIN), any(), any())).thenReturn(
                new AnalysisService.ParsedOutcome(
                        new AnalysisResult(AnalysisResult.Status.SUCCESS, "r", "{}", List.of(),
                                "anthropic", "claude-x", 1, 1, 0.0, 0, List.of(), null),
                        new ModelRouterService.Resolution("anthropic", "claude-x", true,
                                "anthropic", "claude-x", "anthropic", "claude-x", false),
                        1));
    }

    private static ParsedInstanceAnalysisService.InstanceAnalysisCommand command(
            ResolvedInstanceRelease release,
            ParsedDataCompatibilityService.CompatibilityDecision decision) {
        return new ParsedInstanceAnalysisService.InstanceAnalysisCommand(
                BRAIN, INSTANCE, RUN, release, parsedInput(decision), snapshot(), 8, false,
                "correlation-1");
    }

    private static ParsedDataCompatibilityService.CompatibilityDecision compatible() {
        return new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1);
    }

    private static RetrievedChunk chunk() {
        return new RetrievedChunk(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                "Qualifying income is averaged over 24 months.", "Guide", "GUIDELINE",
                "Selling Guide", "Selling Guide", "B3-3.1", 12, null, 0.9, 0.9, 0.9);
    }

    private static CorpusSnapshotService.FrozenCorpusSnapshot snapshot() {
        return new CorpusSnapshotService.FrozenCorpusSnapshot(
                SNAPSHOT, BRAIN, "e".repeat(64), List.of(), List.of());
    }

    private static ParsedDataResolver.VerifiedParsedInput parsedInput(
            ParsedDataCompatibilityService.CompatibilityDecision decision) {
        return parsedInput(decision, REGISTRATION);
    }

    private static ParsedDataResolver.VerifiedParsedInput parsedInput(
            ParsedDataCompatibilityService.CompatibilityDecision decision, UUID registration) {
        EngineResultEnvelope envelope = envelope();
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8));
        return new ParsedDataResolver.VerifiedParsedInput(registration, PACKAGE, 3, JOB, 1,
                "1.0.0", "DOCENGINE-C14N-1", artifact.sha256(), artifact.byteCount(),
                "ab".repeat(32), List.of(SOURCE), envelope, envelope, decision);
    }

    private static ResolvedInstanceRelease release(String retrievalQuery) {
        return new ResolvedInstanceRelease(mock(LabInstance.class), storedRelease(),
                new DecodedInstanceManifest.V2(manifest(retrievalQuery)), true);
    }

    private static LabInstanceRelease storedRelease() {
        LabInstanceRelease stored = mock(LabInstanceRelease.class);
        when(stored.getId()).thenReturn(RELEASE);
        when(stored.getManifestSha256()).thenReturn("f".repeat(64));
        return stored;
    }

    private static InstanceReleaseManifest manifest(String retrievalQuery) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-x",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract(
                        "system", "task", retrievalQuery, new BigDecimal("0.250")),
                List.of(),
                new InstanceReleaseManifest.OutputContract("output", SCHEMA_SHA),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract(
                        "golden", 1, new BigDecimal("0.950")));
    }

    private static EngineResultEnvelope envelope() {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                "1.0.0",
                "DOCENGINE-C14N-1",
                PACKAGE,
                new EngineResultEnvelope.Generation(JOB, 1, 3, "ab".repeat(32),
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new EngineResultEnvelope.SourceFile(
                        SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(new EngineResultEnvelope.EnginePage(PAGE, SOURCE, 0, 0,
                        new BigDecimal("612"), new BigDecimal("792"), 0, null, "NATIVE",
                        false, false, null)),
                List.of(new EngineResultEnvelope.LogicalDocument(
                        new UUID(0xdddd, 0), "PAYSTUB", 0, List.of(PAGE), List.of())),
                List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of(new EngineResultEnvelope.StageAttempt(
                                "EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }
}
