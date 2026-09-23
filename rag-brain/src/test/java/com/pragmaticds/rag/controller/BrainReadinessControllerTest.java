package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrainReadinessControllerTest {

    private BrainProfile profile(boolean publicEnabled, BrainMode mode, String tokenHash, List<String> domains) {
        BrainProfile p = new BrainProfile();
        p.setBrainId(TestBrains.DEFAULT_ID);
        p.setPublicEnabled(publicEnabled);
        p.setMode(mode);
        p.setPublicTokenHash(tokenHash);
        p.setAllowedDomains(domains);
        return p;
    }

    private BrainReadinessController.ReadinessDto run(Brain brain, BrainProfile profile,
                                                     long chunkCount, long activeDocs) {
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findById(any())).thenReturn(Optional.of(brain));
        BrainProfileService profiles = mock(BrainProfileService.class);
        when(profiles.getOrCreate(any())).thenReturn(profile);
        BrainDocumentRepository docs = mock(BrainDocumentRepository.class);
        when(docs.countByBrainIdAndActiveTrue(any())).thenReturn(activeDocs);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(chunks.countByBrainId(any())).thenReturn(chunkCount);

        return new BrainReadinessController(brains, profiles, docs, chunks).readiness(TestBrains.DEFAULT_ID);
    }

    private Brain activeBrain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "mortgage", "Acme Corp");
        brain.setActive(true);
        return brain;
    }

    @Test
    void reportsReadyWhenEveryCheckPasses() {
        BrainReadinessController.ReadinessDto dto = run(activeBrain(),
                profile(true, BrainMode.PUBLIC_SITE, "hash", List.of("example.com")), 1200L, 8L);

        assertTrue(dto.ready(), "a fully configured public brain is ready to attach");
        assertTrue(dto.hasPublicToken());
        assertEquals(1200L, dto.chunks());
        assertEquals(List.of("example.com"), dto.allowedDomains());
        assertTrue(dto.checklist().stream().allMatch(BrainReadinessController.ChecklistItem::ok));
    }

    @Test
    void notReadyWhenCorpusEmptyAndFlagsTheKnowledgeCheck() {
        BrainReadinessController.ReadinessDto dto = run(activeBrain(),
                profile(true, BrainMode.PUBLIC_SITE, "hash", List.of("example.com")), 0L, 0L);

        assertFalse(dto.ready());
        BrainReadinessController.ChecklistItem knowledge = dto.checklist().stream()
                .filter(c -> c.key().equals("knowledge")).findFirst().orElseThrow();
        assertFalse(knowledge.ok());
    }

    @Test
    void notReadyWhenTokenMissingOrDomainsEmpty() {
        BrainReadinessController.ReadinessDto noToken = run(activeBrain(),
                profile(true, BrainMode.PUBLIC_SITE, null, List.of("example.com")), 1200L, 8L);
        assertFalse(noToken.ready());
        assertFalse(noToken.hasPublicToken());

        BrainReadinessController.ReadinessDto noDomains = run(activeBrain(),
                profile(true, BrainMode.PUBLIC_SITE, "hash", List.of()), 1200L, 8L);
        assertFalse(noDomains.ready());
        assertTrue(noDomains.checklist().stream()
                .anyMatch(c -> c.key().equals("domains") && !c.ok()));
    }

    @Test
    void notReadyWhenPublicAccessDisabled() {
        BrainReadinessController.ReadinessDto dto = run(activeBrain(),
                profile(false, BrainMode.PUBLIC_SITE, "hash", List.of("example.com")), 1200L, 8L);
        assertFalse(dto.ready());
    }
}
