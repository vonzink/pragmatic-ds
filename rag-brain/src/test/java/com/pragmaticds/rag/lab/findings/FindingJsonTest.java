package com.pragmaticds.rag.lab.findings;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code toOutput} is the tool stage and is schema-governed: a tool holds no run context, so a
 * finding reaching it with a stamped subject key is a bug to catch rather than a shape to permit.
 * {@code toArray} is the publication stage, called again once an assembler has stamped a subject
 * key, and deliberately allows what {@code toOutput} refuses.
 */
class FindingJsonTest {

    private static Finding finding(String subjectKey) {
        FindingAnchor anchor = new FindingAnchor(
                UUID.randomUUID(), "PAYSTUB", 0, "ytd_gross", null, null, null);
        Finding finding = new Finding("field.manual_review_required", "1.0.0",
                Finding.Severity.WARNING, "The extractor flagged ytd_gross for manual review.",
                null, FindingDigest.of("MANUAL_REVIEW_REQUIRED"), List.of(anchor), List.of());
        return subjectKey == null ? finding : finding.withSubjectKey(subjectKey);
    }

    @Test
    void toOutputRejectsAFindingCarryingASubjectKey() {
        List<Finding> stamped = List.of(finding("loan-42:field.manual_review_required:ytd_gross"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FindingJson.toOutput(stamped));

        // Value-free: never a summary, a field name, or borrower data.
        assertEquals("TOOL_OUTPUT_CARRIES_SUBJECT_KEY", failure.getMessage());
    }

    @Test
    void toOutputAcceptsFindingsWithNoSubjectKey() {
        ObjectNode output = FindingJson.toOutput(List.of(finding(null)));

        assertEquals(1, output.path("findings").size());
    }

    @Test
    void toArrayEmitsSubjectKeyForAStampedFinding() {
        ArrayNode array = FindingJson.toArray(
                List.of(finding("loan-42:field.manual_review_required:ytd_gross")));

        assertEquals("loan-42:field.manual_review_required:ytd_gross",
                array.get(0).path("subjectKey").asText());
    }
}
