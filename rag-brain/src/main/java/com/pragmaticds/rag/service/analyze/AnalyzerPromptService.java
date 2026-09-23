package com.pragmaticds.rag.service.analyze;

import com.pragmaticds.rag.domain.AnalyzerPromptRevision;
import com.pragmaticds.rag.domain.AnalyzerPromptRevision.State;
import com.pragmaticds.rag.pack.AnalyzerConfig;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.AnalyzerPromptRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-brain overrides of an analyzer's base prompt, read through a short cache. Same pattern as
 * {@link com.pragmaticds.rag.service.ai.RulesService} (pack default, append-only PUBLISHED
 * revisions, NULL content = revert marker) plus one DRAFT row that never reaches a run until it
 * is published. Only the base prompt is overridable; every other analyzer field stays pack-owned.
 */
@Service
public class AnalyzerPromptService {

    public record Published(String content, String source, OffsetDateTime updatedAt, String updatedBy) {}

    public record Draft(String content, OffsetDateTime savedAt, String savedBy) {}

    public record PromptState(
            String slug, String displayName, String corpusScope, String retrievalQueryTemplate,
            int retrievalTopK, String envelope, boolean v2, String packDefault,
            Published published, Draft draft) {}

    /** Publish was asked for an analyzer with no draft; maps to 409. */
    public static final class NoDraftException extends RuntimeException {
        public NoDraftException(String slug) {
            super("No draft to publish for analyzer '" + slug + "'");
        }
    }

    public static final int CONTENT_MAX_CHARS = 32_000;
    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final AnalyzerPromptRevisionRepository repo;
    private final DomainPackRegistry registry;

    /** brainId → slug → newest PUBLISHED row (empty when none). */
    private volatile Map<UUID, Map<String, Optional<AnalyzerPromptRevision>>> cache = Map.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public AnalyzerPromptService(AnalyzerPromptRevisionRepository repo, DomainPackRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    // ── run path ──────────────────────────────────────────────────────────────

    public String effectiveBasePrompt(UUID brainId, String slug) {
        AnalyzerConfig analyzer = requireAnalyzer(brainId, slug);
        Optional<AnalyzerPromptRevision> latest = published(brainId, slug);
        if (latest.isPresent() && latest.get().getContent() != null) {
            return latest.get().getContent();
        }
        return analyzer.basePrompt();
    }

    /** The analyzer as the run should see it: the same instance when the pack text is live. */
    public AnalyzerConfig withEffectivePrompt(UUID brainId, AnalyzerConfig analyzer) {
        Optional<AnalyzerPromptRevision> latest = published(brainId, analyzer.slug());
        if (latest.isPresent() && latest.get().getContent() != null) {
            return analyzer.withBasePrompt(latest.get().getContent());
        }
        return analyzer;
    }

    // ── admin API ─────────────────────────────────────────────────────────────

    public List<PromptState> states(UUID brainId) {
        List<PromptState> out = new ArrayList<>();
        for (AnalyzerConfig a : registry.bundle(brainId).pack().analyzers()) {
            out.add(toState(brainId, a));
        }
        return out;
    }

    public PromptState state(UUID brainId, String slug) {
        return toState(brainId, requireAnalyzer(brainId, slug));
    }

    @Transactional
    public PromptState saveDraft(UUID brainId, String slug, String content, String by) {
        requireAnalyzer(brainId, slug);
        requireContent(content);
        repo.deleteByBrainIdAndAnalyzerSlugAndState(brainId, slug, State.DRAFT);
        repo.save(new AnalyzerPromptRevision(brainId, slug, State.DRAFT, content, by));
        invalidate();
        return state(brainId, slug);
    }

    @Transactional
    public PromptState discardDraft(UUID brainId, String slug) {
        requireAnalyzer(brainId, slug);
        repo.deleteByBrainIdAndAnalyzerSlugAndState(brainId, slug, State.DRAFT);
        invalidate();
        return state(brainId, slug);
    }

    @Transactional
    public PromptState publish(UUID brainId, String slug, String by) {
        requireAnalyzer(brainId, slug);
        AnalyzerPromptRevision draft = repo
                .findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(brainId, slug, State.DRAFT)
                .orElseThrow(() -> new NoDraftException(slug));
        repo.save(new AnalyzerPromptRevision(brainId, slug, State.PUBLISHED, draft.getContent(), by));
        repo.deleteByBrainIdAndAnalyzerSlugAndState(brainId, slug, State.DRAFT);
        invalidate();
        return state(brainId, slug);
    }

    @Transactional
    public PromptState revert(UUID brainId, String slug, String by) {
        requireAnalyzer(brainId, slug);
        repo.save(new AnalyzerPromptRevision(brainId, slug, State.PUBLISHED, null, by));
        invalidate();
        return state(brainId, slug);
    }

    public List<AnalyzerPromptRevision> history(UUID brainId, String slug) {
        requireAnalyzer(brainId, slug);
        return repo.findTop20ByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(
                brainId, slug, State.PUBLISHED);
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PromptState toState(UUID brainId, AnalyzerConfig a) {
        Optional<AnalyzerPromptRevision> latest = published(brainId, a.slug());
        Published published;
        if (latest.isEmpty()) {
            published = new Published(a.basePrompt(), "pack", null, null);
        } else if (latest.get().getContent() == null) {
            published = new Published(a.basePrompt(), "pack",
                    latest.get().getCreatedAt(), latest.get().getCreatedBy());
        } else {
            published = new Published(latest.get().getContent(), "custom",
                    latest.get().getCreatedAt(), latest.get().getCreatedBy());
        }
        // Drafts are never cached: they change on every keystroke-save and are admin-only reads.
        Draft draft = repo
                .findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(brainId, a.slug(), State.DRAFT)
                .map(d -> new Draft(d.getContent(), d.getCreatedAt(), d.getCreatedBy()))
                .orElse(null);
        return new PromptState(a.slug(), a.displayName(), a.corpusScope(), a.retrievalQueryTemplate(),
                a.retrievalTopK(), a.envelope() == null ? "v1" : a.envelope(), a.isV2(),
                a.basePrompt(), published, draft);
    }

    private AnalyzerConfig requireAnalyzer(UUID brainId, String slug) {
        return registry.bundle(brainId).pack().analyzers().stream()
                .filter(a -> a.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new AnalyzerNotFoundException(slug));
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        if (content.length() > CONTENT_MAX_CHARS) {
            throw new IllegalArgumentException("content exceeds " + CONTENT_MAX_CHARS + " characters");
        }
    }

    /**
     * Newest PUBLISHED row for one analyzer, from the per-brain cache. Same sentinel guard as
     * RulesService: {@code cachedAtNanos == Long.MIN_VALUE} is tested before the subtraction.
     */
    private Optional<AnalyzerPromptRevision> published(UUID brainId, String slug) {
        long now = System.nanoTime();
        boolean stale = cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS;
        Map<UUID, Map<String, Optional<AnalyzerPromptRevision>>> local = stale ? Map.of() : cache;
        Map<String, Optional<AnalyzerPromptRevision>> perBrain = local.get(brainId);
        if (perBrain != null && perBrain.containsKey(slug)) {
            return perBrain.get(slug);
        }
        Optional<AnalyzerPromptRevision> fresh = repo
                .findFirstByBrainIdAndAnalyzerSlugAndStateOrderByCreatedAtDescIdDesc(brainId, slug, State.PUBLISHED);
        Map<String, Optional<AnalyzerPromptRevision>> nextPerBrain =
                new HashMap<>(perBrain == null ? Map.of() : perBrain);
        nextPerBrain.put(slug, fresh);
        Map<UUID, Map<String, Optional<AnalyzerPromptRevision>>> next = new HashMap<>(local);
        next.put(brainId, Map.copyOf(nextPerBrain));
        cache = Map.copyOf(next);
        if (stale) {
            cachedAtNanos = now;
        }
        return fresh;
    }
}
