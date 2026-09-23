package com.pragmaticds.rag.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.config.LearningProperties;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.ChunkSearchResult;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.ingestion.EmbeddingService;
import com.pragmaticds.rag.service.learning.SourceWeightService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the adaptive-retrieval multiplier helper. The helper is the
 * one place a learned per-source weight touches the ranking score. Its
 * contract: multiply by the document's weight (default 1.0), but never let a
 * caller-supplied weight escape the clamp band — that band is the structural
 * guarantee that learning cannot bury an authoritative source below a
 * non-authoritative one by more than the band.
 */
class RetrievalServiceWeightHookTest {

    private static final double MIN = 0.8;
    private static final double MAX = 1.2;

    private static final UUID DOC_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DOC_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    // Switch OFF / no learned weights: an empty map must be a byte-for-byte
    // no-op — the combined score comes back exactly unchanged.
    @Test
    void emptyMapReturnsScoreUnchanged() {
        double combined = 0.734215;
        assertEquals(combined,
                RetrievalService.applySourceWeight(combined, DOC_A, Map.of(), MIN, MAX),
                0.0);
    }

    // A document with no learned weight in a non-empty map still defaults to 1.0.
    @Test
    void missingDocumentDefaultsToNeutralWeight() {
        double combined = 0.5;
        assertEquals(0.5,
                RetrievalService.applySourceWeight(combined, DOC_B, Map.of(DOC_A, 1.15), MIN, MAX),
                1e-12);
    }

    // A down-weighted document has its score reduced (so it can drop in rank).
    @Test
    void downWeightReducesScore() {
        double combined = 1.0;
        assertEquals(0.8,
                RetrievalService.applySourceWeight(combined, DOC_A, Map.of(DOC_A, 0.8), MIN, MAX),
                1e-12);
    }

    // An up-weighted document has its score increased.
    @Test
    void upWeightRaisesScore() {
        double combined = 1.0;
        assertEquals(1.2,
                RetrievalService.applySourceWeight(combined, DOC_A, Map.of(DOC_A, 1.2), MIN, MAX),
                1e-12);
    }

    // Defense in depth: even if a corrupt weight below the clamp reaches the
    // hook, the multiplier is clamped to MIN — the score cannot fall further
    // than the band allows.
    @Test
    void weightBelowBandIsClampedToMin() {
        double combined = 1.0;
        assertEquals(0.8,
                RetrievalService.applySourceWeight(combined, DOC_A, Map.of(DOC_A, 0.1), MIN, MAX),
                1e-12);
    }

    // Defense in depth: a weight above the band is clamped to MAX.
    @Test
    void weightAboveBandIsClampedToMax() {
        double combined = 1.0;
        assertEquals(1.2,
                RetrievalService.applySourceWeight(combined, DOC_A, Map.of(DOC_A, 5.0), MIN, MAX),
                1e-12);
    }

    /**
     * R7 remediation: proves the query-time clamp reads its bounds from
     * LearningProperties (single source of truth with the aggregation job)
     * instead of hardcoded constants, and that the per-brain learningEnabled
     * flag is cached rather than re-read from the DB on every retrieve() call
     * — with cache invalidation wired to the admin toggle.
     */
    @Nested
    class InstanceBehaviorTest {

        private static final UUID BRAIN_ID = TestBrains.DEFAULT_ID;
        private static final UUID DOC_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

        private DocumentChunkRepository chunkRepository;
        private EmbeddingService embeddingService;
        private RerankerService rerankerService;
        private DomainPackRegistry packRegistry;
        private RuntimeSettings settings;
        private VocabularyService vocabularyService;
        private BrainRepository brainRepository;
        private BrainProfileRepository brainProfileRepository;
        private SourceWeightService sourceWeightService;

        @BeforeEach
        void setUp() {
            chunkRepository = mock(DocumentChunkRepository.class);
            embeddingService = mock(EmbeddingService.class);
            rerankerService = mock(RerankerService.class);
            packRegistry = mock(DomainPackRegistry.class);
            settings = mock(RuntimeSettings.class);
            vocabularyService = mock(VocabularyService.class);
            brainRepository = mock(BrainRepository.class);
            brainProfileRepository = mock(BrainProfileRepository.class);
            sourceWeightService = mock(SourceWeightService.class);

            when(embeddingService.embed(anyString())).thenReturn(new float[]{1f});
            when(packRegistry.bundle(any(UUID.class))).thenReturn(
                    new BrainPackBundle(null, Map.of(), List.of(), Map.of()));
            when(vocabularyService.effectiveSynonyms(any(UUID.class))).thenReturn(Map.of());
            when(settings.rerankEnabled()).thenReturn(false);
            when(settings.topK()).thenReturn(5);
            when(settings.confidenceThreshold()).thenReturn(0.0);
            when(brainProfileRepository.findByBrainId(any(UUID.class))).thenReturn(Optional.empty());
        }

        private RetrievalService serviceWith(LearningProperties learningProperties) {
            RagProperties.Retrieval retrievalConfig = new RagProperties.Retrieval(
                    5, 1, 0.0, 0.5, 0.5, false, 10, true, 0.0);
            RagProperties properties = new RagProperties(
                    new RagProperties.Routing("openai", "openai"),
                    retrievalConfig, null, null, null, null, null);
            return new RetrievalService(
                    chunkRepository, embeddingService, rerankerService, new ObjectMapper(),
                    properties, packRegistry, settings, vocabularyService,
                    brainRepository, brainProfileRepository, sourceWeightService, learningProperties,
                    new AuthorityFilterService());
        }

        private ChunkSearchResult hit(UUID documentId, double score) {
            return hit(documentId, score, "AGENCY_GUIDELINE", "title");
        }

        private ChunkSearchResult hit(UUID documentId, double score, String sourceType, String title) {
            ChunkSearchResult h = mock(ChunkSearchResult.class);
            when(h.getChunkId()).thenReturn(UUID.randomUUID());
            when(h.getDocumentId()).thenReturn(documentId);
            when(h.getContent()).thenReturn("content");
            when(h.getSourceName()).thenReturn("source");
            when(h.getSourceType()).thenReturn(sourceType);
            when(h.getDocumentTitle()).thenReturn(title);
            when(h.getEffectiveDate()).thenReturn(LocalDate.now());
            when(h.getScore()).thenReturn(score);
            return h;
        }

        @Test
        void snapshotRetrievalUsesPinnedQueriesAndCarriesContentIdentity() {
            UUID snapshotId = UUID.fromString("00000000-0000-4000-8000-0000000000d4");
            String contentHash = "a".repeat(64);
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.empty());
            ChunkSearchResult h = hit(DOC_ID, 1.0);
            when(h.getContentSha256()).thenReturn(contentHash);
            when(chunkRepository.searchByVectorSnapshot(
                    anyString(), anyInt(), any(UUID.class), any(), any(UUID.class)))
                    .thenReturn(List.of(h));
            when(chunkRepository.searchByKeywordSnapshot(
                    anyString(), anyInt(), any(UUID.class), any(), any(UUID.class)))
                    .thenReturn(List.of());

            RetrievalResult result = serviceWith(new LearningProperties(
                    0.8, 1.2, 0.05, 5, 3, 0.10, 0.98))
                    .retrieveSnapshot("income question", BRAIN_ID, snapshotId,
                            com.pragmaticds.rag.domain.SourceVisibility.INTERNAL, 3, false);

            assertEquals(1, result.chunks().size());
            assertEquals(contentHash, result.chunks().getFirst().contentSha256());
            verify(chunkRepository).searchByVectorSnapshot(
                    anyString(), org.mockito.ArgumentMatchers.eq(6),
                    org.mockito.ArgumentMatchers.eq(BRAIN_ID),
                    org.mockito.ArgumentMatchers.eq("INTERNAL"),
                    org.mockito.ArgumentMatchers.eq(snapshotId));
            verify(chunkRepository, never()).searchByVector(
                    anyString(), anyInt(), any(UUID.class), any());
            verify(chunkRepository, never()).searchByVectorAdmin(
                    anyString(), anyInt(), any(UUID.class), any(), any());
        }

        /**
         * Proves the authority tie-break is actually REACHED by retrieve() — not
         * merely correct in isolation. Two equally-scored chunks from different
         * source types must come back company-rule first.
         */
        @Test
        void authorityTieBreakIsAppliedThroughRetrieve() {
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.empty());
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of());

            UUID eduDoc = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
            UUID policyDoc = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
            // Identical scores, and the low-authority chunk is returned FIRST so a
            // pass-through would leave it on top. Build the hit mocks BEFORE the
            // outer stubbing — stubbing a mock inside an unfinished when() throws.
            ChunkSearchResult edu = hit(eduDoc, 1.0, "EDUCATIONAL", "educational");
            ChunkSearchResult policy = hit(policyDoc, 1.0, "INTERNAL_POLICY", "policy");
            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of(edu, policy));
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalResult result = serviceWith(new LearningProperties(0.8, 1.2, 0.05, 5, 3, 0.10, 0.98))
                    .retrieve("some question", BRAIN_ID);

            assertEquals(List.of("policy", "educational"),
                    result.chunks().stream().map(RetrievedChunk::documentTitle).toList(),
                    "equally-scored chunks must be ordered authoritative-first by retrieve()");
        }

        /** The safety property, proven end to end: relevance still outranks authority. */
        @Test
        void betterScoringChunkStaysOnTopThroughRetrieve() {
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.empty());
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of());

            UUID eduDoc = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
            UUID policyDoc = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
            ChunkSearchResult edu = hit(eduDoc, 1.0, "EDUCATIONAL", "educational");
            ChunkSearchResult policy = hit(policyDoc, 0.2, "INTERNAL_POLICY", "policy");
            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of(edu, policy));
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalResult result = serviceWith(new LearningProperties(0.8, 1.2, 0.05, 5, 3, 0.10, 0.98))
                    .retrieve("some question", BRAIN_ID);

            assertEquals(List.of("educational", "policy"),
                    result.chunks().stream().map(RetrievedChunk::documentTitle).toList(),
                    "authority must not demote a materially better-scoring chunk");
        }

        // The clamp band must come from the injected LearningProperties, not a
        // hardcoded 0.8/1.2 constant. A non-default (wider) band configured here
        // must NOT clamp a weight that the default 0.8/1.2 band would have clamped.
        @Test
        void clampUsesConfiguredLearningPropertiesBounds() {
            LearningProperties wideBand = new LearningProperties(
                    0.5, 1.5, 0.05, 5, 3, 0.10, 0.98);

            Brain brain = new Brain(BRAIN_ID, "test", "Test");
            brain.setLearningEnabled(true);
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.of(brain));
            // A weight of 1.4 is outside the default [0.8,1.2] band but inside the
            // configured [0.5,1.5] band, so it must survive un-clamped.
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of(DOC_ID, 1.4));

            ChunkSearchResult h = hit(DOC_ID, 1.0);
            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of(h));
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalService service = serviceWith(wideBand);
            RetrievalResult result = service.retrieve("gift funds question", BRAIN_ID);

            assertEquals(1, result.chunks().size());
            // combined = vectorWeight(0.5) * 1.0 (clamped to [0,1]) * weight(1.4) = 0.7
            assertEquals(0.7, result.chunks().get(0).combinedScore(), 1e-9,
                    "clamp must honor the configured weightMin/weightMax, not a hardcoded 0.8/1.2 band");
        }

        // Same corrupt-weight defense-in-depth as the static helper tests, but
        // proven through the full retrieve() path with the configured bounds.
        @Test
        void clampStillBoundsCorruptWeightUsingConfiguredMax() {
            LearningProperties narrowBand = new LearningProperties(
                    0.9, 1.1, 0.05, 5, 3, 0.10, 0.98);

            Brain brain = new Brain(BRAIN_ID, "test", "Test");
            brain.setLearningEnabled(true);
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.of(brain));
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of(DOC_ID, 9.0));

            ChunkSearchResult h = hit(DOC_ID, 1.0);
            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of(h));
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalService service = serviceWith(narrowBand);
            RetrievalResult result = service.retrieve("gift funds question", BRAIN_ID);

            // combined = vectorWeight(0.5) * 1.0 * clamp(9.0 -> 1.1) = 0.55
            assertEquals(0.55, result.chunks().get(0).combinedScore(), 1e-9,
                    "a corrupt weight must clamp to the configured max, not the old hardcoded 1.2");
        }

        // The learningEnabled flag must be read from the DB at most once per
        // brainId across repeated retrieve() calls (cached), proving the fix for
        // the uncached-findById-per-retrieval hot-path issue.
        @Test
        void learningEnabledFlagIsCachedAcrossRetrievals() {
            Brain brain = new Brain(BRAIN_ID, "test", "Test");
            brain.setLearningEnabled(true);
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.of(brain));
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of());

            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalService service = serviceWith(new LearningProperties(
                    0.8, 1.2, 0.05, 5, 3, 0.10, 0.98));

            service.retrieve("question one", BRAIN_ID);
            service.retrieve("question two", BRAIN_ID);
            service.retrieve("question three", BRAIN_ID);

            verify(brainRepository, times(1)).findById(BRAIN_ID);
        }

        // Toggling learning (the admin path calls invalidateLearningEnabledCache)
        // must force the next retrieve() to re-read the flag instead of serving a
        // stale cached value forever.
        @Test
        void invalidatingCacheForcesReReadOnNextRetrieval() {
            Brain brain = new Brain(BRAIN_ID, "test", "Test");
            brain.setLearningEnabled(true);
            when(brainRepository.findById(BRAIN_ID)).thenReturn(Optional.of(brain));
            when(sourceWeightService.weightsFor(BRAIN_ID)).thenReturn(Map.of());

            when(chunkRepository.searchByVector(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());
            when(chunkRepository.searchByKeyword(anyString(), anyInt(), any(UUID.class), any()))
                    .thenReturn(List.of());

            RetrievalService service = serviceWith(new LearningProperties(
                    0.8, 1.2, 0.05, 5, 3, 0.10, 0.98));

            service.retrieve("question one", BRAIN_ID);
            verify(brainRepository, times(1)).findById(BRAIN_ID);

            service.retrieve("question two", BRAIN_ID);
            verify(brainRepository, times(1)).findById(BRAIN_ID); // still cached

            service.invalidateLearningEnabledCache(BRAIN_ID);
            service.retrieve("question three", BRAIN_ID);
            verify(brainRepository, times(2)).findById(BRAIN_ID); // re-read after invalidation
        }
    }
}
