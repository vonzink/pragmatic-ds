package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.RunManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs the pinned Income release against one immutable parsed engine revision — and stops safely
 * when it cannot.
 *
 * <p><b>The rule this class exists to enforce: no raw-document fallback, ever.</b> If the selected
 * release's compatibility predicate rejects the envelope, if the parse is unusable, or if the
 * revision the caller pinned is not the revision it fetched, the run STOPS. There is no branch
 * here that reaches for an original document, and no collaborator injected here that could supply
 * one: this service holds a release resolver, a declarative predicate, a text renderer, and the
 * analyzer's parsed entry point. {@code /analyze}'s multipart path and {@code /extract} remain
 * exactly as they were, and the Lab simply never calls them.
 *
 * <p><b>Where each input comes from, stated so it can be checked.</b> The analyzer instructions,
 * output contract, retrieval query and corpus scope, and the compatibility vocabularies all come
 * from the STORED release manifest — editing the live pack or the static compatibility defaults
 * after a release exists cannot change what that release does. Model selection, retrieval ranking,
 * corpus contents, and the calculator implementation deliberately do not: the release declares
 * them live in {@link LabReleaseManifest#LIVE_DEPENDENCIES}, so the run records what actually ran
 * rather than pretending to have frozen it.
 *
 * <p>Failures stay in their own taxonomies. {@code ReleaseException}, {@code ManifestException},
 * {@code LabContractException}, and {@code DocumentEngineFailure} propagate unwrapped for the Lab
 * exception handler to map; only genuinely parsed-analysis-specific failures become a
 * {@link ParsedAnalysisException}. Nothing here attaches a cause or a message that could carry a
 * value.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class ParsedIncomeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ParsedIncomeAnalysisService.class);

    /**
     * The predicate is a pure function over declarative data, so it is constructed rather than
     * injected — the same treatment {@code HttpDocumentEngineClient} gives the envelope parser.
     * Making it a bean would add a wiring failure mode for something that has no configuration and
     * no state, and the vocabularies it evaluates come from the run's stored release anyway.
     */
    private static final IncomeEnvelopeCompatibility COMPATIBILITY =
            new IncomeEnvelopeCompatibility();

    private final IncomeLabReleaseService releases;
    private final ParsedDocumentPromptRenderer renderer;
    private final AnalysisService analysisService;

    public ParsedIncomeAnalysisService(IncomeLabReleaseService releases,
                                       ParsedDocumentPromptRenderer renderer,
                                       AnalysisService analysisService) {
        this.releases = releases;
        this.renderer = renderer;
        this.analysisService = analysisService;
    }

    /**
     * Analyzes one verified engine revision under the brain's pinned Income release.
     *
     * <p>Order is load-bearing: release, then source identity, then compatibility, and only then
     * retrieval and the model. Every refusal above happens before a single chunk is retrieved or a
     * single token is billed.
     */
    public ParsedRunOutcome analyze(ParsedAnalysisInput input) {
        Objects.requireNonNull(input, "input");

        IncomeLabReleaseService.ResolvedRelease release = releases.resolveForRun(input.brainId());
        LabReleaseManifest manifest = release.manifest();

        EngineResultEnvelope envelope = requireTheRevisionThisRunPinned(input);

        IncomeEnvelopeCompatibility.Decision decision =
                COMPATIBILITY.evaluate(envelope, policyFrom(manifest));
        if (!decision.compatible()) {
            log.warn("Parsed Lab run refused: envelope incompatible with release {} ({}) [{}]",
                    release.releaseId(), decision.rejection(), input.correlationId());
            throw new ParsedAnalysisException(
                    ParsedAnalysisException.Code.PARSED_ENVELOPE_INCOMPATIBLE,
                    decision.rejection().name());
        }

        ParsedDocumentPromptRenderer.Rendered rendered = renderer.render(envelope, decision);

        RunManifest runManifest = RunManifest.forParsedSource(
                input.analysisRunId(), input.brainId(), manifest.analyzerSlug(),
                manifest.pinned().analyzer().promptEnvelope(),
                new RunManifest.ParsedSource(
                        envelope.packageId(),
                        envelope.generation().packageRevision(),
                        envelope.generation().parseGeneration(),
                        envelope.generation().processingJobId(),
                        envelope.generation().sourceSetSha256(),
                        input.verified().artifact().sha256(),
                        input.verified().artifact().byteCount(),
                        envelope.envelopeVersion(),
                        release.releaseId(),
                        input.reviewSnapshotSha256()));

        AnalysisService.ParsedOutcome outcome = analysisService.analyzeParsed(
                input.brainId(),
                runManifest,
                new AnalysisService.ParsedContract(
                        manifest.analyzerSlug(),
                        manifest.pinned().analyzer().promptEnvelope(),
                        manifest.pinned().analyzer().basePrompt(),
                        manifest.pinned().analyzer().outputSchema(),
                        manifest.pinned().retrieval().queryTemplate(),
                        manifest.pinned().retrieval().corpusScope(),
                        rendered.text(),
                        rendered.handleIds(),
                        input.correlationId()));

        return new ParsedRunOutcome(outcome.result(),
                provenance(input, release, manifest, envelope, rendered, decision, runManifest,
                        outcome));
    }

    // ---------------------------------------------------------------- guards

    /**
     * Confirms the envelope in hand is the one this run means to pin.
     *
     * <p>Three ways it can fail, all of them a stop rather than a re-fetch or a fallback: the
     * envelope declares a different package than the caller asked about; the caller's selected
     * descriptor does not describe these bytes (a stale revision, a superseded parse generation, or
     * a different source set); or the parse is simply not this package's.
     */
    private EngineResultEnvelope requireTheRevisionThisRunPinned(ParsedAnalysisInput input) {
        EngineResultEnvelope envelope = input.verified().envelope();
        if (!envelope.packageId().equals(input.packageId())) {
            throw new DocumentEngineFailure(DocumentEngineFailure.Code.ENGINE_PACKAGE_MISMATCH);
        }
        if (!input.descriptor().describes(input.verified())) {
            log.warn("Parsed Lab run refused: revision {} no longer describes the fetched envelope"
                    + " [{}]", input.descriptor().revision(), input.correlationId());
            throw new ParsedAnalysisException(
                    ParsedAnalysisException.Code.PARSED_SOURCE_STALE,
                    String.valueOf(input.descriptor().revision()));
        }
        return envelope;
    }

    /** The compatibility vocabularies THIS release accepts, never the shipped defaults. */
    private static IncomeEnvelopeCompatibility.Policy policyFrom(LabReleaseManifest manifest) {
        LabReleaseManifest.EngineContract contract = manifest.pinned().engineContract();
        return IncomeEnvelopeCompatibility.Policy.of(
                contract.supportedEnvelopeVersions(),
                contract.supportedCanonicalizationVersions(),
                contract.supportedDocumentTypes(),
                contract.reviewWarningValidationStatuses());
    }

    // ---------------------------------------------------------------- provenance

    private static RunProvenance provenance(ParsedAnalysisInput input,
                                            IncomeLabReleaseService.ResolvedRelease release,
                                            LabReleaseManifest manifest,
                                            EngineResultEnvelope envelope,
                                            ParsedDocumentPromptRenderer.Rendered rendered,
                                            IncomeEnvelopeCompatibility.Decision decision,
                                            RunManifest runManifest,
                                            AnalysisService.ParsedOutcome outcome) {
        LabReleaseManifest.Retrieval retrieval = manifest.pinned().retrieval();
        return new RunProvenance(
                input.analysisRunId(),
                release.releaseId(),
                release.releaseNumber(),
                release.manifestSha256(),
                envelope.packageId(),
                envelope.generation().packageRevision(),
                envelope.generation().parseGeneration(),
                envelope.generation().processingJobId(),
                envelope.generation().sourceSetSha256(),
                input.verified().artifact().sha256(),
                input.verified().artifact().byteCount(),
                envelope.envelopeVersion(),
                envelope.canonicalizationVersion(),
                outcome.resolution(),
                new RetrievalProvenance(
                        retrieval.queryTemplate() != null && !retrieval.queryTemplate().isBlank(),
                        retrieval.corpusScope(),
                        retrieval.declaredTopK(),
                        runManifest.retrievedChunkIds().size(),
                        true),
                rendered.handles().stream()
                        .map(ParsedDocumentPromptRenderer.DocumentHandle::handle).toList(),
                decision.warnings().size(),
                outcome.attempts(),
                LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                LabReleaseManifest.LIVE_DEPENDENCIES);
    }

    /** One parsed run's terminal result plus the provenance that says exactly what produced it. */
    public record ParsedRunOutcome(AnalysisResult result, RunProvenance provenance) {}

    /**
     * What this run pinned and what actually ran — value-free by construction: identifiers,
     * digests, versions, counts, and provider names only.
     */
    public record RunProvenance(
            UUID analysisRunId,
            UUID releaseId,
            int releaseNumber,
            String releaseManifestSha256,
            UUID packageId,
            int packageRevision,
            int parseGeneration,
            UUID processingJobId,
            String sourceSetSha256,
            String envelopeSha256,
            int envelopeByteCount,
            String envelopeVersion,
            String canonicalizationVersion,
            ModelRouterService.Resolution modelResolution,
            RetrievalProvenance retrieval,
            List<String> documentHandles,
            int parseWarningCount,
            int providerAttempts,
            String prototypeLimitations,
            List<String> liveDependencies) {

        public RunProvenance {
            documentHandles = List.copyOf(documentHandles);
            liveDependencies = List.copyOf(liveDependencies);
        }
    }

    /**
     * What the release pinned about retrieval, and what retrieval actually did.
     *
     * <p>The distinction is the point. {@code queryFromRelease} and {@code corpusScope} ARE pinned:
     * the run passes the stored values straight to the retrieval call. {@code declaredTopK} is
     * not — {@code RetrievalService.retrieveAdmin} resolves top-k, the confidence threshold, and
     * reranking from live {@code brain_settings} and never consults the analyzer's declared value.
     * So {@code rankingLive} is constantly true and {@code retrievedChunkCount} is reported as the
     * observed fact it is: it may exceed {@code declaredTopK}, and that is not a bug, it is the
     * declared {@code RETRIEVAL_RANKING} live dependency being visible instead of hidden.
     */
    public record RetrievalProvenance(
            boolean queryFromRelease,
            String corpusScope,
            int declaredTopK,
            int retrievedChunkCount,
            boolean rankingLive) {}

    /** A payload-free parsed-analysis failure: a stable code plus a bounded, value-free detail. */
    public static final class ParsedAnalysisException extends RuntimeException {

        /** Stable, value-free parsed-analysis failure taxonomy. */
        public enum Code {
            /**
             * The selected release's compatibility predicate rejected this envelope. There is no
             * raw-document fallback: the run stops.
             */
            PARSED_ENVELOPE_INCOMPATIBLE,
            /**
             * The descriptor the caller pinned no longer describes the bytes it fetched, so the
             * parse this run would analyze is not the parse it selected.
             */
            PARSED_SOURCE_STALE
        }

        private final Code code;
        private final String detail;

        public ParsedAnalysisException(Code code, String detail) {
            super(Objects.requireNonNull(code, "code").name(), null, false, true);
            this.code = code;
            this.detail = detail;
        }

        public Code code() {
            return code;
        }

        /** A bounded discriminator — a rejection code or a revision number, never a value. */
        public String detail() {
            return detail;
        }

        @Override
        public String toString() {
            return "ParsedAnalysisException[code=" + code + ", detail=" + detail + "]";
        }
    }
}
