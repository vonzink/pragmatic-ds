package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.BrainSourceLink;

import java.util.List;

/**
 * Side-evidence collected alongside corpus retrieval (spec §7.5): matched page
 * guides and source links. This is SEPARATE from {@link RetrievalResult} — corpus
 * chunks are NOT merged in here, so the corpus path and the prompt/sufficiency
 * dependencies on {@link RetrievalResult} stay unchanged.
 *
 * <p>The collected evidence now feeds prompt guidance, response chrome
 * ({@code recommendedPage}, {@code links}, {@code nextAction}), and tracing,
 * while remaining separate from the corpus chunk list used for answer grounding.
 *
 * @param pageGuides matched active page guides (never null; possibly empty)
 * @param links      matched active source links (never null; possibly empty)
 */
public record PlannedEvidence(List<BrainPageGuide> pageGuides, List<BrainSourceLink> links) {

    private static final PlannedEvidence EMPTY = new PlannedEvidence(List.of(), List.of());

    /** Empty side-evidence — the default when only CORPUS is planned. */
    public static PlannedEvidence empty() {
        return EMPTY;
    }
}
