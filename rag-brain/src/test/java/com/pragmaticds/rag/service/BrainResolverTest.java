package com.pragmaticds.rag.service;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import org.mockito.Mockito;

class BrainResolverTest {

    private final BrainRepository brains = Mockito.mock(BrainRepository.class);
    private final BrainResolver resolver = new BrainResolver(brains);

    private Brain brain(String slug, boolean active, boolean isDefault) {
        Brain b = new Brain(UUID.randomUUID(), slug, slug);
        b.setActive(active);
        b.setDefault(isDefault);
        return b;
    }

    @Test
    void resolvesDefaultWhenNoOverride() {
        Brain def = brain("mortgage", true, true);
        when(brains.findDefaultBrain()).thenReturn(Optional.of(def));
        assertEquals(def, resolver.resolve(null));
        assertEquals(def, resolver.resolve("  "));
    }

    @Test
    void resolvesBySlugOverride() {
        Brain hr = brain("hr", true, false);
        when(brains.findBySlug("hr")).thenReturn(Optional.of(hr));
        assertEquals(hr, resolver.resolve("hr"));
    }

    @Test
    void rejectsUnknownSlug() {
        when(brains.findBySlug("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("nope"));
    }

    @Test
    void rejectsInactiveBrain() {
        when(brains.findBySlug("old")).thenReturn(Optional.of(brain("old", false, false)));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("old"));
    }
}
