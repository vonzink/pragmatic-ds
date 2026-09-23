package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.repository.BrainSourceWeightEventRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and mutates the learned per-(brain, document) source weights and their
 * audit/review-queue events.
 *
 * The per-brain weight map is cached because retrieval reads it on every request
 * (hot path); the cache is invalidated on any write so a change is visible on the
 * next retrieval. Only brains with learning enabled ever consult these weights.
 *
 * Cache eviction on write is deferred until the enclosing transaction commits
 * (via {@link #invalidateAfterCommit(UUID)}): invalidating mid-transaction would let a
 * concurrent retrieval reload pre-commit rows and re-cache stale data, stranding a
 * just-applied change until the next write. If no transaction is active the cache is
 * evicted immediately, since there is no commit to wait for.
 */
@Service
public class SourceWeightService {

    /**
     * Upper bound on cached brains. Entries are pure cache (reloaded on miss), so a
     * bounded LRU caps memory on long-lived deployments that create/soft-delete many
     * brains without ever evicting — the hot set of active brains stays resident.
     */
    static final int MAX_CACHED_BRAINS = 1024;

    /**
     * Cache freshness bound. A weight write invalidates only the writing instance's
     * cache, so on a multi-instance deployment other replicas would serve the previous
     * weights until their own next write. Expiring an entry after this TTL lets a stale
     * cross-instance entry self-heal without a shared cache or invalidation bus.
     */
    static final long CACHE_TTL_MILLIS = 60_000;

    private final BrainSourceWeightRepository weightRepo;
    private final BrainSourceWeightEventRepository eventRepo;

    /** A cached weight map plus when it was loaded, for TTL expiry. */
    private record CachedWeights(Map<UUID, Double> weights, long loadedAtMillis) {}

    /**
     * brainId -> cached (documentId -> weight) + load time. Rebuilt lazily; cleared on
     * write and expired after {@link #CACHE_TTL_MILLIS}. Access-ordered LRU bounded at
     * {@link #MAX_CACHED_BRAINS}; {@code synchronizedMap} makes the access-order
     * reordering (which mutates on read) and eviction thread-safe.
     */
    private final Map<UUID, CachedWeights> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedWeights> eldest) {
                    return size() > MAX_CACHED_BRAINS;
                }
            });

    public SourceWeightService(BrainSourceWeightRepository weightRepo,
                               BrainSourceWeightEventRepository eventRepo) {
        this.weightRepo = weightRepo;
        this.eventRepo = eventRepo;
    }

    /** Current weight for one document, or {@code 1.0} when none is stored. */
    public double weightFor(UUID brainId, UUID documentId) {
        return weightsFor(brainId).getOrDefault(documentId, LearningWeightMath.NEUTRAL);
    }

    /** Cached document -> weight map for a brain. Empty when nothing is learned yet. */
    public Map<UUID, Double> weightsFor(UUID brainId) {
        long now = now();
        CachedWeights cached = cache.get(brainId);
        if (cached != null && now - cached.loadedAtMillis() < CACHE_TTL_MILLIS) {
            return cached.weights();
        }
        Map<UUID, Double> fresh = load(brainId);
        cache.put(brainId, new CachedWeights(fresh, now));
        return fresh;
    }

    private Map<UUID, Double> load(UUID brainId) {
        return weightRepo.findByBrainId(brainId).stream()
                .collect(Collectors.toUnmodifiableMap(
                        BrainSourceWeight::getDocumentId, BrainSourceWeight::getWeight));
    }

    /** Current time; a seam so tests can advance the clock to exercise TTL expiry. */
    long now() {
        return System.currentTimeMillis();
    }

    /** Drop the cached map for a brain so the next read reloads from the DB. */
    public void invalidate(UUID brainId) {
        cache.remove(brainId);
    }

    /** Number of brains currently cached (for tests to assert the LRU bound). */
    int cacheSize() {
        return cache.size();
    }

    /**
     * Evict the brain's cached weight map once the current transaction commits, so a
     * concurrent retrieval can never reload and re-cache pre-commit rows. Falls back to
     * an immediate {@link #invalidate(UUID)} when no transaction is active.
     */
    private void invalidateAfterCommit(UUID brainId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(brainId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(brainId);
            }
        });
    }

    /** All stored weight rows for a brain (admin transparency view). */
    public List<BrainSourceWeight> weights(UUID brainId) {
        return weightRepo.findByBrainId(brainId);
    }

    /** Pending review-queue events for a brain, newest first. */
    public List<BrainSourceWeightEvent> pending(UUID brainId) {
        return eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(brainId, WeightEventStatus.PENDING.name());
    }

    /**
     * One-click "back to neutral": deletes every learned weight for the brain and
     * writes a REVERTED audit event per document that had one. The cache is
     * invalidated so retrieval immediately returns to baseline ranking.
     */
    @Transactional
    public void reset(UUID brainId, String actor) {
        for (BrainSourceWeight w : weightRepo.findByBrainId(brainId)) {
            BrainSourceWeightEvent event = new BrainSourceWeightEvent(
                    brainId,
                    w.getDocumentId(),
                    w.getWeight(),
                    LearningWeightMath.NEUTRAL,
                    null,
                    w.getFeedbackCount(),
                    WeightEventStatus.REVERTED.name(),
                    "reset to neutral",
                    actor);
            eventRepo.save(event);
        }
        weightRepo.deleteByBrainId(brainId);
        invalidateAfterCommit(brainId);
    }

    /**
     * Applies a PENDING proposal: upserts the weight and writes an APPROVED event.
     *
     * @param brainId the brain the caller is scoped to (resolved from the admin's
     *                selected ?brain=); must match the event's own brain or the
     *                event belongs to a different brain's queue and is rejected.
     */
    @Transactional
    public void approve(UUID eventId, UUID brainId, String actor) {
        BrainSourceWeightEvent pending = requirePending(eventId, brainId);
        upsertWeight(pending.getBrainId(), pending.getDocumentId(),
                pending.getProposedWeight(), pending.getEvidenceCount(), actor);

        BrainSourceWeightEvent applied = new BrainSourceWeightEvent(
                pending.getBrainId(),
                pending.getDocumentId(),
                pending.getOldWeight(),
                pending.getProposedWeight(),
                pending.getProposedWeight(),
                pending.getEvidenceCount(),
                WeightEventStatus.APPROVED.name(),
                "approved from review queue",
                actor);
        eventRepo.save(applied);
        invalidateAfterCommit(pending.getBrainId());
    }

    /**
     * Discards a PENDING proposal: no weight change, writes a REJECTED event.
     *
     * @param brainId the brain the caller is scoped to (resolved from the admin's
     *                selected ?brain=); must match the event's own brain or the
     *                event belongs to a different brain's queue and is rejected.
     */
    @Transactional
    public void reject(UUID eventId, UUID brainId, String actor) {
        BrainSourceWeightEvent pending = requirePending(eventId, brainId);
        BrainSourceWeightEvent rejected = new BrainSourceWeightEvent(
                pending.getBrainId(),
                pending.getDocumentId(),
                pending.getOldWeight(),
                pending.getOldWeight(),
                pending.getProposedWeight(),
                pending.getEvidenceCount(),
                WeightEventStatus.REJECTED.name(),
                "rejected from review queue",
                actor);
        eventRepo.save(rejected);
    }

    /**
     * Looks up the event and verifies it is PENDING and belongs to {@code brainId},
     * so an admin scoped to one brain's queue can never approve/reject another
     * brain's event by guessing/reusing its id.
     */
    private BrainSourceWeightEvent requirePending(UUID eventId, UUID brainId) {
        BrainSourceWeightEvent event = eventRepo.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown weight event: " + eventId));
        if (!event.getBrainId().equals(brainId)) {
            throw new IllegalArgumentException("Weight event " + eventId + " does not belong to brain " + brainId);
        }
        if (!WeightEventStatus.PENDING.name().equals(event.getStatus())) {
            throw new IllegalStateException("Event is not PENDING: " + eventId + " (" + event.getStatus() + ")");
        }
        return event;
    }

    /** Insert-or-update the (brain, document) weight row. Package-private for the job to reuse. */
    void upsertWeight(UUID brainId, UUID documentId, double weight, int evidenceCount, String actor) {
        Optional<BrainSourceWeight> existing = weightRepo.findByBrainIdAndDocumentId(brainId, documentId);
        BrainSourceWeight row = existing.orElseGet(() -> new BrainSourceWeight(brainId, documentId, weight, evidenceCount, actor));
        row.setWeight(weight);
        row.setFeedbackCount(evidenceCount);
        row.setUpdatedBy(actor);
        weightRepo.save(row);
        invalidateAfterCommit(brainId);
    }
}
