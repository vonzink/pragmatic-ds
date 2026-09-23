package com.pragmaticds.docengine.platform.ai;

/**
 * Provider-neutral seam for page-type classification — the model half of the classifying stage
 * (plan 2026-09-09-llm-classification-fallback.md). Rule packs remain the fast, free, deterministic
 * path; this port is only ever consulted about a page the packs left {@code UNKNOWN}, and what it
 * returns is a PROPOSAL, never a verdict. The model reports what type it believes the page is AND
 * the text it read to believe it; the engine independently verifies that text against the page's
 * persisted spans before any of it is allowed to supersede a classification. A model that cannot
 * point at evidence has not classified anything.
 *
 * <p>Same discipline as {@link BoundaryExtractionPort}: implementations never throw, never log
 * document content, and bound their own timeout. Every failure shape degrades to an ERROR result,
 * which downstream means "the page stays UNKNOWN exactly as it is today" — the deterministic path
 * is never made worse by the model being unavailable, misconfigured, or wrong.
 */
public interface PageTypeClassificationPort {

    PageTypeClassificationResult classify(PageTypeClassificationRequest request);

    /**
     * Who answered — the name the {@code ai_interpretation} ledger records and a per-provider cost
     * rollup groups by, exactly as {@code AiExtractionResult.provider()} serves the extraction
     * ledger. It is a property of the ADAPTER, not of one call: the same for every answer an
     * implementation ever gives, which is why it lives here and not on the result.
     *
     * <p>Defaulted so a test double or a future adapter is not forced to invent one, and the stage
     * degrades a missing name to {@code "unknown"} the way the app's ledger already does. An
     * adapter that spends money is expected to override it, or its spend is unattributable.
     */
    default String provider() {
        return "unknown";
    }
}
