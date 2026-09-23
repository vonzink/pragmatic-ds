package com.pragmaticds.rag.pack;

import java.util.List;

/**
 * One folder-brain analyzer, declared in a pack's optional analyzers.yaml.
 * Immutable; travels/versions with the pack. See docs/superpowers/specs/
 * 2026-07-06-folder-brains-ragbrain-rebase-design.md §"rag-brain engine changes".
 *
 * @param slug                   analyzer id (income|assets|title|documents); [a-z0-9-]+
 * @param displayName            human label for the console card
 * @param basePrompt             the analyzer's instruction block (rendered into the prompt)
 * @param retrievalQueryTemplate hybrid-search query text; null ⇒ NO retrieval (classifier)
 * @param retrievalTopK          how many guideline chunks to retrieve (ignored when template null)
 * @param outputSchema           findings JSON shape, rendered verbatim into the prompt
 * @param modelOverride          optional model id override, only honoured when paired
 *                               with providerOverride; null ⇒ brain default
 * @param providerOverride       optional provider for modelOverride (anthropic|openai|
 *                               deepseek|gemini|grok|local). A model id is only valid for
 *                               its own provider, so the two travel together or not at
 *                               all. When the named provider has no API key configured
 *                               the run falls back to the analyze lane and says so in
 *                               the response's provider/model fields.
 * @param corpusScope            optional corpus scope; retrieval sees this scope + shared docs. null ⇒ whole corpus
 * @param envelope               prompt-envelope version: null (⇒ v1), "v1", or "v2"
 * @param pageSelectionProfile   optional page-selection profile name from the pack's
 *                               page-selection.yaml; null ⇒ send every page (no filtering)
 * @param assetsRules            optional deterministic rules applied to a transcribed
 *                               asset ledger; null ⇒ analyzer applies no ledger rules
 * @param instanceSlugs          Lab instance slugs that run under this analyzer's rules. A
 *                               pinned instance run looks its analyzer up by instance slug, so
 *                               an instance named differently from the analyzer (asset-analysis
 *                               for assets-v2) finds it only through this list. Never null.
 */
public record AnalyzerConfig(
        String slug,
        String displayName,
        String basePrompt,
        String retrievalQueryTemplate,
        int retrievalTopK,
        String outputSchema,
        String modelOverride,
        String providerOverride,
        String corpusScope,
        String envelope,
        String pageSelectionProfile,
        AssetsRules assetsRules,
        List<String> instanceSlugs
) {

    public AnalyzerConfig {
        instanceSlugs = instanceSlugs == null ? List.of() : List.copyOf(instanceSlugs);
    }

    /** Convenience constructor for analyzers no instance runs under by another name. */
    public AnalyzerConfig(String slug, String displayName, String basePrompt,
                          String retrievalQueryTemplate, int retrievalTopK,
                          String outputSchema, String modelOverride, String providerOverride,
                          String corpusScope, String envelope, String pageSelectionProfile,
                          AssetsRules assetsRules) {
        this(slug, displayName, basePrompt, retrievalQueryTemplate, retrievalTopK,
                outputSchema, modelOverride, providerOverride, corpusScope, envelope,
                pageSelectionProfile, assetsRules, List.of());
    }

    /** True when an instance with this slug runs under this analyzer. */
    public boolean answersTo(String instanceOrAnalyzerSlug) {
        return slug.equals(instanceOrAnalyzerSlug) || instanceSlugs.contains(instanceOrAnalyzerSlug);
    }

    /** Convenience constructor for analyzers with no assets rules. */
    public AnalyzerConfig(String slug, String displayName, String basePrompt,
                          String retrievalQueryTemplate, int retrievalTopK,
                          String outputSchema, String modelOverride, String corpusScope,
                          String envelope, String pageSelectionProfile) {
        this(slug, displayName, basePrompt, retrievalQueryTemplate, retrievalTopK,
                outputSchema, modelOverride, null, corpusScope, envelope,
                pageSelectionProfile, null);
    }

    /** Convenience constructor for analyzers with no page-selection profile. */
    public AnalyzerConfig(String slug, String displayName, String basePrompt,
                          String retrievalQueryTemplate, int retrievalTopK,
                          String outputSchema, String modelOverride, String corpusScope,
                          String envelope) {
        this(slug, displayName, basePrompt, retrievalQueryTemplate, retrievalTopK,
                outputSchema, modelOverride, null, corpusScope, envelope, null, null);
    }

    /** Convenience constructor for envelope-v1 analyzers (envelope = null). */
    public AnalyzerConfig(String slug, String displayName, String basePrompt,
                          String retrievalQueryTemplate, int retrievalTopK,
                          String outputSchema, String modelOverride, String corpusScope) {
        this(slug, displayName, basePrompt, retrievalQueryTemplate, retrievalTopK,
                outputSchema, modelOverride, null, corpusScope, null, null, null);
    }

    /** True only when this analyzer explicitly opts into the v2 envelope. */
    public boolean isV2() {
        return "v2".equals(envelope);
    }

    /** True when this analyzer's output schema declares the given domain marker, e.g. "submission-domain-v1". */
    public boolean declaresDomain(String marker) {
        return outputSchema != null && outputSchema.contains("\"schemaVersion\": \"" + marker + "\"");
    }

    /** A copy of this analyzer with a different instruction block; every other field is kept. */
    public AnalyzerConfig withBasePrompt(String newBasePrompt) {
        return new AnalyzerConfig(slug, displayName, newBasePrompt, retrievalQueryTemplate,
                retrievalTopK, outputSchema, modelOverride, providerOverride, corpusScope,
                envelope, pageSelectionProfile, assetsRules, instanceSlugs);
    }
}
