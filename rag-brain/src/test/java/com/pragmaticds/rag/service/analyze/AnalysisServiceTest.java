package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.dto.RefineRequest;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.AssetsRules;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.PageSelectionProfile;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.ModelRouterService.RoutedResponse;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import com.pragmaticds.rag.service.analyze.calc.CalculationExecutor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {

    private final UUID brainId = UUID.randomUUID();
    private DomainPackRegistry registry;
    private ModelRouterService router;
    private RetrievalService retrieval;
    private DocumentBlockService blocks;
    private AnalysisTraceService trace;
    private DomainPack pack;
    private AnalysisRunRecorder runRecorder;
    private AnalyzerPromptService prompts;
    private RuntimeSettings settings;
    private AnalysisService service;

    private AnalyzerConfig income = new AnalyzerConfig(
            "income", "Income", "Analyze income.",
            "income guidelines", 8, "{\"items\":[]}", null, "income");
    private AnalyzerConfig classifier = new AnalyzerConfig(
            "documents", "Classifier", "Classify.", null, 0, "{\"items\":[]}", null, null);
    private AnalyzerConfig incomeV2 = new AnalyzerConfig(
            "income", "Income", "Analyze income.",
            "income guidelines", 8, "{\"items\":[]}", null, "income", "v2");
    /** The real pack's income-v2 slug, for the income-domain-v2 contract. */
    private AnalyzerConfig incomeWorksheet = new AnalyzerConfig(
            "income-v2", "Income (v2)", "Analyze income.",
            "income guidelines", 8, "{\"items\":[]}", null, "income", "v2");
    /** Analyzer that pins its own provider+model pair in the pack. */
    private AnalyzerConfig pinned = new AnalyzerConfig(
            "pinned", "Pinned", "Analyze.", "income guidelines", 8, "{\"items\":[]}",
            "claude-sonnet-4-5", "anthropic", "income", null, null, null);
    /**
     * The shape the real pack declares for assets: a v2 analyzer whose deterministic half is
     * carried entirely by its ledger rules.
     */
    private AnalyzerConfig assetsV2 = new AnalyzerConfig(
            "assets-v2", "Assets (v2)", "Transcribe the statements.", "asset guidelines", 8,
            "{\"accounts\":[]}", null, null, "assets", "v2", null,
            new AssetsRules(List.of(
                    new AssetsRules.LargeDepositRule("FANNIE_MAE", "QUALIFYING_MONTHLY_INCOME",
                            new java.math.BigDecimal("50"), List.of("PURCHASE"), false)),
                    List.of(new AssetsRules.FlagRule("overdraft", "Overdraft",
                            List.of("OVERDRAFT"))), 2),
            List.of("asset-analysis"));
    /** Declares the submission-domain-v1 contract, so the application-terms block gates onto it. */
    private AnalyzerConfig submission = new AnalyzerConfig(
            "submission", "Submission Review", "Analyze submission.",
            "submission guidelines", 8, "{\"schemaVersion\": \"submission-domain-v1\"}",
            null, "submission", "v2");

    @BeforeEach
    void setUp() {
        registry = mock(DomainPackRegistry.class);
        router = mock(ModelRouterService.class);
        retrieval = mock(RetrievalService.class);
        blocks = mock(DocumentBlockService.class);
        trace = mock(AnalysisTraceService.class);
        runRecorder = mock(AnalysisRunRecorder.class);

        settings = mock(RuntimeSettings.class);

        pack = mock(DomainPack.class);
        when(pack.analyzers()).thenReturn(List.of(income, classifier, pinned, assetsV2, submission));
        when(pack.disclaimer()).thenReturn("Educational only.");
        BrainPackBundle bundle = mock(BrainPackBundle.class);
        when(bundle.pack()).thenReturn(pack);
        when(registry.bundle(brainId)).thenReturn(bundle);

        // Real CalculationExecutor: it's a pure, dependency-free service (deterministic
        // math + regex substitution), so exercising the actual wiring beats mocking it —
        // a plain mock would return null from execute(...) and NPE the v2 success path.
        // AssetsCalcService and AssetsReportRenderer are pure too, for the same reason.
        ObjectMapper mapper = new ObjectMapper();
        prompts = mock(AnalyzerPromptService.class);
        when(prompts.withEffectivePrompt(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new AnalysisService(registry, router, retrieval, blocks,
                new EnvelopeValidator(), new CalculationExecutor(new IncomeCalcService(), mapper),
                new AssetsCalcService(), new AssetsReportRenderer(),
                new IncomeDomainEnricher(), new IncomeWorksheetRenderer(),
                new AnswerCitationService(),
                mapper, trace, runRecorder, prompts, settings, 20000);
    }

    private DocumentBlockService.BuildResult oneBlock() {
        Media m = Media.builder().mimeType(Media.Format.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1})).build();
        return new DocumentBlockService.BuildResult(
                List.of(new DocumentBlockService.DocBlock("d1", m, 1)), 1, List.of());
    }

    private AnalysisContext ctx() {
        return new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", "paystub.png", "image/png", 42L)),
                null,
                new AnalysisContext.LoanSnapshot(400000.0, 500000.0, List.of("Jane Doe"), 8000.0, null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(),
                false);
    }

    private AnalysisContext ctxWithApplicationTerms() {
        return new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", "contract.pdf", "application/pdf", 42L)),
                null,
                new AnalysisContext.LoanSnapshot(400000.0, 500000.0, List.of("Jane Doe"), 8000.0, null,
                        "FANNIE_MAE", "PURCHASE",
                        410000.0, 20500.0, "123 Main St, Denver, CO 80202", "2026-10-15", null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(),
                false);
    }

    private String promptFor(AnalysisContext ctx) {
        return promptFor(ctx, "income", "{\"reportMarkdown\":\"# R\",\"findings\":{},\"citations\":[]}");
    }

    /**
     * Runs {@code slug} and returns the FIRST captured prompt — the one built before any
     * response is parsed. A v2 slug that declares a domain contract but whose stubbed response
     * lacks one triggers a corrective retry and an eventual ERROR result; that is fine here,
     * since only the prompt this helper hands back is under test.
     */
    private String promptFor(AnalysisContext ctx, String slug, String modelJson) {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse(modelJson, "anthropic", "claude-x", 100, 50), false));
        service.analyze(brainId, slug, List.of(bytesFor("d1")), ctx);
        ArgumentCaptor<AiRequest> req = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, atLeastOnce()).generate(req.capture(), eq(brainId));
        return req.getAllValues().get(0).prompt();
    }

    @Test
    void applicationTermsRenderIntoThePromptWithNotOnApplicationForBlanks() {
        // submission is the only test analyzer that declares the submission-domain-v1 contract.
        String v2Json = "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"submission\",\"reportMarkdown\":\"r\","
                + "\"facts\":[],\"assumptions\":[],\"warnings\":[],\"recommendations\":[],"
                + "\"calculations\":[],\"missingItems\":[],\"citations\":[],\"confidence\":0.5}";
        String prompt = promptFor(ctxWithApplicationTerms(), "submission", v2Json);
        assertTrue(prompt.contains("Application terms (CLAIMED on the loan file"), prompt);
        assertTrue(prompt.contains("  salesPrice=410000.0\n"), prompt);
        assertTrue(prompt.contains("  downPayment=20500.0\n"), prompt);
        assertTrue(prompt.contains("  propertyAddress=123 Main St, Denver, CO 80202\n"), prompt);
        assertTrue(prompt.contains("  consummationDate=2026-10-15\n"), prompt);
        assertTrue(prompt.contains("  sellerCredits=not on application\n"), prompt);
    }

    @Test
    void theApplicationTermsBlockIsAbsentForAnalyzersThatDoNotDeclareTheContract() {
        // income does not declare submission-domain-v1, so the block must not render even
        // though the caller sent application terms.
        String prompt = promptFor(ctxWithApplicationTerms());
        assertFalse(prompt.contains("Application terms"), prompt);
    }

    @Test
    void theApplicationTermsBlockIsAbsentWhenNoCallerSentAny() {
        // Every pre-existing caller uses the 5- or 7-arg LoanSnapshot: the prompt they get is unchanged.
        String prompt = promptFor(ctx());
        assertFalse(prompt.contains("Application terms"), prompt);
        assertTrue(prompt.contains("Loan snapshot: loanAmount=400000.0"), prompt);
    }

    @Test
    void theFiveArgLoanSnapshotStillBindsAndLeavesTheNewFieldsNull() {
        AnalysisContext.LoanSnapshot s =
                new AnalysisContext.LoanSnapshot(1.0, 2.0, List.of("A"), 3.0, null);
        assertNull(s.salesPrice());
        assertNull(s.downPayment());
        assertNull(s.propertyAddress());
        assertNull(s.consummationDate());
        assertNull(s.sellerCredits());
        assertNull(s.program());
    }

    @Test
    void jacksonBindsTheNewLoanSnapshotKeysByName() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AnalysisContext.LoanSnapshot s = om.readValue("""
                {"loanAmount":400000,"borrowers":["Jane Doe"],"salesPrice":410000,
                 "propertyAddress":"123 Main St","consummationDate":"2026-10-15","sellerCredits":8000}
                """, AnalysisContext.LoanSnapshot.class);
        assertEquals(410000.0, s.salesPrice());
        assertEquals("123 Main St", s.propertyAddress());
        assertEquals("2026-10-15", s.consummationDate());
        assertEquals(8000.0, s.sellerCredits());
        assertNull(s.downPayment());
    }

    @Test
    void incomeHappyPathParsesFindingsAndCitations() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(eq("income guidelines"), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        String modelJson = "{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":"
                + "[{\"employer\":\"Acme\",\"monthlyIncome\":5000}]},\"citations\":[]}";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse(modelJson, "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.reportMarkdown().contains("Income"));
        assertTrue(r.findingsJson().contains("Acme"));
        assertEquals("anthropic", r.provider());
        assertEquals(100, r.inputTokens());
        assertEquals(1, r.pageCount());
        assertTrue(r.skippedDocs().isEmpty());
        // Disclaimer is always appended.
        assertTrue(r.reportMarkdown().toLowerCase().contains("verify before underwriting"));
        // Single-attempt trace: attempts=1, tokens = that one call.
        verify(trace).record(eq(brainId), eq("income"), any(AnalysisContext.class),
                any(DocumentBlockService.BuildResult.class), any(AiResponse.class),
                eq(1), eq(100), eq(50), eq("SUCCESS"));
    }

    // --- per-analyzer model selection: runtime setting beats the pack pair ---

    private AiRequest captureAnalyzeRequest(String slug) {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(
                        "{\"reportMarkdown\":\"# R\",\"findings\":{},\"citations\":[]}",
                        "anthropic", "claude-x", 10, 5), false));

        service.analyze(brainId, slug, List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> req = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(req.capture(), eq(brainId));
        return req.getValue();
    }

    /** With no runtime row stored, the analyzer's own pack pair reaches the router. */
    @Test
    void packPairIsUsedWhenNoRuntimeOverride() {
        when(settings.analyzerProvider("pinned")).thenReturn(null);
        when(settings.analyzerModel("pinned")).thenReturn(null);

        AiRequest sent = captureAnalyzeRequest("pinned");

        assertEquals("anthropic", sent.provider());
        assertEquals("claude-sonnet-4-5", sent.model());
    }

    /** A runtime override swaps the model with no redeploy — both halves move together. */
    @Test
    void runtimeOverrideBeatsThePackPair() {
        when(settings.analyzerProvider("pinned")).thenReturn("grok");
        when(settings.analyzerModel("pinned")).thenReturn("grok-4.3");

        AiRequest sent = captureAnalyzeRequest("pinned");

        assertEquals("grok", sent.provider());
        assertEquals("grok-4.3", sent.model());
    }

    /**
     * A half-set runtime override must not blend with the pack pair — pairing "grok"
     * with the pack's Claude model id would post a Claude model to xAI.
     */
    @Test
    void halfSetRuntimeOverrideFallsBackToThePackPairWhole() {
        when(settings.analyzerProvider("pinned")).thenReturn("grok");
        when(settings.analyzerModel("pinned")).thenReturn(null);

        AiRequest sent = captureAnalyzeRequest("pinned");

        assertEquals("anthropic", sent.provider());
        assertEquals("claude-sonnet-4-5", sent.model());
    }

    @Test
    void successfulAnalyzeReturnsRunIdAndRecordsOneSuccessManifest() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(eq("income guidelines"), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertNotNull(r.runId());

        ArgumentCaptor<RunManifest> manifestCaptor = ArgumentCaptor.forClass(RunManifest.class);
        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(runRecorder, times(1)).save(manifestCaptor.capture(), resultCaptor.capture(), eq(1));
        assertEquals(AnalysisResult.Status.SUCCESS, resultCaptor.getValue().status());
        assertEquals(r.runId(), manifestCaptor.getValue().runId().toString());
    }

    @Test
    void modelProviderFailureAlsoRecordsAnErrorManifestAndReturnsRunId() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId))).thenThrow(new RuntimeException("boom"));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.runId());

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(runRecorder, times(1)).save(any(RunManifest.class), resultCaptor.capture(), eq(1));
        assertEquals(AnalysisResult.Status.ERROR, resultCaptor.getValue().status());
    }

    @Test
    void unmodelledExceptionStillRecordsAManifestThenRethrows() {
        // Doc building throwing is not a modelled outcome — it never reaches error(). Without
        // the outer catch it would unwind to a 500 with NO analysis_runs row, which is exactly
        // the run an operator goes looking for. The row must exist; the 500 must still happen.
        when(blocks.build(anyList(), isNull())).thenThrow(new IllegalStateException("pdfbox blew up"));

        assertThrows(IllegalStateException.class,
                () -> service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx()));

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(runRecorder, times(1)).save(any(RunManifest.class), captor.capture(), eq(1));
        assertEquals(AnalysisResult.Status.ERROR, captor.getValue().status());
        assertTrue(captor.getValue().reason().contains("IllegalStateException"),
                "the recorded reason must name the exception type: " + captor.getValue().reason());
        assertTrue(captor.getValue().reason().contains("pdfbox blew up"));
    }

    @Test
    void postModelCallErrorRecordsRealTokensCostAndProviderNotZero() {
        // Billed calls already happened before the unparseable-response error fires — the
        // ledger must reflect that spend, not report the calls as free. Since the v1 retry
        // fix, an unparseable response costs TWO model calls (first + one corrective retry),
        // so the error row carries the SUM of both attempts' tokens.
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("not json at all", "anthropic", "claude-x", 5, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertEquals(10, r.inputTokens(), "tokens are summed across both attempts");
        assertEquals(10, r.outputTokens());
        assertEquals("anthropic", r.provider());
        assertEquals("claude-x", r.model());
        assertEquals(CostTable.usd("anthropic", "claude-x", 10, 10), r.costUsd(), 1e-12);
        assertTrue(r.costUsd() > 0, "real billed calls must not be recorded as free");

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(runRecorder).save(any(RunManifest.class), resultCaptor.capture(), eq(2));
        assertEquals(10, resultCaptor.getValue().inputTokens());
        assertEquals("anthropic", resultCaptor.getValue().provider());
    }

    @Test
    void v2RetryModelCallFailureRecordsFirstCallTokensAndAttemptsTwo() {
        // The retry itself throws (e.g. a network blip) — only the FIRST call's tokens
        // are known, but two model calls were actually made, so attempts must be 2. This
        // is the exact path that silently reported 0 tokens / attempts un-pinned before.
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String invalid = sampleEnvelopeMinusConfidence();
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(invalid, "anthropic", "claude-x", 100, 50), false))
                .thenThrow(new RuntimeException("network blip"));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertEquals(100, r.inputTokens(), "only the first (successful) call's tokens are known");
        assertEquals(50, r.outputTokens());
        assertEquals("anthropic", r.provider());
        assertEquals("claude-x", r.model());
        assertEquals(CostTable.usd("anthropic", "claude-x", 100, 50), r.costUsd(), 1e-12);

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(runRecorder).save(any(RunManifest.class), resultCaptor.capture(), eq(2));
        assertEquals(100, resultCaptor.getValue().inputTokens());
    }

    @Test
    void analyzeRequestUsesConfiguredCompletionTokenCap() {
        // Pins the fix for prod truncation: real income folders overflowed the old
        // hard-coded caps mid-array (4000, then 8000 on a 17-doc folder 2026-07-28),
        // failing JSON parsing (ERROR row). The completion budget is now the injected
        // ragbrain.rag.analyze.max-output-tokens (20000 here, matching the default)
        // so a future edit can't silently drop it.
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertEquals(20000, captor.getValue().maxTokens());
    }

    @Test
    void analyzeRequestUsesAnalyzePurpose() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertEquals(AiRequest.Purpose.ANALYZE, captor.getValue().purpose());
    }

    @Test
    void classifierPathSkipsRetrieval() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"x\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));

        AnalysisResult r = service.analyze(brainId, "documents", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        verify(retrieval, never()).retrieveAdmin(anyString(), any(), any(), any());
    }

    @Test
    void unknownAnalyzerThrows() {
        assertThrows(AnalyzerNotFoundException.class,
                () -> service.analyze(brainId, "nope", List.of(bytesFor("d1")), ctx()));
    }

    @Test
    void allDocsSkippedStillSucceedsWithZeroCoverage() {
        when(blocks.build(anyList(), isNull())).thenReturn(new DocumentBlockService.BuildResult(
                List.of(), 0, List.of(new SkippedDoc("d1", "report.txt", SkipCategory.UNSUPPORTED_TYPE, "unsupported document type: text/plain"))));
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"no docs\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 5, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertEquals(1, r.skippedDocs().size());
        assertEquals(0, r.pageCount());
    }

    @Test
    void unparseableModelJsonYieldsError() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("not json at all", "anthropic", "claude-x", 5, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
    }

    @Test
    void v1UnparseableFirstResponseRetriesOnceThenSucceeds() {
        // Regression (the "unparseable model response" ERROR rows): the v1 analyze path used to fail
        // closed on the FIRST unparseable response — only v2 got a corrective retry. A truncated or
        // comment-polluted first response now gets ONE terser retry before erroring, mirroring v2.
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse("Here is my analysis but no JSON", "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(
                                "{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[{\"employer\":\"Acme\"}]},\"citations\":[]}",
                                "anthropic", "claude-x", 90, 40), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.findingsJson().contains("Acme"));
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
        // Tokens are summed across both attempts; the trace records attempts=2.
        assertEquals(190, r.inputTokens());
        assertEquals(90, r.outputTokens());
        verify(trace).record(eq(brainId), eq("income"), any(AnalysisContext.class),
                any(DocumentBlockService.BuildResult.class), any(AiResponse.class),
                eq(2), eq(190), eq(90), eq("SUCCESS"));
    }

    @Test
    void v1RetryStillUnparseableFailsClosedWithDistinctReason() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse("still not json", "anthropic", "claude-x", 5, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertTrue(r.reason().contains("after retry"));
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v1JsonWithCommentsParsesWithoutRetry() {
        // Models frequently echo the //-comment schema template back (the assets output-schema is
        // literally interpolated with // comments). Strict readTree rejected it → ERROR row. The
        // parser now tolerates JSON comments, so a good analysis parses on the FIRST try (no retry).
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String commented = "{\n  \"reportMarkdown\": \"# Income\", // the report\n"
                + "  \"findings\": {\"items\": [{\"employer\": \"Acme\"}]}, // one account\n"
                + "  \"citations\": []\n}";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(commented, "anthropic", "claude-x", 10, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.findingsJson().contains("Acme"));
        verify(router, times(1)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v1BareArrayResponseBecomesFindings() {
        // Array-shaped output-schemas (e.g. assets: "Return findings as a JSON array") invite a bare
        // top-level array; the old first-'{' … last-'}' slice mangled it into invalid JSON. A bare
        // array is now taken as the findings payload verbatim.
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String bareArray = "[{\"account\":\"Chase ...7427\",\"balance\":1234}, {\"account\":\"Ally ...9\",\"balance\":56}]";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(bareArray, "anthropic", "claude-x", 10, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.findingsJson().contains("Chase"));
        assertTrue(r.findingsJson().trim().startsWith("["));
        verify(router, times(1)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void loanUrlaIncomeFromContextJsonSurfacesInPrompt() throws Exception {
        // Prod parity: AnalyzeController deserializes the `context` part with the
        // Spring-default ObjectMapper (FAIL_ON_UNKNOWN_PROPERTIES disabled), so any
        // loan field the LoanSnapshot record doesn't declare is SILENTLY dropped.
        // The income analyzer's reconciliation step depends on loan.urlaIncome —
        // this pins that the field survives deserialization AND is rendered into
        // the composed prompt, or reconciliation silently no-ops forever.
        ObjectMapper lenient = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String contextJson = """
                {"docs":[{"id":"d1","fileName":"paystub.png","contentType":"image/png","sizeBytes":42}],
                 "loan":{"loanNumber":"L-1001","loanAmount":400000.0,"propertyValue":500000.0,
                         "borrowers":["Jane Doe"],"monthlyIncome":8000.0,
                         "urlaIncome":{"statedTotalMonthly":12500,
                           "borrowers":[{"name":"Jane Doe","sources":[
                             {"type":"BASE","employer":"Acme Co","monthlyStated":8000},
                             {"type":"BONUS","employer":"Acme Co","monthlyStated":4500}]}]}}}
                """;
        AnalysisContext ctx = lenient.readValue(contextJson, AnalysisContext.class);

        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"ok\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        assertTrue(prompt.contains("urlaIncome"),
                "prompt must label the stated URLA income block (loan.urlaIncome)");
        assertTrue(prompt.contains("12500"), "prompt must carry statedTotalMonthly");
        assertTrue(prompt.contains("Acme Co"), "prompt must carry the itemized source employer");
        assertTrue(prompt.contains("monthlyStated"), "prompt must carry per-source stated amounts");
    }

    @Test
    void loanWithoutUrlaIncomeOmitsTheStatedIncomeBlock() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"ok\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertFalse(captor.getValue().prompt().contains("urlaIncome"),
                "no stated-income block when the suite sent none — the model must not be "
                        + "told URLA data is present when it is not");
    }

    @Test
    void analyzerCorpusScopeIsPassedToRetrieval() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), any(), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"ok\",\"findings\":{}}",
                                "anthropic", "claude-x", 10, 5), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        verify(retrieval).retrieveAdmin("income guidelines", brainId,
                com.pragmaticds.rag.domain.SourceVisibility.INTERNAL, "income");
    }

    // ---------------------------------------------------------------- envelope v2

    @Test
    void v2ValidEnvelopeSucceedsFirstTry() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.findingsJson().contains("\"envelopeVersion\":\"2.0\""));
        assertTrue(r.reportMarkdown().contains("Income Analysis (sample)"));
        verify(router, times(1)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v2InvalidThenValidRetriesOnce() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String invalid = sampleEnvelopeMinusConfidence();
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(invalid, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        String retryPrompt = captor.getAllValues().get(1).prompt();
        assertTrue(retryPrompt.contains("failed schema validation"));
        assertTrue(retryPrompt.contains(invalid));
        // Both the first call and the retry must use the configured token cap.
        assertEquals(20000, captor.getAllValues().get(0).maxTokens());
        assertEquals(20000, captor.getAllValues().get(1).maxTokens());
        // The retry must re-send the SAME native doc blocks (media) as the first call.
        List<Media> firstMedia = captor.getAllValues().get(0).media();
        List<Media> retryMedia = captor.getAllValues().get(1).media();
        assertFalse(firstMedia.isEmpty());
        assertEquals(firstMedia.size(), retryMedia.size());
        assertEquals(firstMedia, retryMedia);
        // Token/cost accounting sums BOTH model calls.
        assertEquals(130, r.inputTokens());
        assertEquals(70, r.outputTokens());
        assertEquals(CostTable.usd("anthropic", "claude-x", 130, 70), r.costUsd(), 1e-12);
    }

    @Test
    void v2InvalidTwiceFailsClosed() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String invalid = sampleEnvelopeMinusConfidence();
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(invalid, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(invalid, "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().startsWith("envelope v2 validation failed after retry:"),
                "unexpected reason: " + r.reason());
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v2RetryUnparseableFailsClosed() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(sampleEnvelopeMinusConfidence(),
                                "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse("not json at all",
                                "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().startsWith("envelope v2 retry response unparseable; first-attempt errors: "),
                "unexpected reason: " + r.reason());
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v2SuccessAfterRetryTracesSummedTokensAndAttemptCount() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(sampleEnvelopeMinusConfidence(),
                                "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        // The ONE trace line must agree with the AnalysisResult: BOTH calls summed, attempts=2.
        verify(trace).record(eq(brainId), eq("income"), any(AnalysisContext.class),
                any(DocumentBlockService.BuildResult.class), any(AiResponse.class),
                eq(2), eq(130), eq(70), eq("SUCCESS"));
    }

    @Test
    void v2UnparseableFirstResponseRetriesOnce() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse("not json at all", "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        String retryPrompt = captor.getAllValues().get(1).prompt();
        assertTrue(retryPrompt.contains("failed schema validation"));
        assertTrue(retryPrompt.contains("not json at all"), "retry prompt must include the raw first response");
        // Token/cost accounting sums BOTH model calls.
        assertEquals(130, r.inputTokens());
        assertEquals(70, r.outputTokens());
    }

    @Test
    void v2UnparseableTwiceFailsClosedAfterSingleRetry() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse("not json at all", "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse("still not json", "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().startsWith("envelope v2 retry response unparseable"),
                "unexpected reason: " + r.reason());
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v2AnalyzerMismatchTriggersRetryThenSucceeds() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        // Schema-valid envelope, but the model answered as the WRONG analyzer.
        String mismatched = sampleEnvelopeWithAnalyzer("assets");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(mismatched, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        String retryPrompt = captor.getAllValues().get(1).prompt();
        assertTrue(retryPrompt.contains("failed schema validation"));
        assertTrue(retryPrompt.contains("$.analyzer"), "retry prompt must name the analyzer mismatch");
        assertTrue(retryPrompt.contains("\"income\""));
    }

    @Test
    void v2AnalyzerMismatchTwiceFailsClosed() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String mismatched = sampleEnvelopeWithAnalyzer("assets");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(mismatched, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(mismatched, "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().startsWith("envelope v2 validation failed after retry:"),
                "unexpected reason: " + r.reason());
        assertTrue(r.reason().contains("$.analyzer"), "reason must name the analyzer mismatch: " + r.reason());
        verify(router, times(2)).generate(any(AiRequest.class), eq(brainId));
    }

    @Test
    void v2PromptContainsEnvelopeInstruction() {
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        assertTrue(prompt.contains("Envelope v2"));
        assertTrue(prompt.contains("GUIDELINE"));
        assertTrue(prompt.contains("BORROWER_DOC"));
        assertTrue(prompt.contains("envelopeVersion"));
        assertTrue(prompt.contains("\"analyzer\": \"income\""));
        assertTrue(prompt.contains("{\"items\":[]}"));
        assertFalse(prompt.contains("Return ONLY valid JSON in EXACTLY this shape"));
    }

    @Test
    void v2CalculationRequestsAreExecutedAndReportPlaceholdersSubstituted() throws Exception {
        // Proves the CalculationExecutor wiring: calc1 (income.monthly_from_annual.v1,
        // annual=96000, in the shared fixture) must come back with an engine-computed
        // "result" (never a model-supplied one), and its {{calc:calc1}} placeholder in
        // reportMarkdown must be substituted with that same computed value.
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse(sampleEnvelope(), "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        // 96000 / 12 = 8000.00, computed by IncomeCalcService — not by the model.
        JsonNode findings = new ObjectMapper().readTree(r.findingsJson());
        JsonNode result = findings.at("/calculations/0/result");
        assertEquals("COMPUTED", result.path("status").asText(),
                "findingsJson must carry the engine-computed calculation result: " + r.findingsJson());
        assertEquals(0, new java.math.BigDecimal("8000.00").compareTo(result.path("value").decimalValue()),
                "expected calc1's computed value to be 8000.00, got: " + result.path("value"));
        assertTrue(r.reportMarkdown().contains("Monthly base income: 8000.00"),
                "reportMarkdown must have the {{calc:calc1}} placeholder substituted: " + r.reportMarkdown());
        assertFalse(r.reportMarkdown().contains("{{calc:calc1}}"),
                "the raw placeholder must not leak into the report");
    }

    @Test
    void v2UnsupportedCalculationMethodRetriesThenFailsClosed() {
        // A hallucinated method id passes schema validation (method is just "any non-empty
        // string") — it must NOT silently become a SUCCESS with a failed-calculation report;
        // it must route into the same retry-once-then-fail-closed flow as any other v2
        // validation error, and the failure reason must name the offending method.
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String bogus = sampleEnvelopeWithMethod("income.made_up_method.v1");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(bogus, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(bogus, "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("income.made_up_method.v1"),
                "reason must name the unsupported method: " + r.reason());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        assertTrue(captor.getAllValues().get(1).prompt().contains("income.made_up_method.v1"),
                "retry prompt must surface the unsupported-method validation error");
    }

    @Test
    void incomeDomainShapeErrorRetriesThenFailsClosed() {
        when(pack.analyzers()).thenReturn(List.of(incomeWorksheet));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String missingConfidence = incomeEnvelope(RATE_AND_BROKEN_YTD, incomeDomain(null), "r");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(
                        new RoutedResponse(new AiResponse(missingConfidence, "anthropic", "claude-x", 100, 50), false),
                        new RoutedResponse(new AiResponse(missingConfidence, "anthropic", "claude-x", 30, 20), false));

        AnalysisResult r = service.analyze(brainId, "income-v2", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertTrue(r.reason().contains("$.domain"), "reason must name the domain shape error: " + r.reason());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        assertTrue(captor.getAllValues().get(1).prompt().contains("$.domain.borrowers[0].sources[0]"),
                "retry prompt must surface the domain validation error");
    }

    @Test
    void incomeDomainRunsAreEnrichedAndGetAWorksheetWithoutFailing() throws Exception {
        when(pack.analyzers()).thenReturn(List.of(incomeWorksheet));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String envelope = incomeEnvelope(RATE_AND_BROKEN_YTD, incomeDomain("HIGH"),
                "## Qualifying income\\nBase pay {{calc:src1-monthly-rate}} per month.");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(envelope, "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income-v2", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status(), "a failed YTD calculation must not fail the run");
        JsonNode findings = new ObjectMapper().readTree(r.findingsJson());
        assertEquals("CALCULATOR", findings.at("/domain/borrowers/0/sources/0/computed/basis").asText());
        assertEquals(0, new java.math.BigDecimal("10119.83")
                .compareTo(findings.at("/domain/computedTotalMonthly").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("0.70").compareTo(findings.path("confidence").decimalValue()),
                "the errored src1-ytd-avg lowers run confidence one step");
        assertTrue(findings.path("warnings").toString().contains("\\\"src1-ytd-avg\\\" did not compute"),
                findings.path("warnings").toString());
        assertTrue(r.reportMarkdown().contains("Base pay 10119.83 per month."), r.reportMarkdown());
        assertTrue(r.reportMarkdown().contains("## Calculation worksheet"), r.reportMarkdown());
        assertTrue(r.reportMarkdown().contains("  YTD check:     not computed: period covers 0.4839 months"),
                r.reportMarkdown());
        assertTrue(r.reportMarkdown().indexOf("## Calculation worksheet")
                        < r.reportMarkdown().indexOf("_AI analysis — verify before underwriting decisions"),
                "the worksheet belongs to the report, before AnalysisService.DISCLAIMER");
        // The envelope's own reportMarkdown (inside findingsJson) ends with the worksheet's closing fence.
        assertTrue(findings.path("reportMarkdown").asText().endsWith("```"));

        ArgumentCaptor<RunManifest> manifest = ArgumentCaptor.forClass(RunManifest.class);
        verify(runRecorder).save(manifest.capture(), any(AnalysisResult.class), anyInt());
        assertEquals(1, manifest.getValue().calculationsFailed());
    }

    @Test
    void anUndeclaredIncomeDomainRunIsUnchanged() throws Exception {
        when(pack.analyzers()).thenReturn(List.of(incomeWorksheet));
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        String legacyDomain = "{\"borrowers\":[{\"name\":\"B\",\"sources\":[{\"type\":\"W-2 base\",\"calcIds\":[\"src1-ytd-avg\"]}]}]}";
        String envelope = incomeEnvelope(RATE_AND_BROKEN_YTD, legacyDomain, "Report {{calc:src1-monthly-rate}}");
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(envelope, "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income-v2", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        JsonNode findings = new ObjectMapper().readTree(r.findingsJson());
        assertEquals(0, new java.math.BigDecimal("0.9").compareTo(findings.path("confidence").decimalValue()));
        assertEquals(0, findings.path("warnings").size());
        assertTrue(findings.at("/domain/borrowers/0/sources/0/computed").isMissingNode());
        assertFalse(r.reportMarkdown().contains("Calculation worksheet"));
        assertEquals("Report 10119.83", findings.path("reportMarkdown").asText());
    }

    @Test
    void analystNotesAreRenderedIntoThePrompt() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        AnalysisContext c = new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", "paystub.png", "image/png", 42L)),
                null,
                new AnalysisContext.LoanSnapshot(400000.0, 500000.0, List.of("Jane Doe"), 8000.0, null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(new AnalysisContext.AnalystNote("RNB W-2 (unreadable)", "Box 1 wages 50000", "analyst")),
                false);

        service.analyze(brainId, "income", List.of(bytesFor("d1")), c);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertTrue(captor.getValue().prompt().contains("Box 1 wages 50000"));
        assertTrue(captor.getValue().prompt().contains("RNB W-2 (unreadable)"));
    }

    @Test
    void v1UsesV1SchemaPromptAndRetriesOnceBeforeError() {
        // v1 uses the v1 schema instruction (never the v2 envelope) AND, since the retry fix, an
        // unparseable response now costs ONE corrective retry before failing closed (was: single call).
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("not json at all", "anthropic", "claude-x", 5, 5), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, times(2)).generate(captor.capture(), eq(brainId));
        String firstPrompt = captor.getAllValues().get(0).prompt();
        assertTrue(firstPrompt.contains("Return ONLY valid JSON in EXACTLY this shape"));
        assertFalse(firstPrompt.contains("Envelope v2"));
    }

    @Test
    void refineRejectsV2AnalyzersUntilSupported() {
        // refine has no v2 corrective-retry or calculation-execution path; half-supporting it
        // would make the same analyzer return engine-computed numbers from /analyze and
        // LLM-computed numbers from /refine. Must fail closed with no model call at all.
        when(pack.analyzers()).thenReturn(List.of(incomeV2));
        RefineRequest req = new RefineRequest(ctx(), null, "# Prior", List.of());

        AnalysisResult r = service.refine(brainId, "income", req);

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.reason().toLowerCase().contains("v2"), "reason must mention v2: " + r.reason());
        verify(router, never()).generate(any(AiRequest.class), any());
        verify(retrieval, never()).retrieveAdmin(anyString(), any(), any(), any());
    }

    @Test
    void refineIsTextOnlyAndUsesPriorFindingsAndNotes() throws Exception {
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Updated\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 80, 40), false));

        com.fasterxml.jackson.databind.JsonNode prior =
                new ObjectMapper().readTree("{\"grandTotalMonthly\":5000}");
        AnalysisContext c = new AnalysisContext(
                List.of(), null,
                new AnalysisContext.LoanSnapshot(null, null, List.of("Jane Doe"), null, null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(new AnalysisContext.AnalystNote("RNB W-2", "Box 1 wages 50000", "analyst")),
                false);
        RefineRequest req = new RefineRequest(c, prior, "# Prior report",
                List.of(new RefineRequest.ChatTurn("user", "the RNB job is a second W-2, not rental")));

        AnalysisResult r = service.refine(brainId, "income", req);

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
        assertTrue(r.reportMarkdown().contains("Updated"));
        assertTrue(r.skippedDocs().isEmpty());
        assertEquals(0, r.pageCount());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        AiRequest sent = captor.getValue();
        assertTrue(sent.media().isEmpty(), "refine must be text-only (no vision blocks)");
        assertEquals(AiRequest.Purpose.ANALYZE, sent.purpose());
        assertTrue(sent.prompt().contains("Box 1 wages 50000"));
        assertTrue(sent.prompt().contains("grandTotalMonthly"));
        assertTrue(sent.prompt().contains("second W-2, not rental"));
    }

    @Test
    void refineReturnsErrorOnProviderFailure() {
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId))).thenThrow(new RuntimeException("boom"));

        AnalysisContext c = new AnalysisContext(
                List.of(), null,
                new AnalysisContext.LoanSnapshot(null, null, List.of("Jane Doe"), null, null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(),
                false);
        RefineRequest req = new RefineRequest(c, null, "# Prior", List.of());

        AnalysisResult r = service.refine(brainId, "income", req);

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
    }

    @Test
    void refineWithNullContextDoesNotNpe() {
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# X\",\"findings\":{},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));

        RefineRequest req = new RefineRequest(null, null, null, null);

        AnalysisResult r = service.refine(brainId, "income", req);

        assertEquals(AnalysisResult.Status.SUCCESS, r.status());
    }

    // ---------------------------------------------------------------- page selection

    @Test
    void analyzePassesTheAnalyzersPageSelectionProfileToBlockBuilding() {
        PageSelectionProfile profile = new PageSelectionProfile("federal-income", 8, 2,
                List.of(new PageSelectionProfile.KeepRule("1040", List.of("Form 1040"))), List.of());
        AnalyzerConfig filteredIncome = new AnalyzerConfig(
                "income", "Income", "Analyze income.", "income guidelines", 8,
                "{\"items\":[]}", null, "income", null, "federal-income");
        when(pack.analyzers()).thenReturn(List.of(filteredIncome));
        when(pack.pageSelectionProfile("federal-income")).thenReturn(profile);
        when(blocks.build(anyList(), any())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        verify(blocks).build(anyList(), eq(profile));
    }

    @Test
    void disablePageFilterSendsEveryPage() {
        PageSelectionProfile profile = new PageSelectionProfile("federal-income", 8, 2,
                List.of(new PageSelectionProfile.KeepRule("1040", List.of("Form 1040"))), List.of());
        AnalyzerConfig filteredIncome = new AnalyzerConfig(
                "income", "Income", "Analyze income.", "income guidelines", 8,
                "{\"items\":[]}", null, "income", null, "federal-income");
        when(pack.analyzers()).thenReturn(List.of(filteredIncome));
        when(pack.pageSelectionProfile("federal-income")).thenReturn(profile);
        when(blocks.build(anyList(), any())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        AnalysisContext c = new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", "return.pdf", "application/pdf", 42L)),
                null,
                new AnalysisContext.LoanSnapshot(null, null, List.of("Jane Doe"), null, null),
                new AnalysisContext.OrgCatalog(List.of(), List.of()),
                List.of(),
                true);

        service.analyze(brainId, "income", List.of(bytesFor("d1")), c);

        verify(blocks).build(anyList(), isNull());
    }

    @Test
    void filteredDocsAreReportedAndNotedInThePrompt() {
        when(blocks.build(anyList(), any())).thenReturn(new DocumentBlockService.BuildResult(
                oneBlock().blocks(), 9, List.of(),
                List.of(new FilteredDoc("d1", "2024_1040.pdf", 62, 9, List.of("1040", "Schedule C")))));
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any())).thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 100, 50), false));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(1, r.filtered().size());
        assertEquals("2024_1040.pdf", r.filtered().get(0).fileName());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        // The note names the document by id: the file name never reaches the model.
        assertTrue(prompt.contains("d1"));
        assertFalse(prompt.contains("2024_1040.pdf"), "file name must not reach the model");
        assertTrue(prompt.contains("9 of 62"));
        assertTrue(prompt.toLowerCase().contains("page selection"));
    }

    private String sampleEnvelope() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ai/sample-envelope-v2.json")) {
            assertNotNull(in, "fixture ai/sample-envelope-v2.json missing from test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sampleEnvelopeMinusConfidence() {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(sampleEnvelope());
            node.remove("confidence");
            return node.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** sampleEnvelope() with calc1's method replaced — for exercising the
     *  unsupported-method-fails-validation path with an otherwise-valid envelope. */
    private String sampleEnvelopeWithMethod(String method) {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(sampleEnvelope());
            ((ObjectNode) node.get("calculations").get(0)).put("method", method);
            return node.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sampleEnvelopeWithAnalyzer(String analyzer) {
        try {
            ObjectNode node = (ObjectNode) new ObjectMapper().readTree(sampleEnvelope());
            node.put("analyzer", analyzer);
            return node.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A minimal valid income-v2 envelope carrying an income-domain-v2 domain. calcsJson and
     * domainJson are spliced in verbatim; the one BORROWER_DOC citation names d1.
     */
    private static String incomeEnvelope(String calcsJson, String domainJson, String report) {
        return """
                {"envelopeVersion":"2.0","analyzer":"income-v2","reportMarkdown":"%s",
                 "facts":[{"id":"f1","statement":"Current gross pay per paystub.","value":4670.69,
                           "citationIds":["c1"],"confidence":0.95}],
                 "assumptions":[],"warnings":[],"recommendations":[],
                 "calculations":[%s],"missingItems":[],
                 "citations":[{"id":"c1","class":"BORROWER_DOC","documentId":"d1","page":1}],
                 "confidence":0.9,"domain":%s}
                """.formatted(report, calcsJson, domainJson);
    }

    private static final String RATE_AND_BROKEN_YTD = """
            {"id":"src1-monthly-rate","name":"Current pay","method":"income.monthly_from_rate.v1",
             "inputs":{"rate":4670.69,"frequency":"BIWEEKLY"}},
            {"id":"src1-ytd-avg","name":"YTD","method":"income.ytd_monthly_average.v1",
             "inputs":{"ytdAmount":4670.69,"periodStart":"2026-01-01","periodEnd":"2026-01-15"}},
            {"id":"total","name":"Total","method":"income.total_monthly.v1",
             "inputs":{"amountRefs":["src1-monthly-rate"]}}
            """;

    private static String incomeDomain(String confidence) {
        return """
                {"schemaVersion":"income-domain-v2",
                 "borrowers":[{"name":"Borrower 1","sources":[
                   {"type":"W-2 base","employer":"ACME WIDGETS LLC","factIds":["f1"],
                    "calcIds":["src1-monthly-rate","src1-ytd-avg"],"qualifyingCalcId":"src1-monthly-rate",
                    "monthly":null,"urlaStatedMonthly":null,"varianceCalcId":null,
                    "concerns":[]%s}]}],
                 "totalCalcId":"total","reconciliationCalcId":null,"opportunities":[],"gaps":[]}
                """.formatted(confidence == null ? "" : ",\"confidence\":\"" + confidence + "\"");
    }

    @Test
    void promptListsDocumentsByIdAndNeverShowsTheFileName() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"ok\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));
        String misleadingName = "Sprague-Y-Bank Statement-Stub Feb 20.png";
        AnalysisContext ctx = new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", misleadingName, "image/png", 42L)),
                null, null, new AnalysisContext.OrgCatalog(List.of(), List.of()), List.of(), false);

        service.analyze(brainId, "income",
                List.of(new DocInput("d1", misleadingName, "image/png", new byte[]{1}, 100)), ctx);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        assertTrue(prompt.contains("File names are withheld"), "the model is told why there is no name");
        assertTrue(prompt.contains("1. d1 — image/png, 1 page(s)"),
                "attached block listed by id, in attachment order");
        assertFalse(prompt.contains("Bank Statement-Stub"), "the file name must never reach the model");
    }

    @Test
    void promptRendersInlineTextDocumentsAndWhatIsAlreadyDecided() {
        Media png = Media.builder().mimeType(Media.Format.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1})).name("d2").build();
        DocumentBlockService.BuildResult built = new DocumentBlockService.BuildResult(
                List.of(new DocumentBlockService.DocBlock("d2", png, 1)), 1, List.of(), List.of(),
                List.of(new DocumentBlockService.TextDoc("d1", "text/markdown",
                        "---\nmdContract: DOCENGINE-MD-1\n---\n| gross_pay | 4,400 |\n")));
        when(blocks.build(anyList(), isNull())).thenReturn(built);
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"x\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));
        AnalysisContext ctx = new AnalysisContext(List.of(
                new AnalysisContext.DocMeta("d1", "paystub.fields.md", "text/markdown", 60L,
                        true, "DOCENGINE-MD-1", null, "Paystub", "engine", 0.9, List.of()),
                new AnalysisContext.DocMeta("d2", "bundle.png", "image/png", 1L,
                        null, null, null, null, null, null,
                        List.of(new AnalysisContext.EngineSegment(1, 2, "W-2", 0.85),
                                new AnalysisContext.EngineSegment(3, 3, null, null)))),
                null, null, new AnalysisContext.OrgCatalog(List.of(), List.of()), List.of(), false);

        service.analyze(brainId, "documents", List.of(bytesFor("d1"), bytesFor("d2")), ctx);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        // The parsed rendering is announced on the list, then given in full between markers.
        assertTrue(prompt.contains("d1 — text/markdown, "));
        assertTrue(prompt.contains("the document engine's parsed rendering (DOCENGINE-MD-1)"));
        assertTrue(prompt.contains("<<<BEGIN DOCUMENT id=d1 type=text/markdown>>>\n---\nmdContract"));
        assertTrue(prompt.contains("| gross_pay | 4,400 |\n<<<END DOCUMENT id=d1>>>"));
        // What the consuming system already decided rides on the document's own line.
        assertTrue(prompt.contains("assignedDocType=\"Paystub\" (assignedBy=engine, engineConfidence=0.90)"));
        assertTrue(prompt.contains("1. d2 — image/png, 1 page(s); engineSegments=[pages 1-2 = \"W-2\" (0.85); pages 3-3]"));
        assertFalse(prompt.contains("paystub.fields.md"));
        assertFalse(prompt.contains("bundle.png"));
    }

    @Test
    void theEngineRenderingAccompaniesTheAttachedDocumentRatherThanReplacingIt() {
        // The case that broke prod: the engine read the page and got NOTHING, so its rendering is
        // a header over an empty table. The document must still reach the model as its pages, and
        // the model must be told that an empty rendering is not an empty document.
        String emptyRendering = "---\ndocumentTypeCode: UNKNOWN\noccurrences: 0\n---\n"
                + "## Occurrence detail\n\n| Field | Value |\n|---|---|\n";
        Media png = Media.builder().mimeType(Media.Format.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1})).name("d1").build();
        DocumentBlockService.BuildResult built = new DocumentBlockService.BuildResult(
                List.of(new DocumentBlockService.DocBlock("d1", png, 1)), 1, List.of(), List.of(),
                List.of());
        when(blocks.build(anyList(), isNull())).thenReturn(built);
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"x\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));
        AnalysisContext ctx = new AnalysisContext(List.of(
                new AnalysisContext.DocMeta("d1", "scan.png", "image/png", 1L,
                        true, "DOCENGINE-MD-1", emptyRendering, null, null, null, List.of())),
                null, null, new AnalysisContext.OrgCatalog(List.of(), List.of()), List.of(), false);

        service.analyze(brainId, "documents", List.of(bytesFor("d1")), ctx);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        String prompt = captor.getValue().prompt();
        // The document is still ATTACHED — the pages went, not just the fields.
        assertTrue(prompt.contains("1. d1 — image/png, 1 page(s)"), "the document is still attached");
        assertTrue(prompt.contains("the document engine's rendering of it is included below"));
        // And the rendering rides alongside, in its own fence.
        assertTrue(prompt.contains("<<<BEGIN ENGINE RENDERING id=d1 contract=DOCENGINE-MD-1>>>"));
        assertTrue(prompt.contains("occurrences: 0"));
        assertTrue(prompt.contains("<<<END ENGINE RENDERING id=d1>>>"));
        // With the rule that keeps an empty table from reading as an empty document.
        assertTrue(prompt.contains("Where the two disagree, the pages win."));
        assertFalse(prompt.contains("scan.png"), "the file name still never reaches the model");
    }

    @Test
    void anEngineRenderingForADocumentThatWasNotAttachedIsNotRendered() {
        // Nothing to accompany: rendering it alone would be the very substitution this change ends.
        DocumentBlockService.BuildResult built = new DocumentBlockService.BuildResult(
                List.of(), 0, List.of(), List.of(), List.of());
        when(blocks.build(anyList(), isNull())).thenReturn(built);
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("{\"reportMarkdown\":\"x\",\"findings\":{\"items\":[]},\"citations\":[]}",
                                "anthropic", "claude-x", 10, 5), false));
        AnalysisContext ctx = new AnalysisContext(List.of(
                new AnalysisContext.DocMeta("gone", "x.png", "image/png", 1L,
                        true, "DOCENGINE-MD-1", "| a | 1 |", null, null, null, List.of())),
                null, null, new AnalysisContext.OrgCatalog(List.of(), List.of()), List.of(), false);

        service.analyze(brainId, "documents", List.of(bytesFor("gone")), ctx);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertFalse(captor.getValue().prompt().contains("BEGIN ENGINE RENDERING"));
    }

    private DocInput bytesFor(String id) {
        return new DocInput(id, id + ".png", "image/png", new byte[]{1, 2, 3}, 100);
    }

    // ==================================================================
    // Raw-path characterization — pinned when the parsed entry point began sharing this pipeline.

    @Test
    void theRawPathStillUsesTheLegacyRouterEntryPointAndBestEffortRecording() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(
                        "{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                        "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        verify(router).generate(any(AiRequest.class), eq(brainId));
        verify(router, never()).generateSanitized(any(AiRequest.class), any(UUID.class), anyString());
        verify(runRecorder).save(any(RunManifest.class), any(AnalysisResult.class), eq(1));
        verify(runRecorder, never())
                .saveRequired(any(RunManifest.class), any(AnalysisResult.class), anyInt());
    }

    @Test
    void theRawPathStillAttachesDocumentMediaAndRecordsTheConsoleTrace() {
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(
                        "{\"reportMarkdown\":\"# Income\",\"findings\":{\"items\":[]},\"citations\":[]}",
                        "anthropic", "claude-x", 100, 50), false));

        service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        assertFalse(captor.getValue().media().isEmpty(),
                "the raw path still sends native document blocks");
        verify(trace).record(eq(brainId), eq("income"), any(AnalysisContext.class),
                any(DocumentBlockService.BuildResult.class), any(AiResponse.class),
                eq(1), eq(100), eq(50), eq("SUCCESS"));
    }

    @Test
    void theRawPathStillRecordsTheVerboseProviderReason() {
        // Characterization: the legacy reason quotes the provider message. The Lab path replaces
        // this with a stable code; the raw path deliberately keeps what operators rely on.
        when(blocks.build(anyList(), isNull())).thenReturn(oneBlock());
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenThrow(new RuntimeException("upstream 503 from api.example"));

        AnalysisResult r = service.analyze(brainId, "income", List.of(bytesFor("d1")), ctx());

        assertEquals(AnalysisResult.Status.ERROR, r.status());
        assertEquals("model provider error: upstream 503 from api.example", r.reason());
    }

    // ================================================================ pinned runs

    /**
     * A pinned run's evidence arrives already retrieved from an immutable snapshot. Retrieving
     * again here would ground the answer in whatever the mutable scope holds now, which is exactly
     * what a release id is supposed to rule out.
     */
    @Test
    void aPinnedRunAnswersFromTheCallersChunksAndNeverRetrievesItsOwn() {
        RetrievedChunk chunk = pinnedChunk();
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());
        RunManifest manifest = pinnedManifest();

        AnalysisService.ParsedOutcome outcome = service.analyzePinned(brainId, manifest,
                pinnedContract(List.of(chunk)));

        // Deliberately not asserting the terminal status: envelope validation and citation
        // handling are the shared path's business and have their own tests. What is this test's
        // to prove is where the evidence came from.
        assertNotNull(outcome.result());
        verifyNoInteractions(retrieval);
        assertEquals(List.of(chunk.chunkId().toString()), manifest.retrievedChunkIds(),
                "the run must record the chunks it was handed, not a fresh set");
        assertNotNull(manifest.promptSha256(), "prompt bytes are part of a pinned run's identity");
    }

    /**
     * The whole point of pinning a model: the router may not quietly answer from another
     * provider's default when the release's pair is unavailable.
     */
    @Test
    void aPinnedRunForbidsTheRouterEverySilentSubstitution() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());

        service.analyzePinned(brainId, pinnedManifest(), pinnedContract(List.of()));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        ArgumentCaptor<ModelRouterService.FallbackPolicy> policy =
                ArgumentCaptor.forClass(ModelRouterService.FallbackPolicy.class);
        verify(router).generateSanitized(request.capture(), eq(brainId), anyString(),
                policy.capture());

        assertEquals(ModelRouterService.FallbackPolicy.NONE, policy.getValue());
        assertEquals("anthropic", request.getValue().provider());
        assertEquals("claude-pinned", request.getValue().model());
        assertTrue(request.getValue().hasProviderPair());
        // The legacy entry point would route through the live pack instead of the release.
        verify(router, never()).generate(any(AiRequest.class), any(UUID.class));
    }

    /**
     * A release that declares a temperature gets it.
     *
     * <p>The manifest's temperature is validated, digested into the release hash, and reported in
     * provenance. Executing at the lane default while claiming another value would make the
     * release's own record of itself false.
     */
    @Test
    void aPinnedRunExecutesAtTheReleasesOwnTemperature() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());

        service.analyzePinned(brainId, pinnedManifest(), pinnedContract(List.of()));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generateSanitized(request.capture(), eq(brainId), anyString(), any());
        assertEquals(0.25, request.getValue().temperature(), 0.0001);
    }

    /** A release that declares none runs at the analyze lane's deliberately low default. */
    @Test
    void aReleaseWithNoTemperatureFallsBackToTheAnalyzeDefault() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());

        AnalysisService.PinnedContract noTemperature = new AnalysisService.PinnedContract(
                "income", "1.0.0", "Analyze income.", "{\"items\":[]}", "income guidelines",
                UUID.randomUUID(), List.of(), "anthropic", "claude-pinned", "FACTS",
                java.util.Set.of(), "correlation-1", null, null, null);
        service.analyzePinned(brainId, pinnedManifest(), noTemperature);

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generateSanitized(request.capture(), eq(brainId), anyString(), any());
        assertEquals(AiRequest.ANALYZE_TEMPERATURE, request.getValue().temperature(), 0.0001);
    }

    /**
     * A parsed run sends ZERO media: the rendered fact block is the only borrower evidence in the
     * request, and there is no raw-document fallback to reach for when the parse is unusable.
     */
    @Test
    void aPinnedRunSendsNoMediaAndBuildsNoDocumentBlocks() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());

        service.analyzePinned(brainId, pinnedManifest(), pinnedContract(List.of()));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generateSanitized(request.capture(), eq(brainId), anyString(), any());
        assertTrue(request.getValue().media().isEmpty(), "a pinned run sends no document media");
        verifyNoInteractions(blocks);
    }

    /**
     * A pinned assets run must produce the ENGINE's report, not the model's.
     *
     * <p>This is the bug the parsed entry points shipped with. Both of them built their {@code Run}
     * with a null {@code assetsRules}, so the deterministic half — {@link AssetsCalcService} and
     * {@link AssetsReportRenderer} — was skipped on every parsed run. The assets prompt tells the
     * model to write nothing in {@code reportMarkdown} precisely because the engine replaces it, so
     * the run completed SUCCESS with an empty report and nobody saw a failure. The raw
     * {@code /analyze} path was always correct; only the parsed ones were not.
     */
    @Test
    void aPinnedAssetsRunRendersTheDeterministicReportOverWhateverTheModelWrote() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitizedAssets());

        AnalysisService.ParsedOutcome outcome =
                service.analyzePinned(brainId, pinnedManifest(), assetsContract());

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status(),
                outcome.result().reason());
        String report = outcome.result().reportMarkdown();
        assertFalse(report.contains("MODEL PROSE"),
                "the engine's renderer must replace the model's report, not append to it");
        assertTrue(report.contains("**Assets** — 1 statement(s)"), report);
        assertTrue(report.contains("Ledger reconciles"), report);
        // Derived from the ledger by AssetsCalcService, which only runs when the rules arrive.
        assertTrue(report.contains("OVERDRAFT FEE"), report);
        assertTrue(outcome.result().findingsJson().contains("derived"),
                "the enriched ledger is the findings payload");
    }

    /**
     * An instance whose slug is not an analyzer's gets that analyzer's rules through the alias the
     * analyzer declares. Without it an {@code asset-analysis} run had no rules, skipped the whole
     * deterministic half, and reported success with an empty report.
     */
    @Test
    void aPinnedRunUnderAnAliasedInstanceSlugGetsTheAnalyzersRules() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitizedAssets("asset-analysis"));

        AnalysisService.ParsedOutcome outcome = service.analyzePinned(
                brainId, pinnedManifest(), assetsContract("asset-analysis", null));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status(),
                outcome.result().reason());
        assertTrue(outcome.result().reportMarkdown().contains("Ledger reconciles"),
                outcome.result().reportMarkdown());
        assertTrue(outcome.result().findingsJson().contains("derived"));
    }

    /**
     * The loan facts a pinned run carries are what make its threshold computable.
     *
     * <p>This is the other half of the parsed-path fix: the rules arrive from the pack, the basis
     * arrives from the registration the caller pinned, and only with both does the large-deposit
     * screen actually run. Without the basis the same ledger reports itself unchecked — which the
     * assertion below pins by contrast.
     */
    @Test
    void aPinnedAssetsRunAppliesTheThresholdItsLoanFactsSelect() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitizedAssets());

        AnalysisService.ParsedOutcome outcome = service.analyzePinned(
                brainId, pinnedManifest(),
                assetsContract(new LoanBasis(LoanBasis.Program.FANNIE_MAE,
                        LoanBasis.Purpose.PURCHASE, new java.math.BigDecimal("8000.00"), null)));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status(),
                outcome.result().reason());
        String report = outcome.result().reportMarkdown();
        // 50% of $8,000 is $4,000, so the $5,200 deposit in the ledger is over it.
        assertTrue(report.contains("Large deposits — 1 needs sourcing"), report);
        assertTrue(report.contains("$4,000.00"), report);
        assertFalse(report.contains("not checked"), report);
    }

    /** The same run with no loan facts must say so rather than clearing the deposit. */
    @Test
    void aPinnedAssetsRunWithoutLoanFactsReportsTheScreenAsNotRun() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitizedAssets());

        AnalysisService.ParsedOutcome outcome =
                service.analyzePinned(brainId, pinnedManifest(), assetsContract());

        String report = outcome.result().reportMarkdown();
        assertTrue(report.contains("Large deposits — not checked"), report);
        assertFalse(report.contains("No deposits need documentation"), report);
    }

    /**
     * A ledger the engine built REPLACES the one the model wrote.
     *
     * <p>This is what takes dense financial transcription away from the model. The envelope below
     * carries a model ledger with a single tiny deposit; the pinned tool's ledger carries the
     * $5,200 one. If the substitution did not happen — or merged the two — the report would
     * either miss the large deposit or double-count it, which is exactly the production failure
     * the deterministic path exists to end.
     */
    @Test
    void aToolBuiltLedgerReplacesTheModelsRatherThanMergingWithIt() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class)))
                .thenReturn(sanitizedAssets());

        AnalysisService.PinnedContract contract = new AnalysisService.PinnedContract(
                "assets-v2", "1.0.0", "Transcribe the statements.", "{\"accounts\":[]}",
                "asset guidelines", UUID.randomUUID(), List.of(), "anthropic", "claude-pinned",
                "FACTS", java.util.Set.of(), "correlation-1", null,
                new LoanBasis(LoanBasis.Program.FANNIE_MAE, LoanBasis.Purpose.PURCHASE,
                        new java.math.BigDecimal("8000.00"), null),
                engineLedger());

        AnalysisService.ParsedOutcome outcome =
                service.analyzePinned(brainId, pinnedManifest(), contract);

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status(),
                outcome.result().reason());
        String findings = outcome.result().findingsJson();
        assertTrue(findings.contains("ENGINE WIRE IN"), "the engine's ledger must be the one used");
        assertFalse(findings.contains("MODEL MOBILE DEPOSIT"),
                "the model's ledger must be replaced, not merged with");
        // 50% of $8,000 is $4,000; only the engine's $6,000 row clears it.
        assertTrue(outcome.result().reportMarkdown().contains("ENGINE WIRE IN"),
                outcome.result().reportMarkdown());
    }

    /** One reconciling engine-built account: 0.00 + 6000.00 = 6000.00. */
    private static com.fasterxml.jackson.databind.JsonNode engineLedger() {
        try {
            return new ObjectMapper().readTree(
                    "{\"accounts\":[{\"institution\":\"WF\",\"maskedNumber\":\"7418\","
                            + "\"type\":\"checking\",\"beginningBalance\":0.00,"
                            + "\"endingBalance\":6000.00,\"transcriptionComplete\":true,"
                            + "\"transactions\":[{\"date\":\"2026-06-10\","
                            + "\"description\":\"ENGINE WIRE IN\",\"amount\":6000.00}]}]}");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * A pinned run for an analyzer with no ledger rules is untouched.
     *
     * <p>The fix reads the live pack for every parsed run, so this is the half that proves it
     * reads rather than assumes: income declares no {@code assets-rules} and its report must
     * still be the model's own.
     */
    @Test
    void aPinnedRunForAnAnalyzerWithoutLedgerRulesKeepsTheModelsReport() {
        when(router.generateSanitized(any(AiRequest.class), eq(brainId), anyString(),
                any(ModelRouterService.FallbackPolicy.class))).thenReturn(sanitized());

        AnalysisService.ParsedOutcome outcome =
                service.analyzePinned(brainId, pinnedManifest(), pinnedContract(List.of()));

        assertEquals(AnalysisResult.Status.SUCCESS, outcome.result().status(),
                outcome.result().reason());
        assertTrue(outcome.result().reportMarkdown().startsWith("# R"),
                outcome.result().reportMarkdown());
    }

    private static AnalysisService.PinnedContract assetsContract() {
        return assetsContract(null);
    }

    private static AnalysisService.PinnedContract assetsContract(LoanBasis loanBasis) {
        return assetsContract("assets-v2", loanBasis);
    }

    private static AnalysisService.PinnedContract assetsContract(String slug, LoanBasis loanBasis) {
        return new AnalysisService.PinnedContract(slug, "1.0.0",
                "Transcribe the statements.", "{\"accounts\":[]}", "asset guidelines",
                UUID.randomUUID(), List.of(), "anthropic", "claude-pinned", "FACTS",
                java.util.Set.of(), "correlation-1", null, loanBasis, null);
    }

    /**
     * One reconciling account: 100.00 + 5200.00 − 35.00 = 5265.00. The ledger has to balance or
     * the renderer leads with its "does not reconcile" banner and the assertions above would be
     * measuring the wrong branch.
     */
    private static ModelRouterService.SanitizedResponse sanitizedAssets() {
        return sanitizedAssets("assets-v2");
    }

    private static ModelRouterService.SanitizedResponse sanitizedAssets(String analyzer) {
        String envelope = "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"" + analyzer + "\","
                + "\"reportMarkdown\":\"MODEL PROSE\",\"facts\":[],\"assumptions\":[],"
                + "\"warnings\":[],\"recommendations\":[],\"calculations\":[],"
                + "\"missingItems\":[],\"citations\":[],\"confidence\":0.9,"
                + "\"domain\":{\"accounts\":[{\"institution\":\"WF\","
                + "\"maskedNumber\":\"7418\",\"type\":\"checking\","
                + "\"statementPeriod\":{\"start\":\"2026-06-01\",\"end\":\"2026-06-30\"},"
                + "\"beginningBalance\":100.00,\"endingBalance\":5265.00,"
                + "\"transcriptionComplete\":true,\"transactions\":["
                + "{\"date\":\"2026-06-09\",\"description\":\"MODEL MOBILE DEPOSIT\","
                + "\"amount\":5200.00},"
                + "{\"date\":\"2026-06-27\",\"description\":\"OVERDRAFT FEE\","
                + "\"amount\":-35.00}]}]}}";
        return new ModelRouterService.SanitizedResponse(
                new AiResponse(envelope, "anthropic", "claude-pinned", 100, 50),
                new ModelRouterService.Resolution("anthropic", "claude-pinned", true, "anthropic",
                        "claude-pinned", "anthropic", "claude-pinned", false));
    }

    private static RetrievedChunk pinnedChunk() {
        return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                "Qualifying income is averaged over 24 months.", "Guide", "GUIDELINE",
                "Selling Guide", "Selling Guide", "B3-3.1", 12, null, 0.9, 0.9, 0.9);
    }

    private static RunManifest pinnedManifest() {
        return RunManifest.forParsedSource(UUID.randomUUID(), UUID.randomUUID(), "income", "1.0.0",
                new RunManifest.ParsedSource(UUID.randomUUID(), 3, 1, UUID.randomUUID(),
                        "ab".repeat(32), "cd".repeat(32), 4096, "1.0.0", UUID.randomUUID(), null));
    }

    private static AnalysisService.PinnedContract pinnedContract(List<RetrievedChunk> chunks) {
        return new AnalysisService.PinnedContract("income", "1.0.0", "Analyze income.",
                "{\"items\":[]}", "income guidelines", UUID.randomUUID(), chunks,
                "anthropic", "claude-pinned", "FACTS", java.util.Set.of(), "correlation-1",
                new java.math.BigDecimal("0.250"), null, null);
    }

    /**
     * A complete, schema-valid v2 envelope.
     *
     * <p>{@code analyzePinned} always runs as v2, and the v2 schema sets
     * {@code additionalProperties: false} with eleven required fields. An envelope missing them
     * does not fail the run — it triggers the corrective retry, so the router is called twice and
     * the test silently measures a retry instead of the pinned call it was written to check.
     *
     * <p>Everything is empty and the analyzer name matches the pinned contract's slug, because the
     * cross-check refuses an envelope answering as a different analyzer.
     */
    private static String pinnedEnvelope() {
        return "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"income\","
                + "\"reportMarkdown\":\"# R\",\"facts\":[],\"assumptions\":[],"
                + "\"warnings\":[],\"recommendations\":[],\"calculations\":[],"
                + "\"missingItems\":[],\"citations\":[],\"confidence\":0.9}";
    }


    // --- dashboard override of the base prompt ---

    @Test
    void publishedBasePromptOverrideReachesTheAnalyzePrompt() {
        when(prompts.withEffectivePrompt(eq(brainId), any()))
                .thenAnswer(inv -> ((AnalyzerConfig) inv.getArgument(1)).withBasePrompt("OVERRIDDEN INSTRUCTIONS"));

        AiRequest sent = captureAnalyzeRequest("income");

        assertTrue(sent.prompt().startsWith("OVERRIDDEN INSTRUCTIONS\n\n"));
        assertFalse(sent.prompt().contains("Analyze income."));
    }

    @Test
    void publishedBasePromptOverrideReachesTheRefinePrompt() {
        when(prompts.withEffectivePrompt(eq(brainId), any()))
                .thenAnswer(inv -> ((AnalyzerConfig) inv.getArgument(1)).withBasePrompt("OVERRIDDEN INSTRUCTIONS"));
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(RetrievalResult.empty());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(
                        "{\"reportMarkdown\":\"# R\",\"findings\":{},\"citations\":[]}",
                        "anthropic", "claude-x", 10, 5), false));

        service.refine(brainId, "income", new RefineRequest(ctx(), null, "# Prior", List.of()));

        ArgumentCaptor<AiRequest> req = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(req.capture(), eq(brainId));
        assertTrue(req.getValue().prompt().startsWith("OVERRIDDEN INSTRUCTIONS\n\n"));
    }

    @Test
    void assemblySkeletonUsesTheGivenBasePromptAndNeverCallsAModel() {
        List<AnalysisPromptAssembler.PromptSection> sections =
                service.assembly(brainId, "income", "DRAFT TEXT");

        assertEquals("DRAFT TEXT\n\n", sections.get(0).text());
        assertEquals("output-contract", sections.get(sections.size() - 1).id());
        verify(router, never()).generate(any(), any());
        verify(retrieval, never()).retrieveAdmin(anyString(), any(), any(), any());
        assertThrows(AnalyzerNotFoundException.class, () -> service.assembly(brainId, "nope", "x"));
    }

    private static ModelRouterService.SanitizedResponse sanitized() {
        return new ModelRouterService.SanitizedResponse(
                new AiResponse(pinnedEnvelope(), "anthropic", "claude-pinned", 100, 50),
                new ModelRouterService.Resolution("anthropic", "claude-pinned", true, "anthropic",
                        "claude-pinned", "anthropic", "claude-pinned", false));
    }
}
