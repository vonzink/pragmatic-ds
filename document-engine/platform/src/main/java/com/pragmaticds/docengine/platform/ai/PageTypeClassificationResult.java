package com.pragmaticds.docengine.platform.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Provider-neutral outcome of one page-type-classification call. */
public record PageTypeClassificationResult(
        PageTypeClassificationStatus status, List<PageTypeProposal> proposals, AiTokenCounts tokens) {

    /**
     * One proposed type for one page. {@code quotedEvidenceText} is not decoration — it is the
     * anchoring key, the same discipline {@link BoundaryExtractionResult.ProposedBoundary} applies
     * to a cut: the model must return the text it read, VERBATIM, and the engine independently
     * verifies that text exists in the page's persisted spans before believing the type at all. A
     * proposal whose quote matches nothing is dropped and recorded, never downgraded and kept — a
     * confident type with an invented quote is exactly the failure this stage exists to refuse.
     *
     * <p>{@code documentTypeCode} may be the literal {@code UNKNOWN}: an honest "I could not tell
     * either" from the model, which leaves the deterministic result standing untouched.
     */
    public record PageTypeProposal(
            UUID pageId,
            String documentTypeCode,
            BigDecimal confidence,
            String quotedEvidenceText) {}

    /** The fail-closed answer: no proposals, no spend, every page stays exactly as typed. */
    public static PageTypeClassificationResult disabled() {
        return new PageTypeClassificationResult(
                PageTypeClassificationStatus.DISABLED, List.of(), AiTokenCounts.ZERO);
    }
}
