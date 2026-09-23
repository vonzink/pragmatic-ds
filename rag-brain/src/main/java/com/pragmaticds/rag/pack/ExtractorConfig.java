package com.pragmaticds.rag.pack;

import java.util.List;

/**
 * One document extractor, declared in a pack's optional extractors.yaml.
 * Immutable; travels/versions with the pack. Sibling to {@link AnalyzerConfig}
 * but scoped to single-document, schema-guarded value extraction: no retrieval,
 * no output-schema prose — just a value/meta key manifest that
 * {@code ExtractionService} enforces against the model's JSON response.
 *
 * @param slug          extractor id (e.g. income-schedule-c); [a-z0-9-]+, unique per pack
 * @param displayName   human label for the console
 * @param basePrompt    the extractor's full instruction block (rendered into the prompt)
 * @param valueKeys     numeric value keys this extractor returns (required, non-empty)
 * @param metaKeys      metadata (string) keys this extractor returns (may be empty)
 * @param modelOverride optional model id override; null ⇒ brain default
 */
public record ExtractorConfig(
        String slug,
        String displayName,
        String basePrompt,
        List<String> valueKeys,
        List<String> metaKeys,
        String modelOverride
) {
    public ExtractorConfig {
        valueKeys = valueKeys == null ? List.of() : List.copyOf(valueKeys);
        metaKeys = metaKeys == null ? List.of() : List.copyOf(metaKeys);
    }
}
