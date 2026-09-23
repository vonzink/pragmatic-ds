package com.pragmaticds.rag.service.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final EnvelopeValidator validator = new EnvelopeValidator();

    /** Loads a fresh mutable copy of the shared valid sample envelope. */
    private static ObjectNode sample() {
        try (InputStream in = EnvelopeValidatorTest.class.getResourceAsStream("/ai/sample-envelope-v2.json")) {
            assertNotNull(in, "sample fixture missing: /ai/sample-envelope-v2.json");
            return (ObjectNode) MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void validSamplePassesWithNoErrors() {
        assertEquals(List.of(), validator.validate(sample()));
    }

    @Test
    void missingRequiredFieldIsReported() {
        ObjectNode envelope = sample();
        envelope.remove("confidence");
        List<String> errors = validator.validate(envelope);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("confidence")),
                "expected an error mentioning 'confidence' but got: " + errors);
    }

    @Test
    void unknownTopLevelFieldIsRejected() {
        ObjectNode envelope = sample();
        envelope.put("extra", 1);
        assertFalse(validator.validate(envelope).isEmpty());
    }

    @Test
    void factWithoutCitationIsRejected() {
        ObjectNode envelope = sample();
        ((ObjectNode) envelope.path("facts").get(0)).set("citationIds", MAPPER.createArrayNode());
        assertFalse(validator.validate(envelope).isEmpty());
    }

    @Test
    void badCitationClassIsRejected() {
        ObjectNode envelope = sample();
        ((ObjectNode) envelope.path("citations").get(0)).put("class", "OTHER");
        assertFalse(validator.validate(envelope).isEmpty());
    }

    @Test
    void errorsAreSortedAndLocationPrefixed() {
        ObjectNode envelope = sample();
        // Two violations whose NATURAL emission order (facts first) differs from sorted
        // order ($.citations < $.facts) — so a deleted .sorted() genuinely fails below.
        ((ObjectNode) envelope.path("facts").get(0)).set("citationIds", MAPPER.createArrayNode());
        ((ObjectNode) envelope.path("citations").path(0)).put("class", "OTHER");

        List<String> errors = validator.validate(envelope);
        assertTrue(errors.size() >= 2, "expected at least 2 errors but got: " + errors);
        assertEquals(errors.stream().sorted().toList(), errors, "errors must be sorted");
        assertTrue(errors.stream().anyMatch(e -> e.contains("citationIds")),
                "expected an error mentioning 'citationIds' but got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("citations[0]")),
                "expected an error mentioning 'citations[0]' but got: " + errors);
        for (String error : errors) {
            assertTrue(error.startsWith("$"), "error must start with '$': " + error);
            // The instance location must appear exactly once — no '$: $: ...' stutter.
            String location = error.substring(0, error.indexOf(':'));
            String rest = error.substring(error.indexOf(':') + 1);
            assertFalse(rest.contains(location + ":"),
                    "instance location repeated in error: " + error);
        }
    }

    @Test
    void envelopeVersionMustBeTwo() {
        ObjectNode envelope = sample();
        envelope.put("envelopeVersion", "1.0");
        assertFalse(validator.validate(envelope).isEmpty());
    }
}
