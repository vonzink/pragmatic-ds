package com.pragmaticds.rag.dto;

import java.util.List;

public record BrainProfileRequest(
        String mode,
        String purpose,
        String audience,
        String personality,
        String tone,
        String expertiseLevel,
        String answerLength,
        double confidenceTarget,
        Double retrievalConfidenceThreshold,
        Double answerConfidenceFloor,
        String clarificationPolicy,
        String escalationPolicy,
        String citationPolicy,
        String ctaPolicy,
        String disclaimer,
        boolean publicEnabled,
        List<String> allowedDomains
) {
}
