package com.pragmaticds.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A citation as returned to the website, matching the rag.md format.
 */
public record CitationDto(
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("document_name") String documentName,
        String section,
        @JsonProperty("page_number") String pageNumber,
        @JsonProperty("effective_date") String effectiveDate
) {
}
