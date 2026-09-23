package com.pragmaticds.rag.lab.release;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable snapshot of everything a prototype Income run executes against.
 *
 * <p>The manifest is deliberately split into two halves, because pretending they are the same
 * thing is how a reproducibility promise becomes a lie:
 *
 * <ul>
 *   <li>{@link Pinned} — the analyzer contract. A run receives these values <em>from the stored
 *       release</em>; editing the live pack afterwards cannot change what an existing release
 *       executes.
 *   <li>{@link ObservedInference} — the provider, model, and inference budget that were in
 *       effect when the snapshot was taken, plus every mutable input that produced them.
 *       {@code income-v2} declares no {@code (provider, model)} pair of its own, so its effective
 *       model is resolved at run time from {@code brain_settings}, the brain's answer columns, and
 *       the deployment default. Nothing here <em>pins</em> that choice — but because it is part of
 *       the hashed manifest, a swap changes the digest and surfaces as drift instead of silently
 *       changing what "the Income release" means.
 * </ul>
 *
 * <p>{@link PrototypeLimitations} names that gap out loud: the codes in
 * {@link #LIVE_DEPENDENCIES} are exactly the things this manifest does not freeze.
 *
 * <p>Configuration only — prompt text, schema text, method ids, provider names, counts. No
 * borrower data, no document content, no credential ever belongs in a manifest.
 */
public record LabReleaseManifest(
        String manifestVersion,
        String canonicalization,
        String instanceSlug,
        String analyzerSlug,
        Pinned pinned,
        ObservedInference observedInference,
        PrototypeLimitations prototypeLimitations) {

    /** The manifest schema version; a change here is itself drift. */
    public static final String MANIFEST_VERSION = "1.0.0";

    /** The single prototype limitation banner every Lab response carries. */
    public static final String PROTOTYPE_LIMITATIONS_CODE = "PROTOTYPE_LIVE_DEPENDENCIES";

    /**
     * Exactly what this manifest does <em>not</em> freeze, sorted so the list is itself stable.
     *
     * <ul>
     *   <li>{@code CALCULATOR_IMPLEMENTATION} — the method <em>ids</em> are pinned and verified at
     *       resolve time; the Java that implements them is live code.
     *   <li>{@code CORPUS_CONTENTS} — the retrieval query and scope are pinned; the guideline
     *       documents they retrieve are mutable.
     *   <li>{@code MODEL_INFERENCE_BEHAVIOR} — a provider's weights and decoding are not
     *       versioned; identical inputs need not produce identical output.
     *   <li>{@code MODEL_OUTPUT_TOKEN_BUDGET} — {@code ragbrain.rag.analyze.max-output-tokens} is
     *       deployment configuration read per run.
     *   <li>{@code MODEL_PROVIDER_FALLBACK} — on primary failure the router may serve the request
     *       from the configured fallback provider on that provider's own default model.
     *   <li>{@code MODEL_PROVIDER_SELECTION} — the effective pair is resolved per run from mutable
     *       settings and brain columns.
     *   <li>{@code RETRIEVAL_RANKING} — top-k, confidence threshold, and reranking are read from
     *       {@code brain_settings} per run; the analyzer's declared top-k is recorded but the
     *       retrieval path does not consult it.
     * </ul>
     */
    public static final List<String> LIVE_DEPENDENCIES = List.of(
            "CALCULATOR_IMPLEMENTATION",
            "CORPUS_CONTENTS",
            "MODEL_INFERENCE_BEHAVIOR",
            "MODEL_OUTPUT_TOKEN_BUDGET",
            "MODEL_PROVIDER_FALLBACK",
            "MODEL_PROVIDER_SELECTION",
            "RETRIEVAL_RANKING");

    /** Which resolution step actually chose the effective provider/model pair. */
    public enum SelectionSource {
        /** {@code analyzer.<slug>.provider} + {@code .model}, both set, in brain_settings. */
        ANALYZER_RUNTIME_PAIR,
        /** The analyzer's own pack-declared pair. */
        ANALYZER_PACK_PAIR,
        /** {@code analyze.provider} in brain_settings. */
        ANALYZE_LANE,
        /** The brain's own {@code answer_provider} column. */
        BRAIN_ANSWER_COLUMN,
        /** {@code answer.provider} in brain_settings, else the deployment default provider. */
        GLOBAL_ANSWER_LANE
    }

    /** The analyzer contract a run executes from storage rather than from the live pack. */
    public record Pinned(
            Analyzer analyzer,
            Retrieval retrieval,
            EngineContract engineContract,
            Calculator calculator) {}

    /**
     * The analyzer's instruction block and output contract.
     *
     * <p>The strict v2 output-envelope JSON Schema is pinned by digest rather than copied: it is a
     * build artifact, and a digest that {@code resolveForRun} verifies fails closed just as well
     * while keeping the manifest a description of configuration rather than a second copy of it.
     */
    public record Analyzer(
            String displayName,
            String promptEnvelope,
            String basePrompt,
            String outputSchema,
            String outputEnvelopeSchemaResource,
            String outputEnvelopeSchemaSha256) {}

    /**
     * Guideline retrieval inputs.
     *
     * <p>{@code declaredTopK} is the analyzer's declared value and is recorded for completeness;
     * the retrieval path resolves top-k from {@code brain_settings} instead, which is why
     * {@code RETRIEVAL_RANKING} is a live dependency rather than a pinned one.
     */
    public record Retrieval(String queryTemplate, String corpusScope, int declaredTopK) {}

    /** The Document Engine result contract this release will accept. */
    public record EngineContract(
            String resultMediaType,
            List<String> supportedEnvelopeVersions,
            List<String> supportedCanonicalizationVersions,
            List<String> supportedDocumentTypes,
            List<String> reviewWarningValidationStatuses,
            ReadModelContract readModel) {

        /** The envelope-only contract every release before the read model pinned. */
        public EngineContract(
                String resultMediaType,
                List<String> supportedEnvelopeVersions,
                List<String> supportedCanonicalizationVersions,
                List<String> supportedDocumentTypes,
                List<String> reviewWarningValidationStatuses) {
            this(resultMediaType, supportedEnvelopeVersions, supportedCanonicalizationVersions,
                    supportedDocumentTypes, reviewWarningValidationStatuses, null);
        }

        /** True when runs under this release overlay the engine read model's reviewed values. */
        public boolean readsReviewedValues() {
            return readModel != null;
        }
    }

    /**
     * The read-model surface a release consumes for VALUES. Absent on a release means envelope
     * values only, exactly as before the read model existed; present means every run overlays
     * {@code GET /v1/documents/{id}/fields} and refuses any {@code effectiveStatus} outside
     * {@code effectiveStatuses}.
     */
    public record ReadModelContract(String fieldsContract, List<String> effectiveStatuses) {
        public ReadModelContract {
            Objects.requireNonNull(fieldsContract, "fieldsContract");
            effectiveStatuses = List.copyOf(effectiveStatuses);
        }
    }

    /** The engine fields-view shape this build was written against. */
    public static final String FIELDS_CONTRACT = "DocumentFieldsView@9a300e3";

    /** The deterministic calculation vocabulary the analyzer may request. */
    public record Calculator(List<String> methods) {}

    /** The provider/model/inference selection observed when the snapshot was taken. */
    public record ObservedInference(
            SelectionSource selectionSource,
            String effectiveProvider,
            String effectiveModel,
            String fallbackProvider,
            int maxOutputTokens,
            int maxProviderAttempts,
            ResolutionInputs resolutionInputs) {}

    /**
     * Every mutable input the effective pair was resolved from, recorded as observed.
     *
     * <p>Recording the inputs and not only the outcome keeps the manifest honest even if this
     * module's copy of the resolution order ever diverges from the router's: the inputs are facts
     * about configuration, and they are hashed, so any of them moving raises drift.
     */
    public record ResolutionInputs(
            String analyzerRuntimeProvider,
            String analyzerRuntimeModel,
            String analyzerPackProvider,
            String analyzerPackModel,
            String analyzeLaneProvider,
            String analyzeLaneModel,
            String brainAnswerProvider,
            String brainAnswerModel,
            String globalAnswerProvider,
            String globalAnswerModel,
            boolean brainLocalEndpointConfigured) {}

    /** The prototype boundary, carried by the manifest so no response can omit it. */
    public record PrototypeLimitations(String code, List<String> liveDependencies) {}

    /** The map form that is canonicalized, hashed, and stored in {@code lab_instance_release}. */
    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put("displayName", pinned.analyzer().displayName());
        analyzer.put("promptEnvelope", pinned.analyzer().promptEnvelope());
        analyzer.put("basePrompt", pinned.analyzer().basePrompt());
        analyzer.put("outputSchema", pinned.analyzer().outputSchema());
        analyzer.put("outputEnvelopeSchemaResource",
                pinned.analyzer().outputEnvelopeSchemaResource());
        analyzer.put("outputEnvelopeSchemaSha256", pinned.analyzer().outputEnvelopeSchemaSha256());

        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("queryTemplate", pinned.retrieval().queryTemplate());
        retrieval.put("corpusScope", pinned.retrieval().corpusScope());
        retrieval.put("declaredTopK", pinned.retrieval().declaredTopK());

        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("resultMediaType", pinned.engineContract().resultMediaType());
        engine.put("supportedEnvelopeVersions",
                pinned.engineContract().supportedEnvelopeVersions());
        engine.put("supportedCanonicalizationVersions",
                pinned.engineContract().supportedCanonicalizationVersions());
        engine.put("supportedDocumentTypes", pinned.engineContract().supportedDocumentTypes());
        engine.put("reviewWarningValidationStatuses",
                pinned.engineContract().reviewWarningValidationStatuses());
        if (pinned.engineContract().readModel() != null) {
            Map<String, Object> readModel = new LinkedHashMap<>();
            readModel.put("fieldsContract", pinned.engineContract().readModel().fieldsContract());
            readModel.put("effectiveStatuses", pinned.engineContract().readModel().effectiveStatuses());
            engine.put("readModel", readModel);
        }

        Map<String, Object> pinnedMap = new LinkedHashMap<>();
        pinnedMap.put("analyzer", analyzer);
        pinnedMap.put("retrieval", retrieval);
        pinnedMap.put("engineContract", engine);
        pinnedMap.put("calculator", Map.of("methods", pinned.calculator().methods()));

        ResolutionInputs inputs = observedInference.resolutionInputs();
        Map<String, Object> inputsMap = new LinkedHashMap<>();
        inputsMap.put("analyzerRuntimeProvider", inputs.analyzerRuntimeProvider());
        inputsMap.put("analyzerRuntimeModel", inputs.analyzerRuntimeModel());
        inputsMap.put("analyzerPackProvider", inputs.analyzerPackProvider());
        inputsMap.put("analyzerPackModel", inputs.analyzerPackModel());
        inputsMap.put("analyzeLaneProvider", inputs.analyzeLaneProvider());
        inputsMap.put("analyzeLaneModel", inputs.analyzeLaneModel());
        inputsMap.put("brainAnswerProvider", inputs.brainAnswerProvider());
        inputsMap.put("brainAnswerModel", inputs.brainAnswerModel());
        inputsMap.put("globalAnswerProvider", inputs.globalAnswerProvider());
        inputsMap.put("globalAnswerModel", inputs.globalAnswerModel());
        inputsMap.put("brainLocalEndpointConfigured", inputs.brainLocalEndpointConfigured());

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("selectionSource", observedInference.selectionSource().name());
        observed.put("effectiveProvider", observedInference.effectiveProvider());
        observed.put("effectiveModel", observedInference.effectiveModel());
        observed.put("fallbackProvider", observedInference.fallbackProvider());
        observed.put("maxOutputTokens", observedInference.maxOutputTokens());
        observed.put("maxProviderAttempts", observedInference.maxProviderAttempts());
        observed.put("resolutionInputs", inputsMap);

        Map<String, Object> limitations = new LinkedHashMap<>();
        limitations.put("code", prototypeLimitations.code());
        limitations.put("liveDependencies", prototypeLimitations.liveDependencies());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifestVersion", manifestVersion);
        root.put("canonicalization", canonicalization);
        root.put("instanceSlug", instanceSlug);
        root.put("analyzerSlug", analyzerSlug);
        root.put("pinned", pinnedMap);
        root.put("observedInference", observed);
        root.put("prototypeLimitations", limitations);
        return root;
    }

    /**
     * Reads a stored manifest back into its typed form.
     *
     * <p>Strict on shape, because this is the path a run's contract travels: a member that is
     * absent or of the wrong type is a manifest this build cannot execute, not a default to
     * invent. Failures are the payload-free {@link LabManifestWriter.ManifestException}.
     */
    public static LabReleaseManifest fromMap(Map<String, Object> stored) {
        Map<String, Object> root = requireObject(stored);
        Map<String, Object> pinned = object(root, "pinned");
        Map<String, Object> analyzer = object(pinned, "analyzer");
        Map<String, Object> retrieval = object(pinned, "retrieval");
        Map<String, Object> engine = object(pinned, "engineContract");
        Map<String, Object> calculator = object(pinned, "calculator");
        Map<String, Object> observed = object(root, "observedInference");
        Map<String, Object> inputs = object(observed, "resolutionInputs");
        Map<String, Object> limitations = object(root, "prototypeLimitations");

        return new LabReleaseManifest(
                text(root, "manifestVersion"),
                text(root, "canonicalization"),
                text(root, "instanceSlug"),
                text(root, "analyzerSlug"),
                new Pinned(
                        new Analyzer(
                                text(analyzer, "displayName"),
                                text(analyzer, "promptEnvelope"),
                                text(analyzer, "basePrompt"),
                                text(analyzer, "outputSchema"),
                                text(analyzer, "outputEnvelopeSchemaResource"),
                                text(analyzer, "outputEnvelopeSchemaSha256")),
                        new Retrieval(
                                nullableText(retrieval, "queryTemplate"),
                                nullableText(retrieval, "corpusScope"),
                                integer(retrieval, "declaredTopK")),
                        new EngineContract(
                                text(engine, "resultMediaType"),
                                strings(engine, "supportedEnvelopeVersions"),
                                strings(engine, "supportedCanonicalizationVersions"),
                                strings(engine, "supportedDocumentTypes"),
                                strings(engine, "reviewWarningValidationStatuses"),
                                engine.containsKey("readModel")
                                        ? new ReadModelContract(
                                                text(object(engine, "readModel"), "fieldsContract"),
                                                strings(object(engine, "readModel"), "effectiveStatuses"))
                                        : null),
                        new Calculator(strings(calculator, "methods"))),
                new ObservedInference(
                        selectionSource(text(observed, "selectionSource")),
                        text(observed, "effectiveProvider"),
                        nullableText(observed, "effectiveModel"),
                        nullableText(observed, "fallbackProvider"),
                        integer(observed, "maxOutputTokens"),
                        integer(observed, "maxProviderAttempts"),
                        new ResolutionInputs(
                                nullableText(inputs, "analyzerRuntimeProvider"),
                                nullableText(inputs, "analyzerRuntimeModel"),
                                nullableText(inputs, "analyzerPackProvider"),
                                nullableText(inputs, "analyzerPackModel"),
                                nullableText(inputs, "analyzeLaneProvider"),
                                nullableText(inputs, "analyzeLaneModel"),
                                nullableText(inputs, "brainAnswerProvider"),
                                nullableText(inputs, "brainAnswerModel"),
                                nullableText(inputs, "globalAnswerProvider"),
                                nullableText(inputs, "globalAnswerModel"),
                                bool(inputs, "brainLocalEndpointConfigured"))),
                new PrototypeLimitations(
                        text(limitations, "code"), strings(limitations, "liveDependencies")));
    }

    // ------------------------------------------------------------------ strict readers

    private static Map<String, Object> requireObject(Map<String, Object> value) {
        if (value == null) {
            throw malformed();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String member) {
        if (!(parent.get(member) instanceof Map<?, ?> nested)) {
            throw malformed();
        }
        return (Map<String, Object>) nested;
    }

    private static String text(Map<String, Object> parent, String member) {
        if (!(parent.get(member) instanceof String value)) {
            throw malformed();
        }
        return value;
    }

    private static String nullableText(Map<String, Object> parent, String member) {
        Object value = parent.get(member);
        if (value == null) {
            if (!parent.containsKey(member)) {
                throw malformed();
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw malformed();
        }
        return text;
    }

    private static int integer(Map<String, Object> parent, String member) {
        if (!(parent.get(member) instanceof Number value)) {
            throw malformed();
        }
        return value.intValue();
    }

    private static boolean bool(Map<String, Object> parent, String member) {
        if (!(parent.get(member) instanceof Boolean value)) {
            throw malformed();
        }
        return value;
    }

    private static List<String> strings(Map<String, Object> parent, String member) {
        if (!(parent.get(member) instanceof List<?> values)) {
            throw malformed();
        }
        for (Object value : values) {
            if (!(value instanceof String)) {
                throw malformed();
            }
        }
        return values.stream().map(String::valueOf).toList();
    }

    private static SelectionSource selectionSource(String name) {
        try {
            return SelectionSource.valueOf(Objects.requireNonNull(name).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw malformed();
        }
    }

    private static LabManifestWriter.ManifestException malformed() {
        return new LabManifestWriter.ManifestException(
                LabManifestWriter.ManifestException.Code.MANIFEST_VALUE_UNSUPPORTED);
    }
}
