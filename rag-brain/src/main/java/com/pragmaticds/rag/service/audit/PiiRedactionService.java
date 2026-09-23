package com.pragmaticds.rag.service.audit;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Redacts obvious sensitive data from text before it is persisted to audit
 * logs (rag.md security requirement). This is a best-effort pattern scrub,
 * not a guarantee — do not log anything described as borrower file data.
 */
@Service
public class PiiRedactionService {

    private static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b|\\b\\d{9}\\b(?=.*(?i:ssn|social))");

    private static final Pattern CREDIT_CARD =
            Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b");

    private static final Pattern BANK_ACCOUNT =
            Pattern.compile("(?i)(account\\s*(number|no|#)?\\s*[:#]?\\s*)\\d{6,17}\\b");

    private static final Pattern DATE_OF_BIRTH =
            Pattern.compile("(?i)(dob|date of birth|born( on)?)\\s*[:\\-]?\\s*"
                    + "\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}");

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = SSN.matcher(text).replaceAll("[REDACTED-SSN]");
        result = CREDIT_CARD.matcher(result).replaceAll("[REDACTED-CARD]");
        result = BANK_ACCOUNT.matcher(result).replaceAll("$1[REDACTED-ACCOUNT]");
        result = DATE_OF_BIRTH.matcher(result).replaceAll("$1: [REDACTED-DOB]");
        return result;
    }
}
