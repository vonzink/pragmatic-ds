package com.pragmaticds.docengine.extraction.markdown;

import com.pragmaticds.docengine.extraction.web.DocumentFieldsView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything {@link MarkdownDocumentRenderer} renders — the document read model plus the four
 * document-level facts the read model does not carry (package, ordinal, member pages, per-page
 * classification).
 *
 * <p>A record rather than a set of repository calls inside the renderer, so the renderer stays a
 * PURE function of its input: the determinism claim is only testable if nothing it reads can change
 * between two calls.
 *
 * @param packageId the package the document belongs to
 * @param documentOrdinal the document's position within the package
 * @param fields the read model exactly as {@code GET /v1/documents/{id}/fields} serves it —
 *     already masked, already ordered, already carrying corrections
 * @param pages per member page, 1-based, in document page order
 */
public record MarkdownDocumentSource(
        UUID packageId,
        int documentOrdinal,
        DocumentFieldsView fields,
        List<PageClassification> pages) {

    /**
     * One member page's current classification.
     *
     * <p>PER PAGE, not per document, and deliberately: the document-level classification confidence
     * is a different number with different semantics (it is null for a human-shaped document), and
     * the page rows are what the classifier actually decided. Rendering a derived document-level
     * number here would be inventing a statistic.
     *
     * @param pageNumber 1-based package page number
     * @param documentTypeCode the type the page classified as
     * @param confidence the classifier's confidence, or null when the page has no current result
     * @param rulePackVersion the pack version that decided it, or null
     */
    public record PageClassification(
            int pageNumber,
            String documentTypeCode,
            BigDecimal confidence,
            String rulePackVersion) {}
}
