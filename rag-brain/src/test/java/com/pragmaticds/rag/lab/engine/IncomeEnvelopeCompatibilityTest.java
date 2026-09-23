package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.EnginePage;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldOccurrence;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.FieldStatus;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Generation;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.LogicalDocument;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.NormalizedValue;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.Provenance;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.ReleaseAvailability;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SchemaRef;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.SourceFile;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope.StageAttempt;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility.Decision;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility.RejectionCode;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility.Warning;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility.WarningCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative compatibility policy tests. Envelopes are built directly from the parsed model
 * with synthetic identifiers only, proving the decision is a pure function of document types,
 * field states, and versions — never of fingerprints or provenance.
 */
class IncomeEnvelopeCompatibilityTest {

    private static final UUID PAGE = UUID.fromString("44444444-4444-4444-8444-444444444440");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SCHEMA = UUID.fromString("66666666-6666-4666-8666-666666666660");

    private final IncomeEnvelopeCompatibility compatibility = new IncomeEnvelopeCompatibility();

    private static FieldOccurrence found(String name, String groupKey, String validationStatus) {
        return new FieldOccurrence(
                name,
                groupKey,
                FieldStatus.FOUND,
                "MONEY",
                "1,234.50",
                "1,234.50",
                new NormalizedValue(null, new BigDecimal("1234.5"), null, null),
                new SchemaRef(SCHEMA, "2024.1"),
                "ANCHOR_LABEL",
                "5.1.0",
                new BigDecimal("0.97"),
                new EngineResultEnvelope.ConfidenceComponents(
                        new BigDecimal("0.97"), BigDecimal.ONE, BigDecimal.ONE),
                validationStatus,
                false,
                List.of());
    }

    private static FieldOccurrence missing(String name, String groupKey) {
        return new FieldOccurrence(
                name,
                groupKey,
                FieldStatus.MISSING,
                "MONEY",
                null,
                null,
                null,
                new SchemaRef(SCHEMA, "2024.1"),
                "NONE",
                "5.1.0",
                BigDecimal.ZERO,
                null,
                "NOT_VALIDATED",
                false,
                List.of());
    }

    private static LogicalDocument document(
            int ordinal, String documentTypeCode, FieldOccurrence... fields) {
        return new LogicalDocument(
                new UUID(0x5555, ordinal), documentTypeCode, ordinal, List.of(PAGE),
                List.of(fields));
    }

    private static EngineResultEnvelope envelope(LogicalDocument... documents) {
        return envelope(
                "1.0.0",
                "DOCENGINE-C14N-1",
                "artifact-bytes",
                "b2".repeat(32),
                "c3".repeat(32),
                documents);
    }

    private static EngineResultEnvelope envelope(
            String envelopeVersion,
            String canonicalizationVersion,
            String artifactSeed,
            String sourceSetSha256,
            String stageDigest,
            LogicalDocument... documents) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of(artifactSeed.getBytes(StandardCharsets.UTF_8)),
                envelopeVersion,
                canonicalizationVersion,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                new Generation(
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        1,
                        1,
                        sourceSetSha256,
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(
                        new EnginePage(
                                PAGE,
                                SOURCE,
                                0,
                                0,
                                new BigDecimal("612"),
                                new BigDecimal("792"),
                                0,
                                null,
                                "NATIVE",
                                false,
                                false,
                                null)),
                List.of(documents),
                List.of(),
                new Provenance(
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(
                                new StageAttempt(
                                        "EXTRACTING", 1, stageDigest, "1.4.0", null))));
    }

    // ---------------------------------------------------------------- declarative data

    /**
     * Deliberately does NOT restate the type list.
     *
     * <p>Its predecessor asserted the set was "exactly the pinned four", which is how the drift
     * survived: the engine grew to twenty-two INCOME types and a green test went on insisting four
     * was right, because both sides of the assertion were the same hand-copied literal. Which types
     * belong here is now answered against the engine's own contract by {@link
     * EngineIncomeTypeCoverageTest}. What is left to check here is the part that is genuinely local:
     * the set is immutable, and it is a set of income types rather than a catch-all.
     */
    @Test
    void supportedDocumentTypesAreImmutableAndIncomeOnly() {
        Set<String> supported = IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES;

        assertThrows(
                UnsupportedOperationException.class, () -> supported.add("BANK_STATEMENT"));
        assertFalse(
                supported.contains("BANK_STATEMENT"),
                "BANK_STATEMENT is an ASSET type; the Income policy must not claim it");
        assertTrue(
                supported.containsAll(Set.of("PAYSTUB", "W2", "TAX_RETURN", "SCHEDULE_E")),
                "the four types the Income prototype was built around must remain supported");
    }

    @Test
    void supportedVersionsAreDeclarativeData() {
        assertEquals(
                Set.of("1.0.0"), IncomeEnvelopeCompatibility.SUPPORTED_ENVELOPE_VERSIONS);
        assertEquals(
                Set.of("DOCENGINE-C14N-1"),
                IncomeEnvelopeCompatibility.SUPPORTED_CANONICALIZATION_VERSIONS);
        assertEquals(
                Set.of("MANUAL_REVIEW_REQUIRED"),
                IncomeEnvelopeCompatibility.REVIEW_WARNING_VALIDATION_STATUSES);
    }

    // ---------------------------------------------------------------- acceptance

    @Test
    void eachSupportedDocumentTypeAloneIsCompatible() {
        for (String type : List.of("PAYSTUB", "W2", "TAX_RETURN", "SCHEDULE_E")) {
            Decision decision =
                    compatibility.evaluate(
                            envelope(document(0, type, found("wages", null, "VALID"))));

            assertTrue(decision.compatible(), type);
            assertNull(decision.rejection(), type);
            assertEquals(List.of(), decision.warnings(), type);
        }
    }

    @Test
    void mixedPackagesKeepTheSupportedDocumentAndWarnAboutTheRest() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                document(0, "W2", found("wages", null, "VALID")),
                                document(1, "BANK_STATEMENT", found("balance", null, "VALID"))));

        assertTrue(decision.compatible());
        assertNull(decision.rejection());
        assertEquals(
                List.of(
                        new Warning(
                                WarningCode.UNSUPPORTED_DOCUMENT_IGNORED,
                                1,
                                "BANK_STATEMENT",
                                null,
                                null)),
                decision.warnings());
    }

    // ---------------------------------------------------------------- warnings

    @Test
    void reviewRequiredFieldsArePreservedAsWarningsNotRejections() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                document(
                                        0,
                                        "W2",
                                        found("wages", null, "MANUAL_REVIEW_REQUIRED"))));

        assertTrue(decision.compatible());
        assertEquals(
                List.of(
                        new Warning(
                                WarningCode.FIELD_REVIEW_REQUIRED, 0, "W2", "wages", null)),
                decision.warnings());
    }

    @Test
    void missingFieldsArePreservedAsWarningsWithTheirGroupKey() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                document(
                                        0,
                                        "SCHEDULE_E",
                                        found("rents_received", "A", "VALID"),
                                        missing("rents_received", "C"))));

        assertTrue(decision.compatible());
        assertEquals(
                List.of(
                        new Warning(
                                WarningCode.FIELD_MISSING,
                                0,
                                "SCHEDULE_E",
                                "rents_received",
                                "C")),
                decision.warnings());
    }

    @Test
    void warningsPreserveDocumentThenFieldOrder() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                document(0, "W2", missing("employer_ein", null)),
                                document(
                                        1,
                                        "SCHEDULE_E",
                                        found("rents_received", "B", "MANUAL_REVIEW_REQUIRED"))));

        assertEquals(
                List.of(
                        new Warning(
                                WarningCode.FIELD_MISSING, 0, "W2", "employer_ein", null),
                        new Warning(
                                WarningCode.FIELD_REVIEW_REQUIRED,
                                1,
                                "SCHEDULE_E",
                                "rents_received",
                                "B")),
                decision.warnings());
    }

    @Test
    void warningsListIsUnmodifiable() {
        Decision decision =
                compatibility.evaluate(
                        envelope(document(0, "W2", missing("employer_ein", null))));

        assertThrows(
                UnsupportedOperationException.class, () -> decision.warnings().clear());
    }

    // ---------------------------------------------------------------- rejections

    @Test
    void unsupportedDocumentOnlyPackagesAreRejected() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                document(
                                        0, "BANK_STATEMENT", found("balance", null, "VALID"))));

        assertFalse(decision.compatible());
        assertEquals(RejectionCode.NO_SUPPORTED_DOCUMENT, decision.rejection());
    }

    @Test
    void packagesWithNoDocumentsAreRejected() {
        Decision decision = compatibility.evaluate(envelope());

        assertFalse(decision.compatible());
        assertEquals(RejectionCode.NO_SUPPORTED_DOCUMENT, decision.rejection());
    }

    @Test
    void unsupportedEnvelopeVersionsAreRejected() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                "0.9.0",
                                "DOCENGINE-C14N-1",
                                "artifact-bytes",
                                "b2".repeat(32),
                                "c3".repeat(32),
                                document(0, "W2", found("wages", null, "VALID"))));

        assertFalse(decision.compatible());
        assertEquals(RejectionCode.ENVELOPE_VERSION_UNSUPPORTED, decision.rejection());
    }

    @Test
    void unsupportedCanonicalizationVersionsAreRejected() {
        Decision decision =
                compatibility.evaluate(
                        envelope(
                                "1.0.0",
                                "DOCENGINE-C14N-9",
                                "artifact-bytes",
                                "b2".repeat(32),
                                "c3".repeat(32),
                                document(0, "W2", found("wages", null, "VALID"))));

        assertFalse(decision.compatible());
        assertEquals(
                RejectionCode.CANONICALIZATION_VERSION_UNSUPPORTED, decision.rejection());
    }

    // ---------------------------------------------------------------- fingerprint neutrality

    @Test
    void fingerprintAndProvenanceDifferencesNeverChangeTheDecision() {
        LogicalDocument sameDocument =
                document(0, "W2", found("wages", null, "MANUAL_REVIEW_REQUIRED"));
        Decision baseline =
                compatibility.evaluate(
                        envelope(
                                "1.0.0",
                                "DOCENGINE-C14N-1",
                                "artifact-bytes",
                                "b2".repeat(32),
                                "c3".repeat(32),
                                sameDocument));

        Decision differentArtifact =
                compatibility.evaluate(
                        envelope(
                                "1.0.0",
                                "DOCENGINE-C14N-1",
                                "completely-different-bytes",
                                "b2".repeat(32),
                                "c3".repeat(32),
                                sameDocument));
        Decision differentSourceSet =
                compatibility.evaluate(
                        envelope(
                                "1.0.0",
                                "DOCENGINE-C14N-1",
                                "artifact-bytes",
                                "d4".repeat(32),
                                "c3".repeat(32),
                                sameDocument));
        Decision differentProvenance =
                compatibility.evaluate(
                        envelope(
                                "1.0.0",
                                "DOCENGINE-C14N-1",
                                "artifact-bytes",
                                "b2".repeat(32),
                                "e5".repeat(32),
                                sameDocument));

        assertEquals(baseline, differentArtifact);
        assertEquals(baseline, differentSourceSet);
        assertEquals(baseline, differentProvenance);
    }
}
