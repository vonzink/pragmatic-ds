package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.provider.AiResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Statelessness / PII guard (spec §6): the analyze path must never persist document
 * bytes or extracted text. AnalysisTraceService is metadata-only by construction —
 * it holds NO repository or corpus collaborators, so there is no code path that could
 * write doc content. This test fails loudly if someone later injects a persistence
 * dependency into the trace service (the seam where a content leak would appear).
 */
class AnalyzeStatelessnessTest {

    @Test
    void traceServiceHoldsNoPersistenceCollaborators() {
        for (Field f : AnalysisTraceService.class.getDeclaredFields()) {
            String type = f.getType().getName().toLowerCase();
            assertFalse(type.contains("repository"),
                    "AnalysisTraceService must not hold a repository (statelessness): " + f);
            assertFalse(type.contains("entitymanager"),
                    "AnalysisTraceService must not hold an EntityManager: " + f);
        }
    }

    @Test
    void traceRecordAcceptsOnlyMetadataAndDoesNotThrowWithNoContent() {
        AnalysisTraceService trace = new AnalysisTraceService();
        AnalysisContext ctx = new AnalysisContext(
                List.of(new AnalysisContext.DocMeta("d1", "p.png", "image/png", 3L)),
                null, null, null, List.of(), false);
        DocumentBlockService.BuildResult built =
                new DocumentBlockService.BuildResult(List.of(), 0, List.of());
        AiResponse resp = new AiResponse("irrelevant", "anthropic", "claude-x", 10, 5);
        // Must run purely on metadata (ids/count/pages/tokens) — no bytes touched, no throw.
        assertDoesNotThrow(() -> trace.record(UUID.randomUUID(), "income", ctx, built, resp, 1, 10, 5, "SUCCESS"));
    }
}
