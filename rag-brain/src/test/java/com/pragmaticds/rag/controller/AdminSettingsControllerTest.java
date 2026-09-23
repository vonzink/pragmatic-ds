package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTest {

    private final RuntimeSettings settings = mock(RuntimeSettings.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AdminSettingsController controller =
            new AdminSettingsController(settings, router);

    private void stubAllSettings() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        when(settings.answerProvider()).thenReturn("anthropic");
        when(settings.answerModel()).thenReturn(null);
        when(settings.utilityProvider()).thenReturn("anthropic");
        when(settings.utilityModel()).thenReturn(null);
        when(settings.confidenceThreshold()).thenReturn(0.35);
        when(settings.topK()).thenReturn(8);
        when(settings.rerankEnabled()).thenReturn(true);
        when(settings.overrides()).thenReturn(Map.of());
    }

    @Test
    void getReturnsEffectiveValuesAndOverrides() {
        stubAllSettings();

        Map<String, Object> body = controller.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> effective = (Map<String, Object>) body.get("effective");
        assertEquals("anthropic", effective.get("answer.provider"));
        assertEquals(8, effective.get("retrieval.top-k"));
        assertEquals(Map.of(), body.get("overrides"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReturnsProvidersBlockInCatalogOrder() {
        stubAllSettings();
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai", "deepseek"));

        Map<String, Object> body = controller.get();

        List<Map<String, Object>> known = (List<Map<String, Object>>) body.get("providers");
        assertEquals(6, known.size());
        assertEquals("anthropic", known.get(0).get("name"));
        assertEquals(true,        known.get(0).get("configured"));
        assertEquals("openai",    known.get(1).get("name"));
        assertEquals(true,        known.get(1).get("configured"));
        assertEquals("deepseek",  known.get(2).get("name"));
        assertEquals(true,        known.get(2).get("configured"));
        assertEquals("gemini",    known.get(3).get("name"));
        assertEquals(false,       known.get(3).get("configured"));
        assertEquals("grok",      known.get(4).get("name"));
        assertEquals(false,       known.get(4).get("configured"));
        assertEquals("local",     known.get(5).get("name"));
        assertEquals(false,       known.get(5).get("configured"));
    }

    @Test
    void putValidatesProviderAgainstRegistry() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("answer.provider", "gemini")));
        verify(settings, never()).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void putRejectsUnknownKeysAndBadNumbers() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("nope.key", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.top-k", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.confidence-threshold", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("rerank.enabled", "maybe")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("answer.model", "x".repeat(201))));
    }

    @Test
    void putWritesValidEntriesAndBlankClearsTheOverride() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));

        controller.put(new java.util.LinkedHashMap<>(Map.of(
                "answer.model", "claude-haiku-4-5",
                "retrieval.top-k", "12")));
        verify(settings).put("answer.model", "claude-haiku-4-5", "admin-api");
        verify(settings).put("retrieval.top-k", "12", "admin-api");

        controller.put(Map.of("answer.model", ""));
        verify(settings).clear("answer.model");
    }
}
