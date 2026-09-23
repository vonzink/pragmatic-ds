package com.pragmaticds.rag.service.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.service.ai.ModelAnswer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelAnswerParserTest {

    private final ModelAnswerParser parser = new ModelAnswerParser(new ObjectMapper());

    @Test
    void parseExtractsJsonObjectFromProviderMarkdown() {
        ModelAnswer result = parser.parse("""
                Here is the answer:
                ```json
                {"answer":"PMI is mortgage insurance.","citations":[],"confidence":0.85,
                 "human_escalation_required":false,"disclaimer":"d"}
                ```
                """);

        assertEquals("PMI is mortgage insurance.", result.answer());
        assertEquals(0.85, result.confidence());
    }

    @Test
    void parseReturnsNullWhenNoJsonObjectExists() {
        assertNull(parser.parse("not json"));
    }

    @Test
    void parseReturnsNullForBlankContent() {
        assertNull(parser.parse(" "));
    }
}
