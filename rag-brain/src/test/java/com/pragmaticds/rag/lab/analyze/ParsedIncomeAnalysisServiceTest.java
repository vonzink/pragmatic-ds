package com.pragmaticds.rag.lab.analyze;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.AnalysisRun;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ConfidenceComponents;
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
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.AnalysisRunRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisRunRecorder;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.AnalysisTraceService;
import com.pragmaticds.rag.service.analyze.AnalyzerPromptService;
import com.pragmaticds.rag.service.analyze.AssetsReportRenderer;
import com.pragmaticds.rag.service.analyze.DocumentBlockService;
import com.pragmaticds.rag.service.analyze.EnvelopeValidator;
import com.pragmaticds.rag.service.analyze.IncomeWorksheetRenderer;
import com.pragmaticds.rag.service.analyze.RunManifest;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import com.pragmaticds.rag.service.analyze.calc.CalculationExecutor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The parsed-envelope analysis path, end to end through the real {@link AnalysisService} with
 * mocked provider/retrieval/persistence edges.
 *
 * <p>Every value here is synthetic: invented UUIDs, an invented employer, invented amounts. The
 * canary strings are deliberate nonsense tokens so an assertion that they are ABSENT from a
 * response, a persisted row, or a log line is a real leak test rather than a coincidence.
 */
class ParsedIncomeAnalysisServiceTest {

    private static final UUID BRAIN = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    private static final UUID PACKAGE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID JOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PAGE = UUID.fromString("44444444-4444-4444-8444-444444444440");
    private static final UUID SCHEMA = UUID.fromString("66666666-6666-4666-8666-666666666660");
    private static final UUID DOCUMENT = UUID.fromString("77777777-7777-4777-8777-777777777770");
    private static final UUID RELEASE = UUID.fromString("99999999-9999-4999-8999-999999999990");
    private static final String SOURCE_SET_SHA = "b2".repeat(32);
    private static final String CORRELATION = "corr-0001";

    private static final String STORED_PROMPT = "STORED income instructions (release 1).";
    private static final String STORED_SCHEMA = "{\"storedOutputSchema\":true}";
    private static final String STORED_QUERY = "stored income guidelines";
    private static final String STORED_SCOPE = "income-stored";
    private static final int STORED_TOP_K = 2;

    private IncomeLabReleaseService releaseService;
    private ModelRouterService router;
    private RetrievalService retrieval;
    private DocumentBlockService blocks;
    private AnalysisTraceService trace;
    private AnalysisRunRepository analysisRuns;
    private DomainPackRegistry registry;
    private RuntimeSettings settings;
    private AnalyzerPromptService prompts;
    private AnalysisService analysisService;
    private ParsedIncomeAnalysisService service;
    private ListAppender<ILoggingEvent> logs;

    // ------------------------------------------------------------------ wiring

    @BeforeEach
    void setUp() {
        releaseService = mock(IncomeLabReleaseService.class);
        router = mock(ModelRouterService.class);
        retrieval = mock(RetrievalService.class);
        blocks = mock(DocumentBlockService.class);
        trace = mock(AnalysisTraceService.class);
        analysisRuns = mock(AnalysisRunRepository.class);
        registry = mock(DomainPackRegistry.class);
        settings = mock(RuntimeSettings.class);

        // A LIVE pack that deliberately disagrees with the stored release on every pinned
        // field, so any test that sees stored text proves the run read storage, not the pack.
        AnalyzerConfig livePack = new AnalyzerConfig(
                "income-v2", "Income", "LIVE pack instructions (must not be used).",
                "live pack query", 99, "{\"livePackOutputSchema\":true}", null, "live-scope", "v2");
        DomainPack pack = mock(DomainPack.class);
        when(pack.analyzers()).thenReturn(List.of(livePack));
        BrainPackBundle bundle = mock(BrainPackBundle.class);
        when(bundle.pack()).thenReturn(pack);
        when(registry.bundle(BRAIN)).thenReturn(bundle);

        rebuild(false);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs = new ListAppender<>();
        logs.setContext(context);
        logs.start();
        context.getLogger("com.pragmaticds.rag").setLevel(Level.DEBUG);
        context.getLogger("com.pragmaticds.rag").addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger("com.pragmaticds.rag").detachAppender(logs);
    }

    /** Rebuilds the pipeline with a REAL recorder at the given global persist-findings setting. */
    private void rebuild(boolean persistFindings) {
        reset(analysisRuns);
        ObjectMapper mapper = new ObjectMapper();
        AnalysisRunRecorder recorder =
                new AnalysisRunRecorder(analysisRuns, mapper, persistFindings);
        prompts = mock(AnalyzerPromptService.class);
        when(prompts.withEffectivePrompt(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        analysisService = new AnalysisService(registry, router, retrieval, blocks,
                new EnvelopeValidator(), new CalculationExecutor(new IncomeCalcService(), mapper),
                new AssetsCalcService(), new AssetsReportRenderer(),
                new IncomeDomainEnricher(), new IncomeWorksheetRenderer(), new AnswerCitationService(),
                mapper, trace, recorder, prompts, settings, 20000);
        service = new ParsedIncomeAnalysisService(releaseService,
                new ParsedDocumentPromptRenderer(), analysisService);
        when(analysisRuns.saveAndFlush(any(AnalysisRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(analysisRuns.existsById(any(UUID.class))).thenReturn(true);
    }

    // ------------------------------------------------------------------ fixtures

    private static FieldOccurrence found(String name, String groupKey, BigDecimal number) {
        return new FieldOccurrence(name, groupKey, FieldStatus.FOUND, "MONEY",
                number.toPlainString(), number.toPlainString(),
                new NormalizedValue(null, number, null, null),
                new SchemaRef(SCHEMA, "2024.1"), "ANCHOR_LABEL", "5.1.0",
                new BigDecimal("0.97"),
                new ConfidenceComponents(new BigDecimal("0.97"), BigDecimal.ONE, BigDecimal.ONE),
                "VALIDATED", false,
                List.of(new EvidenceSpan(PAGE, null, 1L, "VALUE", 0,
                        new Box(new BigDecimal("72.00"), new BigDecimal("600.00"),
                                new BigDecimal("180.00"), new BigDecimal("12.00")))));
    }

    private static EngineResultEnvelope envelope(String documentTypeCode) {
        return envelope("1.0.0", "DOCENGINE-C14N-1", documentTypeCode);
    }

    private static EngineResultEnvelope envelope(String envelopeVersion,
                                                 String canonicalizationVersion,
                                                 String documentTypeCode) {
        LogicalDocument document = new LogicalDocument(DOCUMENT, documentTypeCode, 1, List.of(PAGE),
                List.of(found("wages.annual", null, new BigDecimal("96000.00"))));
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("synthetic-envelope".getBytes(StandardCharsets.UTF_8)),
                envelopeVersion,
                canonicalizationVersion,
                PACKAGE,
                new Generation(JOB, 1, 3, SOURCE_SET_SHA, "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(new EnginePage(PAGE, SOURCE, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                List.of(document),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    private static DocumentEngineClient.VerifiedEnvelope verified(EngineResultEnvelope envelope) {
        return new DocumentEngineClient.VerifiedEnvelope(envelope.artifact(), envelope, 3);
    }

    private static DocumentEngineClient.RevisionDescriptor descriptorFor(
            DocumentEngineClient.VerifiedEnvelope fetched) {
        return new DocumentEngineClient.RevisionDescriptor(
                fetched.revision(),
                fetched.envelope().generation().processingJobId(),
                fetched.envelope().generation().parseGeneration(),
                1,
                fetched.envelope().envelopeVersion(),
                fetched.envelope().generation().sourceSetSha256(),
                "d4".repeat(32),
                fetched.artifact().sha256(),
                fetched.artifact().byteCount(),
                "PARSE_ONCE_CURRENT_PACKAGE",
                Instant.parse("2026-08-15T00:00:00Z"));
    }

    private static LabReleaseManifest manifest(List<String> documentTypes) {
        return manifest(documentTypes, List.of("1.0.0"), List.of("DOCENGINE-C14N-1"));
    }

    private static LabReleaseManifest manifest(List<String> documentTypes,
                                               List<String> envelopeVersions,
                                               List<String> canonicalizationVersions) {
        return new LabReleaseManifest(
                LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION,
                IncomeLabReleaseService.INCOME_INSTANCE_SLUG,
                IncomeLabReleaseService.INCOME_ANALYZER_SLUG,
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer("Income", "v2", STORED_PROMPT,
                                STORED_SCHEMA, IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                                "e5".repeat(32)),
                        new LabReleaseManifest.Retrieval(STORED_QUERY, STORED_SCOPE, STORED_TOP_K),
                        new LabReleaseManifest.EngineContract(
                                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE,
                                envelopeVersions, canonicalizationVersions, documentTypes,
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

    private void pinRelease(LabReleaseManifest manifest) {
        when(releaseService.resolveForRun(BRAIN)).thenReturn(
                new IncomeLabReleaseService.ResolvedRelease(RELEASE, 1, "f6".repeat(32), manifest));
    }

    private static ParsedAnalysisInput input(EngineResultEnvelope envelope) {
        DocumentEngineClient.VerifiedEnvelope fetched = verified(envelope);
        return new ParsedAnalysisInput(BRAIN, UUID.randomUUID(), PACKAGE,
                descriptorFor(fetched), fetched, CORRELATION);
    }

    private static String modelEnvelope(String citationDocumentId) {
        return modelEnvelope(citationDocumentId, "c1");
    }

    /** A schema-valid income-v2 envelope with one fact, one calculation, one borrower citation. */
    private static String modelEnvelope(String citationDocumentId, String factCitationId) {
        return "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"income-v2\","
                + "\"reportMarkdown\":\"## Income\\nMonthly base: {{calc:calc1}}\","
                + "\"facts\":[{\"id\":\"f1\",\"statement\":\"Annual base salary is 96,000.\","
                + "\"value\":96000,\"citationIds\":[\"" + factCitationId + "\"],\"confidence\":0.97}],"
                + "\"assumptions\":[],\"warnings\":[],\"recommendations\":[],"
                + "\"calculations\":[{\"id\":\"calc1\",\"name\":\"monthlyBase\","
                + "\"method\":\"income.monthly_from_annual.v1\",\"inputs\":{\"annual\":96000}}],"
                + "\"missingItems\":[],"
                + "\"citations\":[{\"id\":\"c1\",\"class\":\"BORROWER_DOC\",\"documentId\":\""
                + citationDocumentId + "\",\"page\":1}],"
                + "\"confidence\":0.9}";
    }

    private void modelAnswers(String... responses) {
        ModelRouterService.SanitizedResponse[] rest =
                new ModelRouterService.SanitizedResponse[responses.length - 1];
        for (int i = 1; i < responses.length; i++) {
            rest[i - 1] = sanitized(responses[i]);
        }
        when(router.generateSanitized(any(AiRequest.class), eq(BRAIN), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitized(responses[0]), rest);
    }

    private static ModelRouterService.SanitizedResponse sanitized(String content) {
        return new ModelRouterService.SanitizedResponse(
                new AiResponse(content, "anthropic", "claude-x", 100, 50),
                new ModelRouterService.Resolution(null, null, true, "anthropic", "claude-x",
                        "anthropic", "claude-x", false));
    }

    private void retrievalReturns(int chunkCount) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                    "Guideline text " + i, "Guide " + i, "GUIDELINE", "Doc " + i, "Title " + i,
                    "Section " + i, i + 1, null, 0.9, 0.9, 0.9));
        }
        when(retrieval.retrieveAdmin(anyString(), eq(BRAIN), any(), anyString()))
                .thenReturn(new RetrievalResult(chunks, 0.9, true));
    }

    private ParsedIncomeAnalysisService.ParsedRunOutcome runHappyPath() {
        pinRelease(manifest(List.of("PAYSTUB", "W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1"));
        return service.analyze(input(envelope("W2")));
    }

    private AiRequest capturedRequest() {
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        // The four-argument overload — the three-argument one is a different method, and
        // stubbing it left the mock returning null mid-run.
        //
        // CONFIGURED, not NONE. The Lab's parsed path goes through analyzeParsed, which leaves
        // the default policy in place and RECORDS whatever answered in the run's provenance; only
        // analyzePinned, the generalized instance path, sets NONE and refuses substitution
        // outright. The distinction is deliberate and covered by
        // provenanceRecordsTheRoutersOwnResolutionIncludingADroppedOverride.
        verify(router).generateSanitized(captor.capture(), eq(BRAIN), eq(CORRELATION),
                eq(ModelRouterService.FallbackPolicy.CONFIGURED));
        return captor.getValue();
    }

    private AnalysisRun savedRun() {
        ArgumentCaptor<AnalysisRun> captor = ArgumentCaptor.forClass(AnalysisRun.class);
        verify(analysisRuns).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private String capturedLogs() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    // ================================================================ no raw fallback

    @Test
    void theParsedModelRequestCarriesZeroMediaAndNeverBuildsDocumentBlocks() {
        runHappyPath();

        assertTrue(capturedRequest().media().isEmpty(),
                "a parsed run must never attach document media");
        verifyNoInteractions(blocks);
        // The parsed path never looks an analyzer up in the live pack, so the dashboard's
        // published base prompt must not be consulted either: a pinned release's instruction
        // block is the one the release stored.
        verify(prompts, never()).withEffectivePrompt(any(), any());
    }

    @Test
    void noParsedTypeCanAcceptARawDocumentOrAFallbackSupplier() throws Exception {
        Set<String> forbidden = Set.of(
                "org.springframework.web.multipart.MultipartFile",
                "com.pragmaticds.rag.service.analyze.DocInput",
                "byte[]",
                "org.springframework.core.io.InputStreamSource",
                "org.springframework.core.io.Resource",
                "java.util.function.Supplier",
                "java.util.function.Function",
                "com.pragmaticds.rag.service.analyze.DocumentBlockService");

        for (RecordComponent component : ParsedAnalysisInput.class.getRecordComponents()) {
            assertFalse(forbidden.contains(component.getType().getCanonicalName()),
                    "ParsedAnalysisInput." + component.getName());
        }
        for (Class<?> type : List.of(ParsedIncomeAnalysisService.class,
                ParsedDocumentPromptRenderer.class, AnalysisService.ParsedContract.class)) {
            for (Method method : type.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(forbidden.contains(parameter.getCanonicalName()),
                            type.getSimpleName() + "." + method.getName());
                }
            }
        }
        for (java.lang.reflect.Field field
                : ParsedIncomeAnalysisService.class.getDeclaredFields()) {
            assertFalse(forbidden.contains(field.getType().getCanonicalName()), field.getName());
        }
    }

    @Test
    void theLabAnalysisSourcesNameNoRawDocumentPathAtAll() throws Exception {
        // Comments are stripped first: this asserts about CODE. Prose that names the raw routes in
        // order to say the Lab never calls them is documentation, not a call site.
        for (String file : List.of("ParsedAnalysisInput", "ParsedIncomeAnalysisService",
                "ParsedDocumentPromptRenderer")) {
            String source = Files.readString(
                            Path.of("src/main/java/com/pragmaticds/rag/lab/analyze/" + file + ".java"))
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\n]*", " ");
            for (String token : List.of("MultipartFile", "DocInput", "DocumentBlockService",
                    "getBytes()", "analysisService.analyze(", ".refine(", "/analyze", "/extract")) {
                assertFalse(source.contains(token),
                        file + " must not reference " + token
                                + " — the Lab never reaches a raw-document route");
            }
        }
    }

    @Test
    void theSharedRawEntryPointIsNeverInvokedByTheParsedPath() {
        AnalysisService spy = mock(AnalysisService.class);
        ParsedIncomeAnalysisService isolated = new ParsedIncomeAnalysisService(
                releaseService, new ParsedDocumentPromptRenderer(), spy);
        pinRelease(manifest(List.of("W2")));
        when(spy.analyzeParsed(any(UUID.class), any(RunManifest.class),
                any(AnalysisService.ParsedContract.class)))
                .thenReturn(new AnalysisService.ParsedOutcome(
                        new AnalysisResult(AnalysisResult.Status.SUCCESS, "r", "{}", List.of(),
                                "anthropic", "claude-x", 1, 1, 0.0, 0, List.of(), null),
                        new ModelRouterService.Resolution(null, null, true, "anthropic",
                                "claude-x", "anthropic", "claude-x", false),
                        1));

        isolated.analyze(input(envelope("W2")));

        verify(spy, never()).analyze(any(), anyString(), any(), any());
        verify(spy, never()).refine(any(), anyString(), any());
    }

    // ================================================================ stored release wins

    @Test
    void thePromptCarriesTheStoredContractAndNeverTheLivePack() {
        runHappyPath();
        String prompt = capturedRequest().prompt();

        assertTrue(prompt.startsWith(STORED_PROMPT), prompt.substring(0, 80));
        assertTrue(prompt.contains(STORED_SCHEMA), "stored output schema must be in the prompt");
        assertFalse(prompt.contains("LIVE pack instructions"), "the live pack must not be read");
        assertFalse(prompt.contains("livePackOutputSchema"), "the live pack must not be read");
        assertTrue(prompt.contains("PARSED DOCUMENT FACTS"), "the facts block must be present");
        assertTrue(prompt.contains("Analyzer Envelope v2"), "the v2 contract must be present");
        assertTrue(prompt.contains("\"income-v2\""), "the pinned analyzer slug must be declared");
    }

    @Test
    void retrievalUsesThePinnedQueryAndCorpusScope() {
        runHappyPath();

        verify(retrieval).retrieveAdmin(eq(STORED_QUERY), eq(BRAIN),
                eq(SourceVisibility.INTERNAL), eq(STORED_SCOPE));
        verify(retrieval, never()).retrieveAdmin(eq("live pack query"), any(), any(), any());
    }

    @Test
    void compatibilityIsDecidedByTheStoredPolicyNotTheStaticDefaults() {
        // PAYSTUB is a static default, but this release accepts only W2 — the stored policy wins.
        pinRelease(manifest(List.of("W2")));

        ParsedIncomeAnalysisService.ParsedAnalysisException failure =
                assertThrows(ParsedIncomeAnalysisService.ParsedAnalysisException.class,
                        () -> service.analyze(input(envelope("PAYSTUB"))));

        assertEquals(ParsedIncomeAnalysisService.ParsedAnalysisException.Code
                .PARSED_ENVELOPE_INCOMPATIBLE, failure.code());
        assertEquals(IncomeEnvelopeCompatibility.RejectionCode.NO_SUPPORTED_DOCUMENT.name(),
                failure.detail());
        assertTrue(new IncomeEnvelopeCompatibility().evaluate(envelope("PAYSTUB")).compatible(),
                "the static default would have accepted this envelope");
    }

    @Test
    void anEnvelopeVersionTheReleaseDoesNotAcceptIsRefused() {
        pinRelease(manifest(List.of("W2"), List.of("2.0.0"), List.of("DOCENGINE-C14N-1")));

        assertEquals(IncomeEnvelopeCompatibility.RejectionCode.ENVELOPE_VERSION_UNSUPPORTED.name(),
                assertThrows(ParsedIncomeAnalysisService.ParsedAnalysisException.class,
                        () -> service.analyze(input(envelope("W2")))).detail());
    }

    // ================================================================ refuse before spending

    @Test
    void anIncompatibleEnvelopeStopsBeforeRetrievalAndBeforeTheModel() {
        pinRelease(manifest(List.of("W2")));

        assertThrows(ParsedIncomeAnalysisService.ParsedAnalysisException.class,
                () -> service.analyze(input(envelope("PAYSTUB"))));

        verifyNoInteractions(retrieval);
        verifyNoInteractions(router);
        verifyNoInteractions(analysisRuns);
    }

    @Test
    void aStaleRevisionStopsBeforeRetrievalAndBeforeTheModel() {
        pinRelease(manifest(List.of("W2")));
        DocumentEngineClient.VerifiedEnvelope fetched = verified(envelope("W2"));
        DocumentEngineClient.RevisionDescriptor stale =
                new DocumentEngineClient.RevisionDescriptor(
                        2, JOB, 1, 1, "1.0.0", SOURCE_SET_SHA, "d4".repeat(32),
                        fetched.artifact().sha256(), fetched.artifact().byteCount(),
                        "PARSE_ONCE_CURRENT_PACKAGE", Instant.parse("2026-08-15T00:00:00Z"));

        ParsedIncomeAnalysisService.ParsedAnalysisException failure =
                assertThrows(ParsedIncomeAnalysisService.ParsedAnalysisException.class,
                        () -> service.analyze(new ParsedAnalysisInput(BRAIN, UUID.randomUUID(),
                                PACKAGE, stale, fetched, CORRELATION)));

        assertEquals(ParsedIncomeAnalysisService.ParsedAnalysisException.Code.PARSED_SOURCE_STALE,
                failure.code());
        verifyNoInteractions(retrieval);
        verifyNoInteractions(router);
    }

    @Test
    void anEnvelopeForAnotherPackageStopsBeforeRetrievalAndBeforeTheModel() {
        pinRelease(manifest(List.of("W2")));
        DocumentEngineClient.VerifiedEnvelope fetched = verified(envelope("W2"));

        DocumentEngineFailure failure = assertThrows(DocumentEngineFailure.class,
                () -> service.analyze(new ParsedAnalysisInput(BRAIN, UUID.randomUUID(),
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        descriptorFor(fetched), fetched, CORRELATION)));

        assertEquals(DocumentEngineFailure.Code.ENGINE_PACKAGE_MISMATCH, failure.code());
        verifyNoInteractions(retrieval);
        verifyNoInteractions(router);
    }

    // ================================================================ evidence and citations

    @Test
    void aBorrowerCitationNamingAnUnknownHandleIsRetriedThenFailsClosed() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-9"), modelEnvelope("doc-9"));

        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = service.analyze(input(envelope("W2")));

        assertEquals(AnalysisResult.Status.ERROR, outcome.result().status());
        assertEquals(2, outcome.provenance().providerAttempts());
        assertEquals(AnalysisService.ParsedFailureCode.ENVELOPE_VALIDATION_FAILED_AFTER_RETRY.name(),
                outcome.result().reason());
        verify(router, times(2)).generateSanitized(any(AiRequest.class), eq(BRAIN), anyString(),
                any(ModelRouterService.FallbackPolicy.class));
    }

    @Test
    void aBorrowerCitationNamingTheRenderedHandleIsAccepted() {
        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = runHappyPath();

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status());
        assertEquals(List.of("doc-1"), outcome.provenance().documentHandles());
    }

    @Test
    void aFactCitingAnUndeclaredCitationIdIsRefused() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1", "c404"), modelEnvelope("doc-1", "c404"));

        assertEquals(AnalysisResult.Status.ERROR,
                service.analyze(input(envelope("W2"))).result().status());
        verify(router, times(2)).generateSanitized(any(AiRequest.class), eq(BRAIN), anyString(),
                any(ModelRouterService.FallbackPolicy.class));
    }

    @Test
    void theDeterministicCalculatorStillRunsOnTheParsedPath() {
        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = runHappyPath();

        assertTrue(outcome.result().findingsJson().contains("\"value\":8000"),
                "96000 / 12 must be computed by the engine: " + outcome.result().findingsJson());
        assertFalse(outcome.result().reportMarkdown().contains("{{calc:calc1}}"),
                "the placeholder must be substituted");
        assertFalse(savedRun().getCalcAudit().isEmpty(), "the calc audit must be recorded");
    }

    // ================================================================ provenance

    @Test
    void provenancePinsTheReleaseEngineRevisionAndEnvelopeDigest() {
        EngineResultEnvelope envelope = envelope("W2");
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1"));
        ParsedAnalysisInput input = input(envelope);

        ParsedIncomeAnalysisService.RunProvenance provenance =
                service.analyze(input).provenance();

        assertEquals(input.analysisRunId(), provenance.analysisRunId());
        assertEquals(RELEASE, provenance.releaseId());
        assertEquals(1, provenance.releaseNumber());
        assertEquals("f6".repeat(32), provenance.releaseManifestSha256());
        assertEquals(PACKAGE, provenance.packageId());
        assertEquals(3, provenance.packageRevision());
        assertEquals(1, provenance.parseGeneration());
        assertEquals(JOB, provenance.processingJobId());
        assertEquals(SOURCE_SET_SHA, provenance.sourceSetSha256());
        assertEquals(envelope.artifact().sha256(), provenance.envelopeSha256());
        assertEquals(envelope.artifact().byteCount(), provenance.envelopeByteCount());
        assertEquals("1.0.0", provenance.envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", provenance.canonicalizationVersion());
        assertEquals(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                provenance.prototypeLimitations());
        assertEquals(LabReleaseManifest.LIVE_DEPENDENCIES, provenance.liveDependencies());
    }

    @Test
    void provenanceRecordsTheRoutersOwnResolutionIncludingADroppedOverride() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        // The router was asked for a provider it does not have a key for; it dropped the
        // override and served the lane. The Lab must record what RAN, not what was asked for.
        when(router.generateSanitized(any(AiRequest.class), eq(BRAIN), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(new ModelRouterService.SanitizedResponse(
                        new AiResponse(modelEnvelope("doc-1"), "openai", "gpt-x", 10, 5),
                        new ModelRouterService.Resolution("grok", "grok-4", false,
                                "anthropic", "claude-x", "openai", "gpt-x", true)));

        ModelRouterService.Resolution resolution =
                service.analyze(input(envelope("W2"))).provenance().modelResolution();

        assertEquals("grok", resolution.requestedProvider());
        assertFalse(resolution.requestedPairHonored());
        assertEquals("anthropic", resolution.resolvedProvider());
        assertEquals("openai", resolution.answeringProvider());
        assertEquals("gpt-x", resolution.answeringModel());
        assertTrue(resolution.fallbackUsed());
    }

    @Test
    void provenanceReportsRetrievalRankingAsLiveRatherThanPinned() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(5);                       // more than the release's declared top-k of 2
        modelAnswers(modelEnvelope("doc-1"));

        ParsedIncomeAnalysisService.RetrievalProvenance retrievalProvenance =
                service.analyze(input(envelope("W2"))).provenance().retrieval();

        assertTrue(retrievalProvenance.queryFromRelease());
        assertEquals(STORED_SCOPE, retrievalProvenance.corpusScope());
        assertEquals(STORED_TOP_K, retrievalProvenance.declaredTopK());
        assertEquals(5, retrievalProvenance.retrievedChunkCount());
        assertTrue(retrievalProvenance.rankingLive(),
                "the release declares top-k but the retrieval path does not consult it");
        assertTrue(LabReleaseManifest.LIVE_DEPENDENCIES.contains("RETRIEVAL_RANKING"));
    }

    // ================================================================ analyzer identity

    @Test
    void theCallerAllocatedRunIdIsTheOnlyAnalyzerIdentity() {
        EngineResultEnvelope envelope = envelope("W2");
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1"));
        ParsedAnalysisInput input = input(envelope);

        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = service.analyze(input);

        assertEquals(input.analysisRunId().toString(), outcome.result().runId());
        AnalysisRun row = savedRun();
        assertEquals(input.analysisRunId(), row.getId());
        assertEquals(BRAIN, row.getBrainId());
        assertEquals("income-v2", row.getAnalyzerSlug());
        assertEquals("v2", row.getEnvelopeVersion());
        assertEquals(1, row.getDocCount());
        verify(analysisRuns, times(1)).saveAndFlush(any(AnalysisRun.class));
    }

    @Test
    void theRecordedRowPinsThePackageRevisionAndEnvelopeDigestWithoutValues() {
        runHappyPath();
        AnalysisRun row = savedRun();

        assertEquals(1, row.getDocs().size());
        assertEquals(PACKAGE.toString(), row.getDocs().getFirst().get("packageId"));
        assertEquals(3, row.getDocs().getFirst().get("packageRevision"));
        assertEquals(RELEASE.toString(), row.getDocs().getFirst().get("releaseId"));
        assertFalse(row.getDocs().getFirst().containsKey("fileName"));
        assertFalse(row.getDocs().getFirst().containsKey("sha256"),
                "a parsed source is pinned by envelope digest, never by a hash of values");
        assertNotNull(row.getPromptSha256());
    }

    @Test
    void theLabRowStaysMetadataOnlyEvenWhenGlobalPersistFindingsIsOn() {
        for (boolean persistFindings : List.of(false, true)) {
            rebuild(persistFindings);
            runHappyPath();

            AnalysisRun row = savedRun();
            assertTrue(row.getFindings() == null,
                    "persistFindings=" + persistFindings + " must not put findings on a Lab row");
            assertFalse(row.getDocs().getFirst().containsKey("fileName"));
            for (var audit : row.getCalcAudit()) {
                assertEquals(Set.of("id", "method", "status", "error"), audit.keySet());
            }
        }
    }

    @Test
    void aRequiredRecorderFailureCannotProduceASuccessfulParsedRun() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1"));
        when(analysisRuns.saveAndFlush(any(AnalysisRun.class)))
                .thenThrow(new RuntimeException("constraint violated on CANARY-RECORDER-77"));

        AnalysisRunRecorder.RecorderException failure =
                assertThrows(AnalysisRunRecorder.RecorderException.class,
                        () -> service.analyze(input(envelope("W2"))));

        assertEquals(AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_PERSIST_FAILED,
                failure.code());
        assertFalse(failure.getMessage().contains("CANARY-RECORDER-77"));
        assertFalse(capturedLogs().contains("CANARY-RECORDER-77"));
    }

    @Test
    void aParsedSuccessIsNotReturnedUntilTheAnalyzerRowExists() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers(modelEnvelope("doc-1"));
        when(analysisRuns.existsById(any(UUID.class))).thenReturn(false);

        assertEquals(AnalysisRunRecorder.RecorderException.Code.ANALYSIS_RUN_ABSENT_AFTER_WRITE,
                assertThrows(AnalysisRunRecorder.RecorderException.class,
                        () -> service.analyze(input(envelope("W2")))).code());
    }

    // ================================================================ payload-free failures

    @Test
    void aProviderFailureLeavesNoCanaryInTheResponseTheRowOrTheLogs() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        when(router.generateSanitized(any(AiRequest.class), eq(BRAIN), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenThrow(new ModelRouterService.SanitizedProviderException(
                        ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                        "anthropic", "SocketTimeoutException", CORRELATION));

        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = service.analyze(input(envelope("W2")));

        assertEquals(AnalysisResult.Status.ERROR, outcome.result().status());
        assertEquals(AnalysisService.ParsedFailureCode.MODEL_PROVIDER_FAILED.name(),
                outcome.result().reason());
        assertEquals(AnalysisService.ParsedFailureCode.MODEL_PROVIDER_FAILED.name(),
                savedRun().getErrorReason());
        String all = capturedLogs();
        assertTrue(all.contains(CORRELATION), "the correlation id must survive: " + all);
        assertFalse(all.contains("CANARY"));
    }

    @Test
    void anUnparseableProviderResponseNeverEchoesItsBodyAnywhere() {
        pinRelease(manifest(List.of("W2")));
        retrievalReturns(1);
        modelAnswers("total nonsense CANARY-BODY-42 not json",
                "still nonsense CANARY-BODY-42");

        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = service.analyze(input(envelope("W2")));

        assertEquals(AnalysisResult.Status.ERROR, outcome.result().status());
        assertFalse(outcome.result().reason().contains("CANARY-BODY-42"));
        assertFalse(String.valueOf(savedRun().getErrorReason()).contains("CANARY-BODY-42"));
        assertFalse(capturedLogs().contains("CANARY-BODY-42"));
    }

    @Test
    void aRetrievalFailureIsNonFatalAndNeverEchoesItsMessage() {
        pinRelease(manifest(List.of("W2")));
        when(retrieval.retrieveAdmin(anyString(), eq(BRAIN), any(), anyString()))
                .thenThrow(new IllegalStateException("pgvector said CANARY-RETRIEVAL-13"));
        modelAnswers(modelEnvelope("doc-1"));

        ParsedIncomeAnalysisService.ParsedRunOutcome outcome = service.analyze(input(envelope("W2")));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status());
        assertEquals(0, outcome.provenance().retrieval().retrievedChunkCount());
        assertFalse(capturedLogs().contains("CANARY-RETRIEVAL-13"));
        assertTrue(capturedLogs().contains(CORRELATION));
    }

    @Test
    void aRenderFailureIsPayloadFreeAndReachesNoProvider() {
        pinRelease(manifest(List.of("W2")));
        EngineResultEnvelope broken = new EngineResultEnvelope(
                envelope("W2").artifact(), "1.0.0", "DOCENGINE-C14N-1", PACKAGE,
                new Generation(JOB, 1, 3, SOURCE_SET_SHA, "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(),                                       // no pages: evidence is orphaned
                List.of(new LogicalDocument(DOCUMENT, "W2", 1, List.of(PAGE),
                        List.of(found("wages.annual", null, new BigDecimal("1"))))),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"), List.of()));

        assertEquals(LabContractException.Code.PAGE_MEMBERSHIP_VIOLATION,
                assertThrows(LabContractException.class,
                        () -> service.analyze(input(broken))).code());
        verifyNoInteractions(router);
    }

    // ================================================================ taxonomy propagation

    @Test
    void everyLabFailureTaxonomyPropagatesUnwrapped() {
        List<RuntimeException> taxonomies = List.of(
                new IncomeLabReleaseService.ReleaseException(
                        IncomeLabReleaseService.ReleaseException.Code.RELEASE_DIGEST_MISMATCH),
                new LabManifestWriter.ManifestException(
                        LabManifestWriter.ManifestException.Code.MANIFEST_VALUE_UNSUPPORTED),
                new LabContractException(LabContractException.Code.ENVELOPE_VALUE_MALFORMED),
                new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_DIGEST_MISMATCH));

        for (RuntimeException taxonomy : taxonomies) {
            // doThrow, not when(...): when(...) would CALL the already-stubbed mock.
            doThrow(taxonomy).when(releaseService).resolveForRun(BRAIN);
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service.analyze(input(envelope("W2"))));
            assertSame(taxonomy, thrown, taxonomy.getClass().getSimpleName()
                    + " must propagate unwrapped for the Lab exception handler to map");
            assertFalse(thrown.getMessage().toLowerCase(Locale.ROOT).contains("borrower"));
        }
    }
}
