package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pragmaticds.rag.domain.BrainSourceLink;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin/dashboard view of a source link. snake_case wire keys mirror CitationDto;
 * enums are exposed as their .name() string; the boolean is exposed as "active".
 */
public record SourceLinkDto(
        UUID id,
        String name,
        String url,
        String domain,
        String authority,
        List<String> topics,
        @JsonProperty("freshness_required") boolean freshnessRequired,
        @JsonProperty("allowed_use") List<String> allowedUse,
        @JsonProperty("do_not_use_for") List<String> doNotUseFor,
        String surface,
        boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("updated_by") String updatedBy
) {

    public static SourceLinkDto from(BrainSourceLink l) {
        return new SourceLinkDto(
                l.getId(),
                l.getName(),
                l.getUrl(),
                l.getDomain(),
                l.getAuthority().name(),
                l.getTopics(),
                l.isFreshnessRequired(),
                l.getAllowedUse(),
                l.getDoNotUseFor(),
                l.getSurface().name(),
                l.isActive(),
                l.getCreatedAt(),
                l.getCreatedBy(),
                l.getUpdatedAt(),
                l.getUpdatedBy()
        );
    }
}
