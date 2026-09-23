package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.LinkRef;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin/dashboard view of a page guide. snake_case wire keys mirror SourceLinkDto;
 * surface is exposed as its .name() string; the boolean is exposed as "active";
 * internal_links serialize each LinkRef record to {"label":...,"url":...};
 * source_link_ids are stringified UUIDs.
 */
public record PageGuideDto(
        UUID id,
        String route,
        String title,
        String purpose,
        String surface,
        @JsonProperty("user_intents") List<String> userIntents,
        @JsonProperty("allowed_guidance") List<String> allowedGuidance,
        @JsonProperty("internal_links") List<LinkRef> internalLinks,
        @JsonProperty("source_link_ids") List<String> sourceLinkIds,
        List<String> topics,
        boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("updated_by") String updatedBy
) {

    public static PageGuideDto from(BrainPageGuide g) {
        return new PageGuideDto(
                g.getId(),
                g.getRoute(),
                g.getTitle(),
                g.getPurpose(),
                g.getSurface().name(),
                g.getUserIntents(),
                g.getAllowedGuidance(),
                g.getInternalLinks(),
                g.getSourceLinkIds().stream().map(UUID::toString).toList(),
                g.getTopics(),
                g.isActive(),
                g.getCreatedAt(),
                g.getCreatedBy(),
                g.getUpdatedAt(),
                g.getUpdatedBy()
        );
    }
}
