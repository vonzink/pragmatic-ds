package com.pragmaticds.docengine.classification.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response shape of {@code GET /v1/packages/{id}/documents}: the split projection review
 * consumes. Unassigned pages carry the REASON they stayed out of every document — a reviewer
 * must see that page 9 was blank and pages 10–11 duplicates, not wonder where they went.
 */
public record PackageDocumentsView(
        UUID packageId, List<DocumentView> documents, List<UnassignedPageView> unassignedPages) {

    /**
     * @param boundaryProvenance how this document's STARTING boundary was decided — {@code HUMAN},
     *     {@code RULE} (a pack anchor declaring a form header), {@code PACKAGE_START},
     *     {@code TYPE_CHANGE}, or {@code AI} (Phase D). Null only for documents split before the
     *     engine recorded it (V24). A reviewer sorting a queue wants the INFERRED boundaries
     *     first; a consumer auditing a split wants to know which cuts the machine could prove.
     * @param absorbedUntypedPages how many of this document's pages carried NO type and joined it
     *     only because the splitter treats an untyped page as a continuation (V46, issue #60). 0
     *     means every page classified as the document's type; a large number means an unrelated,
     *     untypable document was probably glued onto this one and is where a reviewer should look
     *     first. Null for documents split before the engine counted, and for documents a human
     *     reshaped.
     */
    public record DocumentView(
            UUID id,
            int ordinal,
            String documentTypeCode,
            BigDecimal classificationConfidence,
            String reviewStatus,
            String boundaryProvenance,
            Integer absorbedUntypedPages,
            List<DocumentPageView> pages) {}

    public record DocumentPageView(
            UUID pageId, int packagePageIndex, PageClassificationView classification) {}

    /**
     * The page's current classification — null only if CLASSIFYING has not run yet.
     *
     * @param coQualifyingTypes every type whose rule pack cleared its OWN threshold on this page,
     *     when more than one did — a SUSPECTED multi-document sheet (a licence photocopied beside
     *     a Social Security card, two receipts on one scan). Empty on the normal page. The engine
     *     cannot split within a page, so the verdict is unchanged and the page still belongs to
     *     one document; this exists so the second document stops being invisible.
     */
    public record PageClassificationView(
            String type,
            BigDecimal confidence,
            String rulePackVersion,
            List<String> coQualifyingTypes) {}

    public record UnassignedPageView(UUID pageId, int packagePageIndex, UnassignedReason reason) {}

    public enum UnassignedReason {
        BLANK,
        DUPLICATE,
        /**
         * A reviewer overrode this page's blank/duplicate verdict and no regroup has assigned it
         * yet (audit C5). Before this value existed the page was in NO document and NOT in the
         * tray — an orphan the UI could only paper over with session-local state that any refetch
         * or reload silently discarded. A cleared page is real, assignable work; the read model
         * must say so.
         */
        CLEARED
    }
}
