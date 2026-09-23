package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.pack.DomainPackRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rule-based pre-classifier that runs BEFORE retrieval. Catches questions the
 * bot must never answer (rag.md guardrails) without spending an embedding or
 * LLM call on them.
 *
 * Rule order comes from the brain's pack classifier.yaml list; FRAUD first by
 * pack convention so "can I hide debt to qualify?" is refused as fraud, not
 * escalated as an eligibility question. The compiled patterns are resolved
 * per brain from the DomainPackRegistry (bundle cache), not precomputed once.
 *
 * COMPLIANCE-CRITICAL: patterns are defined in the domain pack.
 * Additions are fine; removals or loosening need review.
 */
@Service
public class QuestionClassifierService {

    private final DomainPackRegistry registry;

    public QuestionClassifierService(DomainPackRegistry registry) {
        this.registry = registry;
    }

    public QuestionCategory classify(String question, UUID brainId) {
        if (question == null || question.isBlank()) {
            return QuestionCategory.EDUCATIONAL;
        }
        String normalized = question.toLowerCase(Locale.US).strip();

        Map<QuestionCategory, List<Pattern>> rules = registry.bundle(brainId).classifierPatterns();
        for (Map.Entry<QuestionCategory, List<Pattern>> entry : rules.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(normalized).find()) {
                    return entry.getKey();
                }
            }
        }
        return QuestionCategory.EDUCATIONAL;
    }
}
