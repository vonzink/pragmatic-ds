package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.dto.RefineRequest;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.ModelRouterService.RoutedResponse;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import com.pragmaticds.rag.service.analyze.calc.CalculationExecutor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the exact prompt strings the analyze and refine paths send. On a mismatch or a missing
 * fixture the actual text is written to build/golden-actual/ so it can be inspected and, when
 * the change is intended, copied over the fixture under src/test/resources.
 */
class AnalysisServicePromptGoldenTest {

    private static final Path FIXTURES = Path.of("src/test/resources/golden/analyzer-prompt");
    private static final Path ACTUAL = Path.of("build/golden-actual");

    private final UUID brainId = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private ModelRouterService router;
    private RetrievalService retrieval;
    private DocumentBlockService blocks;
    private DomainPack pack;
    private AnalysisService service;

    private final AnalyzerConfig incomeV1 = new AnalyzerConfig(
            "income", "Income", "BASE PROMPT V1 — analyze the income folder.",
            "income guidelines", 8, "{\"borrowers\":[]}", null, "income");
    private final AnalyzerConfig incomeV2 = new AnalyzerConfig(
            "income-v2", "Income (v2)", "BASE PROMPT V2 — request calculations, never compute.",
            "income guidelines", 8, "{\"borrowers\":[]}", null, "income", "v2");

    @BeforeEach
    void setUp() {
        DomainPackRegistry registry = mock(DomainPackRegistry.class);
        router = mock(ModelRouterService.class);
        retrieval = mock(RetrievalService.class);
        blocks = mock(DocumentBlockService.class);
        RuntimeSettings settings = mock(RuntimeSettings.class);
        pack = mock(DomainPack.class);
        when(pack.analyzers()).thenReturn(List.of(incomeV1, incomeV2));
        when(pack.disclaimer()).thenReturn("Educational only.");
        BrainPackBundle bundle = mock(BrainPackBundle.class);
        when(bundle.pack()).thenReturn(pack);
        when(registry.bundle(brainId)).thenReturn(bundle);
        ObjectMapper mapper = new ObjectMapper();
        AnalyzerPromptService prompts = mock(AnalyzerPromptService.class);
        when(prompts.withEffectivePrompt(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new AnalysisService(registry, router, retrieval, blocks,
                new EnvelopeValidator(), new CalculationExecutor(new IncomeCalcService(), mapper),
                new AssetsCalcService(), new AssetsReportRenderer(),
                new IncomeDomainEnricher(), new IncomeWorksheetRenderer(),
                new AnswerCitationService(),
                mapper, mock(AnalysisTraceService.class), mock(AnalysisRunRecorder.class),
                prompts, settings, 20000);
        when(retrieval.retrieveAdmin(anyString(), eq(brainId), any(), any()))
                .thenReturn(new RetrievalResult(chunks(), 0.8, true));
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(
                        "{\"reportMarkdown\":\"# R\",\"findings\":{},\"citations\":[]}",
                        "anthropic", "claude-x", 10, 5), false));
    }

    private static List<RetrievedChunk> chunks() {
        UUID c1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID c2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID d1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
        return List.of(
                new RetrievedChunk(c1, d1, "Base pay is averaged over the YTD period.",
                        "Fannie Mae Selling Guide", "official", "B3-3.1-01", "Income", "B3-3.1-01", 12,
                        LocalDate.of(2026, 1, 1), 0.9, 0.5, 0.8),
                new RetrievedChunk(c2, d1, "Overtime needs a 12-month history.",
                        "Fannie Mae Selling Guide", "official", "B3-3.1-03", "Income", "B3-3.1-03", 14,
                        LocalDate.of(2026, 1, 1), 0.8, 0.4, 0.7));
    }

    private static DocumentBlockService.BuildResult built() {
        Media m = Media.builder().mimeType(Media.Format.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1})).build();
        return new DocumentBlockService.BuildResult(
                List.of(new DocumentBlockService.DocBlock("d1", m, 2)),
                2,
                List.of(new SkippedDoc("d3", "broken.pdf", SkipCategory.UNREADABLE_PDF, "no text layer")),
                List.of(new FilteredDoc("d1", "return.pdf", 40, 6, List.of("1040", "Schedule C"))),
                List.of(new DocumentBlockService.TextDoc("d2", "text/markdown", "# W-2\nWages: 84,000\n")));
    }

    private static AnalysisContext ctx() {
        ObjectMapper mapper = new ObjectMapper();
        return new AnalysisContext(
                List.of(
                        new AnalysisContext.DocMeta("d1", "return.pdf", "application/pdf", 4096L,
                                true, "md/1", "| field | value |\n| wages | 84000 |\n",
                                "TAX_RETURN", "engine", 0.93,
                                List.of(new AnalysisContext.EngineSegment(1, 2, "1040", 0.91))),
                        new AnalysisContext.DocMeta("d2", "w2.md", "text/markdown", 64L),
                        new AnalysisContext.DocMeta("d3", "broken.pdf", "application/pdf", 10L)),
                "Focus on the primary borrower.",
                new AnalysisContext.LoanSnapshot(400000.0, 500000.0, List.of("Jane Doe"), 8000.0,
                        mapper.createObjectNode().put("totalMonthly", 8000)),
                new AnalysisContext.OrgCatalog(
                        List.of(new AnalysisContext.OrgCatalog.Folder("t1", "Income")),
                        List.of(new AnalysisContext.OrgCatalog.DocType("PAYSTUB", "Paystub"))),
                List.of(new AnalysisContext.AnalystNote("d1", "Borrower changed jobs in March.", "LO")),
                false);
    }

    private static DocInput bytesFor(String id) {
        return new DocInput(id, id + ".png", "image/png", new byte[]{1, 2, 3}, 100);
    }

    private String captureAnalyze(String slug) {
        when(blocks.build(anyList(), isNull())).thenReturn(built());
        service.analyze(brainId, slug, List.of(bytesFor("d1")), ctx());
        ArgumentCaptor<AiRequest> req = ArgumentCaptor.forClass(AiRequest.class);
        verify(router, atLeastOnce()).generate(req.capture(), eq(brainId));
        return req.getAllValues().get(0).prompt();
    }

    private String captureRefine() {
        RefineRequest req = new RefineRequest(ctx(),
                new ObjectMapper().createObjectNode().put("total", 7900),
                "# Prior report\nTotal 7,900",
                List.of(new RefineRequest.ChatTurn("user", "Recheck overtime."),
                        new RefineRequest.ChatTurn("assistant", "Will do.")));
        service.refine(brainId, "income", req);
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(router).generate(captor.capture(), eq(brainId));
        return captor.getValue().prompt();
    }

    private static void assertGolden(String name, String actual) throws IOException {
        Path fixture = FIXTURES.resolve(name + ".txt");
        Files.createDirectories(ACTUAL);
        Path out = ACTUAL.resolve(name + ".txt");
        Files.writeString(out, actual, StandardCharsets.UTF_8);
        if (!Files.exists(fixture)) {
            fail("Missing golden fixture " + fixture + "; actual written to " + out
                    + ". Copy it into place if this is the intended baseline.");
        }
        assertEquals(Files.readString(fixture, StandardCharsets.UTF_8), actual,
                "Prompt drifted from " + fixture + "; actual written to " + out);
    }

    @Test
    void analyzeV1PromptIsPinned() throws IOException {
        assertGolden("analyze-v1", captureAnalyze("income"));
    }

    @Test
    void analyzeV2PromptIsPinned() throws IOException {
        assertGolden("analyze-v2", captureAnalyze("income-v2"));
    }

    @Test
    void refineV1PromptIsPinned() throws IOException {
        assertGolden("refine-v1", captureRefine());
    }
}
