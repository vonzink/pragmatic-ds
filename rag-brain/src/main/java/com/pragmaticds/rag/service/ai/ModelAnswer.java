package com.pragmaticds.rag.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pragmaticds.rag.dto.CitationDto;

import java.util.List;

/**
 * The JSON object the model is instructed to return (see PromptBuilderService).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelAnswer(
        String answer,
        List<CitationDto> citations,
        Double confidence,
        @JsonProperty("human_escalation_required") Boolean humanEscalationRequired,
        String disclaimer
) {
}
