package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.BrainSetting;
import com.pragmaticds.rag.repository.BrainSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingsTest {

    private final BrainSettingRepository repository = mock(BrainSettingRepository.class);

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.Analyze(null),
            new RagProperties.RateLimit(10, 60, 120));

    private RuntimeSettings settings() {
        return new RuntimeSettings(repository, props);
    }

    // --- per-analyzer model overrides (analyzer.<slug>.provider / .model) ---

    /** With no row stored, the analyzer's own pack pair is used unchanged. */
    @Test
    void analyzerPairFallsBackToThePackWhenUnset() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();

        assertNull(s.analyzerProvider("assets-v2"));
        assertNull(s.analyzerModel("assets-v2"));
    }

    @Test
    void storedAnalyzerPairIsReturned() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("analyzer.assets-v2.provider", "grok", "admin"),
                new BrainSetting("analyzer.assets-v2.model", "grok-4.3", "admin")));
        RuntimeSettings s = settings();

        assertEquals("grok", s.analyzerProvider("assets-v2"));
        assertEquals("grok-4.3", s.analyzerModel("assets-v2"));
        // Another analyzer is unaffected — keys are per-slug.
        assertNull(s.analyzerProvider("income-v2"));
    }

    @Test
    void emptyTableFallsBackToEnvDefaults() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();

        assertEquals("anthropic", s.answerProvider());
        assertNull(s.answerModel(), "unset model means provider default");
        assertEquals("anthropic", s.utilityProvider(), "utility falls back to answer");
        assertNull(s.utilityModel());
        assertEquals(0.35, s.confidenceThreshold());
        assertEquals(8, s.topK());
        assertTrue(s.rerankEnabled());
    }

    @Test
    void storedValuesWinOverDefaults() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("answer.provider", "openai", "t"),
                new BrainSetting("answer.model", "gpt-4.1-nano", "t"),
                new BrainSetting("retrieval.top-k", "12", "t"),
                new BrainSetting("rerank.enabled", "false", "t")));
        RuntimeSettings s = settings();

        assertEquals("openai", s.answerProvider());
        assertEquals("gpt-4.1-nano", s.answerModel());
        assertEquals("openai", s.utilityProvider(), "utility inherits stored answer values");
        assertEquals("gpt-4.1-nano", s.utilityModel());
        assertEquals(12, s.topK());
        assertEquals(false, s.rerankEnabled());
    }

    @Test
    void utilityOverridesAreIndependentOfAnswer() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("utility.provider", "openai", "t"),
                new BrainSetting("utility.model", "gpt-4.1-nano", "t")));
        RuntimeSettings s = settings();

        assertEquals("anthropic", s.answerProvider());
        assertEquals("openai", s.utilityProvider());
        assertEquals("gpt-4.1-nano", s.utilityModel());
    }

    @Test
    void cachesRepositoryReadsWithinTtl() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.answerProvider();
        s.topK();
        s.rerankEnabled();
        verify(repository, times(1)).findAll();
    }

    @Test
    void putWritesAndInvalidatesCache() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.answerProvider(); // prime cache

        s.put("retrieval.top-k", "5", "admin-api");

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(b ->
                b.getKey().equals("retrieval.top-k") && b.getValue().equals("5")
                        && b.getUpdatedBy().equals("admin-api")));
        s.topK(); // must re-read after invalidation
        verify(repository, times(2)).findAll();
    }

    @Test
    void clearDeletesTheOverride() {
        when(repository.findAll()).thenReturn(List.of());
        RuntimeSettings s = settings();
        s.clear("retrieval.top-k");
        verify(repository).deleteById("retrieval.top-k");
    }

    @Test
    void malformedNumericFallsBackToDefaultInsteadOfThrowing() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("retrieval.top-k", "not-a-number", "t")));
        RuntimeSettings s = settings();
        assertEquals(8, s.topK(), "bad stored value must not take down the ask path");
    }

    @Test
    void overridesSnapshotExposesStoredRowsOnly() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("answer.model", "m", "t")));
        RuntimeSettings s = settings();
        assertEquals(java.util.Map.of("answer.model", "m"), s.overrides());
    }

    @Test
    void utilityModelDoesNotCrossProviders() {
        when(repository.findAll()).thenReturn(List.of(
                new BrainSetting("answer.model", "claude-haiku-4-5", "t"),
                new BrainSetting("utility.provider", "openai", "t")));
        RuntimeSettings s = settings();

        assertEquals("claude-haiku-4-5", s.answerModel());
        assertEquals("openai", s.utilityProvider());
        assertNull(s.utilityModel(),
                "an answer-lane model name must never be inherited across providers");
    }
}
