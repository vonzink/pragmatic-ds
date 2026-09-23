package com.pragmaticds.rag.service.answer;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptQuestionContextServiceTest {

    private final PromptQuestionContextService context = new PromptQuestionContextService();

    @Test
    void appendFactsReturnsQuestionUnchangedWhenNoUsableFactsExist() {
        assertEquals("What is PMI?", context.appendFacts("What is PMI?", Map.of()));
        assertEquals("What is PMI?", context.appendFacts("What is PMI?", Map.of(" ", "FHA")));
    }

    @Test
    void appendFactsAddsSanitizedUserContextWithoutChangingTheLeadingQuestion() {
        String result = context.appendFacts("What is PMI?",
                Map.of(" loan\ntype ", " FHA\nloan "));

        assertTrue(result.startsWith("What is PMI?"));
        assertTrue(result.contains("loan type: FHA loan"));
        assertFalse(result.contains("loan\ntype"));
    }

    @Test
    void appendFactsCapsTheNumberOfPromptFacts() {
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            facts.put("key-" + i, "value-" + i);
        }

        String result = context.appendFacts("What is PMI?", facts);

        assertTrue(result.contains("key-0: value-0"));
        assertTrue(result.contains("key-9: value-9"));
        assertFalse(result.contains("key-10: value-10"));
    }
}
