package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-brain pack registry: replaces the single immutable DomainPack bean so
 * each brain runs its OWN pack. Lazy + thread-safe — a brain's pack is loaded,
 * validated, and its derived state precompiled on first use and cached.
 *
 * Lazy-loading sidesteps boot ordering: the default brain row exists from V7
 * and DefaultBrainSeeder reconciles its packRef before any request, so the
 * registry never needs the pack at construction. New/edited brains load +
 * validate their pack here (fail-fast), preserving the per-deployment guard
 * that a brain's slug must match its pack's slug.
 */
@Component
public class DomainPackRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainPackRegistry.class);

    private final BrainRepository brains;
    private final DomainPackLoader loader;
    private final ConcurrentHashMap<UUID, BrainPackBundle> cache = new ConcurrentHashMap<>();

    @Autowired
    public DomainPackRegistry(BrainRepository brains) {
        this(brains, new DomainPackLoader());
    }

    /** Test seam: inject a loader (and preload bundles via {@link #preload}). */
    DomainPackRegistry(BrainRepository brains, DomainPackLoader loader) {
        this.brains = brains;
        this.loader = loader;
    }

    /** The brain's bundle, loaded + validated + cached on first use. */
    public BrainPackBundle bundle(UUID brainId) {
        return cache.computeIfAbsent(brainId, this::load);
    }

    /** Evicts a brain's cached bundle so the next call reloads it (Phase 6 brain edits). */
    public void reload(UUID brainId) {
        cache.remove(brainId);
    }

    private BrainPackBundle load(UUID brainId) {
        Brain brain = brains.findById(brainId)
                .orElseThrow(() -> new IllegalStateException("No brain for id " + brainId));
        Path packDir = Path.of(brain.getPackRef()).toAbsolutePath().normalize();
        DomainPack pack = loader.load(packDir);
        if (!pack.slug().equals(brain.getSlug())) {
            throw new IllegalStateException("brain '" + brain.getSlug() + "' (" + brainId
                    + ") declares pack '" + brain.getPackRef() + "' with slug '" + pack.slug()
                    + "' — brain/pack slug mismatch");
        }
        log.info("Domain pack loaded for brain '{}' ({}): company='{}', path={}",
                brain.getSlug(), brainId, pack.companyName(), packDir);
        return BrainPackBundle.of(pack);
    }

    /** Test-only: seed a pre-built bundle for a brain id without touching the DB or filesystem. */
    void preload(UUID brainId, BrainPackBundle bundle) {
        cache.put(brainId, bundle);
    }
}
