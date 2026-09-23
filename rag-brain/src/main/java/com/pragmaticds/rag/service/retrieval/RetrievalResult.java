package com.pragmaticds.rag.service.retrieval;

import java.util.List;

/**
 * Outcome of hybrid retrieval for one question.
 *
 * @param chunks             ranked best chunks (may be empty)
 * @param confidence         top combined score, 0..1
 * @param sufficientEvidence false when confidence is below the configured
 *                           threshold — the caller must refuse or escalate
 *                           rather than let the model guess
 */
public record RetrievalResult(
        List<RetrievedChunk> chunks,
        double confidence,
        boolean sufficientEvidence
) {

    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), 0.0, false);
    }
}
