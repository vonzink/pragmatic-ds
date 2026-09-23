package com.pragmaticds.docengine.platform.ai;

/**
 * Deterministic disabled adapter: tests, local development, and every fail-closed configuration
 * state (flag off, unknown provider, missing credentials). Proposes nothing, spends nothing — an
 * UNKNOWN page stays UNKNOWN, which is precisely today's behaviour without this stage.
 */
public final class StubPageTypeClassificationAdapter implements PageTypeClassificationPort {

    @Override
    public PageTypeClassificationResult classify(PageTypeClassificationRequest request) {
        return PageTypeClassificationResult.disabled();
    }
}
