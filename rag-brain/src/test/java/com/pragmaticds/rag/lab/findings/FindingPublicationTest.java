package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingPublicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Finding finding() {
        FindingAnchor anchor = new FindingAnchor(
                UUID.randomUUID(), "PAYSTUB", 0, "ytd_gross", null, null, null);
        return new Finding("field.manual_review_required", "1.0.0", Finding.Severity.WARNING,
                "summary", "sha256:bb", "sha256:aa", List.of(anchor), List.of());
    }

    @Test
    void findingsAreAddedToAnExistingEnvelopeWithoutDisturbingIt() throws Exception {
        String envelope = "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"income\","
                + "\"reportMarkdown\":\"r\",\"facts\":[],\"assumptions\":[],\"warnings\":[],"
                + "\"recommendations\":[],\"calculations\":[],\"missingItems\":[],"
                + "\"citations\":[],\"confidence\":0.9}";

        String published = FindingJson.publishInto(envelope, List.of(finding()));
        JsonNode node = MAPPER.readTree(published);

        assertEquals("2.0", node.path("envelopeVersion").asText());
        assertEquals("income", node.path("analyzer").asText());
        assertTrue(node.path("findings").isArray());
        assertEquals(1, node.path("findings").size());
        assertEquals("sha256:bb", node.path("findings").get(0).path("subjectKey").asText());
    }

    @Test
    void anEmptyFindingsListStillPublishesAnEmptyArray() throws Exception {
        String envelope = "{\"envelopeVersion\":\"2.0\"}";

        JsonNode node = MAPPER.readTree(FindingJson.publishInto(envelope, List.of()));

        assertTrue(node.path("findings").isArray());
        assertEquals(0, node.path("findings").size());
    }

    @Test
    void anUnparseableEnvelopeIsReturnedUnchanged() {
        // A run whose envelope never parsed has already failed for a better reason; findings
        // must not convert that into a second, more confusing failure.
        assertEquals("not json", FindingJson.publishInto("not json", List.of(finding())));
    }

    @Test
    void aNullEnvelopeIsReturnedUnchanged() {
        assertEquals(null, FindingJson.publishInto(null, List.of(finding())));
    }
}
