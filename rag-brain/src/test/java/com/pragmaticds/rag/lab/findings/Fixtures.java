package com.pragmaticds.rag.lab.findings;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.eval.InstancePackageFixtureRegistry;

/**
 * Loads a shipped fixture from disk through {@link InstancePackageFixtureRegistry} — the same
 * allowlist, parser, and digest checks a real fixture read goes through.
 *
 * <p>Deliberately thin: this class does not parse JSON, does not know a fixture's shape, and does
 * not decide whether a fixture is trustworthy. All of that is the registry's job. This is a
 * different tool from {@code TestEnvelopes}, which synthesizes envelopes in memory rather than
 * loading a shipped file.
 */
final class Fixtures {

    private static final InstancePackageFixtureRegistry REGISTRY =
            new InstancePackageFixtureRegistry();

    private Fixtures() {}

    static EngineResultEnvelope load(String name) {
        return REGISTRY.require(name).envelope();
    }
}
