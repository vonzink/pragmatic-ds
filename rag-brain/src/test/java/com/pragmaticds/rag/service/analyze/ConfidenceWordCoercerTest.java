package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loan Johnston, 2026-09-22: the first production income-v2 run wrote HIGH/MEDIUM/LOW on facts,
 * assumptions and the envelope where the schema wants a 0–1 score, failed validation twice and
 * was lost. The coercer accepts the words; the validator still owns everything else.
 */
class ConfidenceWordCoercerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void words_on_the_envelope_facts_and_assumptions_become_scores_and_are_counted() throws Exception {
        JsonNode root = json("""
                {"envelopeVersion":"2.0","confidence":"HIGH",
                 "facts":[{"id":"f1","confidence":"high"},{"id":"f2","confidence":" Medium "}],
                 "assumptions":[{"id":"a1","confidence":"LOW"},{"id":"a2","confidence":"MED"}],
                 "warnings":[{"id":"w1","confidence":"low"}]}
                """);

        int n = ConfidenceWordCoercer.coerce(root);

        assertEquals(6, n);
        assertEquals(0.9, root.get("confidence").asDouble(), 1e-9);
        assertEquals(0.9, root.get("facts").get(0).get("confidence").asDouble(), 1e-9);
        assertEquals(0.6, root.get("facts").get(1).get("confidence").asDouble(), 1e-9);
        assertEquals(0.3, root.get("assumptions").get(0).get("confidence").asDouble(), 1e-9);
        assertEquals(0.6, root.get("assumptions").get(1).get("confidence").asDouble(), 1e-9);
        assertEquals(0.3, root.get("warnings").get(0).get("confidence").asDouble(), 1e-9);
        assertTrue(root.get("facts").get(0).get("confidence").isNumber());
    }

    @Test
    void numbers_unknown_words_and_domain_content_are_left_exactly_as_written() throws Exception {
        JsonNode root = json("""
                {"confidence":0.42,
                 "facts":[{"id":"f1","confidence":0.95},{"id":"f2","confidence":"very sure"},{"id":"f3"}],
                 "assumptions":"not-an-array",
                 "citations":[{"id":"c1","confidence":"HIGH"}],
                 "domain":{"borrowers":[{"sources":[{"confidence":"HIGH"}]}]}}
                """);

        int n = ConfidenceWordCoercer.coerce(root);

        assertEquals(0, n);
        assertEquals(0.42, root.get("confidence").asDouble(), 1e-9);
        assertEquals(0.95, root.get("facts").get(0).get("confidence").asDouble(), 1e-9);
        assertEquals("very sure", root.get("facts").get(1).get("confidence").asText());
        // citations carry no schema confidence; a word there stays for the validator to reject.
        assertEquals("HIGH", root.get("citations").get(0).get("confidence").asText());
        // the domain's own HIGH/MEDIUM/LOW is the domain schema's business, never rewritten.
        assertEquals("HIGH", root.at("/domain/borrowers/0/sources/0/confidence").asText());
    }

    @Test
    void a_non_object_root_is_a_no_op() throws Exception {
        assertEquals(0, ConfidenceWordCoercer.coerce(json("[1,2,3]")));
        assertEquals(0, ConfidenceWordCoercer.coerce(null));
    }
}
