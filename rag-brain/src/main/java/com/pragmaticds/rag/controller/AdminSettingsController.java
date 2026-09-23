package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live operational settings (models per purpose, retrieval knobs). Protected
 * by AdminApiKeyFilter. Values validate before any write; a blank value
 * clears the override so the env default applies again.
 */
@RestController
@RequestMapping("/api/ai/admin/settings")
public class AdminSettingsController {

    private static final List<String> KNOWN_PROVIDERS =
            List.of("anthropic", "openai", "deepseek", "gemini", "grok", "local");

    private static final Set<String> PROVIDER_KEYS = Set.of("answer.provider", "utility.provider", "analyze.provider");
    private static final Set<String> MODEL_KEYS = Set.of("answer.model", "utility.model", "analyze.model");

    // Per-analyzer overrides, e.g. analyzer.assets-v2.provider / analyzer.assets-v2.model.
    // The slug pattern matches DomainPackLoader's own analyzer-slug rule.
    private static final java.util.regex.Pattern ANALYZER_PROVIDER_KEY =
            java.util.regex.Pattern.compile("analyzer\\.[a-z0-9-]+\\.provider");
    private static final java.util.regex.Pattern ANALYZER_MODEL_KEY =
            java.util.regex.Pattern.compile("analyzer\\.[a-z0-9-]+\\.model");
    private static final String THRESHOLD_KEY = "retrieval.confidence-threshold";
    private static final String TOP_K_KEY = "retrieval.top-k";
    private static final String RERANK_KEY = "rerank.enabled";

    private final RuntimeSettings settings;
    private final ModelRouterService router;

    public AdminSettingsController(RuntimeSettings settings, ModelRouterService router) {
        this.settings = settings;
        this.router = router;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("answer.provider", settings.answerProvider());
        effective.put("answer.model", settings.answerModel());
        effective.put("utility.provider", settings.utilityProvider());
        effective.put("utility.model", settings.utilityModel());
        // Unlike utility.provider/model (always resolved against a concrete default),
        // a null analyze.provider/model here means the analyze lane inherits the
        // answer lane wholesale — this mirrors resolve()'s ANALYZE fallthrough.
        effective.put("analyze.provider", settings.analyzeProvider());
        effective.put("analyze.model", settings.analyzeModel());
        effective.put("retrieval.confidence-threshold", settings.confidenceThreshold());
        effective.put("retrieval.top-k", settings.topK());
        effective.put("rerank.enabled", settings.rerankEnabled());

        Set<String> configured = router.providerNames();
        List<Map<String, Object>> providers = KNOWN_PROVIDERS.stream()
                .map(n -> Map.<String, Object>of("name", n, "configured", configured.contains(n)))
                .toList();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("effective", effective);
        envelope.put("overrides", settings.overrides());
        envelope.put("providers", providers);
        return envelope;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, String> changes) {
        // Validate everything first — a request either fully applies or not at all.
        for (Map.Entry<String, String> change : changes.entrySet()) {
            validate(change.getKey(), change.getValue());
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (change.getValue() == null || change.getValue().isBlank()) {
                settings.clear(change.getKey());
            } else {
                settings.put(change.getKey(), change.getValue().strip(), "admin-api");
            }
        }
        return get();
    }

    private void validate(String key, String value) {
        boolean known = PROVIDER_KEYS.contains(key) || MODEL_KEYS.contains(key)
                || ANALYZER_PROVIDER_KEY.matcher(key).matches()
                || ANALYZER_MODEL_KEY.matcher(key).matches()
                || THRESHOLD_KEY.equals(key) || TOP_K_KEY.equals(key) || RERANK_KEY.equals(key);
        if (!known) {
            throw new IllegalArgumentException("Unknown setting key: " + key);
        }
        if (value == null || value.isBlank()) {
            return; // blank = clear the override; always valid
        }
        String v = value.strip();
        boolean isProviderKey = PROVIDER_KEYS.contains(key) || ANALYZER_PROVIDER_KEY.matcher(key).matches();
        if (isProviderKey && !router.providerNames().contains(v)) {
            throw new IllegalArgumentException("Unknown provider '" + v
                    + "'. Registered: " + router.providerNames());
        }
        boolean isModelKey = MODEL_KEYS.contains(key) || ANALYZER_MODEL_KEY.matcher(key).matches();
        if (isModelKey && v.length() > 200) {
            throw new IllegalArgumentException(key + " must be 200 characters or fewer");
        }
        if (THRESHOLD_KEY.equals(key)) {
            double d = parseDouble(key, v);
            if (d < 0.0 || d > 1.0) {
                throw new IllegalArgumentException(key + " must be between 0 and 1");
            }
        }
        if (TOP_K_KEY.equals(key)) {
            int i = parseInt(key, v);
            if (i < 1 || i > 50) {
                throw new IllegalArgumentException(key + " must be between 1 and 50");
            }
        }
        if (RERANK_KEY.equals(key) && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
    }

    private double parseDouble(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'");
        }
    }

    private int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'");
        }
    }
}
