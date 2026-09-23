package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a scenario may address, and why an unaddressable pointer must be refused.
 *
 * <p>The load-bearing case is the forbidden one. A required pointer that cannot resolve fails
 * loudly and someone fixes it; a forbidden pointer that cannot resolve is satisfied by every
 * release forever, and records a promotion-authorizing pass that measured nothing. These tests
 * exist so that the shipped income set's v1-era {@code /findings/items/0} can never come back.
 */
class InstanceAssertionDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A stored result as {@code InstanceExecutionService} seals it: findings are a STRING holding
     * the whole enriched v2 envelope.
     *
     * <p>Built with Jackson rather than an escaped literal. JSON nested inside a JSON string is
     * precisely where a hand-written fixture goes quietly wrong, and a fixture that is subtly
     * malformed would make these tests assert something other than they claim.
     */
    private static byte[] sealed(String... envelopeArrays) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("envelopeVersion", "2.0");
        envelope.put("analyzer", "income-v2");
        for (String array : envelopeArrays) {
            envelope.putArray(array).addObject().put("id", array.charAt(0) + "1");
        }
        envelope.putArray("missingItems");
        return sealedWith(envelope.toString());
    }

    /** A stored result whose findings payload is whatever the caller supplies, verbatim. */
    private static byte[] sealedWith(String findingsPayload) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "SUCCESS");
        result.put("reportMarkdown", "## Income");
        result.putArray("citations").addObject().put("id", "c1");
        result.put("provider", "anthropic");
        result.put("model", "claude-opus-5");
        result.put("findingsJson", findingsPayload);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ============================================================ inflation

    @Test
    void findingsAreInflatedSoAPointerCanReachInsideThem() {
        JsonNode document = InstanceAssertionDocument.of(sealed("facts", "calculations"), MAPPER);

        // Against the raw stored bytes none of these resolve, because findingsJson is a string.
        assertTrue(InstanceAssertionDocument.resolves(document, "/findings/facts/0"));
        assertTrue(InstanceAssertionDocument.resolves(document, "/findings/calculations/0"));
        assertEquals("income-v2", document.at("/findings/analyzer").asText());
    }

    @Test
    void theStoredStringSurvivesAlongsideTheInflatedNode() {
        JsonNode document = InstanceAssertionDocument.of(sealed("facts", "calculations"), MAPPER);

        // The assertion document is a readable view of what was sealed, not a replacement for it.
        assertTrue(document.path("findingsJson").isTextual());
        assertTrue(InstanceAssertionDocument.resolves(document, "/reportMarkdown"));
        assertTrue(InstanceAssertionDocument.resolves(document, "/citations/0"));
    }

    @Test
    void anEmptyEnvelopeLeavesEveryPointerIntoItUnresolved() {
        JsonNode document = InstanceAssertionDocument.of(sealed(), MAPPER);

        // The negative scenario's whole point: nothing was fabricated, so nothing resolves.
        assertTrue(InstanceAssertionDocument.resolves(document, "/reportMarkdown"));
        assertFalse(InstanceAssertionDocument.resolves(document, "/findings/facts/0"));
        assertFalse(InstanceAssertionDocument.resolves(document, "/findings/calculations/0"));
    }

    @Test
    void findingsThatAreNotJsonBecomeNullRatherThanLosingTheRun() {
        JsonNode document = InstanceAssertionDocument.of(sealedWith("not json at all"), MAPPER);

        // A verdict of "the pointers did not resolve" is a real outcome; an exception here would
        // throw away the whole evaluation over one malformed case.
        assertFalse(InstanceAssertionDocument.resolves(document, "/findings/facts/0"));
    }

    @Test
    void anExplicitNullCountsAsAbsent() {
        ObjectNode nulled = MAPPER.createObjectNode();
        nulled.put("status", "SUCCESS");
        nulled.putNull("reason");
        JsonNode document = InstanceAssertionDocument.of(
                nulled.toString().getBytes(StandardCharsets.UTF_8), MAPPER);

        // A forbidden pointer must not be satisfied by a null sitting where a value would be.
        assertFalse(InstanceAssertionDocument.resolves(document, "/reason"));
    }

    @Test
    void aResultThatIsNotReadableJsonIsRefused() {
        byte[] truncated = "{\"status\":".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> InstanceAssertionDocument.of(truncated, MAPPER));
    }

    // ============================================================ addressability

    @Test
    void theV1FindingsShapeIsRefusedBecauseV2CallsThatArrayFacts() {
        // The exact defect this type exists to prevent. As a FORBIDDEN pointer it would have been
        // satisfied by every release forever, certifying that findings were not fabricated by a
        // release that fabricated them freely.
        assertFalse(InstanceAssertionDocument.addressable("/findings/items"));
        assertFalse(InstanceAssertionDocument.addressable("/findings/items/0"));

        assertTrue(InstanceAssertionDocument.addressable("/findings/facts"));
        assertTrue(InstanceAssertionDocument.addressable("/findings/facts/0"));
    }

    @Test
    void everyTopLevelResultFieldAndEveryEnvelopePropertyIsAddressable() {
        for (String pointer : new String[] {
                "/status", "/reportMarkdown", "/citations", "/citations/0", "/provider",
                "/model", "/inputTokens", "/costUsd", "/skippedDocs", "/reason", "/filtered"}) {
            assertTrue(InstanceAssertionDocument.addressable(pointer), pointer);
        }
        for (String property : new String[] {
                "envelopeVersion", "analyzer", "facts", "assumptions", "warnings",
                "recommendations", "calculations", "missingItems", "citations", "confidence"}) {
            assertTrue(InstanceAssertionDocument.addressable("/findings/" + property), property);
        }
    }

    @Test
    void deeperPathsAreContentAndPassUnchecked() {
        // Two levels carry the shape contract. Below that is content, and a gate that pretended to
        // type-check it would be a schema validator wearing a promotion gate's clothes.
        assertTrue(InstanceAssertionDocument.addressable("/findings/facts/0/citationIds/0"));
        assertTrue(InstanceAssertionDocument.addressable("/citations/0/documentId"));
    }

    @Test
    void malformedAndUnknownPointersAreRefused() {
        for (String pointer : new String[] {
                null, "", "   ", "reportMarkdown", "/", "//facts",
                "/notAResultField", "/findings/notAnEnvelopeProperty", "/findings/facts//0"}) {
            assertFalse(InstanceAssertionDocument.addressable(pointer), String.valueOf(pointer));
        }
    }
}
