package com.pragmaticds.rag.dto;

/**
 * The page the brain recommends the visitor visit next (spec §6.3). Derived
 * SERVER-SIDE from the top matched page guide — never emitted by the model.
 * Both keys are single words, so (mirroring {@link CitationDto#section()}) no
 * {@code @JsonProperty} is needed; Jackson serializes {@code route} / {@code label}
 * as-is.
 *
 * @param route the page guide's route (e.g. {@code "/loan-options"}); non-blank by
 *              construction (a blank route yields {@code null} recommendedPage upstream)
 * @param label the page guide's title, shown as the link text
 */
public record RecommendedPageDto(String route, String label) {
}
