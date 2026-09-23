package com.pragmaticds.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for everything under ragbrain.rag.* in application.yml.
 * Keeps tuning knobs (retrieval weights, chunk sizes, thresholds) in one place.
 */
@ConfigurationProperties(prefix = "ragbrain.rag")
public record RagProperties(
        Routing routing,
        Retrieval retrieval,
        Chunking chunking,
        Storage storage,
        Admin admin,
        Analyze analyze,
        RateLimit rateLimit
) {

    public record Routing(
            String defaultProvider,
            String fallbackProvider
    ) {}

    public record Retrieval(
            int topK,
            int minResults,
            double confidenceThreshold,
            double vectorWeight,
            double keywordWeight,
            boolean rerankEnabled,
            int rerankCandidates,
            boolean authorityOrderingEnabled,
            double authorityTieBand
    ) {
        // DO NOT add a second constructor to this record. Spring Boot binds
        // @ConfigurationProperties records through the canonical constructor; a
        // second one makes the choice ambiguous, and binding then yields NULL for
        // this whole group instead of failing. The app boots healthy and NPEs on
        // the first retrieval request. That shipped to production on 2026-08-02
        // (a 7-arg convenience overload added for test ergonomics) and took down
        // /ask and every analyzer that retrieves guideline context.
        // RagPropertiesBindingTest is the regression guard. Tests needing this
        // record should build it with all components.
    }

    public record Chunking(
            int targetTokens,
            int maxTokens,
            int overlapTokens
    ) {}

    public record Storage(
            String path
    ) {}

    public record Admin(
            String apiKey
    ) {}

    public record Analyze(
            String apiKey
    ) {}

    public record RateLimit(
            int requestsPerMinute,
            int connectorRequestsPerMinute,
            int adminRequestsPerMinute
    ) {
        // Single constructor, deliberately — see the note on Retrieval. A second one
        // made this group bind to null too, which RateLimitFilter had been quietly
        // papering over with a hardcoded fallback, so the rate-limit settings in
        // application.yml (and their env overrides) never took effect.
    }
}
