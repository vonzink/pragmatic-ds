package com.pragmaticds.rag.lab.release;

import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.analyze.calc.IncomeCalcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The release snapshot is the prototype's reproducibility boundary: a run executes the analyzer
 * contract that was stored, not the one the live pack happens to hold at that moment. These tests
 * pin the three things that boundary rests on.
 *
 * <p><b>What the manifest pins</b>, member by member, so a future edit cannot quietly drop one.
 *
 * <p><b>What the manifest merely observes.</b> {@code income-v2} declares no {@code (provider,
 * model)} pair of its own, so its effective model is resolved at run time from mutable
 * configuration — {@code brain_settings}, the brain's own answer columns, then the deployment
 * default. A manifest that pinned prompt and output contract while leaving that floating would
 * make the reproducibility promise false. So the observed selection and every input that produced
 * it are recorded <em>inside the hashed manifest</em>: they cannot change behavior silently,
 * because changing any of them changes the manifest digest and therefore raises drift.
 *
 * <p><b>That drift never advances production.</b> A changed pack or a swapped model writes one
 * idempotent CANDIDATE row; the pointer stays where it was and runs keep using the stored release.
 */
class IncomeLabReleaseServiceTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BRAIN = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private DomainPackRegistry registry;
    private BrainRepository brains;
    private RuntimeSettings settings;
    private LabInstanceRepository instances;
    private LabInstanceReleaseRepository releases;
    private LabInstancePointerRepository pointers;
    private IncomeLabReleaseService service;

    private AnalyzerConfig incomeV2;
    private Brain brain;

    @BeforeEach
    void setUp() {
        registry = mock(DomainPackRegistry.class);
        brains = mock(BrainRepository.class);
        settings = mock(RuntimeSettings.class);
        instances = mock(LabInstanceRepository.class);
        releases = mock(LabInstanceReleaseRepository.class);
        pointers = mock(LabInstancePointerRepository.class);

        incomeV2 = analyzer(null, null);
        brain = mock(Brain.class);
        when(brains.findById(BRAIN)).thenReturn(Optional.of(brain));
        when(brains.findById(OTHER_BRAIN)).thenReturn(Optional.of(brain));
        bindPack(incomeV2);

        // Nothing overridden: the deployment default answer lane is what actually runs.
        when(settings.answerProvider()).thenReturn("anthropic");

        service = new IncomeLabReleaseService(
                registry, brains, settings,
                new RagProperties.Routing("anthropic", "openai"),
                new LabManifestWriter(), instances, releases, pointers,
                mock(PlatformTransactionManager.class), 20000);
    }

    private void bindPack(AnalyzerConfig... analyzers) {
        DomainPack pack = new DomainPack("example-pack", "Synthetic Pack", null, null, null, null,
                null, null, null, null, null, null, List.of(analyzers), null, null);
        when(registry.bundle(any(UUID.class)))
                .thenReturn(new BrainPackBundle(pack, Map.of(), List.of(), Map.of()));
    }

    private static AnalyzerConfig analyzer(String providerOverride, String modelOverride) {
        return new AnalyzerConfig(
                IncomeLabReleaseService.INCOME_ANALYZER_SLUG,
                "Income Analyzer (v2)",
                "SYNTHETIC base prompt for the income analyzer.",
                "income calculation and documentation guidelines",
                8,
                "SYNTHETIC output schema block.",
                modelOverride,
                providerOverride,
                "income",
                "v2",
                "federal-income",
                null);
    }

    // ================================================================ manifest membership

    @Test
    void theManifestPinsTheAnalyzerContractItsRunWillExecute() {
        LabReleaseManifest manifest = service.buildLiveManifest(BRAIN);

        assertEquals(IncomeLabReleaseService.INCOME_INSTANCE_SLUG, manifest.instanceSlug());
        assertEquals(IncomeLabReleaseService.INCOME_ANALYZER_SLUG, manifest.analyzerSlug());
        assertEquals("Income Analyzer (v2)", manifest.pinned().analyzer().displayName());
        assertEquals("v2", manifest.pinned().analyzer().promptEnvelope());
        assertEquals("SYNTHETIC base prompt for the income analyzer.",
                manifest.pinned().analyzer().basePrompt());
        assertEquals("SYNTHETIC output schema block.", manifest.pinned().analyzer().outputSchema());
    }

    @Test
    void theManifestPinsTheOutputEnvelopeSchemaByDigestRatherThanByReference() throws Exception {
        LabReleaseManifest manifest = service.buildLiveManifest(BRAIN);

        assertEquals(IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE,
                manifest.pinned().analyzer().outputEnvelopeSchemaResource());
        byte[] live;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(IncomeLabReleaseService.OUTPUT_ENVELOPE_SCHEMA_RESOURCE)) {
            live = in.readAllBytes();
        }
        assertEquals(new LabManifestWriter().sha256Hex(live),
                manifest.pinned().analyzer().outputEnvelopeSchemaSha256());
    }

    @Test
    void theManifestPinsRetrievalQueryAndScope() {
        LabReleaseManifest manifest = service.buildLiveManifest(BRAIN);

        assertEquals("income calculation and documentation guidelines",
                manifest.pinned().retrieval().queryTemplate());
        assertEquals("income", manifest.pinned().retrieval().corpusScope());
        assertEquals(8, manifest.pinned().retrieval().declaredTopK());
    }

    @Test
    void theManifestPinsTheEngineEnvelopeAndDocumentTypeContract() {
        LabReleaseManifest.EngineContract engine =
                service.buildLiveManifest(BRAIN).pinned().engineContract();

        assertEquals(EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE, engine.resultMediaType());
        assertEquals(sorted(IncomeEnvelopeCompatibility.SUPPORTED_ENVELOPE_VERSIONS),
                engine.supportedEnvelopeVersions());
        assertEquals(sorted(IncomeEnvelopeCompatibility.SUPPORTED_CANONICALIZATION_VERSIONS),
                engine.supportedCanonicalizationVersions());
        assertEquals(sorted(IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES),
                engine.supportedDocumentTypes());
        assertEquals(sorted(IncomeEnvelopeCompatibility.REVIEW_WARNING_VALIDATION_STATUSES),
                engine.reviewWarningValidationStatuses());
        assertEquals(LabReleaseManifest.FIELDS_CONTRACT, engine.readModel().fieldsContract());
        assertEquals(List.of("CORRECTED", "MACHINE", "REJECTED"), engine.readModel().effectiveStatuses());
    }

    @Test
    void theManifestPinsTheDeterministicCalculatorMethodList() {
        assertEquals(sorted(IncomeCalcService.SUPPORTED_METHODS),
                service.buildLiveManifest(BRAIN).pinned().calculator().methods());
    }

    @Test
    void theManifestStatesItsPrototypeLimitationsAndEveryLiveDependency() {
        LabReleaseManifest.PrototypeLimitations limitations =
                service.buildLiveManifest(BRAIN).prototypeLimitations();

        assertEquals("PROTOTYPE_LIVE_DEPENDENCIES", limitations.code());
        assertEquals(List.of("CALCULATOR_IMPLEMENTATION",
                        "CORPUS_CONTENTS",
                        "MODEL_INFERENCE_BEHAVIOR",
                        "MODEL_OUTPUT_TOKEN_BUDGET",
                        "MODEL_PROVIDER_FALLBACK",
                        "MODEL_PROVIDER_SELECTION",
                        "RETRIEVAL_RANKING"),
                limitations.liveDependencies());
    }

    @Test
    void anUnknownInstanceIsRefusedRatherThanSnapshotted() {
        assertEquals(IncomeLabReleaseService.ReleaseException.Code.INSTANCE_UNKNOWN,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.buildLiveManifest(BRAIN, "assets")).code());
    }

    @Test
    void aBrainWhosePackHasNoIncomeV2CannotBeSnapshotted() {
        bindPack(new AnalyzerConfig("income", "Income Analyzer (v1)", "p", "q", 8, "s",
                null, null, "income", null, null, null));

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.ANALYZER_ABSENT,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.buildLiveManifest(BRAIN)).code());
    }

    // ================================================================ observed inference

    @Test
    void aRuntimeAnalyzerPairIsRecordedAsTheEffectiveSelection() {
        when(settings.analyzerProvider("income-v2")).thenReturn("anthropic");
        when(settings.analyzerModel("income-v2")).thenReturn("claude-sonnet-4-5");

        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals(LabReleaseManifest.SelectionSource.ANALYZER_RUNTIME_PAIR,
                observed.selectionSource());
        assertEquals("anthropic", observed.effectiveProvider());
        assertEquals("claude-sonnet-4-5", observed.effectiveModel());
    }

    @Test
    void aHalfSetRuntimeOverrideIsNotBlendedButIsStillRecorded() {
        // AnalysisService discards a half-set pair rather than sending a model to a foreign
        // provider. The manifest must agree about what runs AND still show what was configured.
        when(settings.analyzerModel("income-v2")).thenReturn("claude-sonnet-4-5");

        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals(LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE,
                observed.selectionSource());
        assertEquals("anthropic", observed.effectiveProvider());
        assertNull(observed.effectiveModel());
        assertEquals("claude-sonnet-4-5", observed.resolutionInputs().analyzerRuntimeModel());
        assertNull(observed.resolutionInputs().analyzerRuntimeProvider());
    }

    @Test
    void thePackPairIsUsedWhenNoRuntimeOverrideIsSet() {
        bindPack(analyzer("anthropic", "claude-sonnet-4-5"));

        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals(LabReleaseManifest.SelectionSource.ANALYZER_PACK_PAIR,
                observed.selectionSource());
        assertEquals("claude-sonnet-4-5", observed.effectiveModel());
    }

    @Test
    void theAnalyzeLaneIsUsedWhenTheAnalyzerPinsNothing() {
        when(settings.analyzeProvider()).thenReturn("deepseek");
        when(settings.analyzeModel()).thenReturn("deepseek-chat");

        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals(LabReleaseManifest.SelectionSource.ANALYZE_LANE, observed.selectionSource());
        assertEquals("deepseek", observed.effectiveProvider());
        assertEquals("deepseek-chat", observed.effectiveModel());
    }

    @Test
    void theBrainsOwnAnswerColumnIsUsedBeforeTheGlobalDefault() {
        when(brain.getAnswerProvider()).thenReturn("openai");
        when(brain.getAnswerModel()).thenReturn("gpt-4.1-nano");

        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals(LabReleaseManifest.SelectionSource.BRAIN_ANSWER_COLUMN,
                observed.selectionSource());
        assertEquals("openai", observed.effectiveProvider());
        assertEquals("gpt-4.1-nano", observed.effectiveModel());
    }

    @Test
    void theObservedInferenceRecordsTheFallbackLaneAndBudgetsThatRemainLive() {
        LabReleaseManifest.ObservedInference observed =
                service.buildLiveManifest(BRAIN).observedInference();

        assertEquals("openai", observed.fallbackProvider());
        assertEquals(20000, observed.maxOutputTokens());
        // The v2 contract's single corrective retry: at most two provider attempts per run.
        assertEquals(2, observed.maxProviderAttempts());
    }

    @Test
    void theObservedInferenceRecordsEveryMutableResolutionInput() {
        when(settings.analyzerProvider("income-v2")).thenReturn("anthropic");
        when(settings.analyzerModel("income-v2")).thenReturn("claude-sonnet-4-5");
        when(settings.analyzeProvider()).thenReturn("deepseek");
        when(settings.analyzeModel()).thenReturn("deepseek-chat");
        when(settings.answerModel()).thenReturn("claude-haiku-4-5");
        when(brain.getAnswerProvider()).thenReturn("openai");
        when(brain.getAnswerModel()).thenReturn("gpt-4.1-nano");
        when(brain.getLocalBaseUrl()).thenReturn("http://127.0.0.1:11434/v1");
        bindPack(analyzer("grok", "grok-4"));

        LabReleaseManifest.ResolutionInputs inputs =
                service.buildLiveManifest(BRAIN).observedInference().resolutionInputs();

        assertEquals("anthropic", inputs.analyzerRuntimeProvider());
        assertEquals("claude-sonnet-4-5", inputs.analyzerRuntimeModel());
        assertEquals("grok", inputs.analyzerPackProvider());
        assertEquals("grok-4", inputs.analyzerPackModel());
        assertEquals("deepseek", inputs.analyzeLaneProvider());
        assertEquals("deepseek-chat", inputs.analyzeLaneModel());
        assertEquals("openai", inputs.brainAnswerProvider());
        assertEquals("gpt-4.1-nano", inputs.brainAnswerModel());
        assertEquals("anthropic", inputs.globalAnswerProvider());
        assertEquals("claude-haiku-4-5", inputs.globalAnswerModel());
        assertTrue(inputs.brainLocalEndpointConfigured());
    }

    @Test
    void swappingTheRuntimeModelChangesTheManifestDigestSoItCannotDriftSilently() {
        LabManifestWriter writer = new LabManifestWriter();
        String before = writer.sha256Hex(
                writer.canonicalize(service.buildLiveManifest(BRAIN).toCanonicalMap()));

        when(settings.analyzerProvider("income-v2")).thenReturn("anthropic");
        when(settings.analyzerModel("income-v2")).thenReturn("claude-sonnet-4-5");
        String after = writer.sha256Hex(
                writer.canonicalize(service.buildLiveManifest(BRAIN).toCanonicalMap()));

        assertNotEquals(before, after,
                "a provider/model swap must be visible in the manifest digest");
    }

    @Test
    void theManifestSurvivesACanonicalRoundTripUnchanged() {
        LabReleaseManifest manifest = service.buildLiveManifest(BRAIN);
        LabManifestWriter writer = new LabManifestWriter();

        Map<String, Object> reread =
                writer.readCanonical(writer.canonicalize(manifest.toCanonicalMap()));

        assertEquals(manifest, LabReleaseManifest.fromMap(reread));
        assertEquals(manifest.toCanonicalMap(), reread);
    }

    // ================================================================ lazy bootstrap

    @Test
    void firstUseWritesReleaseOneAndInitializesThePointer() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.empty());
        when(releases.saveAndFlush(any())).thenAnswer(inv -> persisted(inv.getArgument(0)));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        LabInstanceRelease written = captureRelease();
        assertEquals(1, written.getReleaseNumber());
        assertEquals(LabInstanceRelease.ProvenanceMode.PRODUCTION, written.getProvenanceMode());
        assertEquals(BRAIN, written.getBrainId());
        assertEquals("income", written.getInstanceSlug());
        assertNull(written.getPredecessorReleaseId());
        assertFalse(state.driftDetected());
        assertEquals(written.getManifestSha256(), state.productionManifestSha256());

        LabInstancePointer pointer = capturePointer();
        assertEquals(BRAIN, pointer.getBrainId());
        assertEquals("income", pointer.getInstanceSlug());
        assertEquals(written.getId(), pointer.getProductionReleaseId());
    }

    @Test
    void repeatedUseWithTheSameManifestWritesNothingNew() {
        LabInstanceRelease production = existingProduction();

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertFalse(state.driftDetected());
        assertEquals(production.getId(), state.productionReleaseId());
        assertEquals(1, state.productionReleaseNumber());
        verify(releases, never()).saveAndFlush(any());
        verify(releases, never()).save(any());
        verify(pointers, never()).saveAndFlush(any());
        verify(pointers, never()).save(any());
    }

    @Test
    void aConcurrentFirstUseLoserAdoptsTheWinnersReleaseInsteadOfFailing() {
        LabInstanceRelease winner = release(1, LabInstanceRelease.ProvenanceMode.PRODUCTION,
                liveSha(), null);
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, "income"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(pointerTo(winner)));
        when(releases.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_lab_release_number"));
        when(releases.findByIdAndBrainIdAndInstanceSlug(winner.getId(), BRAIN, "income"))
                .thenReturn(Optional.of(winner));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertEquals(winner.getId(), state.productionReleaseId());
        assertFalse(state.driftDetected());
    }

    @Test
    void aConcurrentRegistryParentCreationAlsoAdoptsTheWinnersRelease() {
        LabInstanceRelease winner = release(1, LabInstanceRelease.ProvenanceMode.PRODUCTION,
                liveSha(), null);
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, "income"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(pointerTo(winner)));
        when(instances.existsByBrainIdAndSlug(BRAIN, "income")).thenReturn(false);
        when(instances.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_lab_instance_brain_slug"));
        when(releases.findByIdAndBrainIdAndInstanceSlug(winner.getId(), BRAIN, "income"))
                .thenReturn(Optional.of(winner));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertEquals(winner.getId(), state.productionReleaseId());
        assertFalse(state.driftDetected());
        verify(releases, never()).saveAndFlush(any());
    }

    @Test
    void aBootstrapRaceThatNeverResolvesFailsWithACodeRatherThanLooping() {
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, "income")).thenReturn(Optional.empty());
        when(releases.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_lab_release_number"));

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.RELEASE_BOOTSTRAP_CONFLICT,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.resolveInstance(BRAIN)).code());
    }

    // ================================================================ drift

    @Test
    void aChangedPackWritesOneNonCurrentCandidateAndLeavesProductionPointed() {
        LabInstanceRelease production = existingProduction();
        bindPack(analyzer("anthropic", "claude-sonnet-4-5"));   // the drift
        when(releases.findByBrainIdAndInstanceSlugAndManifestSha256(eq(BRAIN), eq("income"), any()))
                .thenReturn(Optional.empty());
        when(releases.findFirstByBrainIdAndInstanceSlugOrderByReleaseNumberDesc(BRAIN, "income"))
                .thenReturn(Optional.of(production));
        when(releases.saveAndFlush(any())).thenAnswer(inv -> persisted(inv.getArgument(0)));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        LabInstanceRelease candidate = captureRelease();
        assertEquals(LabInstanceRelease.ProvenanceMode.CANDIDATE, candidate.getProvenanceMode());
        assertEquals(2, candidate.getReleaseNumber());
        assertEquals(production.getId(), candidate.getPredecessorReleaseId());
        assertTrue(state.driftDetected());
        assertEquals(candidate.getId(), state.candidateReleaseId());
        assertEquals(production.getId(), state.productionReleaseId());
        verify(pointers, never()).save(any());
        verify(pointers, never()).saveAndFlush(any());
    }

    @Test
    void thatSameDriftSeenAgainDoesNotAppendASecondCandidate() {
        LabInstanceRelease production = existingProduction();
        bindPack(analyzer("anthropic", "claude-sonnet-4-5"));
        LabInstanceRelease candidate = release(2, LabInstanceRelease.ProvenanceMode.CANDIDATE,
                liveSha(), production.getId());
        when(releases.findByBrainIdAndInstanceSlugAndManifestSha256(BRAIN, "income", liveSha()))
                .thenReturn(Optional.of(candidate));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertTrue(state.driftDetected());
        assertEquals(candidate.getId(), state.candidateReleaseId());
        verify(releases, never()).saveAndFlush(any());
    }

    @Test
    void driftIsReportedWithBothDigestsSoAnOperatorCanSeeWhatMoved() {
        LabInstanceRelease production = existingProduction();
        bindPack(analyzer("anthropic", "claude-sonnet-4-5"));
        LabInstanceRelease candidate = release(2, LabInstanceRelease.ProvenanceMode.CANDIDATE,
                liveSha(), production.getId());
        when(releases.findByBrainIdAndInstanceSlugAndManifestSha256(BRAIN, "income", liveSha()))
                .thenReturn(Optional.of(candidate));

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertNotEquals(state.productionManifestSha256(), state.candidateManifestSha256());
        assertEquals(production.getManifestSha256(), state.productionManifestSha256());
        assertEquals(candidate.getManifestSha256(), state.candidateManifestSha256());
    }

    @Test
    void instanceStateAlwaysCarriesThePrototypeLimitationBanner() {
        existingProduction();

        IncomeLabReleaseService.InstanceState state = service.resolveInstance(BRAIN);

        assertEquals("PROTOTYPE_LIVE_DEPENDENCIES", state.prototypeLimitations());
        assertTrue(state.liveDependencies().contains("MODEL_PROVIDER_SELECTION"));
        assertTrue(state.liveDependencies().contains("CORPUS_CONTENTS"));
    }

    // ================================================================ resolve for a run

    @Test
    void aRunResolvesTheStoredReleaseNotTheLivePack() {
        LabInstanceRelease production = existingProduction();
        bindPack(analyzer("anthropic", "claude-sonnet-4-5"));   // pack changed after release
        LabInstanceRelease candidate = release(2, LabInstanceRelease.ProvenanceMode.CANDIDATE,
                liveSha(), production.getId());
        when(releases.findByBrainIdAndInstanceSlugAndManifestSha256(eq(BRAIN), eq("income"),
                eq(candidate.getManifestSha256()))).thenReturn(Optional.of(candidate));

        IncomeLabReleaseService.ResolvedRelease resolved = service.resolveForRun(BRAIN);

        assertEquals(production.getId(), resolved.releaseId());
        assertEquals(production.getManifestSha256(), resolved.manifestSha256());
        assertEquals(LabReleaseManifest.SelectionSource.GLOBAL_ANSWER_LANE,
                resolved.manifest().observedInference().selectionSource());
        assertNull(resolved.manifest().observedInference().effectiveModel());
    }

    @Test
    void aRunNeverReceivesAPromotionOrUpdatePath() {
        existingProduction();

        service.resolveForRun(BRAIN);

        verify(releases, never()).save(any());
        verify(releases, never()).saveAndFlush(any());
        verify(releases, never()).delete(any());
        verify(pointers, never()).save(any());
        verify(pointers, never()).saveAndFlush(any());
    }

    @Test
    void aPointerFromAnotherBrainIsNotAnAccessPath() {
        LabInstanceRelease production = existingProduction();
        // OTHER_BRAIN has its own (empty) pointer; the leaked release UUID must not resolve.
        when(pointers.lockByBrainIdAndInstanceSlug(OTHER_BRAIN, "income"))
                .thenReturn(Optional.of(pointerTo(production)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(production.getId(), OTHER_BRAIN, "income"))
                .thenReturn(Optional.empty());

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.RELEASE_NOT_FOUND,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.resolveForRun(OTHER_BRAIN)).code());
    }

    @Test
    void aStoredManifestThatNoLongerMatchesItsOwnDigestIsRefused() {
        LabInstanceRelease production = existingProduction();
        production.setManifestSha256("f".repeat(64));

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.RELEASE_DIGEST_MISMATCH,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.resolveForRun(BRAIN)).code());
    }

    @Test
    void aPinnedOutputEnvelopeSchemaThatTheBuildNoLongerCarriesIsRefused() {
        LabInstanceRelease production = existingProduction();
        Map<String, Object> tampered = new java.util.LinkedHashMap<>(production.getManifest());
        LabReleaseManifest manifest = LabReleaseManifest.fromMap(tampered);
        LabReleaseManifest.Analyzer analyzer = manifest.pinned().analyzer();
        LabReleaseManifest drifted = withAnalyzer(manifest, new LabReleaseManifest.Analyzer(
                analyzer.displayName(), analyzer.promptEnvelope(), analyzer.basePrompt(),
                analyzer.outputSchema(), analyzer.outputEnvelopeSchemaResource(), "a".repeat(64)));
        production.setManifest(drifted.toCanonicalMap());
        production.setManifestSha256(new LabManifestWriter()
                .sha256Hex(new LabManifestWriter().canonicalize(drifted.toCanonicalMap())));

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.RELEASE_SCHEMA_DRIFTED,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.resolveForRun(BRAIN)).code());
    }

    @Test
    void aPinnedCalculationMethodTheEngineCanNoLongerDispatchIsRefused() {
        LabInstanceRelease production = existingProduction();
        LabReleaseManifest manifest = LabReleaseManifest.fromMap(production.getManifest());
        List<String> methods = new ArrayList<>(manifest.pinned().calculator().methods());
        methods.add("income.retired_method.v1");
        LabReleaseManifest drifted = withCalculator(manifest,
                new LabReleaseManifest.Calculator(List.copyOf(methods)));
        production.setManifest(drifted.toCanonicalMap());
        production.setManifestSha256(new LabManifestWriter()
                .sha256Hex(new LabManifestWriter().canonicalize(drifted.toCanonicalMap())));

        assertEquals(IncomeLabReleaseService.ReleaseException.Code.RELEASE_CALCULATOR_DRIFTED,
                assertThrows(IncomeLabReleaseService.ReleaseException.class,
                        () -> service.resolveForRun(BRAIN)).code());
    }

    @Test
    void releaseFailuresCarryACodeAndNoManifestContent() {
        IncomeLabReleaseService.ReleaseException failure =
                new IncomeLabReleaseService.ReleaseException(
                        IncomeLabReleaseService.ReleaseException.Code.RELEASE_NOT_FOUND);

        assertEquals("RELEASE_NOT_FOUND", failure.getMessage());
        assertNull(failure.getCause());
    }

    // ================================================================ helpers

    private static List<String> sorted(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static LabReleaseManifest withAnalyzer(LabReleaseManifest manifest,
                                                   LabReleaseManifest.Analyzer analyzer) {
        LabReleaseManifest.Pinned pinned = manifest.pinned();
        return new LabReleaseManifest(manifest.manifestVersion(), manifest.canonicalization(),
                manifest.instanceSlug(), manifest.analyzerSlug(),
                new LabReleaseManifest.Pinned(analyzer, pinned.retrieval(), pinned.engineContract(),
                        pinned.calculator()),
                manifest.observedInference(), manifest.prototypeLimitations());
    }

    private static LabReleaseManifest withCalculator(LabReleaseManifest manifest,
                                                     LabReleaseManifest.Calculator calculator) {
        LabReleaseManifest.Pinned pinned = manifest.pinned();
        return new LabReleaseManifest(manifest.manifestVersion(), manifest.canonicalization(),
                manifest.instanceSlug(), manifest.analyzerSlug(),
                new LabReleaseManifest.Pinned(pinned.analyzer(), pinned.retrieval(),
                        pinned.engineContract(), calculator),
                manifest.observedInference(), manifest.prototypeLimitations());
    }

    /** The digest the current live pack + settings produce. */
    private String liveSha() {
        LabManifestWriter writer = new LabManifestWriter();
        return writer.sha256Hex(
                writer.canonicalize(service.buildLiveManifest(BRAIN).toCanonicalMap()));
    }

    /** Stands up an already-bootstrapped instance whose release matches the live pack. */
    private LabInstanceRelease existingProduction() {
        LabInstanceRelease production =
                release(1, LabInstanceRelease.ProvenanceMode.PRODUCTION, liveSha(), null);
        when(pointers.lockByBrainIdAndInstanceSlug(BRAIN, "income"))
                .thenReturn(Optional.of(pointerTo(production)));
        when(releases.findByIdAndBrainIdAndInstanceSlug(production.getId(), BRAIN, "income"))
                .thenReturn(Optional.of(production));
        return production;
    }

    private LabInstanceRelease release(int number, LabInstanceRelease.ProvenanceMode mode,
                                       String sha, UUID predecessor) {
        LabInstanceRelease row = new LabInstanceRelease();
        row.setId(UUID.randomUUID());
        row.setBrainId(BRAIN);
        row.setInstanceSlug("income");
        row.setReleaseNumber(number);
        row.setProvenanceMode(mode);
        row.setManifest(service.buildLiveManifest(BRAIN).toCanonicalMap());
        row.setManifestSha256(sha);
        row.setPredecessorReleaseId(predecessor);
        return row;
    }

    private static LabInstancePointer pointerTo(LabInstanceRelease release) {
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(release.getBrainId());
        pointer.setInstanceSlug(release.getInstanceSlug());
        pointer.setProductionReleaseId(release.getId());
        return pointer;
    }

    private static LabInstanceRelease persisted(LabInstanceRelease row) {
        if (row.getId() == null) {
            row.setId(UUID.randomUUID());
        }
        return row;
    }

    private LabInstanceRelease captureRelease() {
        org.mockito.ArgumentCaptor<LabInstanceRelease> captor =
                org.mockito.ArgumentCaptor.forClass(LabInstanceRelease.class);
        verify(releases).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private LabInstancePointer capturePointer() {
        org.mockito.ArgumentCaptor<LabInstancePointer> captor =
                org.mockito.ArgumentCaptor.forClass(LabInstancePointer.class);
        verify(pointers).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
