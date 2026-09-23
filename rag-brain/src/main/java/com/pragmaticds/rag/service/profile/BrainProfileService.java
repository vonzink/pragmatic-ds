package com.pragmaticds.rag.service.profile;

import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.dto.BrainProfileRequest;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class BrainProfileService {

    private final BrainRepository brains;
    private final BrainProfileRepository profiles;

    public BrainProfileService(BrainRepository brains, BrainProfileRepository profiles) {
        this.brains = brains;
        this.profiles = profiles;
    }

    @Transactional
    public BrainProfile getOrCreate(UUID brainId) {
        brains.findById(brainId).orElseThrow(() -> new IllegalArgumentException("Brain not found: " + brainId));
        return profiles.findByBrainId(brainId).orElseGet(() -> {
            BrainProfile profile = new BrainProfile();
            profile.setBrainId(brainId);
            profile.setMode(BrainMode.PUBLIC_SITE);
            profile.setPurpose("Answer questions from approved sources.");
            profile.setAudience("public visitor");
            profile.setPersonality("Conversational, concise, source-grounded assistant.");
            profile.setTone("professional");
            profile.setExpertiseLevel("intermediate");
            profile.setAnswerLength("balanced");
            profile.setConfidenceTarget(0.90);
            profile.setClarificationPolicy("Ask one focused clarifying question when required facts are missing.");
            profile.setEscalationPolicy("Escalate personalized, unsupported, sensitive, or low-confidence requests.");
            profile.setCitationPolicy("required_when_sources_used");
            profile.setCtaPolicy("Recommend relevant pages or a human handoff when useful.");
            profile.setDisclaimer("This answer is generated from approved source context and may be incomplete.");
            profile.setPublicEnabled(false);
            profile.setAllowedDomains(List.of());
            return profiles.save(profile);
        });
    }

    @Transactional
    public BrainProfile update(UUID brainId, BrainProfileRequest req) {
        BrainProfile profile = getOrCreate(brainId);
        BrainMode mode = parseMode(req.mode());
        List<String> allowedDomains = normalizeAllowedDomains(req.allowedDomains());
        if (req.publicEnabled() && allowedDomains.isEmpty()) {
            throw new IllegalArgumentException("allowedDomains is required when public access is enabled");
        }
        profile.setMode(mode);
        profile.setPurpose(required(req.purpose(), "purpose"));
        profile.setAudience(required(req.audience(), "audience"));
        profile.setPersonality(required(req.personality(), "personality"));
        profile.setTone(required(req.tone(), "tone"));
        profile.setExpertiseLevel(required(req.expertiseLevel(), "expertiseLevel"));
        profile.setAnswerLength(required(req.answerLength(), "answerLength"));
        profile.setConfidenceTarget(req.confidenceTarget());
        profile.setRetrievalConfidenceThreshold(req.retrievalConfidenceThreshold());
        profile.setAnswerConfidenceFloor(req.answerConfidenceFloor());
        profile.setClarificationPolicy(required(req.clarificationPolicy(), "clarificationPolicy"));
        profile.setEscalationPolicy(required(req.escalationPolicy(), "escalationPolicy"));
        profile.setCitationPolicy(required(req.citationPolicy(), "citationPolicy"));
        profile.setCtaPolicy(required(req.ctaPolicy(), "ctaPolicy"));
        profile.setDisclaimer(required(req.disclaimer(), "disclaimer"));
        profile.setPublicEnabled(req.publicEnabled());
        profile.setAllowedDomains(allowedDomains);
        return profiles.save(profile);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static BrainMode parseMode(String value) {
        String mode = required(value, "mode");
        try {
            return BrainMode.valueOf(mode);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "mode must be one of PUBLIC_SITE, PRIVATE_SITE, SECURE_DEPLOYMENT");
        }
    }

    private static List<String> normalizeAllowedDomains(List<String> allowedDomains) {
        if (allowedDomains == null) {
            return List.of();
        }
        if (allowedDomains.stream().anyMatch(domain -> domain == null)) {
            throw new IllegalArgumentException("allowedDomains must not contain null entries");
        }
        return allowedDomains.stream()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(BrainProfileService::toHost)
                .distinct()
                .toList();
    }

    private static String toHost(String value) {
        String raw = value.strip();
        String withScheme = raw.matches("(?i)^[a-z][a-z0-9+.-]*://.*")
                ? raw
                : "https://" + raw;
        try {
            String host = URI.create(withScheme).getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("allowedDomains entries must be valid domains or origins");
            }
            return host.toLowerCase(Locale.US);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("allowedDomains entries must be valid domains or origins");
        }
    }
}
