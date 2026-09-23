package com.pragmaticds.docengine.platform.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * One ambiguity window, as the model sees it. The engine never sends a whole package: {@code
 * pages} is the ambiguous window plus one confirmed page of padding on each side, so the model
 * always has a page whose type is KNOWN as an anchor for what it is asked to discriminate
 * (design §5). On a clean package no window opens and no request is ever built.
 *
 * <p>{@code taxonomy} is data, not prompt: the document types the model may name, seeded from the
 * {@code document_type} rows (design §7) — which keeps the mortgage knowledge out of this module.
 * A proposal naming a type outside it is treated like any other unverifiable claim.
 */
public record BoundaryExtractionRequest(
        List<CandidatePage> pages, List<TypeDescription> taxonomy, String partitionHint) {

    /**
     * One page of the window. Text only in v1 ({@code headText}/{@code footText} — reading-order
     * span text from the top and bottom bands of the page), mirroring the AI extraction layer's
     * input contract; {@code tableStructureText} is reserved for the L2 summary and travels null
     * until the live adapter (Phase E) assembles real prompts. Page images are the additive v2.
     */
    public record CandidatePage(
            int packagePageIndex,
            String deterministicTypeCode,
            BigDecimal deterministicConfidence,
            String headText,
            String footText,
            String tableStructureText) {}

    /** One document type the model may propose: engine code + one authored sentence. */
    public record TypeDescription(String code, String description) {}
}
