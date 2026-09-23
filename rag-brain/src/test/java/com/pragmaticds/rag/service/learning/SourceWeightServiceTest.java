package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.repository.BrainSourceWeightEventRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceWeightServiceTest {

    @Mock BrainSourceWeightRepository weightRepo;
    @Mock BrainSourceWeightEventRepository eventRepo;
    @InjectMocks SourceWeightService service;

    private final UUID brain = UUID.randomUUID();
    private final UUID docA = UUID.randomUUID();
    private final UUID docB = UUID.randomUUID();

    private static BrainSourceWeight weight(UUID brain, UUID doc, double w) {
        return new BrainSourceWeight(brain, doc, w, 0, "test");
    }

    private static BrainSourceWeightEvent pendingEvent(UUID brain, UUID doc, Double oldWeight,
                                                         Double proposedWeight, int evidenceCount) {
        return new BrainSourceWeightEvent(brain, doc, oldWeight, null, proposedWeight,
                evidenceCount, WeightEventStatus.PENDING.name(), "proposed by job", "job");
    }

    @Test
    void weightCacheIsBoundedByLruEviction() {
        when(weightRepo.findByBrainId(any())).thenReturn(List.of());

        for (int i = 0; i < SourceWeightService.MAX_CACHED_BRAINS + 50; i++) {
            service.weightsFor(UUID.randomUUID());
        }

        assertEquals(SourceWeightService.MAX_CACHED_BRAINS, service.cacheSize(),
                "cache must not grow past the LRU bound");
    }

    @Test
    void cacheEntryExpiresAfterTtlAndReloads() {
        long[] clock = {0L};
        SourceWeightService svc = new SourceWeightService(weightRepo, eventRepo) {
            @Override
            long now() {
                return clock[0];
            }
        };
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 1.1)));

        svc.weightsFor(brain);                            // load at t=0
        svc.weightsFor(brain);                            // still fresh -> served from cache
        clock[0] = SourceWeightService.CACHE_TTL_MILLIS;  // advance to the TTL boundary -> expired
        svc.weightsFor(brain);                            // expired -> reloads

        verify(weightRepo, times(2)).findByBrainId(brain);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        // Defensive: guarantee no leftover thread-bound tx state leaks across tests
        // even if a test fails before it can clean up itself.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Simulates an active Spring @Transactional method without needing a Spring context. */
    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void commitTransaction() {
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.beforeCommit(false);
        }
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void weightForReturnsNeutralWhenNoRowExists() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of());
        assertEquals(1.0, service.weightFor(brain, docA), 1e-9);
    }

    @Test
    void weightForReturnsStoredWeight() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 1.15)));
        assertEquals(1.15, service.weightFor(brain, docA), 1e-9);
    }

    @Test
    void weightsForReturnsMapAndIsCachedUntilInvalidated() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 0.9)));

        Map<UUID, Double> first = service.weightsFor(brain);
        Map<UUID, Double> second = service.weightsFor(brain);

        assertEquals(0.9, first.get(docA), 1e-9);
        assertEquals(0.9, second.get(docA), 1e-9);
        // Cache hit: the repo is queried only once across two calls.
        verify(weightRepo, times(1)).findByBrainId(brain);
    }

    @Test
    void invalidateForcesReloadOnNextCall() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 0.9)));
        service.weightsFor(brain);
        service.invalidate(brain);
        service.weightsFor(brain);
        verify(weightRepo, times(2)).findByBrainId(brain);
    }

    @Test
    void resetDeletesWeightsWritesRevertedEventsAndInvalidatesCache() {
        when(weightRepo.findByBrainId(brain))
                .thenReturn(List.of(weight(brain, docA, 1.15), weight(brain, docB, 0.85)));

        service.reset(brain, "admin@x");

        // One REVERTED event per previously-weighted document.
        ArgumentCaptor<BrainSourceWeightEvent> ev = ArgumentCaptor.forClass(BrainSourceWeightEvent.class);
        verify(eventRepo, times(2)).save(ev.capture());
        assertEquals(WeightEventStatus.REVERTED.name(), ev.getAllValues().get(0).getStatus());
        assertEquals("admin@x", ev.getAllValues().get(0).getActor());
        verify(weightRepo).deleteByBrainId(brain);
        // Next read reloads (cache invalidated).
        service.weightsFor(brain);
        verify(weightRepo, times(2)).findByBrainId(brain);
    }

    @Test
    void pendingListsPendingEventsForBrain() {
        BrainSourceWeightEvent e = pendingEvent(brain, docA, 1.0, 1.1, 6);
        when(eventRepo.findByBrainIdAndStatusOrderByCreatedAtDesc(brain, WeightEventStatus.PENDING.name()))
                .thenReturn(List.of(e));
        assertEquals(1, service.pending(brain).size());
    }

    @Test
    void approveAppliesProposedWeightUpsertsAndWritesApprovedEvent() {
        UUID eventId = UUID.randomUUID();
        BrainSourceWeightEvent pending = pendingEvent(brain, docA, 1.0, 1.12, 8);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(pending));
        when(weightRepo.findByBrainIdAndDocumentId(brain, docA)).thenReturn(Optional.empty());

        service.approve(eventId, brain, "admin@x");

        ArgumentCaptor<BrainSourceWeight> saved = ArgumentCaptor.forClass(BrainSourceWeight.class);
        verify(weightRepo).save(saved.capture());
        assertEquals(1.12, saved.getValue().getWeight(), 1e-9);
        ArgumentCaptor<BrainSourceWeightEvent> ev = ArgumentCaptor.forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.APPROVED.name(), ev.getValue().getStatus());
    }

    @Test
    void rejectWritesRejectedEventAndDoesNotChangeWeights() {
        UUID eventId = UUID.randomUUID();
        BrainSourceWeightEvent pending = pendingEvent(brain, docA, 1.0, 1.12, 8);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(pending));

        service.reject(eventId, brain, "admin@x");

        verify(weightRepo, never()).save(any());
        ArgumentCaptor<BrainSourceWeightEvent> ev = ArgumentCaptor.forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.REJECTED.name(), ev.getValue().getStatus());
    }

    @Test
    void approveRejectsNonPendingEvent() {
        UUID eventId = UUID.randomUUID();
        BrainSourceWeightEvent applied = new BrainSourceWeightEvent(brain, docA, 1.0, 1.05, null,
                8, WeightEventStatus.APPLIED.name(), "applied by job", "job");
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(applied));
        assertThrows(IllegalStateException.class, () -> service.approve(eventId, brain, "admin@x"));
    }

    @Test
    void approveThrowsOnUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(eventRepo.findById(eventId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.approve(eventId, brain, "admin@x"));
    }

    // --- R8: approve/reject must be scoped to the resolved brain -------------------

    @Test
    void approveRejectsEventBelongingToADifferentBrain() {
        UUID eventId = UUID.randomUUID();
        UUID otherBrain = UUID.randomUUID();
        BrainSourceWeightEvent pending = pendingEvent(otherBrain, docA, 1.0, 1.12, 8);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.approve(eventId, brain, "admin@x"));

        verify(weightRepo, never()).save(any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void rejectRejectsEventBelongingToADifferentBrain() {
        UUID eventId = UUID.randomUUID();
        UUID otherBrain = UUID.randomUUID();
        BrainSourceWeightEvent pending = pendingEvent(otherBrain, docA, 1.0, 1.12, 8);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class, () -> service.reject(eventId, brain, "admin@x"));

        verify(weightRepo, never()).save(any());
        verify(eventRepo, never()).save(any());
    }

    // --- R2: cache invalidation must be deferred until after commit ---------------

    @Test
    void upsertWeightDoesNotInvalidateCacheBeforeCommitWhenTransactionActive() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 1.0)));
        when(weightRepo.findByBrainIdAndDocumentId(brain, docA)).thenReturn(Optional.empty());

        // Warm the cache, then perform the write inside a simulated transaction.
        service.weightsFor(brain);
        beginTransaction();
        try {
            service.upsertWeight(brain, docA, 1.2, 5, "admin@x");

            // Pre-commit: a concurrent retrieval must still see the (stale) cached map,
            // not reload and re-cache pre-commit rows.
            service.weightsFor(brain);
            verify(weightRepo, times(1)).findByBrainId(brain);
        } finally {
            commitTransaction();
        }

        // After commit: the cache is evicted and the next read reloads.
        service.weightsFor(brain);
        verify(weightRepo, times(2)).findByBrainId(brain);
    }

    @Test
    void upsertWeightInvalidatesImmediatelyWhenNoTransactionIsActive() {
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 1.0)));
        when(weightRepo.findByBrainIdAndDocumentId(brain, docA)).thenReturn(Optional.empty());

        service.weightsFor(brain);
        service.upsertWeight(brain, docA, 1.2, 5, "admin@x");
        service.weightsFor(brain);

        // No active transaction to defer to, so eviction happens synchronously.
        verify(weightRepo, times(2)).findByBrainId(brain);
    }

    @Test
    void resetDoesNotInvalidateCacheBeforeCommit() {
        when(weightRepo.findByBrainId(brain))
                .thenReturn(List.of(weight(brain, docA, 1.15), weight(brain, docB, 0.85)));

        // Call #1: warm the cache.
        service.weightsFor(brain);
        beginTransaction();
        try {
            // Call #2: reset() itself reads the rows to emit REVERTED events (not a cache read).
            service.reset(brain, "admin@x");
            // Pre-commit: the cache must still hold the warmed (stale) map, so this read
            // must NOT trigger another repo call.
            service.weightsFor(brain);
            verify(weightRepo, times(2)).findByBrainId(brain);
        } finally {
            commitTransaction();
        }

        // After commit: cache evicted, next read reloads (call #3).
        service.weightsFor(brain);
        verify(weightRepo, times(3)).findByBrainId(brain);
    }

    @Test
    void approveDoesNotInvalidateCacheBeforeCommit() {
        UUID eventId = UUID.randomUUID();
        BrainSourceWeightEvent pending = pendingEvent(brain, docA, 1.0, 1.12, 8);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(pending));
        when(weightRepo.findByBrainIdAndDocumentId(brain, docA)).thenReturn(Optional.empty());
        when(weightRepo.findByBrainId(brain)).thenReturn(List.of(weight(brain, docA, 1.0)));

        service.weightsFor(brain);
        beginTransaction();
        try {
            service.approve(eventId, brain, "admin@x");
            service.weightsFor(brain);
            verify(weightRepo, times(1)).findByBrainId(brain);
        } finally {
            commitTransaction();
        }

        service.weightsFor(brain);
        verify(weightRepo, times(2)).findByBrainId(brain);
    }
}
