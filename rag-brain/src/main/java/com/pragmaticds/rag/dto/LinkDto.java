package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A curated external source link surfaced with the answer (spec §6.3), resolved
 * SERVER-SIDE from the active Link Registry via the Phase 6/7 planner — never
 * emitted by the model. All keys are single words; {@code @JsonProperty("name")}
 * is explicit to mirror {@link CitationDto}'s annotation style, while {@code url}
 * and {@code authority} need none (identical to {@link CitationDto#section()}).
 *
 * @param name      the link's display name
 * @param url       the link's URL (non-blank by construction)
 * @param authority the link's trust tier name (e.g. {@code "PRIMARY"}), the bare
 *                  {@link com.pragmaticds.rag.domain.LinkAuthority} enum name
 */
public record LinkDto(
        @JsonProperty("name") String name,
        String url,
        String authority
) {
}
