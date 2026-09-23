package com.pragmaticds.rag.service.clarification;

import com.pragmaticds.rag.domain.ResponseType;

import java.util.List;
import java.util.Map;

public record ClarificationDecision(
        ResponseType responseType,
        String question,
        List<String> missingFacts,
        Map<String, Object> reason
) {
    public static ClarificationDecision answer() {
        return new ClarificationDecision(ResponseType.ANSWER, null, List.of(), Map.of("decision", "answer"));
    }
}
