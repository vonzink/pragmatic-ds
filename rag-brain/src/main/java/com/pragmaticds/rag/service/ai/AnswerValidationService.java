package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.pack.DomainPackRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Compliance gate that runs on every model answer before it reaches the
 * website. An answer that fails here is never shown to the visitor —
 * the caller returns the escalation response instead. The prohibited-phrase
 * and eligible-phrase lists are resolved per brain from DomainPackRegistry.
 *
 * COMPLIANCE-CRITICAL: phrase lists come from the domain pack (guardrails
 * section). Additions to the pack are fine; removals need review.
 */
@Service
public class AnswerValidationService {

    private final DomainPackRegistry registry;

    public AnswerValidationService(DomainPackRegistry registry) {
        this.registry = registry;
    }

    /**
     * Full gate: content/compliance checks PLUS the citation-presence requirement.
     */
    public ValidationResult validate(ModelAnswer answer, boolean evidenceWasSufficient, UUID brainId) {
        ValidationResult content = validateContent(answer, brainId);
        if (!content.valid()) {
            return content;
        }

        if (evidenceWasSufficient
                && (answer.citations() == null || answer.citations().isEmpty())) {
            return ValidationResult.fail("Answer is missing citations");
        }

        return ValidationResult.pass();
    }

    /**
     * Content/compliance gate on the model's answer text only (empty answer,
     * prohibited phrases, unquoted "you are eligible"). This MUST be run on the
     * model's raw output before any post-processing (e.g. citation backfill) so
     * that massaging the response can never mask a non-compliant answer.
     */
    public ValidationResult validateContent(ModelAnswer answer, UUID brainId) {
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return ValidationResult.fail("Model returned an empty answer");
        }

        var guardrails = registry.bundle(brainId).pack().guardrails();
        List<String> prohibitedPhrases = guardrails.prohibitedPhrases();
        String eligiblePhrase = guardrails.eligiblePhrase();

        String lower = answer.answer().toLowerCase(Locale.US);

        for (String phrase : prohibitedPhrases) {
            if (lower.contains(phrase)) {
                return ValidationResult.fail("Prohibited phrase detected: \"" + phrase + "\"");
            }
        }

        if (lower.contains(eligiblePhrase) && !isQuoted(answer.answer(), eligiblePhrase)) {
            return ValidationResult.fail("\"You are eligible\" used outside a direct guideline quote");
        }

        return ValidationResult.pass();
    }

    private boolean isQuoted(String text, String phrase) {
        String lower = text.toLowerCase(Locale.US);
        int idx = lower.indexOf(phrase);
        while (idx >= 0) {
            boolean openQuoteBefore = text.lastIndexOf('"', idx) >= 0
                    || text.lastIndexOf('“', idx) >= 0;
            int end = idx + phrase.length();
            boolean closeQuoteAfter = text.indexOf('"', end) >= 0
                    || text.indexOf('”', end) >= 0;
            if (!(openQuoteBefore && closeQuoteAfter)) {
                return false;
            }
            idx = lower.indexOf(phrase, end);
        }
        return true;
    }

    public record ValidationResult(boolean valid, String failureReason) {

        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
