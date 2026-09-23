package com.pragmaticds.docengine.platform.ai;

/**
 * Provider-neutral seam for boundary extraction — the model half of document splitting
 * (design 2026-08-22-ai-document-splitting-design.md §4). The model EXTRACTS a fact about the
 * package ("a new document starts on page N, here is the header text I read"); it never asserts a
 * boundary over evidence — every proposal must survive the engine's anchoring gates before it can
 * cut anything, and the cut itself is made by the one splitter.
 *
 * <p>Same discipline as {@link AiExtractionPort}: implementations never throw, never log document
 * content, and bound their own timeout; failure degrades to an ERROR result, which downstream
 * means "today's deterministic split stands".
 */
public interface BoundaryExtractionPort {

    BoundaryExtractionResult proposeBoundaries(BoundaryExtractionRequest request);
}
