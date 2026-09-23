package com.pragmaticds.rag.pack;

import java.nio.file.Path;

/** Loads the bundled generic pack for tests (working dir = repo root under Gradle). */
public final class TestPacks {

    private static DomainPack sample;

    private TestPacks() {}

    /** Lazy + memoized: a broken pack fails each test with the loader's own message. */
    public static synchronized DomainPack sample() {
        if (sample == null) {
            sample = new DomainPackLoader().load(Path.of("packs/sample-mortgage"));
        }
        return sample;
    }

    /** A registry preloaded with one brain id → bundle, for consumer unit tests. */
    public static DomainPackRegistry registryFor(java.util.UUID brainId, DomainPack pack) {
        DomainPackRegistry registry =
                new DomainPackRegistry(org.mockito.Mockito.mock(
                        com.pragmaticds.rag.repository.BrainRepository.class));
        registry.preload(brainId, BrainPackBundle.of(pack));
        return registry;
    }

    /** A registry preloaded with several brain ids → packs. */
    public static DomainPackRegistry registryFor(java.util.Map<java.util.UUID, DomainPack> packs) {
        DomainPackRegistry registry = new DomainPackRegistry(
                org.mockito.Mockito.mock(com.pragmaticds.rag.repository.BrainRepository.class));
        packs.forEach((id, p) -> registry.preload(id, BrainPackBundle.of(p)));
        return registry;
    }

    /** The default-brain registry (DEFAULT_ID → the bundled generic pack). */
    public static DomainPackRegistry registry() {
        return registryFor(com.pragmaticds.rag.TestBrains.DEFAULT_ID, sample());
    }
}
