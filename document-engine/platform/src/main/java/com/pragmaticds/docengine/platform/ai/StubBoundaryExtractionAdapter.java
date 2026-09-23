package com.pragmaticds.docengine.platform.ai;

/**
 * Deterministic disabled adapter: tests, local development, and every fail-closed configuration
 * state. Proposes nothing, spends nothing — the deterministic split stands unchanged.
 */
public final class StubBoundaryExtractionAdapter implements BoundaryExtractionPort {

    @Override
    public BoundaryExtractionResult proposeBoundaries(BoundaryExtractionRequest request) {
        return BoundaryExtractionResult.disabled();
    }
}
