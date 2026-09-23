package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.RuleRevision;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.repository.RuleRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Live effective text for the two owner-editable rule blocks, read through a
 * short cache.  Pattern mirrors RuntimeSettings exactly, including the fixed
 * Long.MIN_VALUE sentinel guard that avoids the overflow present in a naïve
 * {@code now - Long.MIN_VALUE} check.
 */
@Service
public class RulesService {

    /** Per-key summary exposed to callers (state endpoint, dashboard). */
    public record RuleState(
            String key,
            String content,
            String source,          // "pack" | "custom"
            OffsetDateTime updatedAt,
            String updatedBy) {}

    // ── constants ─────────────────────────────────────────────────────────────

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s
    private static final int  CONTENT_MAX_CHARS = 20_000;
    public static final Set<String>  KEYS = Set.of("rules.hard", "rules.guidance");

    // ── dependencies ──────────────────────────────────────────────────────────

    private final RuleRevisionRepository repo;
    private final DomainPackRegistry registry;

    // ── cache ─────────────────────────────────────────────────────────────────

    private volatile Map<UUID, Map<String, Optional<RuleRevision>>> cache = Map.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public RulesService(RuleRevisionRepository repo, DomainPackRegistry registry) {
        this.repo = repo;
        this.registry = registry;
    }

    // ── public accessors ──────────────────────────────────────────────────────

    public String effectiveHard(UUID brainId) {
        return effective(brainId, "rules.hard", () -> registry.bundle(brainId).pack().hardRules());
    }

    public String effectiveGuidance(UUID brainId) {
        return effective(brainId, "rules.guidance", () -> registry.bundle(brainId).pack().guidance());
    }

    /** Per-key state map for the admin API and dashboard. */
    public Map<String, RuleState> state(UUID brainId) {
        var pack = registry.bundle(brainId).pack();
        Map<String, Optional<RuleRevision>> snap = snapshot(brainId);
        return Map.of(
                "rules.hard",     toState("rules.hard",     snap.get("rules.hard"),     pack.hardRules()),
                "rules.guidance", toState("rules.guidance", snap.get("rules.guidance"), pack.guidance()));
    }

    /** History (newest-first, up to 20 revisions) for a validated key. */
    public List<RuleRevision> history(UUID brainId, String key) {
        requireKnownKey(key);
        return repo.findTop20ByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(brainId, key);
    }

    // ── mutation ──────────────────────────────────────────────────────────────

    @Transactional
    public void save(UUID brainId, String key, String content, String updatedBy) {
        requireKnownKey(key);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content must be non-blank for key: " + key);
        }
        if (content.length() > CONTENT_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "Content exceeds " + CONTENT_MAX_CHARS + " characters for key: " + key);
        }
        repo.save(new RuleRevision(brainId, key, content, updatedBy));
        invalidate();
    }

    @Transactional
    public void revert(UUID brainId, String key, String updatedBy) {
        requireKnownKey(key);
        repo.save(new RuleRevision(brainId, key, null, updatedBy));
        invalidate();
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String effective(UUID brainId, String key, Supplier<String> packDefault) {
        Optional<RuleRevision> latest = snapshot(brainId).get(key);
        if (latest != null && latest.isPresent() && latest.get().getContent() != null) {
            return latest.get().getContent();
        }
        return packDefault.get();
    }

    private RuleState toState(String key, Optional<RuleRevision> latest, String packDefault) {
        if (latest == null || latest.isEmpty()) {
            // no revision at all — pure pack default
            return new RuleState(key, packDefault, "pack", null, null);
        }
        RuleRevision rev = latest.get();
        if (rev.getContent() == null) {
            // explicit revert-to-pack marker: source is "pack" but attribution preserved
            return new RuleState(key, packDefault, "pack", rev.getCreatedAt(), rev.getCreatedBy());
        }
        return new RuleState(key, rev.getContent(), "custom", rev.getCreatedAt(), rev.getCreatedBy());
    }

    /**
     * Refreshes the cache when stale.  The sentinel condition
     * {@code cachedAtNanos == Long.MIN_VALUE} is tested BEFORE the arithmetic
     * expression to avoid the overflow that {@code now - Long.MIN_VALUE} would
     * produce on a freshly-initialised (or invalidated) instance.
     */
    private Map<String, Optional<RuleRevision>> snapshot(UUID brainId) {
        long now = System.nanoTime();
        Map<UUID, Map<String, Optional<RuleRevision>>> local = cache;
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            local = Map.of();
        } else if (local.containsKey(brainId)) {
            return local.get(brainId);
        }

        Map<String, Optional<RuleRevision>> fresh = Map.of(
                "rules.hard",     repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(brainId, "rules.hard"),
                "rules.guidance", repo.findFirstByBrainIdAndRuleKeyOrderByCreatedAtDescIdDesc(brainId, "rules.guidance"));

        Map<UUID, Map<String, Optional<RuleRevision>>> next = new HashMap<>(local);
        next.put(brainId, fresh);
        cache = Map.copyOf(next);
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            cachedAtNanos = now;
        }
        return fresh;
    }

    private void requireKnownKey(String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("Unknown rule key: " + key + ". Must be one of " + KEYS);
        }
    }
}
