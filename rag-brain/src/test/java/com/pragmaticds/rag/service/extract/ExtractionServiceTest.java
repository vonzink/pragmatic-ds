package com.pragmaticds.rag.service.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.ExtractorConfig;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.ModelRouterService.RoutedResponse;
import com.pragmaticds.rag.service.analyze.DocInput;
import com.pragmaticds.rag.service.analyze.DocumentBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stubs ONLY at the ModelRouterService boundary (the AnalysisServiceTest pattern) —
 * DomainPackRegistry and DocumentBlockService are mocked collaborators, never the
 * router's own provider-resolution internals.
 */
class ExtractionServiceTest {

    private final UUID brainId = UUID.randomUUID();
    private DomainPackRegistry registry;
    private ModelRouterService router;
    private DocumentBlockService blocks;
    private DomainPack pack;
    private ExtractionService service;

    private final ExtractorConfig scheduleC = new ExtractorConfig(
            "income-schedule-c", "Schedule C", "Extract Schedule C values.",
            List.of("netProfit", "depreciation"), List.of("taxYear", "businessName"), null);

    @BeforeEach
    void setUp() {
        registry = mock(DomainPackRegistry.class);
        router = mock(ModelRouterService.class);
        blocks = mock(DocumentBlockService.class);

        pack = mock(DomainPack.class);
        when(pack.extractors()).thenReturn(List.of(scheduleC));
        BrainPackBundle bundle = mock(BrainPackBundle.class);
        when(bundle.pack()).thenReturn(pack);
        when(registry.bundle(brainId)).thenReturn(bundle);

        service = new ExtractionService(registry, router, blocks, new ObjectMapper(), 2000);
    }

    private DocInput doc() {
        return new DocInput("d1", "sc.pdf", "application/pdf", new byte[]{1, 2, 3}, 1L);
    }

    private DocumentBlockService.DocBlock oneBlock() {
        Media m = Media.builder().mimeType(Media.Format.DOC_PDF)
                .data(new ByteArrayResource(new byte[]{1})).build();
        return new DocumentBlockService.DocBlock(m, 1);
    }

    @Test
    void happyPathReturnsOnlyManifestKeysTypedCorrectly() {
        when(blocks.buildOne(any(DocInput.class))).thenReturn(oneBlock());
        String json = "{\"taxYear\":2024,\"businessName\":\"Acme LLC\",\"netProfit\":100000,\"depreciation\":2000}";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(json, "anthropic", "claude-x", 100, 50), false));

        ExtractionResult r = service.extract(brainId, "income-schedule-c", doc());

        assertEquals(ExtractionResult.Status.SUCCESS, r.status());
        assertEquals("anthropic", r.provider());
        assertEquals("claude-x", r.model());
        assertEquals(100, r.inputTokens());
        assertEquals(50, r.outputTokens());
        assertEquals(100000.0, r.values().get("netProfit"));
        assertEquals(2000.0, r.values().get("depreciation"));
        assertEquals("Acme LLC", r.values().get("businessName"));
        assertEquals("2024", r.values().get("taxYear"), "meta keys are kept as strings");
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void nonJsonModelResponseIsErrorNeverThrows() {
        when(blocks.buildOne(any(DocInput.class))).thenReturn(oneBlock());
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(
                        new AiResponse("Sorry, I can't help with that.", "anthropic", "claude-x", 10, 5), false));

        ExtractionResult r = assertDoesNotThrow(() -> service.extract(brainId, "income-schedule-c", doc()));

        assertEquals(ExtractionResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        assertTrue(r.values().isEmpty());
    }

    @Test
    void unknownKeysAreDroppedWithAWarning() {
        when(blocks.buildOne(any(DocInput.class))).thenReturn(oneBlock());
        String json = "{\"taxYear\":2024,\"netProfit\":50000,\"totalIncome\":50000,\"randomField\":\"x\"}";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(json, "anthropic", "claude-x", 10, 5), false));

        ExtractionResult r = service.extract(brainId, "income-schedule-c", doc());

        assertEquals(ExtractionResult.Status.SUCCESS, r.status());
        assertFalse(r.values().containsKey("totalIncome"), "totalIncome is not in the manifest, must be dropped");
        assertFalse(r.values().containsKey("randomField"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("totalIncome")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("randomField")));
    }

    @Test
    void unknownExtractorSlugThrowsExtractorNotFound() {
        assertThrows(ExtractorNotFoundException.class,
                () -> service.extract(brainId, "nope", doc()));
        verifyNoInteractions(blocks);
    }

    @Test
    void commaAndDollarFormattedNumericStringsAreCoerced() {
        when(blocks.buildOne(any(DocInput.class))).thenReturn(oneBlock());
        String json = "{\"taxYear\":2024,\"netProfit\":\"$1,234\",\"depreciation\":\"1,234\"}";
        when(router.generate(any(AiRequest.class), eq(brainId)))
                .thenReturn(new RoutedResponse(new AiResponse(json, "anthropic", "claude-x", 10, 5), false));

        ExtractionResult r = service.extract(brainId, "income-schedule-c", doc());

        assertEquals(ExtractionResult.Status.SUCCESS, r.status());
        assertEquals(1234.0, r.values().get("netProfit"));
        assertEquals(1234.0, r.values().get("depreciation"));
    }

    @Test
    void unbuildableDocumentIsErrorNeverThrows() {
        when(blocks.buildOne(any(DocInput.class))).thenReturn(null);

        ExtractionResult r = assertDoesNotThrow(() -> service.extract(brainId, "income-schedule-c", doc()));

        assertEquals(ExtractionResult.Status.ERROR, r.status());
        assertNotNull(r.reason());
        verifyNoInteractions(router);
    }
}
