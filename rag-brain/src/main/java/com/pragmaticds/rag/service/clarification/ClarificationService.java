package com.pragmaticds.rag.service.clarification;

import com.pragmaticds.rag.domain.ClarificationRule;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.repository.ClarificationRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ClarificationService {

    private final ClarificationRuleRepository rules;

    public ClarificationService(ClarificationRuleRepository rules) {
        this.rules = rules;
    }

    public ClarificationDecision decide(UUID brainId, String question, String surface, Map<String, Object> facts) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.US);
        if (normalized.contains("where do i") || normalized.contains("how do i apply")
                || normalized.contains("show me") || normalized.contains("page")) {
            return new ClarificationDecision(ResponseType.NAVIGATE, null, List.of(), Map.of("decision", "navigation intent"));
        }
        Map<String, Object> knownFacts = facts == null ? Map.of() : facts;
        boolean publicSurface = surface == null || surface.isBlank() || "PUBLIC".equalsIgnoreCase(surface);
        for (ClarificationRule rule : rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(brainId)) {
            if (publicSurface && !rule.isRequiredForPublic()) {
                continue;
            }
            List<String> missing = rule.getRequiredFacts().stream()
                    .filter(f -> !knownFacts.containsKey(f) || String.valueOf(knownFacts.get(f)).isBlank())
                    .toList();
            if (!missing.isEmpty() && questionMatches(rule, normalized)) {
                return new ClarificationDecision(ResponseType.CLARIFY, rule.getQuestion(), List.of(missing.getFirst()),
                        Map.of("topic", rule.getTopic(), "intent", rule.getIntent()));
            }
        }
        return ClarificationDecision.answer();
    }

    private static boolean questionMatches(ClarificationRule rule, String normalized) {
        String topic = rule.getTopic() == null ? "" : rule.getTopic().toLowerCase(Locale.US);
        String intent = rule.getIntent() == null ? "" : rule.getIntent().toLowerCase(Locale.US);
        return normalized.contains(topic)
                || normalized.contains(intent)
                || normalized.contains("qualify")
                || normalized.contains("eligible")
                || normalized.contains("can i");
    }
}
