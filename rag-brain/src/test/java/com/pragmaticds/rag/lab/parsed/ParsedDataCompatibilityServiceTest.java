package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
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
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService.CompatibilityDecision;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService.Policy;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService.RejectionCode;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService.WarningCode;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generalized compatibility predicate, exercised where it goes beyond the Income prototype.
 *
 * <p>Income's own contract — warn on review and missing fields, one minimum document, no required
 * type — is already pinned by {@code IncomeEnvelopeCompatibilityTest} through the adapter. What
 * matters here is everything a non-Income release can declare: required document types, a minimum
 * count above one, and REJECT policies that turn a warning into a refusal.
 */
class ParsedDataCompatibilityServiceTest {

    private static final UUID PAGE = UUID.fromString("44444444-4444-4444-8444-444444444440");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SCHEMA = UUID.fromString("66666666-6666-4666-8666-666666666660");

    private final ParsedDataCompatibilityService compatibility = new ParsedDataCompatibilityService();

    @Test
    void aRequiredDocumentTypeMustActuallyBePresent() {
        Policy policy = policy(2, Set.of("PAYSTUB", "W2"), Set.of("W2"),
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);

        CompatibilityDecision without = compatibility.evaluate(
                envelope(document(0, "PAYSTUB"), document(1, "PAYSTUB")), policy);
        assertFalse(without.compatible());
        assertEquals(RejectionCode.REQUIRED_DOCUMENT_TYPE_MISSING, without.rejection());
        assertEquals(2, without.supportedDocumentCount(),
                "the documents were supported; the required type is what is missing");

        CompatibilityDecision with = compatibility.evaluate(
                envelope(document(0, "PAYSTUB"), document(1, "W2")), policy);
        assertTrue(with.compatible());
        assertNull(with.rejection());
    }

    @Test
    void tooFewSupportedDocumentsIsDistinctFromNoneAtAll() {
        Policy policy = policy(2, Set.of("PAYSTUB"), Set.of(),
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);

        CompatibilityDecision tooFew = compatibility.evaluate(envelope(document(0, "PAYSTUB")), policy);
        assertEquals(RejectionCode.MINIMUM_DOCUMENTS_NOT_MET, tooFew.rejection());
        assertEquals(1, tooFew.supportedDocumentCount());

        // Nothing analyzable is a different operational problem from not enough to analyze, and an
        // operator reading the code must be able to tell them apart.
        CompatibilityDecision none = compatibility.evaluate(envelope(document(0, "BANK_STATEMENT")), policy);
        assertEquals(RejectionCode.NO_SUPPORTED_DOCUMENT, none.rejection());
        assertEquals(0, none.supportedDocumentCount());
        assertEquals(List.of(WarningCode.UNSUPPORTED_DOCUMENT_IGNORED),
                none.warnings().stream().map(ParsedDataCompatibilityService.Warning::code).toList());
    }

    @Test
    void rejectPoliciesTurnWarningsIntoRefusalsWithoutLosingTheWarnings() {
        EngineResultEnvelope envelope = envelope(document(0, "PAYSTUB",
                missing("grossPay", "current"),
                found("netPay", "current", "MANUAL_REVIEW_REQUIRED")));

        CompatibilityDecision missingRejects = compatibility.evaluate(envelope,
                policy(1, Set.of("PAYSTUB"), Set.of(),
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.REJECT));
        assertEquals(RejectionCode.MISSING_FIELD, missingRejects.rejection());
        assertEquals(2, missingRejects.warnings().size(),
                "a refusal still reports everything observed, not just the first offence");

        CompatibilityDecision reviewRejects = compatibility.evaluate(envelope,
                policy(1, Set.of("PAYSTUB"), Set.of(),
                        InstanceReleaseManifest.ReviewPolicy.REJECT,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE));
        assertEquals(RejectionCode.REVIEW_REQUIRED, reviewRejects.rejection());

        // The same envelope under the Income-shaped contract is merely noteworthy.
        CompatibilityDecision warns = compatibility.evaluate(envelope,
                policy(1, Set.of("PAYSTUB"), Set.of(),
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE));
        assertTrue(warns.compatible());
        assertEquals(2, warns.warnings().size());
    }

    @Test
    void anUnreadableVersionShortCircuitsBeforeAnyDocumentIsInspected() {
        Policy policy = policy(1, Set.of("PAYSTUB"), Set.of(),
                InstanceReleaseManifest.ReviewPolicy.WARN,
                InstanceReleaseManifest.MissingFieldPolicy.PRESERVE);

        CompatibilityDecision badEnvelope = compatibility.evaluate(
                envelope("9.9.9", "DOCENGINE-C14N-1", document(0, "BANK_STATEMENT")), policy);
        assertEquals(RejectionCode.ENVELOPE_VERSION_UNSUPPORTED, badEnvelope.rejection());
        assertEquals(List.of(), badEnvelope.warnings(),
                "a version this build cannot read makes every downstream observation meaningless");

        CompatibilityDecision badCanonicalization = compatibility.evaluate(
                envelope("1.0.0", "DOCENGINE-C14N-9", document(0, "BANK_STATEMENT")), policy);
        assertEquals(RejectionCode.CANONICALIZATION_VERSION_UNSUPPORTED,
                badCanonicalization.rejection());
        assertEquals(List.of(), badCanonicalization.warnings());
    }

    @Test
    void aStoredContractBecomesExactlyTheVocabulariesItDeclared() {
        InstanceReleaseManifest.ParsedDataContract contract =
                new InstanceReleaseManifest.ParsedDataContract(
                        "1.0.0", "DOCENGINE-C14N-1", Set.of("PAYSTUB", "W2"), Set.of("W2"), 2,
                        InstanceReleaseManifest.ReviewPolicy.REJECT,
                        InstanceReleaseManifest.MissingFieldPolicy.REJECT);

        Policy policy = Policy.of(contract, Set.of("MANUAL_REVIEW_REQUIRED"));

        assertEquals(Set.of("1.0.0"), policy.envelopeVersions());
        assertEquals(Set.of("DOCENGINE-C14N-1"), policy.canonicalizationVersions());
        assertEquals(Set.of("PAYSTUB", "W2"), policy.allowedDocumentTypes());
        assertEquals(Set.of("W2"), policy.requireAnyDocumentTypes());
        assertEquals(2, policy.minimumSupportedDocuments());
        assertEquals(InstanceReleaseManifest.ReviewPolicy.REJECT, policy.reviewRequired());
        assertEquals(InstanceReleaseManifest.MissingFieldPolicy.REJECT, policy.missingFields());
    }

    // ---------------------------------------------------------------- fixtures

    private static Policy policy(int minimum, Set<String> allowed, Set<String> requireAny,
                                 InstanceReleaseManifest.ReviewPolicy review,
                                 InstanceReleaseManifest.MissingFieldPolicy missing) {
        return new Policy(Set.of("1.0.0"), Set.of("DOCENGINE-C14N-1"), allowed, requireAny,
                minimum, Set.of("MANUAL_REVIEW_REQUIRED"), review, missing);
    }

    private static FieldOccurrence found(String name, String groupKey, String validationStatus) {
        return new FieldOccurrence(name, groupKey, FieldStatus.FOUND, "MONEY", "1,234.50",
                "1,234.50", new NormalizedValue(null, new BigDecimal("1234.5"), null, null),
                new SchemaRef(SCHEMA, "2024.1"), "ANCHOR_LABEL", "5.1.0", new BigDecimal("0.97"),
                new EngineResultEnvelope.ConfidenceComponents(
                        new BigDecimal("0.97"), BigDecimal.ONE, BigDecimal.ONE),
                validationStatus, false, List.of());
    }

    private static FieldOccurrence missing(String name, String groupKey) {
        return new FieldOccurrence(name, groupKey, FieldStatus.MISSING, "MONEY", null, null, null,
                new SchemaRef(SCHEMA, "2024.1"), "NONE", "5.1.0", BigDecimal.ZERO, null,
                "NOT_VALIDATED", false, List.of());
    }

    private static LogicalDocument document(
            int ordinal, String documentTypeCode, FieldOccurrence... fields) {
        return new LogicalDocument(new UUID(0x5555, ordinal), documentTypeCode, ordinal,
                List.of(PAGE), List.of(fields));
    }

    private static EngineResultEnvelope envelope(LogicalDocument... documents) {
        return envelope("1.0.0", "DOCENGINE-C14N-1", documents);
    }

    private static EngineResultEnvelope envelope(String envelopeVersion,
                                                 String canonicalizationVersion,
                                                 LogicalDocument... documents) {
        return new EngineResultEnvelope(
                EngineArtifactDescriptor.of("artifact-bytes".getBytes(StandardCharsets.UTF_8)),
                envelopeVersion,
                canonicalizationVersion,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                new Generation(UUID.fromString("22222222-2222-4222-8222-222222222222"), 1, 1,
                        "b2".repeat(32), "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(new SourceFile(SOURCE, 0, "a1".repeat(32), 48211L, "application/pdf")),
                List.of(new EnginePage(PAGE, SOURCE, 0, 0, new BigDecimal("612"),
                        new BigDecimal("792"), 0, null, "NATIVE", false, false, null)),
                List.of(documents),
                List.of(),
                new Provenance(new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        new ReleaseAvailability("UNAVAILABLE"),
                        List.of(new StageAttempt("EXTRACTING", 1, "c3".repeat(32), "1.4.0", null))));
    }
}
