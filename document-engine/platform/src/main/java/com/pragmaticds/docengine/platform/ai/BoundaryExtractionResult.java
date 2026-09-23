package com.pragmaticds.docengine.platform.ai;

import java.math.BigDecimal;
import java.util.List;

/** Provider-neutral outcome of one boundary-extraction call. */
public record BoundaryExtractionResult(
        BoundaryExtractionStatus status, List<ProposedBoundary> boundaries, AiTokenCounts tokens) {

    /**
     * One proposed cut: this page STARTS a document. {@code quotedHeaderText} is not decoration —
     * it is the anchoring key (design §6): the model must return the text it saw, VERBATIM, and
     * the engine independently verifies that text exists on that page before believing anything.
     * A proposal whose quote matches nothing is dropped and recorded, never downgraded and kept.
     */
    public record ProposedBoundary(
            int packagePageIndex,
            String documentTypeCode,
            BigDecimal confidence,
            String quotedHeaderText,
            String partitionValue) {}

    /** The fail-closed answer: no proposals, no spend, deterministic split stands. */
    public static BoundaryExtractionResult disabled() {
        return new BoundaryExtractionResult(
                BoundaryExtractionStatus.DISABLED, List.of(), AiTokenCounts.ZERO);
    }
}
