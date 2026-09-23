package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.dto.RefineRequest;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.AssetsRules;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.PageSelectionProfile;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.calc.AssetsCalcService;
import com.pragmaticds.rag.service.analyze.calc.CalculationExecutor;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import com.pragmaticds.rag.service.analyze.calc.IncomeDomainEnricher;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.pragmaticds.rag.service.answer.AnswerCitationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Folder-brain analysis pipeline — the sibling to {@link com.pragmaticds.rag.service.AskService}.
 *
 *   load analyzer profile → build doc blocks → retrieve guideline chunks (if the
 *   analyzer declares a query template) → assemble prompt → model call →
 *   parse+validate findings JSON → map citations → cost → metadata-only trace.
 *
 * STATELESS on document content: bytes/extracted text are never written to the
 * corpus, disk, or the trace body. Each run persists a METADATA-ONLY manifest row
 * (analysis_runs: ids, hashes, method names, counts, tokens — calculation
 * inputs/values, calculation display names, document filenames, and findings
 * are all persisted only behind ragbrain.rag.analyze.persist-findings, redacted
 * at the AnalysisRunRecorder boundary). The suite is the system of record for results.
 * Never {@code @Transactional}: the pipeline makes a blocking model call.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);


    private static final String DISCLAIMER =
            "\n\n---\n_AI analysis — verify before underwriting decisions; not an underwriting decision._";

    /**
     * Lenient reader for the model's JSON envelope ONLY. Models frequently echo the schema
     * template's {@code //} comments (the v1 output-schema is interpolated verbatim, comments and
     * all) and add a trailing comma; strict {@code readTree} rejected both, turning a good
     * analysis into an ERROR row. Comment/trailing-comma tolerance never accepts INvalid data —
     * it only forgives these two shapes. Scoped here (not the injected mapper) so nothing else in
     * the app loosens its parsing.
     */
    private static final ObjectMapper LENIENT_JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /** Sorted, comma-joined {@link IncomeCalcService#SUPPORTED_METHODS} for validator error text. */
    private static final String SUPPORTED_METHODS_JOINED = IncomeCalcService.SUPPORTED_METHODS.stream()
            .sorted().collect(Collectors.joining(", "));

    /**
     * Completion budget for analyze model calls — {@code ragbrain.rag.analyze.max-output-tokens}
     * (env {@code ANALYZE_MAX_OUTPUT_TOKENS}, default 20000). A response that hits this cap
     * truncates mid-findings-array and fails JSON parsing; v1 and v2 each get ONE corrective
     * retry before erroring, but the budget should still fit the whole envelope so the retry
     * stays the exception. History: 4000 truncated
     * 8+-doc folders; the hard-coded 8000 truncated a 17-doc income folder in prod
     * (2026-07-28, twice). 20000 keeps ~2.5× headroom over that failure while staying far
     * under claude-haiku-4-5's 64K output ceiling (the configured default answer model).
     * An analyzer that pins its own provider+model pair (assets-v2 does) runs on that model
     * instead, so this budget must stay under the smallest ceiling any pinned model has.
     */
    private final int maxOutputTokens;

    private final DomainPackRegistry registry;
    private final ModelRouterService router;
    private final RetrievalService retrieval;
    private final DocumentBlockService documentBlockService;
    private final EnvelopeValidator envelopeValidator;
    private final CalculationExecutor calculationExecutor;
    private final AssetsCalcService assetsCalc;
    private final AssetsReportRenderer assetsReportRenderer;
    private final IncomeDomainEnricher incomeDomainEnricher;
    private final IncomeWorksheetRenderer incomeWorksheetRenderer;
    private final AnswerCitationService citationService;
    private final ObjectMapper objectMapper;
    private final AnalysisTraceService traceService;
    private final AnalysisRunRecorder runRecorder;
    private final AnalyzerPromptService analyzerPrompts;
    private final RuntimeSettings settings;

    public AnalysisService(DomainPackRegistry registry,
                           ModelRouterService router,
                           RetrievalService retrieval,
                           DocumentBlockService documentBlockService,
                           EnvelopeValidator envelopeValidator,
                           CalculationExecutor calculationExecutor,
                           AssetsCalcService assetsCalc,
                           AssetsReportRenderer assetsReportRenderer,
                           IncomeDomainEnricher incomeDomainEnricher,
                           IncomeWorksheetRenderer incomeWorksheetRenderer,
                           AnswerCitationService citationService,
                           ObjectMapper objectMapper,
                           AnalysisTraceService traceService,
                           AnalysisRunRecorder runRecorder,
                           AnalyzerPromptService analyzerPrompts,
                           RuntimeSettings settings,
                           @Value("${ragbrain.rag.analyze.max-output-tokens:20000}") int maxOutputTokens) {
        this.registry = registry;
        this.router = router;
        this.retrieval = retrieval;
        this.documentBlockService = documentBlockService;
        this.envelopeValidator = envelopeValidator;
        this.calculationExecutor = calculationExecutor;
        this.assetsCalc = assetsCalc;
        this.assetsReportRenderer = assetsReportRenderer;
        this.incomeDomainEnricher = incomeDomainEnricher;
        this.incomeWorksheetRenderer = incomeWorksheetRenderer;
        this.citationService = citationService;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.runRecorder = runRecorder;
        this.analyzerPrompts = analyzerPrompts;
        this.settings = settings;
        this.maxOutputTokens = maxOutputTokens;
    }

    public AnalysisResult analyze(UUID brainId, String analyzerSlug,
                                  List<DocInput> docs, AnalysisContext context) {
        DomainPack pack = registry.bundle(brainId).pack();
        AnalyzerConfig analyzer = pack.analyzers().stream()
                .filter(a -> a.slug().equals(analyzerSlug))
                .findFirst()
                .orElseThrow(() -> new AnalyzerNotFoundException(analyzerSlug));
        analyzer = analyzerPrompts.withEffectivePrompt(brainId, analyzer);

        RunManifest manifest = RunManifest.start(brainId, analyzerSlug,
                analyzer.isV2() ? "v2" : "v1", docs);

        // Every MODELLED failure returns through error(), which records a manifest. This
        // catch covers the rest — an unmodelled RuntimeException from doc building, prompt
        // assembly, the executor, citations, or costing would otherwise unwind to a 500
        // leaving no analysis_runs row at all, which is exactly the run an operator needs
        // to look up. Record it, then rethrow so the caller's error handling is unchanged.
        try {
            return analyzeInternal(pack, analyzer, brainId, analyzerSlug, docs, context, manifest);
        } catch (RuntimeException e) {
            runRecorder.save(manifest, unmodelledError(e, manifest), 1);
            throw e;
        }
    }

    /**
     * The (provider, model) this analyzer should run on: a runtime override from
     * brain_settings if one is fully set, else the analyzer's own pack pair.
     *
     * <p>Runtime wins so a model can be swapped without rebuilding and redeploying the
     * pack. A HALF-set runtime override is discarded rather than blended with the pack —
     * mixing a runtime provider with the pack's model id would send that model to a
     * provider it does not belong to, which is the exact failure the pairing rule exists
     * to prevent.
     *
     * @return a two-element array of {provider, model}, either may be null
     */
    private String[] modelPair(AnalyzerConfig analyzer) {
        String runtimeProvider = settings.analyzerProvider(analyzer.slug());
        String runtimeModel = settings.analyzerModel(analyzer.slug());
        if (runtimeProvider != null && !runtimeProvider.isBlank()
                && runtimeModel != null && !runtimeModel.isBlank()) {
            return new String[]{runtimeProvider, runtimeModel};
        }
        if (runtimeProvider != null || runtimeModel != null) {
            log.warn("Ignoring half-set runtime model override for analyzer {} "
                    + "(provider and model must both be set); using the pack pair",
                    analyzer.slug());
        }
        return new String[]{analyzer.providerOverride(), analyzer.modelOverride()};
    }

    /**
     * Qualifying monthly income used as the large-deposit threshold basis. Returns null
     * when the loan context carries no monthly income — the deposits block then reports
     * why the threshold is unavailable rather than silently using zero, which would flag
     * every deposit in the file.
     *
     * <p>Deliberately reads the loan snapshot's monthlyIncome, NOT loan.urlaIncome: the
     * URLA figure is CLAIMED and unverified, and this codebase already treats it as a
     * comparison target only, never an input to a calculation.
     */
    /**
     * The loan facts the large-deposit threshold is selected and computed from.
     *
     * <p>Every field is passed through as the suite sent it, or null. Nothing is defaulted —
     * an absent program does not become conventional and an absent purpose does not become a
     * purchase, because either guess yields a threshold the report would present as fact.
     */
    private static LoanBasis loanBasis(AnalysisContext context) {
        if (context == null || context.loan() == null) {
            return null;
        }
        AnalysisContext.LoanSnapshot loan = context.loan();
        return new LoanBasis(
                LoanBasis.programOf(loan.program()),
                LoanBasis.purposeOf(loan.loanPurpose()),
                money(loan.monthlyIncome()),
                money(loan.propertyValue()));
    }

    /** valueOf, never new BigDecimal(double): the latter carries IEEE-754 noise into money. */
    private static BigDecimal money(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Builds the ERROR result recorded for an unmodelled exception; never surfaced to the caller. */
    private AnalysisResult unmodelledError(RuntimeException e, RunManifest manifest) {
        return new AnalysisResult(AnalysisResult.Status.ERROR,
                "", "{}", List.of(), null, null, 0, 0, 0.0, 0, List.of(),
                "unhandled " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                List.of(), manifest.runId().toString());
    }

    private AnalysisResult analyzeInternal(DomainPack pack, AnalyzerConfig analyzer,
                                           UUID brainId, String analyzerSlug,
                                           List<DocInput> docs, AnalysisContext context,
                                           RunManifest manifest) {
        // 1. Build native document blocks + caps. Page selection trims large PDFs to the
        //    analyzer's whitelisted pages first; the analyst can disable it per run.
        PageSelectionProfile pageProfile = null;
        if (analyzer.pageSelectionProfile() != null && !context.disablePageFilter()) {
            pageProfile = pack.pageSelectionProfile(analyzer.pageSelectionProfile());
            if (pageProfile == null) {
                log.warn("Analyzer {} references unknown page-selection profile {}; sending every page",
                        analyzerSlug, analyzer.pageSelectionProfile());
            }
        }
        DocumentBlockService.BuildResult built = documentBlockService.build(docs, pageProfile);

        // 2. Retrieval — only when the analyzer declares a query template (classifier = null).
        List<RetrievedChunk> chunks = retrieveGuidelines(brainId, analyzerSlug, manifest,
                analyzer.retrievalQueryTemplate(), analyzer.corpusScope(), null);

        // 3. Assemble the prompt (base + guideline chunks + loan ctx + extra + schema instruction).
        String prompt = buildPrompt(analyzer, chunks, context, built);
        manifest.setPromptSha256(RunManifest.sha256Hex(prompt));

        // 4-8. Shared execution: model call, envelope validation and corrective retry,
        //      deterministic calculations, citations, cost, trace, terminal mapping.
        //      An analyzer pins its model as a (provider, model) pair; a model alone is
        //      ignored by the router, since a model id is only valid for its own provider.
        String[] pair = modelPair(analyzer);
        List<Media> media = built.blocks().stream()
                .map(DocumentBlockService.DocBlock::media).toList();
        String requiredDomainMarker = analyzer.declaresDomain("submission-domain-v1")
                ? "submission-domain-v1" : null;
        return execute(new Run(brainId, analyzerSlug, analyzer.isV2(), manifest, built, media,
                prompt, pair[0], pair[1], context, analyzer.assetsRules(), Set.of(),
                chunks, null, true, false, requiredDomainMarker));
    }

    // ================================================================ parsed entry point

    /**
     * The pinned half of a parsed Lab run — every member comes from the caller's STORED release
     * manifest, never from the live pack.
     *
     * <p>Deliberately primitive-typed: {@code lab} depends on {@code service.analyze}, and this
     * record is the seam that keeps the arrow pointing one way. It also has no member that could
     * hold a document, a byte array, or a supplier of one, which is the structural half of the
     * no-raw-fallback rule.
     *
     * @param factsBlock the rendered machine-fact text that REPLACES document media
     * @param documentHandles the citation handles the facts block authorized; a
     *     {@code BORROWER_DOC} citation naming anything else fails validation
     * @param correlationId the id every sanitized log line and failure carries instead of a message
     */
    /**
     * Everything a pinned run adds to {@link ParsedContract}: which frozen corpus the evidence
     * came from, the evidence itself, and the exact model. The chunks arrive already retrieved
     * because the caller must seal them into provenance and must be able to stop the run on a
     * retrieval failure before any tool or model call.
     */
    public record PinnedContract(
            String analyzerSlug,
            String envelopeVersion,
            String basePrompt,
            String outputSchema,
            String retrievalQueryTemplate,
            UUID corpusSnapshotId,
            List<RetrievedChunk> chunks,
            String provider,
            String model,
            String factsBlock,
            Set<String> documentHandles,
            String correlationId,
            /** The release's own temperature; null falls back to the analyze lane's default. */
            java.math.BigDecimal temperature,
            /**
             * The loan facts the assets large-deposit rule is selected from, or null.
             *
             * <p>Null is the ordinary case and is never a defect: an instance that needs no loan
             * facts stores none, and the threshold then reports itself unavailable rather than
             * being computed from a guess.
             */
            LoanBasis loanBasis,
            /**
             * The {@code domain} payload a pinned tool computed from parsed facts, or null.
             *
             * <p>When present it REPLACES whatever the model wrote in {@code domain}, before any
             * deterministic rule reads it. Merging the two, or preferring the model's, would let
             * a run record an engine-built ledger in provenance while reporting on a
             * model-built one.
             */
            JsonNode computedDomain) {

        public PinnedContract {
            Objects.requireNonNull(corpusSnapshotId, "corpusSnapshotId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
            documentHandles = Set.copyOf(Objects.requireNonNull(documentHandles, "documentHandles"));
        }

        /**
         * The prompt-shaped view. corpusScope is null because a pinned run never uses scope
         * retrieval; reusing the existing contract keeps the prompt builder — whose bytes are
         * hashed into prompt_sha256 — untouched.
         */
        public ParsedContract asParsedContract() {
            return new ParsedContract(analyzerSlug, envelopeVersion, basePrompt, outputSchema,
                    retrievalQueryTemplate, null, factsBlock, documentHandles, correlationId);
        }
    }

    public record ParsedContract(
            String analyzerSlug,
            String envelopeVersion,
            String basePrompt,
            String outputSchema,
            String retrievalQueryTemplate,
            String corpusScope,
            String factsBlock,
            Set<String> documentHandles,
            String correlationId) {

        public ParsedContract {
            Objects.requireNonNull(analyzerSlug, "analyzerSlug");
            Objects.requireNonNull(basePrompt, "basePrompt");
            Objects.requireNonNull(outputSchema, "outputSchema");
            Objects.requireNonNull(factsBlock, "factsBlock");
            Objects.requireNonNull(correlationId, "correlationId");
            documentHandles = Set.copyOf(documentHandles);
        }
    }

    /** A parsed run's result plus the router's own resolution and its provider-attempt count. */
    public record ParsedOutcome(AnalysisResult result, ModelRouterService.Resolution resolution,
                                int attempts) {}

    /** Stable, value-free terminal reasons for a parsed Lab run. */
    public enum ParsedFailureCode {
        /** The provider (and any fallback) failed; the cause is deliberately not carried. */
        MODEL_PROVIDER_FAILED,
        /** The corrective retry's response was still not one JSON object. */
        MODEL_RESPONSE_UNPARSEABLE,
        /** The envelope failed schema, analyzer-identity, calculator, or citation validation. */
        ENVELOPE_VALIDATION_FAILED_AFTER_RETRY
    }

    /**
     * Analyzes an already-verified, already-compatible parsed engine envelope.
     *
     * <p>No overload of this method accepts a {@link org.springframework.web.multipart.MultipartFile},
     * a {@link DocInput}, document bytes, or a supplier of any of them, and it never calls
     * {@link DocumentBlockService}: a parsed run sends ZERO media and the rendered fact block is
     * the only borrower evidence in the request. That is the whole point of the Lab path — there
     * is no raw-document fallback to reach for when the parse is unusable, only a safe stop.
     *
     * <p>The prompt, output contract, and retrieval query/scope all come from {@code contract},
     * i.e. from the stored release. Model selection deliberately does <em>not</em>: the release
     * declares {@code MODEL_PROVIDER_SELECTION} and {@code MODEL_PROVIDER_FALLBACK} as live
     * dependencies, so pinning a pair here would make the manifest lie in the other direction. The
     * live pair is resolved exactly as the raw path resolves it, and the returned
     * {@link ModelRouterService.Resolution} states what actually ran.
     *
     * @param analysisRunManifest a manifest built with the caller-allocated
     *     {@code analysis_runs.id}, so the Lab request and the analyzer share one identity
     */
    public ParsedOutcome analyzeParsed(UUID brainId, RunManifest analysisRunManifest,
                                       ParsedContract contract) {
        Objects.requireNonNull(analysisRunManifest, "analysisRunManifest");
        Objects.requireNonNull(contract, "contract");

        List<RetrievedChunk> chunks = retrieveGuidelines(brainId, contract.analyzerSlug(),
                analysisRunManifest, contract.retrievalQueryTemplate(), contract.corpusScope(),
                contract.correlationId());

        String prompt = buildParsedPrompt(contract, chunks);
        analysisRunManifest.setPromptSha256(RunManifest.sha256Hex(prompt));

        String[] pair = livePairFor(brainId, contract.analyzerSlug());
        Run run = new Run(brainId, contract.analyzerSlug(), true, analysisRunManifest,
                EMPTY_BUILD, List.of(), prompt, pair[0], pair[1], null,
                liveAssetsRulesFor(brainId, contract.analyzerSlug()),
                contract.documentHandles(), chunks, contract.correlationId(), false, true);
        AnalysisResult result = execute(run);
        return new ParsedOutcome(result, run.resolution, run.attempts);
    }

    /**
     * A fully pinned run: an exact corpus snapshot, an exact provider and model, and no
     * substitution permitted anywhere.
     *
     * <p>Two things differ from {@link #analyzeParsed}, and both exist so the release id a run
     * records stays true. The evidence arrives already retrieved from an immutable snapshot rather
     * than being fetched from a mutable scope here — the caller retrieves so that a retrieval
     * failure stops the run before any tool or model call, and so the exact chunks can be sealed
     * into the run's provenance. The model pair comes from the release instead of the live pack,
     * and {@link ModelRouterService.FallbackPolicy#NONE} stops the router substituting another
     * provider's default model for it.
     *
     * <p>Everything after that is the shared execution path: the same prompt builder, schema
     * validation, citation handling, and recording the parsed entry point already uses.
     */
    public ParsedOutcome analyzePinned(UUID brainId, RunManifest analysisRunManifest,
                                       PinnedContract pinned) {
        Objects.requireNonNull(analysisRunManifest, "analysisRunManifest");
        Objects.requireNonNull(pinned, "pinned");
        ParsedContract contract = pinned.asParsedContract();

        List<RetrievedChunk> chunks = pinned.chunks();
        analysisRunManifest.setRetrievedChunkIds(
                chunks.stream().map(c -> c.chunkId().toString()).toList());
        String prompt = buildParsedPrompt(contract, chunks);
        analysisRunManifest.setPromptSha256(RunManifest.sha256Hex(prompt));

        Run run = new Run(brainId, contract.analyzerSlug(), true, analysisRunManifest,
                EMPTY_BUILD, List.of(), prompt, pinned.provider(), pinned.model(), null,
                liveAssetsRulesFor(brainId, contract.analyzerSlug()),
                contract.documentHandles(), chunks, contract.correlationId(), false, true);
        // A pinned run has no AnalysisContext to derive a basis from; its loan facts travel with
        // the parsed input the caller pinned, which is why they arrive on the contract instead.
        run.loanBasis = pinned.loanBasis();
        run.computedDomain = pinned.computedDomain();
        run.fallbackPolicy = ModelRouterService.FallbackPolicy.NONE;
        if (pinned.temperature() != null) {
            run.temperature = pinned.temperature().doubleValue();
        }
        AnalysisResult result = execute(run);
        return new ParsedOutcome(result, run.resolution, run.attempts);
    }

    /**
     * The live pack's declaration of one analyzer, or empty when the pack no longer names it.
     *
     * <p>Absence is never fatal here. A release pins the analyzer's <em>instructions</em>; the two
     * things read through this lookup are deliberately not pinned, and both are already named in
     * {@code LabReleaseManifest.LIVE_DEPENDENCIES} — {@code MODEL_PROVIDER_SELECTION} for the
     * routing lane, {@code CALCULATOR_IMPLEMENTATION} for the deterministic calculator code whose
     * thresholds and keywords the ledger rules supply. Failing a run because the live pack dropped
     * an analyzer would be stricter than the release's own promise.
     */
    private Optional<AnalyzerConfig> liveAnalyzer(UUID brainId, String analyzerSlug) {
        try {
            // By slug or by a declared instance slug: a pinned run passes its instance slug, and an
            // instance may run under an analyzer of another name (asset-analysis → assets-v2). The
            // loader guarantees an instance slug names exactly one analyzer and no analyzer slug.
            return registry.bundle(brainId).pack().analyzers().stream()
                    .filter(a -> a.answersTo(analyzerSlug))
                    .findFirst();
        } catch (RuntimeException unloadable) {
            // A pack that will not load must not take a pinned run down with it. Everything read
            // through here is a declared LIVE dependency, and every caller already has a correct
            // answer for absence: no model pair means the routing lane resolves it, and no ledger
            // rules means the large-deposit screen reports itself unavailable rather than
            // applying a threshold. Failing the whole run instead would make a release's
            // execution depend on live pack state the release deliberately does not pin.
            //
            // Class name and ids only: the message carries a filesystem path.
            log.warn("Live pack unavailable for brain {} ({}); {} runs without live analyzer"
                    + " configuration", brainId, unloadable.getClass().getSimpleName(),
                    analyzerSlug);
            return Optional.empty();
        }
    }

    /**
     * The live (provider, model) pair for one analyzer slug, or {@code (null, null)} when the
     * live pack no longer declares it.
     */
    private String[] livePairFor(UUID brainId, String analyzerSlug) {
        return liveAnalyzer(brainId, analyzerSlug)
                .map(this::modelPair)
                .orElseGet(() -> new String[]{null, null});
    }

    /**
     * The live ledger rules for one analyzer slug, or null when the analyzer declares none.
     *
     * <p>Without this a parsed assets run reached {@link #execute} with {@code assetsRules == null}
     * and silently skipped the whole deterministic half — {@link AssetsCalcService} never derived a
     * thing and {@link AssetsReportRenderer} never ran. Since the assets prompt tells the model to
     * write nothing in {@code reportMarkdown} precisely because the engine replaces it, the run
     * completed successfully with an empty report. The raw {@code /analyze} path never had the bug:
     * it has always passed {@code analyzer.assetsRules()} straight from the pack.
     */
    private AssetsRules liveAssetsRulesFor(UUID brainId, String analyzerSlug) {
        return liveAnalyzer(brainId, analyzerSlug).map(AnalyzerConfig::assetsRules).orElse(null);
    }

    // ================================================================ shared execution

    /** A build result for a run that has no documents at all — the parsed path's shape. */
    private static final DocumentBlockService.BuildResult EMPTY_BUILD =
            new DocumentBlockService.BuildResult(List.of(), 0, List.of(), List.of());

    /**
     * One run's shared execution state. Everything the raw and parsed entry points agree on lives
     * here; the two fields that differ in KIND — {@code media} (native blocks vs none) and
     * {@code correlationId} (null = legacy verbose disclosure, non-null = Lab-safe codes only) —
     * are what the entry points set differently.
     */
    private static final class Run {
        final UUID brainId;
        final String analyzerSlug;
        final boolean v2;
        final RunManifest manifest;
        final DocumentBlockService.BuildResult built;
        final List<Media> media;
        final String prompt;
        final String provider;
        final String model;
        final AnalysisContext context;
        final AssetsRules assetsRules;
        final Set<String> documentHandles;
        final List<RetrievedChunk> chunks;
        /** Null on the legacy raw path; a Lab correlation id otherwise. */
        final String correlationId;
        final boolean recordTrace;
        final boolean requireRecorder;
        /** Non-null when this run's analyzer declares a domain contract the response MUST match. */
        final String requiredDomainMarker;

        ModelRouterService.Resolution resolution;
        int attempts = 1;
        /**
         * The loan facts the assets large-deposit rule is selected and computed from.
         *
         * <p>Derived from {@code context} on the raw path, where the suite sends a loan snapshot.
         * A parsed run has no context, so this stays null until the run's own inputs supply it —
         * and a null basis makes the threshold report itself unavailable rather than guessing.
         */
        LoanBasis loanBasis;
        /**
         * A domain payload the engine computed, which outranks the model's.
         *
         * <p>Null on every path but a pinned run whose release pinned a domain-producing tool.
         */
        JsonNode computedDomain;
        /** CONFIGURED everywhere except a pinned release, which forbids substitution. */
        ModelRouterService.FallbackPolicy fallbackPolicy =
                ModelRouterService.FallbackPolicy.CONFIGURED;
        /** The analyze default unless a pinned release declared its own. */
        double temperature = AiRequest.ANALYZE_TEMPERATURE;

        Run(UUID brainId, String analyzerSlug, boolean v2, RunManifest manifest,
            DocumentBlockService.BuildResult built, List<Media> media, String prompt,
            String provider, String model, AnalysisContext context, AssetsRules assetsRules,
            Set<String> documentHandles, List<RetrievedChunk> chunks, String correlationId,
            boolean recordTrace, boolean requireRecorder) {
            this(brainId, analyzerSlug, v2, manifest, built, media, prompt, provider, model,
                    context, assetsRules, documentHandles, chunks, correlationId, recordTrace,
                    requireRecorder, null);
        }

        Run(UUID brainId, String analyzerSlug, boolean v2, RunManifest manifest,
            DocumentBlockService.BuildResult built, List<Media> media, String prompt,
            String provider, String model, AnalysisContext context, AssetsRules assetsRules,
            Set<String> documentHandles, List<RetrievedChunk> chunks, String correlationId,
            boolean recordTrace, boolean requireRecorder, String requiredDomainMarker) {
            this.brainId = brainId;
            this.analyzerSlug = analyzerSlug;
            this.v2 = v2;
            this.manifest = manifest;
            this.built = built;
            this.media = media;
            this.prompt = prompt;
            this.provider = provider;
            this.model = model;
            this.context = context;
            this.loanBasis = loanBasis(context);
            this.assetsRules = assetsRules;
            this.documentHandles = documentHandles;
            this.chunks = chunks;
            this.correlationId = correlationId;
            this.recordTrace = recordTrace;
            this.requireRecorder = requireRecorder;
            this.requiredDomainMarker = requiredDomainMarker;
        }

        boolean labSafe() {
            return correlationId != null;
        }
    }

    /** Retrieval is identical for both entry points; only the failure disclosure differs. */
    private List<RetrievedChunk> retrieveGuidelines(UUID brainId, String analyzerSlug,
                                                    RunManifest manifest, String queryTemplate,
                                                    String corpusScope, String correlationId) {
        if (queryTemplate == null || queryTemplate.isBlank()) {
            return List.of();
        }
        try {
            RetrievalResult rr = retrieval.retrieveAdmin(
                    queryTemplate, brainId, SourceVisibility.INTERNAL, corpusScope);
            List<RetrievedChunk> chunks = rr.chunks();
            manifest.setRetrievedChunkIds(chunks.stream()
                    .map(c -> c.chunkId().toString()).toList());
            return chunks;
        } catch (RuntimeException e) {
            // Empty retrieval is non-fatal: the report simply notes no guideline grounding.
            if (correlationId == null) {
                log.warn("Retrieval failed for analyzer {}: {}", analyzerSlug, e.getMessage());
            } else {
                log.warn("Retrieval failed for analyzer {} ({}) [{}]",
                        analyzerSlug, e.getClass().getSimpleName(), correlationId);
            }
            return List.of();
        }
    }

    private AnalysisResult execute(Run run) {
        // 4. Model call. Provider/parse failure → ERROR (never a raw 500).
        Answer answer;
        try {
            answer = call(run, run.prompt);
        } catch (RuntimeException e) {
            logModelFailure(run, e, false);
            return error(run, "model provider error: " + e.getMessage(),
                    ParsedFailureCode.MODEL_PROVIDER_FAILED, 1);
        }

        // 5. Parse the model's JSON envelope. v1: { reportMarkdown, findings, citations }.
        //    v2: the whole strict envelope, schema-validated with ONE corrective retry —
        //    an unparseable FIRST response gets the same retry (validateV2 reports it as
        //    a validation error). v1 gets its own ONE corrective retry below, so both
        //    versions fail closed only after a second bad response.
        JsonNode root = extractJson(answer.response.content());
        int inTok = nz(answer.response.promptTokens());
        int outTok = nz(answer.response.completionTokens());
        if (root == null && !run.v2) {
            // v1 previously failed closed on the FIRST unparseable response (only v2 retried).
            // A truncated or comment-polluted first response now gets ONE corrective retry —
            // the same recovery v2 already has — before erroring. The retry prompt asks for a
            // complete, terse JSON object, which typically fits the token budget on the second
            // try. Unlike v2's retry it does NOT echo the previous response back (nothing in a
            // parse failure is worth resending borrower evidence for).
            log.warn("v1 analyze response unparseable for {}; retrying once", run.analyzerSlug);
            String retryPrompt = run.prompt
                    + "\n\nYour previous response could not be parsed as JSON. Return ONLY the corrected, "
                    + "COMPLETE JSON object in the shape above — no prose, no markdown fences, no comments, "
                    + "and do not truncate it.";
            Answer retried;
            run.attempts = 2;
            try {
                // The corrective retry must run on the SAME model as the first attempt.
                retried = call(run, retryPrompt);
            } catch (RuntimeException e) {
                logModelFailure(run, e, true);
                // The retry itself threw, so only the FIRST call's tokens are known — but
                // attempts is still 2 (two model calls were made; the second just failed).
                return error(run, "model provider error: " + e.getMessage(),
                        ParsedFailureCode.MODEL_PROVIDER_FAILED, 2, inTok, outTok, answer);
            }
            // Token/cost accounting sums BOTH model calls — a billed call already happened;
            // carry its spend into any error row rather than reporting a real call as free.
            inTok += nz(retried.response.promptTokens());
            outTok += nz(retried.response.completionTokens());
            answer = retried;
            root = extractJson(answer.response.content());
            if (root == null) {
                log.warn("v1 retry response still unparseable for {}; failing closed", run.analyzerSlug);
                return error(run, "unparseable model response after retry",
                        ParsedFailureCode.MODEL_RESPONSE_UNPARSEABLE, run.attempts,
                        inTok, outTok, answer);
            }
        }

        String findingsJson;
        if (run.v2) {
            List<String> validationErrors = validateV2(root, run.analyzerSlug, run.documentHandles, run.requiredDomainMarker);
            if (!validationErrors.isEmpty()) {
                // NPI: log slug + counts only — never validator messages or payload content.
                log.warn("Envelope v2 validation failed for {} ({} errors); retrying once",
                        run.analyzerSlug, validationErrors.size());
                String retryPrompt = run.prompt
                        + "\n\nYour previous response failed schema validation with these errors:\n"
                        + String.join("\n", validationErrors)
                        + "\nPrevious response:\n" + answer.response.content()
                        + "\nReturn the corrected JSON only.";
                Answer retried;
                run.attempts = 2;
                try {
                    // The corrective retry must run on the SAME model as the first attempt.
                    retried = call(run, retryPrompt);
                } catch (RuntimeException e) {
                    logModelFailure(run, e, true);
                    // The retry itself threw, so only the FIRST call's tokens are known — but
                    // attempts is still 2 (two model calls were made; the second just failed).
                    return error(run, "model provider error: " + e.getMessage(),
                            ParsedFailureCode.MODEL_PROVIDER_FAILED, 2, inTok, outTok, answer);
                }
                // Token/cost accounting sums BOTH model calls.
                inTok += nz(retried.response.promptTokens());
                outTok += nz(retried.response.completionTokens());
                answer = retried;
                root = extractJson(answer.response.content());
                if (root == null) {
                    // Retry came back unparseable — distinct reason; first-attempt errors kept for context.
                    log.warn("Envelope v2 retry response unparseable for {} ({} first-attempt errors); failing closed",
                            run.analyzerSlug, validationErrors.size());
                    return error(run, "envelope v2 retry response unparseable; first-attempt errors: "
                                    + firstThree(validationErrors),
                            ParsedFailureCode.MODEL_RESPONSE_UNPARSEABLE, run.attempts,
                            inTok, outTok, answer);
                }
                validationErrors = validateV2(root, run.analyzerSlug, run.documentHandles, run.requiredDomainMarker);
                if (!validationErrors.isEmpty()) {
                    log.warn("Envelope v2 validation failed after retry for {} ({} errors); failing closed",
                            run.analyzerSlug, validationErrors.size());
                    return error(run, "envelope v2 validation failed after retry: "
                                    + firstThree(validationErrors),
                            ParsedFailureCode.ENVELOPE_VALIDATION_FAILED_AFTER_RETRY, run.attempts,
                            inTok, outTok, answer);
                }
            }
            // A domain the engine computed from parsed facts outranks the model's, and is
            // substituted BEFORE any deterministic rule reads it. This is what takes dense
            // financial transcription away from the model: the release pins a tool that built
            // the ledger from the engine's own extraction, and whatever the model wrote in
            // domain — including nothing, which is what its prompt now asks for — is discarded
            // rather than merged. Merging would leave the run reporting on a ledger that is
            // neither the one it recorded nor the one it was pinned to.
            if (run.computedDomain != null && root instanceof ObjectNode envelope) {
                envelope.set("domain", run.computedDomain.deepCopy());
            }

            // Valid: run the calculation requests deterministically, then the WHOLE
            // enriched envelope is the findings payload (suite-side jsonb contract).
            CalculationExecutor.Execution exec = calculationExecutor.execute(root);
            ObjectNode enriched = exec.enriched();
            root = enriched;
            run.manifest.setCalcAudit(exec.audit());

            // Assets analyzers additionally derive their rules from the transcribed
            // ledger and render the report deterministically, so the layout and the rule
            // outcomes cannot vary run to run. Reads domain directly rather than
            // model-requested calculations — see the design spec §6.3.
            if (run.assetsRules != null) {
                assetsCalc.enrich(enriched, run.assetsRules, run.loanBasis);
                enriched.put("reportMarkdown", assetsReportRenderer.render(enriched));
            } else if (IncomeDomainEnricher.appliesTo(enriched)) {
                // Income runs that declare the income-domain-v2 contract: label each source's
                // basis, degrade failed calculations visibly (never fail the run), then append
                // the worksheet after placeholder substitution. Undeclared domains skip this.
                IncomeDomainEnricher.Summary summary = incomeDomainEnricher.enrich(enriched);
                enriched.put("reportMarkdown", enriched.path("reportMarkdown").asText("").stripTrailing()
                        + "\n\n" + incomeWorksheetRenderer.render(enriched));
                // NPI: slug + counts only.
                log.info("Income worksheet for {}: {} calculator, {} model-stated, {} none;"
                                + " {} failed references, {} unmatched placeholders, {} calculations failed",
                        run.analyzerSlug, summary.calculator(), summary.modelStated(), summary.none(),
                        summary.failedReferences(), summary.unmatchedPlaceholders(),
                        run.manifest.calculationsFailed());
            }

            findingsJson = root.toString();
        } else if (root.isArray()) {
            // A bare top-level array IS the findings payload — array-shaped output-schemas
            // (e.g. the assets analyzer: "Return findings as a JSON array") invite the model
            // to emit exactly this.
            findingsJson = root.toString();
        } else {
            JsonNode findings = root.has("findings") ? root.get("findings")
                    : objectMapper.createObjectNode();
            findingsJson = findings.toString();
        }
        String reportMarkdown = root.path("reportMarkdown").asText("");

        // 6. Citations — the model may emit them; the response uses the retrieved chunks.
        List<CitationDto> citations = citationService.citationsFromChunks(run.chunks);

        // 7. Cost from token counts (deterministic; provider price table).
        double cost = CostTable.usd(answer.response.providerName(), answer.response.modelName(),
                inTok, outTok);

        // 8. Metadata-only trace (ids/hashes/sizes/tokens/status) — NEVER doc content.
        //    Tokens are the summed totals across attempts, so the trace agrees with the result.
        //    The Lab has its own audit trail and no analyze-console trace, so it opts out.
        if (run.recordTrace) {
            traceService.record(run.brainId, run.analyzerSlug, run.context, run.built,
                    answer.response, run.attempts, inTok, outTok, "SUCCESS");
        }

        AnalysisResult ok = new AnalysisResult(
                AnalysisResult.Status.SUCCESS,
                reportMarkdown + DISCLAIMER,
                findingsJson,
                citations,
                answer.response.providerName(),
                answer.response.modelName(),
                inTok, outTok, cost,
                run.built.pageCount(),
                run.built.skipped(),
                null,
                run.built.filtered(),
                run.manifest.runId().toString());
        record(run, ok, run.attempts);
        return ok;
    }

    /** One model call, through the legacy or the sanitized router entry point. */
    private Answer call(Run run, String prompt) {
        AiRequest request = AiRequest.forAnalysis(prompt, run.media, maxOutputTokens,
                run.provider, run.model, run.temperature);
        if (!run.labSafe()) {
            return new Answer(router.generate(request, run.brainId).response());
        }
        ModelRouterService.SanitizedResponse sanitized =
                router.generateSanitized(request, run.brainId, run.correlationId,
                        run.fallbackPolicy);
        run.resolution = sanitized.resolution();
        return new Answer(sanitized.response());
    }

    /** The model's response, decoupled from which router entry point produced it. */
    private record Answer(com.pragmaticds.rag.provider.AiResponse response) {}

    private void logModelFailure(Run run, RuntimeException failure, boolean retry) {
        if (!run.labSafe()) {
            log.error(retry ? "Analyze retry model call failed for {}: {}"
                            : "Analyze model call failed for {}: {}",
                    run.analyzerSlug, failure.getMessage());
            return;
        }
        // Class name and correlation id only: a provider exception's message routinely carries
        // the request URI and the provider's own response body.
        log.error(retry ? "Parsed analyze retry model call failed for {} ({}) [{}]"
                        : "Parsed analyze model call failed for {} ({}) [{}]",
                run.analyzerSlug, failure.getClass().getSimpleName(), run.correlationId);
    }

    private void record(Run run, AnalysisResult result, int attempts) {
        if (run.requireRecorder) {
            runRecorder.saveRequired(run.manifest, result, attempts);
        } else {
            runRecorder.save(run.manifest, result, attempts);
        }
    }

    public AnalysisResult refine(UUID brainId, String analyzerSlug, RefineRequest req) {
        DomainPack pack = registry.bundle(brainId).pack();
        AnalyzerConfig analyzer = pack.analyzers().stream()
                .filter(a -> a.slug().equals(analyzerSlug))
                .findFirst()
                .orElseThrow(() -> new AnalyzerNotFoundException(analyzerSlug));
        analyzer = analyzerPrompts.withEffectivePrompt(brainId, analyzer);

        if (analyzer.isV2()) {
            // Refine has no v2 corrective-retry or calculation-execution path. Half-supporting
            // it would let the same analyzer return engine-computed numbers from /analyze and
            // LLM-computed numbers from /refine — making the determinism guarantee
            // endpoint-dependent, which is worse than not having it at all. Fail closed until
            // refine grows the same envelope-v2 handling analyze has.
            return refineError("refine does not support envelope-v2 analyzers yet (analyzer=" + analyzerSlug + ")");
        }

        // Guideline grounding (cheap; same retrieval as analyze). Non-fatal on failure.
        List<RetrievedChunk> chunks = List.of();
        if (analyzer.retrievalQueryTemplate() != null && !analyzer.retrievalQueryTemplate().isBlank()) {
            try {
                RetrievalResult rr = retrieval.retrieveAdmin(
                        analyzer.retrievalQueryTemplate(), brainId,
                        SourceVisibility.INTERNAL, analyzer.corpusScope());
                chunks = rr.chunks();
            } catch (RuntimeException e) {
                log.warn("Retrieval failed for refine {}: {}", analyzerSlug, e.getMessage());
            }
        }

        String prompt = buildRefinePrompt(analyzer, chunks, req);

        ModelRouterService.RoutedResponse routed;
        try {
            routed = router.generate(AiRequest.forRefine(prompt, maxOutputTokens), brainId);
        } catch (RuntimeException e) {
            log.error("Refine model call failed for {}: {}", analyzerSlug, e.getMessage());
            return refineError("model provider error: " + e.getMessage());
        }

        JsonNode root = extractJson(routed.response().content());
        if (root == null) {
            return refineError("unparseable model response");
        }
        int inTok = nz(routed.response().promptTokens());
        int outTok = nz(routed.response().completionTokens());

        // Refine uses the v1 envelope shape ({reportMarkdown, findings, citations}). v2
        // analyzers are rejected above before any model call, so every analyzer that reaches
        // this point is guaranteed v1 — no corrective-retry or calculation-execution logic needed.
        JsonNode findings = root.has("findings") ? root.get("findings") : objectMapper.createObjectNode();
        String findingsJson = findings.toString();
        String reportMarkdown = root.path("reportMarkdown").asText("");

        List<CitationDto> citations = citationService.citationsFromChunks(chunks);
        double cost = CostTable.usd(routed.response().providerName(), routed.response().modelName(), inTok, outTok);

        return new AnalysisResult(
                AnalysisResult.Status.SUCCESS,
                reportMarkdown + DISCLAIMER,
                findingsJson,
                citations,
                routed.response().providerName(),
                routed.response().modelName(),
                inTok, outTok, cost,
                0,            // no pages read on a text-only refine
                List.of(),    // nothing skipped (no docs processed)
                null);
    }

    private AnalysisResult refineError(String reason) {
        return new AnalysisResult(AnalysisResult.Status.ERROR,
                "AI analysis could not be refined." + DISCLAIMER, "{}",
                List.of(), null, null, 0, 0, 0.0, 0, List.of(), reason);
    }

    private String buildRefinePrompt(AnalyzerConfig analyzer, List<RetrievedChunk> chunks,
                                     RefineRequest req) {
        return AnalysisPromptAssembler.join(
                AnalysisPromptAssembler.refine(AnalysisPromptAssembler.Mode.RUN, analyzer, chunks, req));
    }

    /**
     * Schema validation plus the analyzer-identity cross-check: a schema-valid envelope
     * answering as a DIFFERENT analyzer (e.g. "assets" on an income run) is a contract
     * violation and must feed the same retry/fail-closed flow.
     */
    private List<String> validateV2(JsonNode root, String requestedSlug,
                                    Set<String> allowedDocumentHandles, String requiredDomainMarker) {
        if (root == null) {
            return List.of("response was not parseable as a single JSON object");
        }
        // HIGH/MEDIUM/LOW written where the envelope wants a 0–1 score is an unambiguous
        // formatting slip (2026-09-22: it failed the first production income-v2 run twice).
        // Coerce it here so the schema judges the substance; counts only in the log — never content.
        int coercedWords = ConfidenceWordCoercer.coerce(root);
        if (coercedWords > 0) {
            log.info("Envelope v2 for {}: coerced {} confidence word(s) to scores before validation",
                    requestedSlug, coercedWords);
        }
        List<String> errors = new java.util.ArrayList<>(envelopeValidator.validate(root));
        // A declared domain (income, submission, …) is a shape contract like the envelope itself:
        // a violation routes into the same retry-once-then-fail-closed flow. Undeclared domains
        // pass, unless this analyzer requires one (requiredDomainMarker non-null).
        errors.addAll(envelopeValidator.validateDomain(root, requiredDomainMarker));
        String declared = root.path("analyzer").asText("");
        if (!requestedSlug.equals(declared)) {
            errors.add("$.analyzer: must be the requested analyzer \"" + requestedSlug
                    + "\" but was \"" + declared + "\"");
        }
        // A hallucinated method id passes schema validation (method is just "any non-empty
        // string") and would otherwise reach the executor, land in CalcResult.error(...), and
        // still return SUCCESS with a "[calculation failed: id]" report — silently defeating
        // the determinism guarantee. Reject it here instead, so it routes into the same
        // retry-once-then-fail-closed flow as any other schema violation, and by the time the
        // executor runs (after this passes, possibly after one retry) every method id is
        // guaranteed to be one IncomeCalcService.compute(...) can actually dispatch.
        JsonNode calcs = root.path("calculations");
        if (calcs.isArray()) {
            int i = 0;
            for (JsonNode c : calcs) {
                String method = c.path("method").asText("");
                if (!IncomeCalcService.SUPPORTED_METHODS.contains(method)) {
                    errors.add("$.calculations[" + i + "].method: unsupported method \"" + method
                            + "\"; supported: " + SUPPORTED_METHODS_JOINED);
                }
                i++;
            }
        }
        errors.addAll(validateEvidenceIntegrity(root, allowedDocumentHandles));
        return errors;
    }

    /**
     * Parsed-run evidence integrity, skipped entirely when {@code allowedDocumentHandles} is empty
     * so the raw path's behaviour is untouched.
     *
     * <p>The schema already forces every fact to list at least one {@code citationIds} entry, but
     * it cannot check that the entry RESOLVES, and it cannot know which borrower documents this
     * run actually rendered. Both gaps are how a model produces a report that looks cited and is
     * not: a fact pointing at a citation id that does not exist, or a {@code BORROWER_DOC}
     * citation naming a document the parse never contained. Both are reported here so they feed
     * the same retry-once-then-fail-closed flow as any other envelope violation.
     */
    private static List<String> validateEvidenceIntegrity(JsonNode root,
                                                          Set<String> allowedDocumentHandles) {
        if (allowedDocumentHandles.isEmpty()) {
            return List.of();
        }
        List<String> errors = new java.util.ArrayList<>();
        java.util.Set<String> declaredCitationIds = new java.util.LinkedHashSet<>();
        JsonNode citations = root.path("citations");
        if (citations.isArray()) {
            int i = 0;
            for (JsonNode citation : citations) {
                declaredCitationIds.add(citation.path("id").asText(""));
                if ("BORROWER_DOC".equals(citation.path("class").asText(""))) {
                    String documentId = citation.path("documentId").asText("");
                    if (!allowedDocumentHandles.contains(documentId)) {
                        errors.add("$.citations[" + i + "].documentId: \"" + documentId
                                + "\" is not a document handle from this parse; use exactly one of: "
                                + String.join(", ",
                                        allowedDocumentHandles.stream().sorted().toList()));
                    }
                }
                i++;
            }
        }
        JsonNode facts = root.path("facts");
        if (facts.isArray()) {
            int i = 0;
            for (JsonNode fact : facts) {
                JsonNode ids = fact.path("citationIds");
                if (!ids.isArray() || ids.isEmpty()) {
                    errors.add("$.facts[" + i + "].citationIds: every fact must cite at least one"
                            + " citation from this envelope");
                } else {
                    for (JsonNode id : ids) {
                        if (!declaredCitationIds.contains(id.asText(""))) {
                            errors.add("$.facts[" + i + "].citationIds: \"" + id.asText("")
                                    + "\" does not match any citations[].id in this envelope");
                        }
                    }
                }
                i++;
            }
        }
        return errors;
    }

    /** First (up to) 3 validator errors, "; "-joined — for the ERROR reason, never for logs (NPI). */
    private static String firstThree(List<String> errors) {
        return String.join("; ", errors.subList(0, Math.min(3, errors.size())));
    }

    /** Pre-call overload: no model call happened yet, so tokens/cost/provider/model are all unknown. */
    private AnalysisResult error(Run run, String legacyReason, ParsedFailureCode labCode,
                                 int attempts) {
        return error(run, legacyReason, labCode, attempts, 0, 0, 0.0, null, null);
    }

    /**
     * Post-call overload: one or two model calls already billed before this error was
     * raised (v1 unparseable response; any v2 retry-path failure). The ledger must
     * reflect the real spend — a v2 retry failure is the most expensive run in the
     * system (up to two full document-bearing calls) and must never report as free.
     */
    private AnalysisResult error(Run run, String legacyReason, ParsedFailureCode labCode,
                                 int attempts, int inputTokens, int outputTokens, Answer answer) {
        return error(run, legacyReason, labCode, attempts, inputTokens, outputTokens,
                CostTable.usd(answer.response.providerName(), answer.response.modelName(),
                        inputTokens, outputTokens),
                answer.response.providerName(), answer.response.modelName());
    }

    /**
     * The single terminal-error mapping.
     *
     * <p>{@code legacyReason} is the existing free-text reason the raw path has always recorded —
     * it names the exception and can quote a provider or validator message. On the Lab path that
     * is exactly what must not leak, so the reason becomes {@code labCode} alone. This is the
     * failure-disclosure policy threaded through shared execution: one code path, two disclosures,
     * neither able to drift from the other.
     */
    private AnalysisResult error(Run run, String legacyReason, ParsedFailureCode labCode,
                                 int attempts, int inputTokens, int outputTokens, double costUsd,
                                 String provider, String model) {
        // Carry the page-trim reports even on failure: a run that trimmed 62 pages to 9 and then
        // hit a provider error is exactly when the analyst wants "re-run without filtering".
        AnalysisResult r = new AnalysisResult(AnalysisResult.Status.ERROR,
                "AI analysis could not be completed." + DISCLAIMER, "{}",
                List.of(), provider, model, inputTokens, outputTokens, costUsd,
                run.built.pageCount(), run.built.skipped(),
                run.labSafe() ? labCode.name() : legacyReason, run.built.filtered(),
                run.manifest.runId().toString());
        record(run, r, attempts);
        return r;
    }

    /**
     * Pull the JSON value out of a model response that may carry a prose preamble, a markdown
     * fence, or a trailing note. Handles both a wrapper object and a bare top-level array (what
     * array-shaped output-schemas invite). Parsing is lenient about //-comments and a trailing
     * comma ({@link #LENIENT_JSON}); anything still not valid JSON — including a truncated
     * response with no matching close — returns null so the caller can retry / fail closed.
     */
    private JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String candidate = sliceJsonCandidate(content.strip());
        if (candidate == null) {
            return null;
        }
        try {
            return LENIENT_JSON.readTree(candidate);
        } catch (Exception e) {
            log.error("Failed to parse analyze JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Slice out the first structural JSON value: a bare array when a '[' opens before any '{'
     * (findings-array responses), otherwise the object from its first '{' to its last '}'.
     * Returns null when neither a complete object nor array delimiter pair is present.
     */
    private static String sliceJsonCandidate(String s) {
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            int arrEnd = s.lastIndexOf(']');
            if (arrEnd > arrStart) {
                return s.substring(arrStart, arrEnd + 1);
            }
        }
        if (objStart >= 0) {
            int objEnd = s.lastIndexOf('}');
            if (objEnd > objStart) {
                return s.substring(objStart, objEnd + 1);
            }
        }
        return null;
    }

    private String buildPrompt(AnalyzerConfig analyzer, List<RetrievedChunk> chunks,
                               AnalysisContext ctx, DocumentBlockService.BuildResult built) {
        return AnalysisPromptAssembler.join(
                AnalysisPromptAssembler.analyze(AnalysisPromptAssembler.Mode.RUN, analyzer, chunks, ctx, built));
    }

    /**
     * The analyze prompt as the dashboard shows it: the same ordered sections a run assembles,
     * with the given base prompt in the first slot and a description wherever a run would insert
     * loan data. Never calls a model.
     */
    public List<AnalysisPromptAssembler.PromptSection> assembly(UUID brainId, String analyzerSlug,
                                                                String basePrompt) {
        AnalyzerConfig analyzer = registry.bundle(brainId).pack().analyzers().stream()
                .filter(a -> a.slug().equals(analyzerSlug))
                .findFirst()
                .orElseThrow(() -> new AnalyzerNotFoundException(analyzerSlug))
                .withBasePrompt(basePrompt);
        return AnalysisPromptAssembler.analyze(AnalysisPromptAssembler.Mode.SKELETON, analyzer,
                List.of(), null, null);
    }

    /**
     * The parsed prompt: the STORED instruction block, the retrieved guideline context, the
     * rendered machine facts, and the stored output contract.
     *
     * <p>Deliberately short of the raw prompt's loan snapshot, org catalog, analyst notes, and
     * page-selection notes: none of them exists on this path. A parsed run has exactly one
     * evidence source, and everything the model is told about it is in {@code factsBlock}.
     */
    private String buildParsedPrompt(ParsedContract contract, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(contract.basePrompt()).append("\n\n");
        AnalysisPromptAssembler.appendGuidelineChunks(sb, chunks);
        sb.append(contract.factsBlock()).append('\n');
        AnalysisPromptAssembler.appendV2EnvelopeInstruction(sb, contract.analyzerSlug(), contract.outputSchema());
        sb.append("Parsed-facts run rules (these OVERRIDE the citation guidance above):\n");
        sb.append("- No document images or page text are attached. Every borrower value you may")
                .append(" use appears in the PARSED DOCUMENT FACTS block above.\n");
        sb.append("- A BORROWER_DOC citation's documentId MUST be exactly one of the citation")
                .append(" handles listed there (")
                .append(String.join(", ", contract.documentHandles().stream().sorted().toList()))
                .append("). Never a filename, a page id, or any other identifier.\n");
        sb.append("- Every citationIds entry MUST match a citations[].id in this same envelope.\n");
        sb.append("- A field shown as MISSING has no value. Report it under missingItems; never")
                .append(" infer, estimate, or carry it over from another document.\n");
        return sb.toString();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
