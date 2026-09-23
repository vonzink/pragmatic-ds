package com.pragmaticds.docengine.platform.ai;

import java.util.List;
import java.util.UUID;

/**
 * One batch of pages the deterministic rule packs could not type, as the model sees it. Unlike a
 * boundary window there is no padding and no ordering claim to defend: each candidate page is an
 * independent question ("what IS this page?"), so the batch exists purely to spend one call instead
 * of N. The stage caps the batch size — an unbounded package must never become an unbounded prompt.
 *
 * <p>{@code taxonomy} is data, not prompt: the document types the model may name, seeded from the
 * {@code document_type} rows, which keeps the mortgage knowledge out of this module entirely. A
 * proposal naming a type outside it is treated like any other unverifiable claim and dropped. The
 * type list is declared here rather than borrowed from {@link BoundaryExtractionRequest} on
 * purpose: the two stages ask different questions and must be free to evolve their contracts
 * independently, and a shared record would quietly couple them.
 */
public record PageTypeClassificationRequest(
        List<CandidatePage> pages, List<TypeDescription> taxonomy) {

    /**
     * One page to be typed. {@code pageId} is the engine's own identity for the page and travels
     * back on every proposal so the stage never has to match answers by position — a model that
     * reorders, duplicates, or drops entries cannot silently retype the wrong page. {@code
     * packagePageIndex} is carried only so the model can read a page's place in the package; it is
     * never the join key. Text only: reading-order span text from the top ({@code headText}) and
     * bottom ({@code footText}) bands of the page, the same bands the boundary request uses.
     */
    public record CandidatePage(
            UUID pageId, int packagePageIndex, String headText, String footText) {}

    /** One document type the model may propose: engine code + one authored sentence. */
    public record TypeDescription(String code, String description) {}
}
