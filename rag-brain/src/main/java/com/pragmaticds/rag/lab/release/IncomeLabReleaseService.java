package com.pragmaticds.rag.lab.release;

import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Snapshots and resolves the prototype Income release.
 *
 * <p>A Lab run must execute a contract that was <em>decided</em>, not whatever the live pack
 * happens to hold when the request arrives. So the analyzer's instruction block, output contract,
 * retrieval query/scope, accepted engine envelope and document types, and deterministic
 * calculation vocabulary are captured once into an immutable {@code lab_instance_release} row, and
 * every run reads that row.
 *
 * <p><b>What the snapshot cannot freeze, and says so.</b> {@code income-v2} declares no
 * {@code (provider, model)} pair, so its effective model is chosen per run from mutable
 * configuration — {@code analyzer.income-v2.*} and {@code analyze.*} in {@code brain_settings}, the
 * brain's own {@code answer_provider}/{@code answer_model} columns, then the deployment default —
 * and the router may still divert to the fallback provider on failure. A manifest that pinned the
 * prompt while leaving that floating would make the reproducibility promise false, so the observed
 * selection and every input behind it are recorded <em>inside the hashed manifest</em>: they are
 * not pinned, but they cannot move without changing the digest and raising drift.
 * {@link LabReleaseManifest#LIVE_DEPENDENCIES} enumerates the gap.
 *
 * <p><b>Drift never promotes.</b> A live manifest that hashes differently from the pointed release
 * writes one idempotent {@code CANDIDATE} row and appears in instance metadata. The pointer does
 * not move, no promotion route exists in this prototype, and runs keep using the stored release.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class IncomeLabReleaseService {

    private static final Logger log = LoggerFactory.getLogger(IncomeLabReleaseService.class);

    /** The only Lab instance this prototype hosts. */
    public static final String INCOME_INSTANCE_SLUG = "income";

    /** The analyzer that instance snapshots. */
    public static final String INCOME_ANALYZER_SLUG = "income-v2";

    private static final String INCOME_DISPLAY_NAME = "Income";
    private static final String INCOME_PURPOSE = "Evaluate parsed income documents";

    /** The strict v2 output-envelope schema the analyzer's response is validated against. */
    public static final String OUTPUT_ENVELOPE_SCHEMA_RESOURCE =
            "ai/analyzer-envelope-v2.schema.json";

    /**
     * The v2 contract's provider-attempt ceiling: one call plus at most one corrective retry.
     * Recorded as observation, not enforcement — {@code AnalysisService} owns the retry.
     */
    private static final int MAX_PROVIDER_ATTEMPTS = 2;

    private final DomainPackRegistry registry;
    private final BrainRepository brains;
    private final RuntimeSettings settings;
    private final RagProperties.Routing routing;
    private final LabManifestWriter writer;
    private final LabInstanceRepository instances;
    private final LabInstanceReleaseRepository releases;
    private final LabInstancePointerRepository pointers;
    private final TransactionTemplate isolatedWrite;
    private final int maxOutputTokens;

    @Autowired
    public IncomeLabReleaseService(DomainPackRegistry registry,
                                   BrainRepository brains,
                                   RuntimeSettings settings,
                                   RagProperties properties,
                                   LabManifestWriter writer,
                                   LabInstanceRepository instances,
                                   LabInstanceReleaseRepository releases,
                                   LabInstancePointerRepository pointers,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${ragbrain.rag.analyze.max-output-tokens:20000}")
                                   int maxOutputTokens) {
        this(registry, brains, settings, properties.routing(), writer, instances, releases, pointers,
                transactionManager, maxOutputTokens);
    }

    public IncomeLabReleaseService(DomainPackRegistry registry,
                                   BrainRepository brains,
                                   RuntimeSettings settings,
                                   RagProperties.Routing routing,
                                   LabManifestWriter writer,
                                   LabInstanceRepository instances,
                                   LabInstanceReleaseRepository releases,
                                   LabInstancePointerRepository pointers,
                                   PlatformTransactionManager transactionManager,
                                   int maxOutputTokens) {
        this.registry = registry;
        this.brains = brains;
        this.settings = settings;
        this.routing = routing;
        this.writer = writer;
        this.instances = instances;
        this.releases = releases;
        this.pointers = pointers;
        this.maxOutputTokens = maxOutputTokens;
        // REQUIRES_NEW: the bootstrap insert races on purpose and expects to lose sometimes. A
        // constraint violation inside the caller's transaction would mark it rollback-only, so
        // the loser could not then read the winner's row. Its own transaction keeps that read
        // available.
        this.isolatedWrite = new TransactionTemplate(transactionManager);
        this.isolatedWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================================================================ snapshotting

    /** The manifest the current pack and configuration would produce for the Income instance. */
    public LabReleaseManifest buildLiveManifest(UUID brainId) {
        return buildLiveManifest(brainId, INCOME_INSTANCE_SLUG);
    }

    /** The manifest the current pack and configuration would produce for one Lab instance. */
    public LabReleaseManifest buildLiveManifest(UUID brainId, String instanceSlug) {
        requireIncomeInstance(instanceSlug);
        AnalyzerConfig analyzer = analyzer(brainId);
        Brain brain = brains.findById(brainId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.BRAIN_UNKNOWN));

        return new LabReleaseManifest(
                LabReleaseManifest.MANIFEST_VERSION,
                LabManifestWriter.CANONICALIZATION_VERSION,
                instanceSlug,
                INCOME_ANALYZER_SLUG,
                new LabReleaseManifest.Pinned(
                        new LabReleaseManifest.Analyzer(
                                analyzer.displayName(),
                                analyzer.isV2() ? "v2" : "v1",
                                analyzer.basePrompt(),
                                analyzer.outputSchema(),
                                OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                                liveOutputEnvelopeSchemaSha256()),
                        new LabReleaseManifest.Retrieval(
                                analyzer.retrievalQueryTemplate(),
                                analyzer.corpusScope(),
                                analyzer.retrievalTopK()),
                        new LabReleaseManifest.EngineContract(
                                EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE,
                                sorted(IncomeEnvelopeCompatibility.SUPPORTED_ENVELOPE_VERSIONS),
                                sorted(IncomeEnvelopeCompatibility
                                        .SUPPORTED_CANONICALIZATION_VERSIONS),
                                sorted(IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES),
                                sorted(IncomeEnvelopeCompatibility
                                        .REVIEW_WARNING_VALIDATION_STATUSES),
                                new LabReleaseManifest.ReadModelContract(
                                        LabReleaseManifest.FIELDS_CONTRACT,
                                        List.of("CORRECTED", "MACHINE", "REJECTED"))),
                        new LabReleaseManifest.Calculator(
                                sorted(IncomeCalcService.SUPPORTED_METHODS))),
                observeInference(analyzer, brain),
                new LabReleaseManifest.PrototypeLimitations(
                        LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                        LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    /**
     * Records the provider/model selection in effect right now, and the inputs behind it.
     *
     * <p>The order mirrors {@code AnalysisService.modelPair} followed by
     * {@code ModelRouterService.resolve(brain, ANALYZE)}: a fully-set runtime analyzer pair, then
     * the analyzer's pack pair, then the analyze lane, then the brain's answer column, then the
     * global answer lane. A half-set runtime override is discarded rather than blended — mixing a
     * runtime provider with the pack's model id would send that model to a provider it does not
     * belong to — but both halves are still recorded as inputs.
     *
     * <p>One residual gap the manifest cannot see: the router additionally drops an override that
     * names a provider with no API key configured and falls back to the lane. That is one more
     * reason {@code MODEL_PROVIDER_SELECTION} is a live dependency rather than a pin.
     */
    private LabReleaseManifest.ObservedInference observeInference(AnalyzerConfig analyzer,
                                                                  Brain brain) {
        String runtimeProvider = settings.analyzerProvider(analyzer.slug());
        String runtimeModel = settings.analyzerModel(analyzer.slug());
        String analyzeProvider = settings.analyzeProvider();
        String analyzeModel = settings.analyzeModel();
        String brainProvider = brain.getAnswerProvider();
        String brainModel = brain.getAnswerModel();
        String globalProvider = settings.answerProvider();
        String globalModel = settings.answerModel();

        LabReleaseManifest.SelectionSource source;
        String provider;
        String model;
        if (set(runtimeProvider) && set(runtimeModel)) {
            source = LabReleaseManifest.SelectionSource.ANALYZER_RUNTIME_PAIR;
            provider = runtimeProvider;
            model = runtimeModel;
        } else if (set(analyzer.providerOverride()) && set(analyzer.modelOverride())) {
            source = LabReleaseManifest.SelectionSource.ANALYZER_PACK_PAIR;
            provider = analyzer.providerOverride();
            model = analyzer.modelOverride();
        } else if (set(analyzeProvider)) {
            source = LabReleaseManifest.SelectionSource.ANALYZE_LANE;
            provider = analyzeProvider;
            model = analyzeModel;
        } else if (set(brainProvider)) {
            source = LabReleaseManifest.SelectionSource.BRAIN_ANSWER_COLUMN;
            provider = brainProvider;
            model = brainModel;
        } else {
            source = LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE;
            provider = set(globalProvider) ? globalProvider : routing.defaultProvider();
            model = globalModel;
        }

        return new LabReleaseManifest.ObservedInference(
                source,
                provider,
                model,
                routing.fallbackProvider(),
                maxOutputTokens,
                MAX_PROVIDER_ATTEMPTS,
                new LabReleaseManifest.ResolutionInputs(
                        runtimeProvider, runtimeModel,
                        analyzer.providerOverride(), analyzer.modelOverride(),
                        analyzeProvider, analyzeModel,
                        brainProvider, brainModel,
                        globalProvider, globalModel,
                        set(brain.getLocalBaseUrl())));
    }

    // ================================================================ bootstrap and drift

    /** Instance metadata: the pointed release, plus any drift the live pack has introduced. */
    @Transactional
    public InstanceState resolveInstance(UUID brainId) {
        return resolveInstance(brainId, INCOME_INSTANCE_SLUG);
    }

    /**
     * Lazily bootstraps the instance on first use, then reports its pointed release and drift.
     *
     * <p>Bootstrap cannot be serialized by the pointer lock alone: {@code SELECT … FOR UPDATE}
     * locks rows that exist, and on first use there is none. So the insert is attempted and the
     * database's {@code UNIQUE} constraints arbitrate; a loser re-reads the winner's pointer. That
     * is honest about the race instead of pretending a lock covered it.
     */
    @Transactional
    public InstanceState resolveInstance(UUID brainId, String instanceSlug) {
        LabReleaseManifest live = buildLiveManifest(brainId, instanceSlug);
        String liveSha = digestOf(live);
        LabInstanceRelease production = pointedRelease(brainId, instanceSlug, live, liveSha);

        if (production.getManifestSha256().equals(liveSha)) {
            return state(instanceSlug, production, null);
        }
        return state(instanceSlug, production,
                candidateFor(brainId, instanceSlug, live, liveSha, production));
    }

    /**
     * The exact stored production release a run executes against.
     *
     * <p>Deliberately does not consult the live pack beyond the bootstrap it may have to perform:
     * drift belongs to instance metadata, and a run that quietly re-derived its contract from the
     * pack would be the very thing this release exists to prevent.
     */
    @Transactional
    public ResolvedRelease resolveForRun(UUID brainId) {
        return resolveForRun(brainId, INCOME_INSTANCE_SLUG);
    }

    /** The exact stored production release for one instance, with its pins verified. */
    @Transactional
    public ResolvedRelease resolveForRun(UUID brainId, String instanceSlug) {
        LabReleaseManifest live = buildLiveManifest(brainId, instanceSlug);
        LabInstanceRelease production =
                pointedRelease(brainId, instanceSlug, live, digestOf(live));
        LabReleaseManifest stored = LabReleaseManifest.fromMap(production.getManifest());
        verifyPinsAreStillSatisfiable(stored);
        return new ResolvedRelease(production.getId(), production.getReleaseNumber(),
                production.getManifestSha256(), stored);
    }

    /**
     * Loads the pointed release, bootstrapping the instance if it has none yet, and re-verifies
     * that the stored manifest still hashes to the digest stored beside it.
     */
    private LabInstanceRelease pointedRelease(UUID brainId, String instanceSlug,
                                              LabReleaseManifest live, String liveSha) {
        Optional<LabInstancePointer> pointer =
                pointers.lockByBrainIdAndInstanceSlug(brainId, instanceSlug);
        LabInstanceRelease production = pointer.isPresent()
                ? load(pointer.get().getProductionReleaseId(), brainId, instanceSlug)
                : bootstrap(brainId, instanceSlug, live, liveSha);

        if (!digestOfStored(production).equals(production.getManifestSha256())) {
            // The stored manifest and its recorded digest disagree, so neither can be trusted as
            // the contract a run executes. Refuse rather than analyze against an unknown release.
            throw new ReleaseException(ReleaseException.Code.RELEASE_DIGEST_MISMATCH);
        }
        return production;
    }

    /** Writes release 1 and the pointer, or adopts the release a concurrent first use won. */
    private LabInstanceRelease bootstrap(UUID brainId, String instanceSlug,
                                         LabReleaseManifest live, String liveSha) {
        try {
            return isolatedWrite.execute(status -> {
                establishRegistryParent(brainId, instanceSlug);
                LabInstanceRelease created = releases.saveAndFlush(newRelease(
                        brainId, instanceSlug, 1,
                        LabInstanceRelease.ProvenanceMode.PRODUCTION, live, liveSha, null));
                LabInstancePointer pointer = new LabInstancePointer();
                pointer.setBrainId(brainId);
                pointer.setInstanceSlug(instanceSlug);
                pointer.setProductionReleaseId(created.getId());
                pointers.saveAndFlush(pointer);
                return created;
            });
        } catch (DataIntegrityViolationException lostTheRace) {
            log.info("Lab instance {} was bootstrapped concurrently; adopting the stored release",
                    instanceSlug);
            LabInstancePointer winner =
                    pointers.lockByBrainIdAndInstanceSlug(brainId, instanceSlug)
                            .orElseThrow(() -> new ReleaseException(
                                    ReleaseException.Code.RELEASE_BOOTSTRAP_CONFLICT));
            return load(winner.getProductionReleaseId(), brainId, instanceSlug);
        }
    }

    /**
     * Establishes the V35 parent in the same isolated transaction as release 1 and its pointer.
     * A concurrent first use can race between the scoped check and insert; the unique registry key
     * rejects one transaction, and the existing bootstrap loser path adopts the winner's pointer.
     */
    private void establishRegistryParent(UUID brainId, String instanceSlug) {
        if (!instances.existsByBrainIdAndSlug(brainId, instanceSlug)) {
            instances.saveAndFlush(new LabInstance(
                    brainId, instanceSlug, INCOME_DISPLAY_NAME, INCOME_PURPOSE));
        }
    }

    /**
     * Records drift as one non-current candidate. Idempotent by
     * {@code UNIQUE (brain_id, instance_slug, manifest_sha256)}: the same drift seen again finds
     * its existing row instead of appending another. The pointer is never touched here.
     */
    private LabInstanceRelease candidateFor(UUID brainId, String instanceSlug,
                                            LabReleaseManifest live, String liveSha,
                                            LabInstanceRelease production) {
        Optional<LabInstanceRelease> existing = releases
                .findByBrainIdAndInstanceSlugAndManifestSha256(brainId, instanceSlug, liveSha);
        if (existing.isPresent()) {
            return existing.get();
        }
        int nextNumber = releases
                .findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(brainId, instanceSlug)
                .map(LabInstanceRelease::getReleaseNumber)
                .orElse(production.getReleaseNumber()) + 1;
        log.info("Lab instance {} drifted from its pointed release; recording candidate {}",
                instanceSlug, nextNumber);
        try {
            return isolatedWrite.execute(status -> releases.saveAndFlush(newRelease(
                    brainId, instanceSlug, nextNumber,
                    LabInstanceRelease.ProvenanceMode.CANDIDATE, live, liveSha,
                    production.getId())));
        } catch (DataIntegrityViolationException concurrentlyRecorded) {
            return releases
                    .findByBrainIdAndInstanceSlugAndManifestSha256(brainId, instanceSlug, liveSha)
                    .orElseThrow(() -> new ReleaseException(
                            ReleaseException.Code.RELEASE_BOOTSTRAP_CONFLICT));
        }
    }

    private static LabInstanceRelease newRelease(UUID brainId, String instanceSlug, int number,
                                                 LabInstanceRelease.ProvenanceMode mode,
                                                 LabReleaseManifest manifest, String sha,
                                                 UUID predecessor) {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setBrainId(brainId);
        release.setInstanceSlug(instanceSlug);
        release.setReleaseNumber(number);
        release.setProvenanceMode(mode);
        release.setManifest(manifest.toCanonicalMap());
        release.setManifestSha256(sha);
        release.setPredecessorReleaseId(predecessor);
        return release;
    }

    /**
     * Every release read is brain- and instance-scoped, so a leaked release UUID is not an access
     * path: another brain's pointer resolves to nothing rather than to that brain's contract.
     */
    private LabInstanceRelease load(UUID releaseId, UUID brainId, String instanceSlug) {
        return releases.findByIdAndBrainIdAndInstanceSlug(releaseId, brainId, instanceSlug)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.RELEASE_NOT_FOUND));
    }

    /**
     * Confirms the running build can still honor what the stored release pinned.
     *
     * <p>The v2 output-envelope schema is pinned by digest and the calculation vocabulary by id;
     * neither is copied into the manifest, so both are checked here. A build that no longer
     * carries the pinned schema, or that can no longer dispatch a pinned method, would silently
     * analyze under a different contract — so it fails closed instead.
     */
    private void verifyPinsAreStillSatisfiable(LabReleaseManifest stored) {
        if (!liveOutputEnvelopeSchemaSha256()
                .equals(stored.pinned().analyzer().outputEnvelopeSchemaSha256())) {
            throw new ReleaseException(ReleaseException.Code.RELEASE_SCHEMA_DRIFTED);
        }
        if (!IncomeCalcService.SUPPORTED_METHODS
                .containsAll(stored.pinned().calculator().methods())) {
            throw new ReleaseException(ReleaseException.Code.RELEASE_CALCULATOR_DRIFTED);
        }
    }

    // ================================================================ helpers

    private AnalyzerConfig analyzer(UUID brainId) {
        return registry.bundle(brainId).pack().analyzers().stream()
                .filter(candidate -> INCOME_ANALYZER_SLUG.equals(candidate.slug()))
                .findFirst()
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.ANALYZER_ABSENT));
    }

    private static void requireIncomeInstance(String instanceSlug) {
        if (!INCOME_INSTANCE_SLUG.equals(instanceSlug)) {
            throw new ReleaseException(ReleaseException.Code.INSTANCE_UNKNOWN);
        }
    }

    private String digestOf(LabReleaseManifest manifest) {
        return writer.sha256Hex(writer.canonicalize(manifest.toCanonicalMap()));
    }

    private String digestOfStored(LabInstanceRelease release) {
        return writer.sha256Hex(writer.canonicalize(release.getManifest()));
    }

    private String liveOutputEnvelopeSchemaSha256() {
        try (InputStream in = IncomeLabReleaseService.class.getClassLoader()
                .getResourceAsStream(OUTPUT_ENVELOPE_SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new ReleaseException(ReleaseException.Code.RELEASE_SCHEMA_DRIFTED);
            }
            return writer.sha256Hex(in.readAllBytes());
        } catch (IOException unreadable) {
            throw new ReleaseException(ReleaseException.Code.RELEASE_SCHEMA_DRIFTED);
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static boolean set(String value) {
        return value != null && !value.isBlank();
    }

    private static InstanceState state(String instanceSlug, LabInstanceRelease production,
                                       LabInstanceRelease candidate) {
        return new InstanceState(
                instanceSlug,
                INCOME_ANALYZER_SLUG,
                production.getId(),
                production.getReleaseNumber(),
                production.getManifestSha256(),
                candidate != null,
                candidate == null ? null : candidate.getId(),
                candidate == null ? null : candidate.getManifestSha256(),
                LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                LabReleaseManifest.LIVE_DEPENDENCIES);
    }

    /**
     * Instance metadata for the Lab's instances view: identifiers, digests, and the prototype
     * boundary. Value-free by construction — no manifest body travels in it.
     */
    public record InstanceState(
            String instanceSlug,
            String analyzerSlug,
            UUID productionReleaseId,
            int productionReleaseNumber,
            String productionManifestSha256,
            boolean driftDetected,
            UUID candidateReleaseId,
            String candidateManifestSha256,
            String prototypeLimitations,
            List<String> liveDependencies) {}

    /** The stored release a run executes against. */
    public record ResolvedRelease(
            UUID releaseId,
            int releaseNumber,
            String manifestSha256,
            LabReleaseManifest manifest) {}

    /** A payload-free release failure: a stable code, no manifest content, no cause. */
    public static final class ReleaseException extends RuntimeException {

        /** Stable, value-free release failure taxonomy. */
        public enum Code {
            /** The requested Lab instance is not hosted by this prototype. */
            INSTANCE_UNKNOWN,
            /** The brain does not exist. */
            BRAIN_UNKNOWN,
            /** The brain's pack declares no {@code income-v2} analyzer to snapshot. */
            ANALYZER_ABSENT,
            /** No release with that identity exists for this brain and instance. */
            RELEASE_NOT_FOUND,
            /** A stored manifest no longer hashes to the digest recorded beside it. */
            RELEASE_DIGEST_MISMATCH,
            /** The running build no longer carries the pinned output-envelope schema. */
            RELEASE_SCHEMA_DRIFTED,
            /** The engine can no longer dispatch a calculation method the release pinned. */
            RELEASE_CALCULATOR_DRIFTED,
            /** A concurrent bootstrap neither won nor left a pointer to adopt. */
            RELEASE_BOOTSTRAP_CONFLICT
        }

        private final Code code;

        public ReleaseException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /** Exposed for callers that need the instance's live dependency list without a lookup. */
    public static Map<String, Object> prototypeBoundary() {
        return Map.of(
                "code", LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE,
                "liveDependencies", LabReleaseManifest.LIVE_DEPENDENCIES);
    }
}
