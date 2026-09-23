package com.pragmaticds.rag.service.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiRedactionServiceTest {

    private final PiiRedactionService redactor = new PiiRedactionService();

    @Test
    void redactsSsn() {
        String result = redactor.redact("My SSN is 123-45-6789, can I qualify?");
        assertFalse(result.contains("123-45-6789"));
        assertTrue(result.contains("[REDACTED-SSN]"));
    }

    @Test
    void redactsCreditCard() {
        String result = redactor.redact("Card: 4111 1111 1111 1111 has a balance");
        assertFalse(result.contains("4111 1111 1111 1111"));
        assertTrue(result.contains("[REDACTED-CARD]"));
    }

    @Test
    void redactsBankAccount() {
        String result = redactor.redact("My account number: 123456789012 at Chase");
        assertFalse(result.contains("123456789012"));
        assertTrue(result.contains("[REDACTED-ACCOUNT]"));
    }

    @Test
    void redactsDateOfBirth() {
        String result = redactor.redact("DOB: 04/15/1985 and I want a loan");
        assertFalse(result.contains("04/15/1985"));
        assertTrue(result.contains("[REDACTED-DOB]"));
    }

    @Test
    void leavesNormalQuestionsUntouched() {
        String question = "Can I use gift funds for my down payment on a conventional loan?";
        assertEquals(question, redactor.redact(question));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals(null, redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }
}
