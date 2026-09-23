package com.pragmaticds.rag.lab.release;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, fully-pinned release contract for an instance that is eligible for promotion.
 *
 * <p>Version 2 deliberately describes executable dependencies rather than observing mutable
 * runtime configuration. Every collection here is defensively copied at the boundary so a
 * caller cannot change a release after its digest has been calculated.
 */
public record InstanceReleaseManifest(
        int manifestVersion,
        ParsedDataContract parsedData,
        ModelContract model,
        CorpusContract corpus,
        BehaviorContract behavior,
        List<ToolContract> tools,
        OutputContract output,
        LimitContract limits,
        EvaluationContract evaluations) {

    public InstanceReleaseManifest {
        if (manifestVersion != 2) {
            throw new IllegalArgumentException("manifestVersion must be 2");
        }
        parsedData = Objects.requireNonNull(parsedData, "parsedData");
        model = Objects.requireNonNull(model, "model");
        corpus = Objects.requireNonNull(corpus, "corpus");
        behavior = Objects.requireNonNull(behavior, "behavior");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        output = Objects.requireNonNull(output, "output");
        limits = Objects.requireNonNull(limits, "limits");
        evaluations = Objects.requireNonNull(evaluations, "evaluations");
    }

    public record ParsedDataContract(
            String envelopeVersion,
            String canonicalizationVersion,
            Set<String> allowedDocumentTypes,
            Set<String> requireAnyDocumentTypes,
            int minimumSupportedDocuments,
            ReviewPolicy reviewRequired,
            MissingFieldPolicy missingFields) {
        public ParsedDataContract {
            allowedDocumentTypes = Set.copyOf(Objects.requireNonNull(allowedDocumentTypes,
                    "allowedDocumentTypes"));
            requireAnyDocumentTypes = Set.copyOf(Objects.requireNonNull(requireAnyDocumentTypes,
                    "requireAnyDocumentTypes"));
        }
    }

    public record ModelContract(String provider, String model, FallbackPolicy fallbackPolicy) {}

    public record CollectionRef(UUID collectionId, long collectionVersion) {}

    public record CorpusContract(List<CollectionRef> collections) {
        public CorpusContract {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
        }
    }

    public record BehaviorContract(
            String systemPrompt, String taskPrompt, String retrievalQuery, BigDecimal temperature) {}

    public record ToolContract(
            String name, String version, String inputSchemaSha256, String outputSchemaSha256) {}

    public record OutputContract(String schemaId, String schemaSha256) {}

    public record LimitContract(
            long maximumInputTokens,
            int maximumRetrievedTokens,
            int maximumOutputTokens,
            int maximumDiscussionTokens,
            int maximumConcurrentRuns,
            BigDecimal maximumExpectedCostUsd) {}

    public record EvaluationContract(
            String scenarioSetId, int scenarioSetVersion, BigDecimal minimumScore) {}

    public enum ReviewPolicy { WARN, REJECT }
    public enum MissingFieldPolicy { PRESERVE, REJECT }
    public enum FallbackPolicy { NONE, CONFIGURED }
}
