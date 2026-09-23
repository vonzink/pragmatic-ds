package com.pragmaticds.rag.dto;

import java.util.List;

/** Folder-brain chat response: a plain answer plus opaque corpus citations. */
public record ChatResponse(String answer, List<CitationDto> citations) {
}
