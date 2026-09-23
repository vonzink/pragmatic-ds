package com.pragmaticds.rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.config.LearningProperties;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.pack.BrainPackBundle;
import com.pragmaticds.rag.pack.CompiledProgram;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.ChunkSearchResult;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.service.ai.RuntimeSettings;
import com.pragmaticds.rag.service.learning.SourceWeightService;
import com.pragmaticds.rag.service.ingestion.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval: vector similarity + keyword full-text search, merged with
 * a weighted score. Only chunks from active, currently effective documents are
 * eligible (enforced in the repository queries).
 *
 * Acronym expansions and program detection rules come from the domain pack
 * (injected via the constructor) so they are company-specific and versioned
 * alongside the pack YAML, not hardcoded here.
 *
 * Compliance note: the sufficientEvidence flag is the gate that prevents the
 * model from answering without approved source material. Do not bypass it.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;
    private final ObjectMapper objectMapper;
    private final RagProperties.Retrieval config;
    private final RuntimeSettings settings;
    private final DomainPackRegistry packRegistry;
    private final VocabularyService vocabularyService;
    private final BrainRepository brainRepository;
    private final BrainProfileRepository brainProfileRepository;
    private final SourceWeightService sourceWeightService;
    private final LearningProperties learningProperties;
    private final AuthorityFilterService authorityFilterService;

    // Per-brain learningEnabled cache. retrieve() is the hot path and previously
    // ran an uncached brainRepository.findById() on every single call just to read
    // this flag; caching it (mirroring SourceWeightService's weight cache) avoids
    // that per-retrieval DB round-trip. Invalidated whenever
    // BrainAdminController#learning() flips the switch, so the next retrieval
    // always re-reads the current value.
    private final Map<UUID, Boolean> learningEnabledCache = new ConcurrentHashMap<>();

    public RetrievalService(DocumentChunkRepository chunkRepository,
                            EmbeddingService embeddingService,
                            RerankerService rerankerService,
                            ObjectMapper objectMapper,
                            RagProperties properties,
                            DomainPackRegistry packRegistry,
                            RuntimeSettings settings,
                            VocabularyService vocabularyService,
                            BrainRepository brainRepository,
                            BrainProfileRepository brainProfileRepository,
                            SourceWeightService sourceWeightService,
                            LearningProperties learningProperties,
                            AuthorityFilterService authorityFilterService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.objectMapper = objectMapper;
        this.config = properties.retrieval();
        this.settings = settings;
        this.packRegistry = packRegistry;
        this.vocabularyService = vocabularyService;
        this.brainRepository = brainRepository;
        this.brainProfileRepository = brainProfileRepository;
        this.sourceWeightService = sourceWeightService;
        this.learningProperties = learningProperties;
        this.authorityFilterService = authorityFilterService;
    }

    /**
     * Drops the cached learningEnabled flag for a brain so the next retrieve()
     * re-reads it from the database. Called by BrainAdminController#learning()
     * immediately after toggling the switch, so a change is visible on the very
     * next retrieval rather than staying stale until process restart.
     */
    public void invalidateLearningEnabledCache(UUID brainId) {
        learningEnabledCache.remove(brainId);
    }

    // NOTE: deliberately NOT @Transactional. This path calls the embedding API
    // (and optionally the LLM reranker); wrapping it in a transaction would pin a
    // JDBC connection across that external I/O. The two search queries run as
    // independent autocommit reads, which is correct for read-only retrieval.
    public RetrievalResult retrieve(String question, UUID brainId) {
        return retrieve(question, brainId, SourceVisibility.PUBLIC);
    }

    public RetrievalResult retrieve(String question, UUID brainId, SourceVisibility visibility) {
        return retrieveByVisibility(question, brainId, visibility, false, null);
    }

    public RetrievalResult retrieveAdmin(String question, UUID brainId) {
        return retrieveAdmin(question, brainId, null, null);
    }

    public RetrievalResult retrieveAdmin(String question, UUID brainId, SourceVisibility visibility) {
        return retrieveAdmin(question, brainId, visibility, null);
    }

    /** Admin retrieval, optionally restricted to one analyzer's corpus scope (+ shared docs). */
    public RetrievalResult retrieveAdmin(String question, UUID brainId,
                                         SourceVisibility visibility, String analyzerScope) {
        return retrieveByVisibility(question, brainId, visibility, true, analyzerScope);
    }

    /**
     * Runs the same hybrid scoring pipeline against immutable snapshot membership.
     * The caller must validate current document facts against the frozen manifest
     * before invoking this method.
     */
    public RetrievalResult retrieveSnapshot(String question, UUID brainId, UUID snapshotId,
                                            SourceVisibility visibility, int topK,
                                            boolean rerank) {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        return retrieveByVisibility(question, brainId, visibility, false, null,
                snapshotId, topK, rerank);
    }

    private RetrievalResult retrieveByVisibility(String question,
                                                 UUID brainId,
                                                 SourceVisibility visibility,
                                                 boolean includeBlockedSources,
                                                 String analyzerScope) {
        return retrieveByVisibility(question, brainId, visibility, includeBlockedSources,
                analyzerScope, null, null, null);
    }

    private RetrievalResult retrieveByVisibility(String question,
                                                 UUID brainId,
                                                 SourceVisibility visibility,
                                                 boolean includeBlockedSources,
                                                 String analyzerScope,
                                                 UUID snapshotId,
                                                 Integer topKOverride,
                                                 Boolean rerankOverride) {
        if (question == null || question.isBlank()) {
            return RetrievalResult.empty();
        }

        BrainPackBundle bundle = packRegistry.bundle(brainId);
        Map<String, String> acronyms = vocabularyService.effectiveSynonyms(brainId);
        List<CompiledProgram> programs = bundle.programs();

        boolean rerank = rerankOverride != null ? rerankOverride : settings.rerankEnabled();
        int topK = topKOverride != null ? topKOverride : settings.topK();
        double threshold = resolveThreshold(brainId);

        // Fetch a wider candidate pool from each method, then merge.
        int candidatePool = rerank
                ? (snapshotId == null
                        ? config.rerankCandidates()
                        : Math.max(topK, config.rerankCandidates()))
                : topK * 2;

        // Expand acronyms from the domain pack (e.g. a terse acronym question
        // retrieves the same definitions as its fully spelled-out phrasing).
        // Only the retrieval inputs use the expansion; program detection and
        // the reranker below still operate on the original question.
        String expandedQuestion = expandQuery(question, acronyms);

        float[] questionEmbedding = embeddingService.embed(expandedQuestion);
        String vectorLiteral = EmbeddingService.toVectorLiteral(questionEmbedding);

        String visibilityName = visibility == null ? null : visibility.name();
        List<ChunkSearchResult> vectorHits;
        List<ChunkSearchResult> keywordHits;
        if (snapshotId != null) {
            vectorHits = chunkRepository.searchByVectorSnapshot(
                    vectorLiteral, candidatePool, brainId, visibilityName, snapshotId);
            keywordHits = chunkRepository.searchByKeywordSnapshot(
                    toOrQuery(expandedQuestion), candidatePool, brainId,
                    visibilityName, snapshotId);
        } else {
            vectorHits = includeBlockedSources
                    ? chunkRepository.searchByVectorAdmin(vectorLiteral, candidatePool,
                            brainId, visibilityName, analyzerScope)
                    : chunkRepository.searchByVector(
                            vectorLiteral, candidatePool, brainId, visibilityName);
            keywordHits = includeBlockedSources
                    ? chunkRepository.searchByKeywordAdmin(toOrQuery(expandedQuestion),
                            candidatePool, brainId, visibilityName, analyzerScope)
                    : chunkRepository.searchByKeyword(toOrQuery(expandedQuestion),
                            candidatePool, brainId, visibilityName);
        }

        Map<UUID, MutableHit> merged = new HashMap<>();
        for (ChunkSearchResult hit : vectorHits) {
            merged.computeIfAbsent(hit.getChunkId(), id -> new MutableHit(hit))
                    .vectorScore = clamp(hit.getScore());
        }
        // Keyword ts_rank_cd values cluster low even for good matches;
        // normalize against the best hit so the top keyword match scores 1.0.
        double maxKeyword = keywordHits.stream()
                .mapToDouble(h -> h.getScore() == null ? 0 : h.getScore())
                .max().orElse(0);
        for (ChunkSearchResult hit : keywordHits) {
            double normalized = maxKeyword > 0 ? clamp(hit.getScore()) / maxKeyword : 0;
            merged.computeIfAbsent(hit.getChunkId(), id -> new MutableHit(hit))
                    .keywordScore = normalized;
        }

        // Adaptive retrieval (spec §4): load the per-brain learned source weights
        // once per retrieval, only when this brain has learning enabled. An empty
        // map => the multiply below is a byte-for-byte no-op, so the OFF path is
        // identical to the pre-feature baseline. learningEnabled is cached per
        // brainId (see learningEnabledCache) instead of hitting the DB on every
        // retrieve() call; the cache is invalidated the moment an admin toggles it.
        boolean learningEnabled = learningEnabledCache.computeIfAbsent(brainId, id ->
                brainRepository.findById(id)
                        .map(com.pragmaticds.rag.domain.Brain::isLearningEnabled)
                        .orElse(false));
        Map<UUID, Double> sourceWeights = learningEnabled
                ? sourceWeightService.weightsFor(brainId)
                : Map.of();

        java.util.Set<String> questionPrograms = detectPrograms(question, programs);
        List<RetrievedChunk> ranked = merged.values().stream()
                .map(hit -> toRetrievedChunk(hit, questionPrograms, programs, sourceWeights))
                .sorted(Comparator.comparingDouble(RetrievedChunk::combinedScore).reversed())
                .limit(candidatePool)
                .toList();

        // LLM rerank: hybrid scores find the neighborhood, the reranker picks
        // the truly relevant chunks. Replaces combinedScore with rerank score.
        if (rerank && !ranked.isEmpty()) {
            ranked = rerankerService.rerank(question, ranked, topK, brainId);
        } else if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }

        // Authority tie-break, applied AFTER rerank on purpose: the reranker
        // replaces combinedScore and re-sorts, so ordering applied any earlier
        // would simply be discarded. Relevance still decides the ranking; this only
        // settles chunks the scorer rated equally, preferring the company rule over
        // borrower-facing background. Never reorders across different scores, so it
        // cannot move the top score and cannot affect the sufficientEvidence gate.
        if (config.authorityOrderingEnabled()) {
            ranked = authorityFilterService.orderCorpusByAuthority(ranked, config.authorityTieBand());
        }

        double confidence = ranked.isEmpty() ? 0.0 : ranked.getFirst().combinedScore();
        boolean sufficient = confidence >= threshold
                && ranked.size() >= Math.min(config.minResults(), topK);

        log.debug("Retrieval: {} vector hits, {} keyword hits, {} merged, confidence={}",
                vectorHits.size(), keywordHits.size(), ranked.size(), confidence);

        return new RetrievalResult(ranked, confidence, sufficient);
    }

    private RetrievedChunk toRetrievedChunk(MutableHit hit, java.util.Set<String> questionPrograms,
                                            List<CompiledProgram> programs,
                                            Map<UUID, Double> sourceWeights) {
        double combined = config.vectorWeight() * hit.vectorScore
                + config.keywordWeight() * hit.keywordScore;

        // Program-aware ranking: when the question names loan program(s), boost
        // sources for any named program and demote clearly mismatched ones.
        // Prevents e.g. Fannie Mae's 620 conventional minimum from answering an
        // FHA credit-score question, while still letting a question that names
        // TWO programs ("FHA vs conventional") retrieve both sides.
        if (!questionPrograms.isEmpty()) {
            String chunkProgram = detectPrograms(
                    hit.source.getSourceName() + " " + hit.source.getDocumentTitle(),
                    programs).stream().findFirst().orElse(null);
            combined = Math.min(1.0, combined * programScoreFactor(questionPrograms, chunkProgram));
        }

        // Adaptive retrieval hook: multiply the combined score by this source's
        // learned weight (default 1.0). Applied AFTER the program boost so the
        // program-mismatch demotion still dominates; the clamp band bounds how
        // far learning can move a source. No-op when sourceWeights is empty.
        combined = applySourceWeight(combined, hit.source.getDocumentId(),
                sourceWeights, learningProperties.weightMin(), learningProperties.weightMax());

        String section = null;
        Integer pageNumber = null;
        try {
            JsonNode metadata = objectMapper.readTree(
                    hit.source.getMetadataJson() == null ? "{}" : hit.source.getMetadataJson());
            if (metadata.hasNonNull("section")) {
                section = metadata.get("section").asText();
            }
            if (metadata.hasNonNull("page_number")) {
                pageNumber = metadata.get("page_number").asInt();
            }
        } catch (Exception e) {
            log.warn("Unparseable chunk metadata for chunk {}", hit.source.getChunkId());
        }

        return new RetrievedChunk(
                hit.source.getChunkId(),
                hit.source.getDocumentId(),
                assembledContent(hit.source.getContent(), hit.source.getParentContent()),
                hit.source.getParentChunkId(),
                hit.source.getParentContent(),
                hit.source.getHierarchyPath(),
                hit.source.getSourceName(),
                hit.source.getSourceType(),
                hit.source.getDocumentName(),
                hit.source.getDocumentTitle(),
                section,
                pageNumber,
                hit.source.getEffectiveDate(),
                hit.vectorScore,
                hit.keywordScore,
                combined,
                hit.source.getExternalDocId(),
                hit.source.getContentSha256()
        );
    }

    /**
     * Multiplies a combined retrieval score by the learned weight for its source
     * document. Returns the score unchanged when {@code weights} is empty or has
     * no entry for {@code documentId} (the OFF / neutral path is a byte-for-byte
     * no-op). Any supplied weight is re-clamped to [{@code min}, {@code max}] so a
     * corrupt stored weight cannot widen the band at query time — this is the
     * structural guarantee that learning cannot bury an authoritative source
     * below a non-authoritative one by more than the clamp band.
     */
    static double applySourceWeight(double combined, UUID documentId,
                                    Map<UUID, Double> weights, double min, double max) {
        if (weights.isEmpty()) {
            return combined;
        }
        Double weight = weights.get(documentId);
        if (weight == null) {
            return combined;
        }
        double clamped = Math.max(min, Math.min(max, weight));
        return combined * clamped;
    }

    /**
     * Separates the parent-section preamble from the focused child chunk inside an
     * assembled content string. {@link RerankerService} splits on this so it scores
     * the focused child (which carries the specific answer), not the generic parent
     * preamble that would otherwise fill its short excerpt window.
     */
    static final String FOCUSED_CHUNK_MARKER = "Focused retrieved chunk:\n";

    private static String assembledContent(String childContent, String parentContent) {
        if (parentContent == null || parentContent.isBlank()
                || parentContent.equals(childContent)) {
            return childContent;
        }
        String parent = parentContent.length() <= 4000
                ? parentContent
                : parentContent.substring(0, 4000) + "\n[section context truncated]";
        return "Parent section context:\n" + parent
                + "\n\n" + FOCUSED_CHUNK_MARKER + childContent;
    }

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "how", "i", "in", "is", "it", "my", "of", "on", "or",
            "the", "to", "use", "used", "we", "what", "when", "which", "will", "with");

    /**
     * Appends expansions for any domain acronyms in the question so a terse
     * acronym question retrieves the same sources as its fully spelled-out
     * phrasing. The expanded text feeds both the embedding and the keyword
     * query; the original question still drives program detection and
     * reranking. Returns the question unchanged when it contains no known
     * acronym. Matching is token-based, so an acronym only expands as a
     * standalone word. Expansions come from the domain pack.
     */
    static String expandQuery(String question, Map<String, String> acronyms) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String[] tokens = question.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+");
        java.util.LinkedHashSet<String> expansions = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            String expansion = acronyms.get(token);
            if (expansion != null) {
                expansions.add(expansion);
            }
        }
        if (expansions.isEmpty()) {
            return question;
        }
        return question + " " + String.join(" ", expansions);
    }

    /**
     * Converts a natural-language question into an OR'd tsquery
     * ("minimum | credit | score | fha | loan"). websearch_to_tsquery ANDs
     * every word, so one missing term zeroes the whole match — far too
     * brittle for conversational questions against guideline text.
     */
    static String toOrQuery(String question) {
        String[] words = question.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+");
        return java.util.Arrays.stream(words)
                .filter(w -> w.length() > 1 && !STOPWORDS.contains(w))
                .distinct()
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Detects every loan program a piece of text refers to, in priority order
     * defined by the domain pack. A comparison question naming two programs
     * returns both, so neither side is demoted in {@link #toRetrievedChunk}.
     * Rules come from the domain pack (pre-compiled via {@link CompiledProgram#compile}).
     */
    static java.util.Set<String> detectPrograms(String text, List<CompiledProgram> programs) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        for (CompiledProgram program : programs) {
            boolean hit = program.keywords().stream().anyMatch(lower::contains)
                    || program.patterns().stream().anyMatch(p -> p.matcher(lower).find());
            if (hit) {
                found.add(program.name());
            }
        }
        return found;
    }

    /**
     * Program-match multiplier for a chunk: 1.2 when the chunk's program is one
     * the question named, 0.4 when the question named program(s) but not this
     * one, 1.0 when the question named no program or the chunk has none. A
     * two-program comparison boosts both named programs.
     */
    static double programScoreFactor(java.util.Set<String> questionPrograms, String chunkProgram) {
        if (questionPrograms.isEmpty() || chunkProgram == null) {
            return 1.0;
        }
        return questionPrograms.contains(chunkProgram) ? 1.2 : 0.4;
    }

    /** Per-brain retrieval floor if set, else the global runtime/env default. */
    static double effectiveThreshold(Double perBrainOverride, double globalDefault) {
        return perBrainOverride != null ? perBrainOverride : globalDefault;
    }

    private double resolveThreshold(UUID brainId) {
        Double perBrain = brainProfileRepository.findByBrainId(brainId)
                .map(BrainProfile::getRetrievalConfidenceThreshold)
                .orElse(null);
        return effectiveThreshold(perBrain, settings.confidenceThreshold());
    }

    private static double clamp(Double value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    /** Accumulator while merging the two result lists. */
    private static final class MutableHit {
        final ChunkSearchResult source;
        double vectorScore;
        double keywordScore;

        MutableHit(ChunkSearchResult source) {
            this.source = source;
        }
    }
}
