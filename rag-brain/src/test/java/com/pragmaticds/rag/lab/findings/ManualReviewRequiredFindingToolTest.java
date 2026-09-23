package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Box;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualReviewRequiredFindingToolTest {

    // SchemaRef takes a UUID id, not two Strings.
    private static final UUID SCHEMA_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final ManualReviewRequiredFindingTool tool = new ManualReviewRequiredFindingTool();

    private static FieldOccurrence occurrence(String name, String validationStatus) {
        return new FieldOccurrence(name, null, FieldStatus.FOUND, "MONEY", null, null, null,
                new SchemaRef(SCHEMA_ID, "1"), "OCR", "1.0.0", BigDecimal.ONE, null,
                validationStatus, false,
                List.of(new EvidenceSpan(UUID.randomUUID(), null, null, "VALUE", 1,
                        new Box(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                                BigDecimal.ONE))));
    }

    private static EngineResultEnvelope envelopeWith(FieldOccurrence... occurrences) {
        LogicalDocument document = new LogicalDocument(UUID.randomUUID(), "PAYSTUB", 0,
                List.of(), List.of(occurrences));
        return TestEnvelopes.withDocuments(List.of(document));
    }

    @Test
    void isRegisteredAsAFindingsProducerAndNotADomainProducer() {
        assertTrue(tool.producesFindings());
        assertEquals(false, tool.producesDomain());
        assertEquals("field.manual_review_required", tool.name());
        assertEquals("1.0.0", tool.version());
    }

    @Test
    void firesOncePerManualReviewOccurrence() {
        JsonNode output = tool.execute(envelopeWith(
                occurrence("ytd_gross", "MANUAL_REVIEW_REQUIRED"),
                occurrence("net_pay", "OK"),
                occurrence("gross_pay", "MANUAL_REVIEW_REQUIRED")));

        List<Finding> findings = FindingJson.fromOutput(output);

        assertEquals(2, findings.size());
        assertEquals("ytd_gross", findings.get(0).anchors().get(0).fieldName());
        assertEquals("gross_pay", findings.get(1).anchors().get(0).fieldName());
    }

    @Test
    void emitsNothingWhenNoOccurrenceNeedsReview() {
        JsonNode output = tool.execute(envelopeWith(occurrence("net_pay", "OK")));
        assertEquals(0, FindingJson.fromOutput(output).size());
    }

    @Test
    void findingsCarryNoSubjectKey() {
        JsonNode output = tool.execute(
                envelopeWith(occurrence("ytd_gross", "MANUAL_REVIEW_REQUIRED")));
        assertNull(FindingJson.fromOutput(output).get(0).subjectKey());
    }

    @Test
    void outputIsByteStableAcrossRuns() {
        EngineResultEnvelope envelope =
                envelopeWith(occurrence("ytd_gross", "MANUAL_REVIEW_REQUIRED"));
        assertEquals(tool.execute(envelope).toString(), tool.execute(envelope).toString());
    }

    @Test
    void schemaDigestsAreSixtyFourHexCharacters() {
        assertTrue(tool.inputSchemaSha256().matches("[0-9a-f]{64}"));
        assertTrue(tool.outputSchemaSha256().matches("[0-9a-f]{64}"));
    }
}
