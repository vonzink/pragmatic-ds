package com.pragmaticds.docengine.gold;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A reviewed document's human decisions in the extraction harness's truth shape, UNMASKED. Plain
 * {@code String}s on purpose — never {@code MaskableValue} — because this response is what turns a
 * reviewer's decisions into a gold-set label, and a label that reads {@code •••-••-6789} would
 * teach the scorer that the mask is the answer. ADMIN-only, on the RAW-content matcher list
 * ({@code SecurityConfig}), beside the engine-result and span reads that already serve raw text.
 *
 * <p>Consumed by {@code tools/gold.py export}, which writes {@code case.json} + {@code truth.json}
 * from it. Design: {@code docs/superpowers/specs/2026-09-22-field-accuracy-gold-set-design.md} §5.
 */
public record GoldDocumentView(
        UUID documentId,
        UUID packageId,
        String documentTypeCode,
        List<GoldPage> pages,
        List<GoldField> fields) {

    /**
     * One document page. {@code pageIndex} is document-relative (what truth uses);
     * {@code packagePageIndex} lets a multi-document case be re-based onto the package's pages.
     */
    public record GoldPage(
            int pageIndex,
            int packagePageIndex,
            BigDecimal widthPt,
            BigDecimal heightPt,
            int contentRotation,
            String expectedType,
            List<GoldWord> words) {}

    /** One persisted text span — exactly what the pipeline saw at upload. */
    public record GoldWord(String text, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}

    /**
     * @param decision CONFIRMED · CORRECTED · REJECTED (the field's review status)
     * @param displayedText the effective value; null for REJECTED ("not printed")
     * @param pageIndex document-relative page: the correction's page, else the VALUE evidence page,
     *     else 0
     */
    public record GoldField(
            String field, String groupKey, String decision, String displayedText, int pageIndex) {}
}
