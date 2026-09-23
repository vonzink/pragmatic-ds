package com.pragmaticds.docengine.review.triage;

import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.BestLoser;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.TriageItem;
import com.pragmaticds.docengine.classification.triage.UnknownTriagePlanner.WeakAnchor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shape of {@code GET /v1/packages/{id}/triage}: the work the engine could not classify,
 * with the evidence needed to act on it and whatever a human has already said about it.
 *
 * <p>{@link BestLoser} and {@link WeakAnchor} are re-exported from
 * {@code UnknownTriagePlanner} rather than re-declared here. They are already the exact wire
 * shapes — plain value records of ids, codes, and numbers with no JPA and no behaviour — and a
 * parallel pair of near-identical records would only create a mapping layer whose sole failure
 * mode is drifting from the thing it copies. The ITEM is re-declared, because it gains a field the
 * planner cannot know: {@link LabelView}, which is a {@code :review} concern.
 *
 * <p><b>Nothing here is PII.</b> Page ranges, type codes, confidences, and ANCHOR IDS —
 * classification evidence stores anchor identifiers and never matched text (Phase 4 rule 3), and
 * this projection carries that rule forward unchanged.
 */
public record PackageTriageView(UUID packageId, List<TriageItemView> items) {

    /**
     * @param reason {@code WHOLE_UNKNOWN_DOCUMENT} — a document typed UNKNOWN end to end — or
     *     {@code ABSORBED_UNKNOWN_RUN}, a stretch of untyped pages swallowed by the typed document
     *     in front of it (the loss {@code PackageSplitter.group} documents and accepts).
     * @param nearMiss the best losing pack fell within the configured margin of its OWN threshold.
     *     Near misses sort first: the pack is nearly right, so one label against it is the cheapest
     *     fix available.
     * @param bestLoser the strongest non-winning signal in the run, or null when no pack matched
     *     anything at all. A zero or negative {@code shortfall} is the ambiguous-tie UNKNOWN — two
     *     packs both qualified and the classifier refused to flip a coin.
     * @param weakAnchors every anchor that matched somewhere in the run, with how many of the run's
     *     pages it hit. Anchor IDS only, by design.
     * @param label the latest human label recorded against exactly this page run, or null while it
     *     is still open work. Labelled items stay in the response and sort LAST: the queue is a
     *     read-time projection with no state of its own, so hiding a labelled run would leave the
     *     label unverifiable from the surface that asked for it.
     */
    public record TriageItemView(
            UUID logicalDocumentId,
            int documentOrdinal,
            String documentTypeCode,
            String reason,
            int startPackagePageIndex,
            int endPackagePageIndex,
            List<UUID> pageIds,
            boolean nearMiss,
            BestLoser bestLoser,
            List<WeakAnchor> weakAnchors,
            LabelView label) {

        static TriageItemView of(TriageItem item, LabelView label) {
            return new TriageItemView(
                    item.logicalDocumentId(),
                    item.documentOrdinal(),
                    item.documentTypeCode(),
                    item.reason(),
                    item.startPackagePageIndex(),
                    item.endPackagePageIndex(),
                    item.pageIds(),
                    item.nearMiss(),
                    item.bestLoser(),
                    item.weakAnchors(),
                    label);
        }
    }

    /** A human's answer, read straight off the append-only {@code review_decision} row. */
    public record LabelView(
            UUID decisionId,
            String documentTypeCode,
            String reason,
            UUID decidedBy,
            Instant decidedAt) {}
}
