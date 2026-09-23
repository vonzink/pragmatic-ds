package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.PackTemplateService;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrainAdminLearningToggleTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final DomainPackRegistry packRegistry = mock(DomainPackRegistry.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final PackTemplateService packTemplate = mock(PackTemplateService.class);
    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final BrainDailyUsageRepository usageRepository = mock(BrainDailyUsageRepository.class);
    private final SpendGuardService spendGuardService = mock(SpendGuardService.class);
    private final BrainAdminController controller =
            new BrainAdminController(brains, syncService, packRegistry, router, packTemplate, retrievalService,
                    usageRepository, spendGuardService);

    private Brain brain() {
        Brain b = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        b.setActive(true);
        return b;
    }

    @Test
    void enableTurnsLearningOnAndPersists() {
        Brain b = brain();
        when(brains.findById(TestBrains.DEFAULT_ID)).thenReturn(Optional.of(b));
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainAdminController.BrainDto dto = controller.learning(TestBrains.DEFAULT_ID, true);

        assertTrue(dto.learningEnabled());
        assertTrue(b.isLearningEnabled());
        verify(brains).save(b);
        // RetrievalService caches learningEnabled per brainId; toggling must evict
        // it so the next retrieve() re-reads the flag instead of serving a stale value.
        verify(retrievalService).invalidateLearningEnabledCache(TestBrains.DEFAULT_ID);
    }

    @Test
    void disableTurnsLearningOffAndPersists() {
        Brain b = brain();
        b.setLearningEnabled(true);
        when(brains.findById(TestBrains.DEFAULT_ID)).thenReturn(Optional.of(b));
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainAdminController.BrainDto dto = controller.learning(TestBrains.DEFAULT_ID, false);

        assertFalse(dto.learningEnabled());
        assertFalse(b.isLearningEnabled());
        verify(brains).save(b);
        verify(retrievalService).invalidateLearningEnabledCache(TestBrains.DEFAULT_ID);
    }

    @Test
    void unknownBrainThrows() {
        UUID missing = UUID.randomUUID();
        when(brains.findById(missing)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.learning(missing, true));

        assertEquals("Unknown brain: " + missing, ex.getMessage());
    }
}
