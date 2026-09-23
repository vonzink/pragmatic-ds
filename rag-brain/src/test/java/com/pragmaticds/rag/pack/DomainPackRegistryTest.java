package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Load + validate + cache + reload, exercising the real loader against the bundled generic pack. */
class DomainPackRegistryTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Brain brain(String slug, String packRef) {
        // packRef points at the real pack dir; slug must match the pack's slug.
        Brain b = new Brain(ID, slug, slug);
        b.setPackRef(packRef);
        return b;
    }

    @Test
    void loadsValidatesAndCaches() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.of(brain("generic", "packs/generic")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        BrainPackBundle first = registry.bundle(ID);
        BrainPackBundle second = registry.bundle(ID);

        assertEquals("mortgage", first.pack().slug());
        assertSame(first, second, "bundle must be cached, not reloaded");
        verify(brains, times(1)).findById(ID); // computeIfAbsent loads exactly once
    }

    @Test
    void rejectsBrainPackSlugMismatch() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        // brain slug "hr" but the pack at packs/generic declares "generic"
        when(brains.findById(ID)).thenReturn(Optional.of(brain("hr", "packs/generic")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.bundle(ID));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("slug mismatch"));
    }

    @Test
    void rejectsUnknownBrain() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.empty());
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        assertThrows(IllegalStateException.class, () -> registry.bundle(ID));
    }

    @Test
    void reloadEvictsSoNextCallRebuilds() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.of(brain("generic", "packs/generic")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        BrainPackBundle first = registry.bundle(ID);
        registry.reload(ID);
        BrainPackBundle rebuilt = registry.bundle(ID);

        org.junit.jupiter.api.Assertions.assertNotSame(first, rebuilt, "reload must rebuild the bundle");
        verify(brains, times(2)).findById(ID);
    }
}
