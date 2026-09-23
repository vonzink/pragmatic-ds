package com.pragmaticds.rag.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankerServiceTest {

    private final RerankerService reranker =
            new RerankerService(null, new ObjectMapper());

    private static ModelRouterService.RoutedResponse routed(String content) {
        return new ModelRouterService.RoutedResponse(
                new AiResponse(content, "anthropic", "claude", 10, 5), false);
    }

    private static RetrievedChunk chunk(String externalDocId, String content) {
        return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), content,
                null, null, null, "Source", "AGENCY_GUIDELINE", "doc.md", "Doc",
                null, null, null, 0.5, 0.5, 0.5, externalDocId, "a".repeat(64));
    }

    @Test
    void rerankPreservesExternalDocId() {
        // Rerank rebuilds every chunk, so a dropped field here would blank the
        // eval join key for the whole pipeline — rerank is on by default.
        ModelRouterService router = mock(ModelRouterService.class);
        when(router.generate(any(), any())).thenReturn(routed("[{\"index\":0,\"score\":3},{\"index\":1,\"score\":9}]"));

        List<RetrievedChunk> out = new RerankerService(router, new ObjectMapper()).rerank(
                "where is the pipeline board?",
                List.of(chunk("suite_page_reports", "Reports live here."),
                        chunk("suite_page_pipeline_board", "The pipeline board lists loans.")),
                2, UUID.randomUUID());

        assertEquals(2, out.size());
        assertEquals("suite_page_pipeline_board", out.getFirst().externalDocId(),
                "highest-scored chunk should rank first and keep its id");
        assertEquals("suite_page_reports", out.get(1).externalDocId());
        assertEquals("a".repeat(64), out.getFirst().contentSha256(),
                "reranking must preserve the snapshot evidence hash");
    }

    @Test
    void parsesCleanScoreArray() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":7},{\"index\":1,\"score\":2},{\"index\":2,\"score\":10}]", 3);
        assertArrayEquals(new double[]{7, 2, 10}, scores);
    }

    @Test
    void parsesArrayWrappedInProse() {
        double[] scores = reranker.parseScores(
                "Here are the scores:\n[{\"index\":0,\"score\":5},{\"index\":1,\"score\":9}]\nDone.", 2);
        assertArrayEquals(new double[]{5, 9}, scores);
    }

    @Test
    void clampsOutOfRangeScores() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":15},{\"index\":1,\"score\":-3}]", 2);
        assertArrayEquals(new double[]{10, 0}, scores);
    }

    @Test
    void ignoresOutOfBoundsIndexes() {
        double[] scores = reranker.parseScores(
                "[{\"index\":0,\"score\":6},{\"index\":99,\"score\":9}]", 2);
        assertEquals(6, scores[0]);
        assertEquals(0, scores[1]);
    }

    // When a retrieved chunk carries parent-section context, the actual answer
    // sits after the "Focused retrieved chunk:" marker — often past the excerpt
    // window the reranker sees. Scoring must judge the focused child, not the
    // generic parent preamble (which scored 0 and collapsed confidence).
    @Test
    void excerptSourceUsesFocusedChildNotParentPreamble() {
        String assembled = "Parent section context:\n"
                + "generic section preamble text ".repeat(40)
                + "\n\nFocused retrieved chunk:\n"
                + "New loans are created from the pipeline with Create.";

        String excerptSource = RerankerService.excerptSource(assembled);

        assertTrue(excerptSource.startsWith("New loans are created"),
                "Excerpt should start at the focused child content");
        assertFalse(excerptSource.contains("generic section preamble"),
                "Excerpt must not spend its window on parent preamble");
    }

    @Test
    void excerptSourceReturnsPlainContentUnchanged() {
        String plain = "A focused chunk with no parent-context prefix at all.";
        assertEquals(plain, RerankerService.excerptSource(plain));
    }

    @Test
    void returnsNullForGarbage() {
        assertNull(reranker.parseScores("I cannot rank these.", 3));
        assertNull(reranker.parseScores("", 3));
    }

    // A malformed/empty array must fail open to the original ranking, NOT
    // silently score every candidate 0 (which collapses retrieval confidence
    // to 0.0 and forces a false "no source" refusal).

    @Test
    void returnsNullForEmptyArray() {
        assertNull(reranker.parseScores("[]", 3));
    }

    @Test
    void returnsNullWhenNoEntryHasAUsableIndex() {
        assertNull(reranker.parseScores("[{\"score\":5},{\"relevance\":9}]", 2));
    }

    @Test
    void returnsNullWhenEveryIndexIsOutOfBounds() {
        assertNull(reranker.parseScores("[{\"index\":7,\"score\":9},{\"index\":8,\"score\":4}]", 2));
    }
}
