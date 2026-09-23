package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EvidenceSpan;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReviewState;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.engine.ReviewedFields.GroupKind;
import com.pragmaticds.rag.lab.engine.ReviewedFields.ReviewedField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedValueOverlayTest {

    private static final UUID PACKAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SOURCE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PAGE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID DOC = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final SchemaRef SCHEMA =
            new SchemaRef(UUID.fromString("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"), "2025.5a");

    private final ReviewedValueOverlay overlay = new ReviewedValueOverlay();

    // ------------------------------------------------------------------ fixtures

    private static FieldOccurrence machineOccurrence(String name, String groupKey, String text,
                                                     BigDecimal number) {
        return new FieldOccurrence(name, groupKey, FieldStatus.FOUND, number == null ? "TEXT" : "MONEY",
                text, text, new NormalizedValue(number == null ? text : null, number, null, null),
                SCHEMA, "ANCHOR_LABEL", "1.4.0", new BigDecimal("0.97"), null, "OK", false,
                List.of(new EvidenceSpan(PAGE, null, 7L, "VALUE", 0,
                        new EngineResultEnvelope.Box(BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ONE, BigDecimal.ONE))));
    }

    private static FieldOccurrence missingOccurrence(String name) {
        return new FieldOccurrence(name, null, FieldStatus.MISSING, "MONEY", null, null, null,
                SCHEMA, "NONE", "1.4.0", BigDecimal.ZERO, null, "MANUAL_REVIEW_REQUIRED", false,
                List.of());
    }

    private static EngineResultEnvelope envelope(List<FieldOccurrence> fields) {
        return envelopeOf(List.of(new LogicalDocument(DOC, "PAYSTUB", 1, List.of(PAGE), fields)));
    }

    private static EngineResultEnvelope envelopeOf(List<LogicalDocument> logicalDocuments) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("synthetic".getBytes(StandardCharsets.UTF_8)),
                "1.0.0", "DOCENGINE-C14N-1", PACKAGE,
                new Generation(JOB, 1, 3, "9f".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 64L, "application/pdf")),
                List.of(new EnginePage(PAGE, SOURCE, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                logicalDocuments,
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }

    private static ReviewedField reviewed(String name, String groupKey, ReviewState status,
                                          String displayed, String raw, String text,
                                          BigDecimal number, String method, boolean sensitive) {
        return new ReviewedField(name, groupKey, groupKey == null ? GroupKind.NONE : GroupKind.ROW,
                status, number == null ? "TEXT" : "MONEY", displayed, raw,
                new NormalizedValue(text, number, null, null), method, sensitive, List.of(PAGE));
    }

    private static ReviewedFields view(String sha, List<ReviewedField> fields) {
        return new ReviewedFields(DOC, "PAYSTUB", "2025.5a", fields, sha, 1234);
    }

    private static Map<UUID, ReviewedFields> only(ReviewedFields view) {
        return Map.of(view.documentId(), view);
    }

    private static FieldOccurrence field(EngineResultEnvelope envelope, String name) {
        return envelope.documents().get(0).fields().stream()
                .filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ status mapping

    @Test
    void machineKeepsTheReadModelValueAndMarksItsProvenance() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("grossPay", null, "4,670.69", new BigDecimal("4670.69"))));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.MACHINE, "4,670.69", "4,670.69", null,
                        new BigDecimal("4670.69"), "TABLE_CLUSTER", false)));

        ReviewedValueOverlay.Overlaid result = overlay.apply(pinned, only(view));
        FieldOccurrence gross = field(result.envelope(), "grossPay");

        assertEquals(FieldStatus.FOUND, gross.status());
        assertEquals(ReviewState.MACHINE, gross.reviewState());
        assertEquals(new BigDecimal("4670.69"), gross.normalized().number());
        assertEquals("TABLE_CLUSTER", gross.method(), "method follows the read model");
        assertEquals(1, gross.evidence().size(), "identity stays from the envelope");
        assertEquals(SCHEMA, gross.schema());
        assertEquals(1, result.snapshot().machineCount());
        assertEquals(0, result.snapshot().correctedCount());
        assertEquals(0, result.snapshot().rejectedCount());
    }

    @Test
    void aMachineRowWithNoValueArmsIsMissing() {
        EngineResultEnvelope pinned = envelope(List.of(missingOccurrence("federalWithholding")));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("federalWithholding", null, ReviewState.MACHINE, null, null, null, null,
                        "NONE", false)));

        FieldOccurrence result = field(overlay.apply(pinned, only(view)).envelope(), "federalWithholding");

        assertEquals(FieldStatus.MISSING, result.status());
        assertNull(result.normalized());
        assertEquals(ReviewState.MACHINE, result.reviewState());
        assertEquals("NONE", result.method(), "a MISSING occurrence must not carry a real method");
        assertNull(result.rawValue());
        assertNull(result.displayedText());
    }

    @Test
    void correctedUsesTheHumanValueAndKeepsTheMachineRaw() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("grossPay", null, "4,670.69", new BigDecimal("4670.69"))));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.CORRECTED, "4,760.69", "4,670.69", null,
                        new BigDecimal("4760.69"), "TABLE_CLUSTER", false)));

        FieldOccurrence gross = field(overlay.apply(pinned, only(view)).envelope(), "grossPay");

        assertEquals(FieldStatus.FOUND, gross.status());
        assertEquals(ReviewState.CORRECTED, gross.reviewState());
        assertEquals("4,760.69", gross.displayedText());
        assertEquals("4,670.69", gross.rawValue());
        assertEquals(new BigDecimal("4760.69"), gross.normalized().number());
    }

    @Test
    void aCorrectionOfAMachineMissIsFoundEvenThoughTheMethodIsNone() {
        EngineResultEnvelope pinned = envelope(List.of(missingOccurrence("federalWithholding")));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("federalWithholding", null, ReviewState.CORRECTED, "812.00", null, null,
                        new BigDecimal("812.00"), "NONE", false)));

        FieldOccurrence result = field(overlay.apply(pinned, only(view)).envelope(), "federalWithholding");

        assertEquals(FieldStatus.FOUND, result.status());
        assertEquals(new BigDecimal("812.00"), result.normalized().number());
        assertEquals(ReviewState.CORRECTED, result.reviewState());
    }

    @Test
    void rejectedBecomesMissingWithNoValueAndMethodNone() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("employerName", "A", "ACME", null)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("employerName", "A", ReviewState.REJECTED, "ACME", "ACME", "ACME", null,
                        "REGEX", false)));

        FieldOccurrence employer = field(overlay.apply(pinned, only(view)).envelope(), "employerName");

        assertEquals(FieldStatus.MISSING, employer.status());
        assertEquals(ReviewState.REJECTED, employer.reviewState());
        assertNull(employer.displayedText());
        assertNull(employer.rawValue());
        assertNull(employer.normalized());
        assertEquals("NONE", employer.method());
        assertEquals("A", employer.groupKey());
        assertEquals(1, employer.evidence().size(), "where the rejected reading was stays citable");
    }

    @Test
    void sensitivityFollowsTheReadModel() {
        EngineResultEnvelope pinned = envelope(List.of(machineOccurrence("ssn", null, "123-45-6789", null)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("ssn", null, ReviewState.MACHINE, "***-**-6789", "***-**-6789",
                        "***-**-6789", null, "ANCHOR_LABEL", true)));

        FieldOccurrence ssn = field(overlay.apply(pinned, only(view)).envelope(), "ssn");

        assertTrue(ssn.sensitive());
        assertEquals("***-**-6789", ssn.displayedText(), "the masked value replaces the raw one");
    }

    // ------------------------------------------------------------------ join failures

    @Test
    void aReadModelFieldTheEnvelopeLacksIsAContractViolation() {
        EngineResultEnvelope pinned = envelope(List.of(machineOccurrence("grossPay", null, "1", BigDecimal.ONE)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false),
                reviewed("netPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false)));

        DocumentEngineFailure failure =
                assertThrows(DocumentEngineFailure.class, () -> overlay.apply(pinned, only(view)));
        assertEquals(DocumentEngineFailure.Code.READMODEL_FIELD_MISMATCH, failure.code());
    }

    @Test
    void anEnvelopeFieldTheReadModelLacksIsAContractViolation() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("grossPay", null, "1", BigDecimal.ONE),
                machineOccurrence("netPay", null, "1", BigDecimal.ONE)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false)));

        DocumentEngineFailure failure =
                assertThrows(DocumentEngineFailure.class, () -> overlay.apply(pinned, only(view)));
        assertEquals(DocumentEngineFailure.Code.READMODEL_FIELD_MISMATCH, failure.code());
    }

    @Test
    void aMissingOrMistypedDocumentIsAContractViolation() {
        EngineResultEnvelope pinned = envelope(List.of(machineOccurrence("grossPay", null, "1", BigDecimal.ONE)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false)));

        assertEquals(DocumentEngineFailure.Code.READMODEL_DOCUMENT_MISMATCH,
                assertThrows(DocumentEngineFailure.class, () -> overlay.apply(pinned, Map.of())).code());
        ReviewedFields wrongType = new ReviewedFields(DOC, "W2", "2025.5a", view.fields(), view.sha256(), 1);
        assertEquals(DocumentEngineFailure.Code.READMODEL_DOCUMENT_MISMATCH,
                assertThrows(DocumentEngineFailure.class,
                        () -> overlay.apply(pinned, only(wrongType))).code());
    }

    // ------------------------------------------------------------------ snapshot

    @Test
    void theSnapshotDigestDependsOnTheBytesNotTheFieldOrderAndCountsEveryStatus() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("a", null, "1", BigDecimal.ONE),
                machineOccurrence("b", null, "1", BigDecimal.ONE),
                machineOccurrence("c", null, "1", BigDecimal.ONE)));
        ReviewedField a = reviewed("a", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false);
        ReviewedField b = reviewed("b", null, ReviewState.CORRECTED, "2", "1", null, BigDecimal.TWO, "REGEX", false);
        ReviewedField c = reviewed("c", null, ReviewState.REJECTED, "1", "1", null, BigDecimal.ONE, "REGEX", false);

        ReviewSnapshot first = overlay.apply(pinned, only(view("aa".repeat(32), List.of(a, b, c)))).snapshot();
        ReviewSnapshot reordered = overlay.apply(pinned, only(view("aa".repeat(32), List.of(c, b, a)))).snapshot();
        ReviewSnapshot otherBytes = overlay.apply(pinned, only(view("bb".repeat(32), List.of(a, b, c)))).snapshot();

        assertEquals(first.sha256(), reordered.sha256());
        assertNotEquals(first.sha256(), otherBytes.sha256());
        assertEquals(1, first.documentCount());
        assertEquals(1, first.machineCount());
        assertEquals(1, first.correctedCount());
        assertEquals(1, first.rejectedCount());
        assertEquals(Map.of(DOC, "2025.5a"), first.schemaVersions());
        assertTrue(first.sha256().matches("[0-9a-f]{64}"));
    }

    // ------------------------------------------------------------------ fix round 1

    @Test
    void aCorrectedRowWithNoValueIsAContractViolation() {
        EngineResultEnvelope pinned = envelope(List.of(missingOccurrence("federalWithholding")));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("federalWithholding", null, ReviewState.CORRECTED, null, null, null, null,
                        "NONE", false)));

        DocumentEngineFailure failure =
                assertThrows(DocumentEngineFailure.class, () -> overlay.apply(pinned, only(view)));
        assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_MALFORMED, failure.code());
    }

    @Test
    void aDuplicateKeyOnThePinnedSideIsAContractViolation() {
        EngineResultEnvelope pinned = envelope(List.of(
                machineOccurrence("grossPay", null, "1", BigDecimal.ONE),
                machineOccurrence("grossPay", null, "1", BigDecimal.ONE)));
        ReviewedFields view = view("aa".repeat(32), List.of(
                reviewed("grossPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false),
                reviewed("netPay", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false)));

        DocumentEngineFailure failure =
                assertThrows(DocumentEngineFailure.class, () -> overlay.apply(pinned, only(view)));
        assertEquals(DocumentEngineFailure.Code.READMODEL_FIELD_MISMATCH, failure.code());
    }

    @Test
    void theSnapshotDigestFollowsDocumentOrdinalOrder() {
        UUID DOC1 = UUID.fromString("77777777-7777-4777-8777-777777777771");
        UUID DOC2 = UUID.fromString("77777777-7777-4777-8777-777777777772");
        EngineResultEnvelope pinned = envelopeOf(List.of(
                new LogicalDocument(DOC1, "PAYSTUB", 2, List.of(PAGE),
                        List.of(machineOccurrence("a", null, "1", BigDecimal.ONE))),
                new LogicalDocument(DOC2, "PAYSTUB", 1, List.of(PAGE),
                        List.of(machineOccurrence("b", null, "2", BigDecimal.TWO)))));

        ReviewedFields view1 = view("aa".repeat(32), List.of(
                reviewed("a", null, ReviewState.MACHINE, "1", "1", null, BigDecimal.ONE, "REGEX", false)));
        ReviewedFields view2 = view("bb".repeat(32), List.of(
                reviewed("b", null, ReviewState.MACHINE, "2", "2", null, BigDecimal.TWO, "REGEX", false)));

        ReviewSnapshot pinnedOrder = overlay.apply(pinned, Map.of(DOC1, view1, DOC2, view2)).snapshot();

        EngineResultEnvelope reordered = envelopeOf(List.of(
                new LogicalDocument(DOC2, "PAYSTUB", 1, List.of(PAGE),
                        List.of(machineOccurrence("b", null, "2", BigDecimal.TWO))),
                new LogicalDocument(DOC1, "PAYSTUB", 2, List.of(PAGE),
                        List.of(machineOccurrence("a", null, "1", BigDecimal.ONE)))));

        ReviewSnapshot reorderedOrder = overlay.apply(reordered, Map.of(DOC1, view1, DOC2, view2)).snapshot();

        assertEquals(pinnedOrder.sha256(), reorderedOrder.sha256(), "snapshot digest follows ordinal order");
    }
}
