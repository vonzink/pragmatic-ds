package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainProfile;

import java.util.List;
import java.util.UUID;

public record BrainProfileDto(
        UUID brainId,
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
    public static BrainProfileDto from(BrainProfile p) {
        return new BrainProfileDto(
                p.getBrainId(), p.getMode().name(), p.getPurpose(), p.getAudience(),
                p.getPersonality(), p.getTone(), p.getExpertiseLevel(), p.getAnswerLength(),
                p.getConfidenceTarget(), p.getRetrievalConfidenceThreshold(), p.getAnswerConfidenceFloor(),
                p.getClarificationPolicy(), p.getEscalationPolicy(),
                p.getCitationPolicy(), p.getCtaPolicy(), p.getDisclaimer(),
                p.isPublicEnabled(), p.getAllowedDomains());
    }
}
